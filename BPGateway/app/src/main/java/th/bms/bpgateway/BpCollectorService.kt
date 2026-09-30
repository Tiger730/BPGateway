package th.bms.bpgateway

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * พอร์ตจาก collector.py: สแตนด์บายสแกนตลอดเวลา -> เจอ Yuwell -> เชื่อมต่อ ->
 * ซิงค์เวลา -> subscribe 0x2A35 -> รับค่า -> บันทึก CSV + POST API -> ตัดการเชื่อมต่อ -> สแกนต่อ
 *
 * ทุกอย่างที่เกี่ยวกับ BLE ทำบน main thread (ผ่าน Handler) เพื่อไม่ให้ state ชนกัน
 * งานเครือข่าย/ไฟล์ทำบน io thread แยก จึงไม่ทำให้ BLE ค้าง
 */
@SuppressLint("MissingPermission") // ตรวจสิทธิ์ใน MainActivity / BootReceiver ก่อนเริ่มบริการแล้ว
class BpCollectorService : Service() {

    companion object {
        private const val CHANNEL_ID = "bp_collector"
        private const val NOTIF_ID = 1001

        private const val CONNECT_TIMEOUT_MS = 12_000L
        private const val MEASUREMENT_TIMEOUT_MS = 90_000L
        private const val HISTORY_SILENCE_MS = 3_000L
        private const val RETRY_PENDING_MS = 60_000L
        private const val SCAN_RECYCLE_MS = 10 * 60_000L   // Android ลดระดับการสแกนที่นานเกิน 30 นาที
        private const val KNOWN_NAME = "Yuwell BP-YE680B"

        fun uuid16(v: Int): UUID =
            UUID.fromString(String.format("%08x-0000-1000-8000-00805f9b34fb", v))

        val BP_SERVICE: UUID = uuid16(0x1810)
        val BP_MEASUREMENT: UUID = uuid16(0x2A35)
        val INTERMEDIATE_CUFF: UUID = uuid16(0x2A36)
        val CURRENT_TIME: UUID = uuid16(0x2A2B)
        val DATE_TIME: UUID = uuid16(0x2A08)
        val RACP: UUID = uuid16(0x2A52)
        val CCCD: UUID = uuid16(0x2902)

        @Volatile var isRunning = false
            private set

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, BpCollectorService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, BpCollectorService::class.java))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var io: ExecutorService
    private lateinit var prefs: AppSettings
    private lateinit var storage: CsvStorage
    private var adapter: BluetoothAdapter? = null
    private var toneGen: ToneGenerator? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // สถานะการสแกน
    private var scanning = false
    private val scanStarts = ArrayDeque<Long>()

    // สถานะการเชื่อมต่อ
    private var gatt: BluetoothGatt? = null
    private var sessionActive = false
    private var deviceLabel = ""
    private var measurementCount = 0
    private var sessionStart = 0L

    // คิวคำสั่ง GATT (Android ทำได้ทีละคำสั่ง)
    private val ops = ArrayDeque<() -> Boolean>()

    // ---------------------------------------------------------------- lifecycle

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        io = Executors.newSingleThreadExecutor()
        prefs = AppSettings(this)
        storage = CsvStorage(this)
        adapter = getSystemService(BluetoothManager::class.java)?.adapter
        toneGen = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90) }.getOrNull()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground("กำลังเริ่มบริการ...")
        if (!isRunning) {
            if (!Permissions.hasBluetooth(this)) {
                CollectorBus.status("ยังไม่ได้รับสิทธิ์ Bluetooth — เปิดแอปเพื่ออนุญาต")
                stopSelf()
                return START_NOT_STICKY
            }
            isRunning = true
            CollectorBus.setRunning(true)
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BPGateway::collector")
                .apply { setReferenceCounted(false); acquire() }
            CollectorBus.log("เริ่มบริการ | MAC เป้าหมาย: ${prefs.targetMac.ifBlank { "(ค้นหาอัตโนมัติ)" }} | " +
                    if (prefs.fetchHistory) "โหมดกวาดประวัติ" else "โหมด Real-time")
            startScan()
            main.postDelayed(retryPendingRunnable, 5_000)
            main.postDelayed(scanRecycleRunnable, SCAN_RECYCLE_MS)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        main.removeCallbacksAndMessages(null)
        stopScan()
        sessionActive = false
        closeGatt()
        io.shutdown()
        toneGen?.release()
        wakeLock?.let { if (it.isHeld) it.release() }
        CollectorBus.setRunning(false)
        CollectorBus.status("หยุดบริการแล้ว")
        super.onDestroy()
    }

    // ---------------------------------------------------------------- notification

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "BP Gateway", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bp)
            .setContentTitle("BP Gateway กำลังทำงาน")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .build()
    }

    private fun startAsForeground(text: String) {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(text), type)
    }

    private fun status(text: String) {
        CollectorBus.status(text)
        if (isRunning) runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
        }
    }

    // ---------------------------------------------------------------- scanning

    private val scanRunnable = Runnable { startScan() }

    private fun scheduleScan(delayMs: Long) {
        main.removeCallbacks(scanRunnable)
        main.postDelayed(scanRunnable, delayMs)
    }

    private fun startScan() {
        if (!isRunning || scanning || gatt != null) return
        val bt = adapter
        if (bt == null || !bt.isEnabled) {
            status("Bluetooth ปิดอยู่ — รอเปิด Bluetooth...")
            scheduleScan(5_000)
            return
        }
        // Android อนุญาตให้เริ่มสแกนได้ไม่เกิน 5 ครั้งใน 30 วินาที
        val now = SystemClock.elapsedRealtime()
        while (scanStarts.isNotEmpty() && now - scanStarts.first() > 30_000) scanStarts.removeFirst()
        if (scanStarts.size >= 4) {
            scheduleScan(30_000 - (now - scanStarts.first()) + 500)
            return
        }
        val scanner = bt.bluetoothLeScanner ?: run { scheduleScan(3_000); return }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(buildFilters(), settings, scanCallback)
            scanning = true
            scanStarts.addLast(now)
            status("สแตนด์บาย: รอสัญญาณจากเครื่องวัดความดัน...")
        } catch (e: Exception) {
            CollectorBus.log("เริ่มสแกนไม่สำเร็จ: ${e.message}")
            scheduleScan(5_000)
        }
    }

    /**
     * ถ้ากำหนด MAC ไว้ จะใช้ ScanFilter (ทำงานได้แม้ปิดหน้าจอ)
     * ถ้าไม่กำหนด จะสแกนแบบไม่กรอง แล้วคัดด้วยชื่อ/Service ใน callback (ต้องเปิดหน้าจอไว้)
     */
    private fun buildFilters(): List<ScanFilter> {
        val mac = prefs.targetMac.trim().uppercase()
        if (!BluetoothAdapter.checkBluetoothAddress(mac)) return emptyList()
        return listOf(
            ScanFilter.Builder().setDeviceAddress(mac).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(BP_SERVICE)).build(),
            ScanFilter.Builder().setDeviceName(KNOWN_NAME).build()
        )
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private val scanRecycleRunnable = object : Runnable {
        override fun run() {
            if (isRunning && scanning && gatt == null) {
                stopScan()
                startScan()
            }
            main.postDelayed(this, SCAN_RECYCLE_MS)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post { onDeviceSeen(result) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            main.post { results.forEach { onDeviceSeen(it) } }
        }

        override fun onScanFailed(errorCode: Int) {
            main.post {
                scanning = false
                CollectorBus.log("สแกนล้มเหลว (error $errorCode)")
                // 2 = registration failed, 6 = scanning too frequently -> พักนานขึ้น
                scheduleScan(if (errorCode == 2 || errorCode == 6) 30_000 else 5_000)
            }
        }
    }

    private fun isTarget(r: ScanResult): Boolean {
        val mac = prefs.targetMac.trim()
        if (mac.isNotEmpty() && r.device.address.equals(mac, ignoreCase = true)) return true
        val name = (r.device.name ?: r.scanRecord?.deviceName ?: "").uppercase()
        if (listOf("YUWELL", "YE680", "680B").any { name.contains(it) }) return true
        return r.scanRecord?.serviceUuids?.any { it.uuid == BP_SERVICE } == true
    }

    private fun onDeviceSeen(r: ScanResult) {
        if (!isRunning || !scanning || gatt != null) return
        if (!isTarget(r)) return
        val name = (r.device.name ?: r.scanRecord?.deviceName)?.trim()
        deviceLabel = if (name.isNullOrEmpty()) prefs.defaultDeviceId else name
        stopScan()
        connect(r.device, r.rssi)
    }

    // ---------------------------------------------------------------- connection

    private val connectTimeout = Runnable { endSession("เชื่อมต่อไม่สำเร็จภายใน ${CONNECT_TIMEOUT_MS / 1000} วินาที") }
    private val measurementTimeout = Runnable {
        status("รอนานเกิน ${MEASUREMENT_TIMEOUT_MS / 1000} วินาที ไม่ได้รับข้อมูลจากเครื่อง")
        endSession()
    }
    private val historySilence = Runnable {
        CollectorBus.log("กวาดข้อมูลเสร็จ ทั้งหมด $measurementCount รายการ")
        endSession()
    }
    private val finishRunnable = Runnable { endSession() }
    private val progressRunnable = object : Runnable {
        override fun run() {
            if (!sessionActive || measurementCount > 0) return
            val s = (SystemClock.elapsedRealtime() - sessionStart) / 1000
            status("กำลังรอผลการวัด (ปั๊มลม/วัดความดัน... ${s}s/${MEASUREMENT_TIMEOUT_MS / 1000}s)")
            main.postDelayed(this, 5_000)
        }
    }

    private fun connect(device: BluetoothDevice, rssi: Int) {
        sessionActive = true
        measurementCount = 0
        ops.clear()
        status("พบ $deviceLabel [${device.address}] RSSI $rssi dBm — กำลังเชื่อมต่อ...")
        gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) {
            endSession("connectGatt ล้มเหลว")
            return
        }
        main.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
    }

    private fun closeGatt() {
        val g = gatt ?: return
        gatt = null
        runCatching { g.disconnect() }
        runCatching { g.close() }
    }

    /** จบรอบการเชื่อมต่อ ปล่อย BLE handle แล้วกลับไปสแกนต่อ */
    private fun endSession(reason: String? = null) {
        main.removeCallbacks(connectTimeout)
        main.removeCallbacks(measurementTimeout)
        main.removeCallbacks(historySilence)
        main.removeCallbacks(progressRunnable)
        main.removeCallbacks(finishRunnable)
        ops.clear()
        reason?.let { CollectorBus.log(it) }
        closeGatt()
        if (!sessionActive) return
        sessionActive = false
        if (measurementCount > 0) status("บันทึกเรียบร้อย — สแตนด์บายพร้อมรับการวัดครั้งถัดไป")
        else status("ตัดการเชื่อมต่อแล้ว — กลับสู่โหมดสแตนด์บาย")
        scheduleScan(1_000)
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, st: Int, newState: Int) {
            main.post {
                if (g != gatt) { runCatching { g.close() }; return@post }
                if (newState == BluetoothProfile.STATE_CONNECTED && st == BluetoothGatt.GATT_SUCCESS) {
                    main.removeCallbacks(connectTimeout)
                    CollectorBus.log("เชื่อมต่อสำเร็จ กำลังอ่าน GATT services...")
                    main.postDelayed({
                        if (g == gatt && !g.discoverServices()) endSession("discoverServices ล้มเหลว")
                    }, 300)
                } else {
                    endSession("อุปกรณ์ตัดการเชื่อมต่อ (status=$st)")
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, st: Int) {
            main.post {
                if (g != gatt) return@post
                if (st != BluetoothGatt.GATT_SUCCESS) endSession("อ่าน services ไม่สำเร็จ (status=$st)")
                else setupSession(g)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, st: Int) {
            main.post {
                if (g != gatt) return@post
                if (st != BluetoothGatt.GATT_SUCCESS)
                    CollectorBus.log("เขียน CCCD ${short(d.characteristic.uuid)} ไม่สำเร็จ (status=$st)")
                nextOp()
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, st: Int) {
            main.post {
                if (g != gatt) return@post
                if (st != BluetoothGatt.GATT_SUCCESS)
                    CollectorBus.log("เขียน ${short(c.uuid)} ไม่สำเร็จ (status=$st)")
                nextOp()
            }
        }

        // Android 13+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            val copy = value.copyOf()
            main.post { onNotify(g, c.uuid, copy) }
        }

        // Android 12 และต่ำกว่า
        @Deprecated("Deprecated in API 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            val copy = c.value?.copyOf() ?: return
            main.post { onNotify(g, c.uuid, copy) }
        }
    }

    private fun findChar(g: BluetoothGatt, uuid: UUID): BluetoothGattCharacteristic? =
        g.services.asSequence().flatMap { it.characteristics.asSequence() }.firstOrNull { it.uuid == uuid }

    private fun canWrite(c: BluetoothGattCharacteristic) =
        (c.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0

    private fun setupSession(g: BluetoothGatt) {
        val bpChar = g.getService(BP_SERVICE)?.getCharacteristic(BP_MEASUREMENT) ?: findChar(g, BP_MEASUREMENT)
        if (bpChar == null) {
            endSession("ไม่พบ Characteristic 0x2A35 บนอุปกรณ์นี้")
            return
        }

        // 1) ซิงค์เวลาเข้าเครื่องวัด (0x2A2B หรือ 0x2A08)
        val ct = findChar(g, CURRENT_TIME)?.takeIf { canWrite(it) }
        val dt = findChar(g, DATE_TIME)?.takeIf { canWrite(it) }
        when {
            ct != null -> enqueue { writeChar(g, ct, timeBytes(full = true)).also { if (it) CollectorBus.log("ซิงค์เวลา (0x2A2B)") } }
            dt != null -> enqueue { writeChar(g, dt, timeBytes(full = false)).also { if (it) CollectorBus.log("ซิงค์เวลา (0x2A08)") } }
        }

        // 2) Subscribe Indication 0x2A35
        enqueue {
            enableCccd(g, bpChar).also { if (!it) CollectorBus.log("subscribe 0x2A35 ไม่สำเร็จ") }
        }

        // 3) 0x2A36 Intermediate Cuff Pressure (ถ้ามี)
        findChar(g, INTERMEDIATE_CUFF)?.let { c -> enqueue { enableCccd(g, c) } }

        // 4) RACP: ขอประวัติทั้งหมด (01 01) เมื่อเปิดโหมดกวาดประวัติ
        if (prefs.fetchHistory) {
            findChar(g, RACP)?.let { c ->
                enqueue { enableCccd(g, c) }
                enqueue {
                    CollectorBus.log("ส่งคำสั่ง RACP 01-01 (ขอประวัติทั้งหมด)")
                    writeChar(g, c, byteArrayOf(0x01, 0x01))
                }
            }
        }

        // 5) เริ่มจับเวลารอผล
        enqueue { onSubscribed(); false }
        nextOp()
    }

    private fun onSubscribed() {
        sessionStart = SystemClock.elapsedRealtime()
        status(
            if (prefs.fetchHistory) "เชื่อมต่อสำเร็จ! กำลังกวาดข้อมูลผลการวัดในเครื่อง..."
            else "เชื่อมต่อสำเร็จ! รอรับผลการวัดความดันล่าสุด..."
        )
        main.postDelayed(measurementTimeout, MEASUREMENT_TIMEOUT_MS)
        main.postDelayed(progressRunnable, 5_000)
    }

    // ---------------------------------------------------------------- GATT op queue

    private fun enqueue(op: () -> Boolean) = ops.addLast(op)

    /** รันคำสั่งถัดไป; op คืน true = รอ callback, false = ข้ามไปคำสั่งถัดไปทันที */
    private fun nextOp() {
        while (ops.isNotEmpty() && gatt != null) {
            val op = ops.removeFirst()
            val started = try { op() } catch (e: Exception) {
                CollectorBus.log("GATT error: ${e.message}"); false
            }
            if (started) return
        }
    }

    @Suppress("DEPRECATION")
    private fun enableCccd(g: BluetoothGatt, c: BluetoothGattCharacteristic): Boolean {
        val d = c.getDescriptor(CCCD) ?: return false
        if (!g.setCharacteristicNotification(c, true)) return false
        val value = if ((c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0)
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
        } else {
            d.value = value
            g.writeDescriptor(d)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeChar(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray): Boolean {
        val type = if ((c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, value, type) == BluetoothStatusCodes.SUCCESS
        } else {
            c.writeType = type
            c.value = value
            g.writeCharacteristic(c)
        }
    }

    /** full=true: Current Time 0x2A2B (10 bytes), false: Date Time 0x2A08 (7 bytes) */
    private fun timeBytes(full: Boolean): ByteArray {
        val n = LocalDateTime.now()
        val base = byteArrayOf(
            (n.year and 0xFF).toByte(), ((n.year shr 8) and 0xFF).toByte(),
            n.monthValue.toByte(), n.dayOfMonth.toByte(),
            n.hour.toByte(), n.minute.toByte(), n.second.toByte()
        )
        return if (full) base + byteArrayOf(n.dayOfWeek.value.toByte(), 0, 0) else base
    }

    // ---------------------------------------------------------------- data

    private fun onNotify(g: BluetoothGatt, uuid: UUID, value: ByteArray) {
        if (g != gatt) return
        when (uuid) {
            BP_MEASUREMENT -> handleMeasurement(value)
            RACP -> CollectorBus.log("RACP response: ${value.toHex()}")
            else -> Unit // 0x2A36 แรงดันระหว่างปั๊ม ไม่ใช้
        }
    }

    private fun handleMeasurement(value: ByteArray) {
        CollectorBus.log("ได้รับ 0x2A35 (${value.size} bytes): ${value.toHex()}")
        val parsed = try {
            BpParser.parse(value)
        } catch (e: Exception) {
            CollectorBus.log("ถอดรหัสไม่สำเร็จ: ${e.message}")
            return
        }
        val reading = parsed.copy(deviceId = deviceLabel)
        measurementCount++

        val isNew = !reading.hasDeviceTime || prefs.rememberRaw(reading.rawHex)
        if (isNew) {
            beep()
            CollectorBus.reading(reading)
            status("ได้รับผล: SYS ${reading.sys} / DIA ${reading.dia} mmHg, ชีพจร ${reading.pulse} bpm")
            persist(reading)
        } else {
            CollectorBus.log("ข้อมูลซ้ำกับที่เคยบันทึกแล้ว (${reading.timestamp}) — ข้าม")
        }

        if (prefs.fetchHistory) {
            main.removeCallbacks(measurementTimeout)
            main.removeCallbacks(historySilence)
            main.postDelayed(historySilence, HISTORY_SILENCE_MS)
        } else if (measurementCount == 1) {
            main.postDelayed(finishRunnable, 300)
        }
    }

    private fun persist(r: BpReading) {
        val url = prefs.apiUrl
        val token = prefs.apiToken
        val apiOn = prefs.apiEnabled
        io.execute {
            if (storage.appendCsv(r)) CollectorBus.log("บันทึกลง CSV แล้ว")
            if (apiOn && url.isNotBlank()) {
                val res = ApiClient.post(r, url, token)
                if (res.ok) {
                    CollectorBus.log("[Cloud API] ส่งสำเร็จ: ${res.message}")
                } else {
                    storage.addPending(r)
                    CollectorBus.log("[Cloud API] ส่งไม่สำเร็จ (${res.message}) — เก็บไว้ในคิว จะส่งซ้ำอัตโนมัติ")
                }
            }
        }
    }

    private val retryPendingRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            if (prefs.apiEnabled) {
                val url = prefs.apiUrl
                val token = prefs.apiToken
                io.execute {
                    val sent = storage.retryPending { ApiClient.post(it, url, token).ok }
                    if (sent > 0) CollectorBus.log("[Cloud API] ส่งข้อมูลค้างส่งสำเร็จ $sent รายการ")
                }
            }
            main.postDelayed(this, RETRY_PENDING_MS)
        }
    }

    private fun beep() {
        toneGen?.let { tg ->
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            main.postDelayed({ runCatching { tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 200) } }, 200)
        }
    }

    private fun short(u: UUID) = "0x" + u.toString().substring(4, 8).uppercase()
}
