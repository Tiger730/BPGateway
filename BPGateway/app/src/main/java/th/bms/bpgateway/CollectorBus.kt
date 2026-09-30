package th.bms.bpgateway

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet

/** ช่องทางส่งสถานะ/log/ผลการวัด จาก Service ไปยังหน้าจอ */
object CollectorBus {

    interface Listener {
        fun onStatus(status: String) {}
        fun onLog(line: String) {}
        fun onReading(reading: BpReading) {}
        fun onRunningChanged(running: Boolean) {}
    }

    private const val TAG = "BPGateway"
    private const val MAX_LOG = 300
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val logLines = ArrayDeque<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile var lastStatus: String = "ยังไม่เริ่มบริการ"
        private set
    @Volatile var lastReading: BpReading? = null
        private set
    @Volatile var running: Boolean = false
        private set

    fun addListener(l: Listener) = listeners.add(l)
    fun removeListener(l: Listener) = listeners.remove(l)

    fun status(s: String) {
        lastStatus = s
        log(s)
        main.post { listeners.forEach { it.onStatus(s) } }
    }

    fun log(msg: String) {
        Log.i(TAG, msg)
        val line = synchronized(timeFmt) { timeFmt.format(Date()) } + "  " + msg
        synchronized(logLines) {
            logLines.addLast(line)
            while (logLines.size > MAX_LOG) logLines.removeFirst()
        }
        main.post { listeners.forEach { it.onLog(line) } }
    }

    fun logs(): List<String> = synchronized(logLines) { logLines.toList() }

    fun reading(r: BpReading) {
        lastReading = r
        main.post { listeners.forEach { it.onReading(r) } }
    }

    fun setRunning(v: Boolean) {
        running = v
        main.post { listeners.forEach { it.onRunningChanged(v) } }
    }
}
