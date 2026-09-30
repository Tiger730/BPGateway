package th.bms.bpgateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** เปิดบริการอัตโนมัติหลังรีสตาร์ทเครื่อง (ถ้าเคยกด "เริ่ม" ไว้และได้รับสิทธิ์ครบ) */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = AppSettings(context)
        if (prefs.autoStart && Permissions.hasBluetooth(context)) {
            runCatching { BpCollectorService.start(context) }
        }
    }
}
