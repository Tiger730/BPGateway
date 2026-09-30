package th.bms.bpgateway

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity(), CollectorBus.Listener {

    private lateinit var prefs: AppSettings
    private lateinit var storage: CsvStorage

    private lateinit var tvStatus: TextView
    private lateinit var tvSys: TextView
    private lateinit var tvDia: TextView
    private lateinit var tvPulse: TextView
    private lateinit var tvReadingInfo: TextView
    private lateinit var tvFiles: TextView
    private lateinit var tvLog: TextView
    private lateinit var etApiUrl: EditText
    private lateinit var etApiToken: EditText
    private lateinit var etMac: EditText
    private lateinit var etDeviceId: EditText
    private lateinit var cbApiEnabled: CheckBox
    private lateinit var cbFetchHistory: CheckBox
    private lateinit var cbKeepScreenOn: CheckBox
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (Permissions.hasBluetooth(this)) ensureBluetoothThenStart()
            else toast("ต้องอนุญาตสิทธิ์ Bluetooth ก่อน จึงจะเชื่อมต่อเครื่องวัดได้")
        }

    private val enableBtLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (bluetoothEnabled()) startCollector()
            else toast("กรุณาเปิด Bluetooth")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = AppSettings(this)
        storage = CsvStorage(this)

        tvStatus = findViewById(R.id.tvStatus)
        tvSys = findViewById(R.id.tvSys)
        tvDia = findViewById(R.id.tvDia)
        tvPulse = findViewById(R.id.tvPulse)
        tvReadingInfo = findViewById(R.id.tvReadingInfo)
        tvFiles = findViewById(R.id.tvFiles)
        tvLog = findViewById(R.id.tvLog)
        etApiUrl = findViewById(R.id.etApiUrl)
        etApiToken = findViewById(R.id.etApiToken)
        etMac = findViewById(R.id.etMac)
        etDeviceId = findViewById(R.id.etDeviceId)
        cbApiEnabled = findViewById(R.id.cbApiEnabled)
        cbFetchHistory = findViewById(R.id.cbFetchHistory)
        cbKeepScreenOn = findViewById(R.id.cbKeepScreenOn)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)

        loadSettingsToUi()

        btnStart.setOnClickListener {
            if (saveSettingsFromUi()) requestPermissionsThenStart()
        }
        btnStop.setOnClickListener {
            prefs.autoStart = false
            BpCollectorService.stop(this)
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            if (saveSettingsFromUi()) {
                if (BpCollectorService.isRunning) {
                    // รีสตาร์ทบริการเพื่อใช้ค่าใหม่
                    BpCollectorService.stop(this)
                    tvStatus.postDelayed({ BpCollectorService.start(this) }, 800)
                }
                toast("บันทึกการตั้งค่าแล้ว")
            }
        }

        // เปิดแอปแล้วเริ่มทำงานต่ออัตโนมัติ ถ้าเคยกดเริ่มไว้
        if (savedInstanceState == null && prefs.autoStart && !BpCollectorService.isRunning &&
            Permissions.hasBluetooth(this) && bluetoothEnabled()
        ) {
            startCollector()
        }
    }

    override fun onStart() {
        super.onStart()
        CollectorBus.addListener(this)
        onStatus(CollectorBus.lastStatus)
        CollectorBus.lastReading?.let { onReading(it) }
        tvLog.text = CollectorBus.logs().takeLast(80).reversed().joinToString("\n")
        onRunningChanged(BpCollectorService.isRunning)
        refreshFilesInfo()
    }

    override fun onStop() {
        CollectorBus.removeListener(this)
        super.onStop()
    }

    // ------------------------------------------------------------ settings

    private fun loadSettingsToUi() {
        etApiUrl.setText(prefs.apiUrl)
        etApiToken.setText(prefs.apiToken)
        etMac.setText(prefs.targetMac)
        etDeviceId.setText(prefs.defaultDeviceId)
        cbApiEnabled.isChecked = prefs.apiEnabled
        cbFetchHistory.isChecked = prefs.fetchHistory
        cbKeepScreenOn.isChecked = prefs.keepScreenOn
        applyKeepScreenOn()
    }

    private fun saveSettingsFromUi(): Boolean {
        val url = etApiUrl.text.toString().trim()
        val mac = etMac.text.toString().trim().uppercase()
        if (cbApiEnabled.isChecked && !(url.startsWith("http://") || url.startsWith("https://"))) {
            etApiUrl.error = "ต้องขึ้นต้นด้วย http:// หรือ https://"
            return false
        }
        if (mac.isNotEmpty() && !BluetoothAdapter.checkBluetoothAddress(mac)) {
            etMac.error = "รูปแบบ MAC ไม่ถูกต้อง (เช่น C0:22:03:28:03:6F)"
            return false
        }
        prefs.apiUrl = url
        prefs.apiToken = etApiToken.text.toString()
        prefs.targetMac = mac
        prefs.defaultDeviceId = etDeviceId.text.toString().ifBlank { AppSettings.DEFAULT_DEVICE_ID }
        prefs.apiEnabled = cbApiEnabled.isChecked
        prefs.fetchHistory = cbFetchHistory.isChecked
        prefs.keepScreenOn = cbKeepScreenOn.isChecked
        applyKeepScreenOn()
        return true
    }

    private fun applyKeepScreenOn() {
        if (prefs.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ------------------------------------------------------------ start flow

    private fun requestPermissionsThenStart() {
        val missing = (Permissions.bluetooth + Permissions.optional).filterNot { Permissions.has(this, it) }
        if (missing.isEmpty()) ensureBluetoothThenStart()
        else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun bluetoothEnabled(): Boolean =
        getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    private fun ensureBluetoothThenStart() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        when {
            adapter == null -> toast("อุปกรณ์นี้ไม่รองรับ Bluetooth")
            !adapter.isEnabled -> enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            else -> startCollector()
        }
    }

    private fun startCollector() {
        prefs.autoStart = true
        BpCollectorService.start(this)
    }

    // ------------------------------------------------------------ bus listener

    override fun onStatus(status: String) {
        tvStatus.text = status
    }

    override fun onLog(line: String) {
        val current = tvLog.text?.toString().orEmpty()
        tvLog.text = (line + "\n" + current).lineSequence().take(80).joinToString("\n")
        if (line.contains("CSV") || line.contains("Cloud API")) refreshFilesInfo()
    }

    override fun onReading(reading: BpReading) {
        tvSys.text = reading.sys.toString()
        tvDia.text = reading.dia.toString()
        tvPulse.text = reading.pulse.toString()
        tvReadingInfo.text = "${reading.deviceId} • ${reading.timestamp.replace('T', ' ')}"
    }

    override fun onRunningChanged(running: Boolean) {
        btnStart.isEnabled = !running
        btnStop.isEnabled = running
    }

    private fun refreshFilesInfo() {
        tvFiles.text = "ไฟล์ CSV: ${storage.csvFile.absolutePath}\n" +
                "ค้างส่ง API: ${storage.pendingCount()} รายการ"
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
