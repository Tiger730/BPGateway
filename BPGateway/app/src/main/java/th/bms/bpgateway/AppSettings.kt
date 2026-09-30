package th.bms.bpgateway

import android.content.Context

/** ค่าตั้งค่าทั้งหมด (แทน config.py / .env เดิม) เก็บใน SharedPreferences */
class AppSettings(ctx: Context) {

    companion object {
        const val DEFAULT_API_URL = "https://kiosk-track.bmscloud.in.th/api/dev/bp"
        const val DEFAULT_MAC = "C0:22:03:28:03:6F"
        const val DEFAULT_DEVICE_ID = "YUWELL_YE680B"
        private const val RECENT_LIMIT = 50
    }

    private val sp = ctx.applicationContext.getSharedPreferences("bp_gateway", Context.MODE_PRIVATE)

    var apiUrl: String
        get() = sp.getString("api_url", DEFAULT_API_URL) ?: DEFAULT_API_URL
        set(v) = sp.edit().putString("api_url", v.trim()).apply()

    /** ถ้ากรอก จะส่ง header "Authorization: Bearer <token>" ไปด้วย */
    var apiToken: String
        get() = sp.getString("api_token", "") ?: ""
        set(v) = sp.edit().putString("api_token", v.trim()).apply()

    /** MAC ของเครื่องวัด (ว่าง = ค้นหาจากชื่อ/Service 0x1810 แทน) */
    var targetMac: String
        get() = sp.getString("target_mac", DEFAULT_MAC) ?: ""
        set(v) = sp.edit().putString("target_mac", v.trim().uppercase()).apply()

    var defaultDeviceId: String
        get() = sp.getString("device_id", DEFAULT_DEVICE_ID) ?: DEFAULT_DEVICE_ID
        set(v) = sp.edit().putString("device_id", v.trim()).apply()

    var apiEnabled: Boolean
        get() = sp.getBoolean("api_enabled", true)
        set(v) = sp.edit().putBoolean("api_enabled", v).apply()

    /** true = กวาดประวัติทั้งหมดในเครื่องผ่าน RACP, false = รับเฉพาะค่าล่าสุด */
    var fetchHistory: Boolean
        get() = sp.getBoolean("fetch_history", false)
        set(v) = sp.edit().putBoolean("fetch_history", v).apply()

    var keepScreenOn: Boolean
        get() = sp.getBoolean("keep_screen_on", true)
        set(v) = sp.edit().putBoolean("keep_screen_on", v).apply()

    /** เปิดบริการอัตโนมัติเมื่อเปิดแอป / เปิดเครื่อง (ตั้งเป็น true เมื่อกดเริ่ม, false เมื่อกดหยุด) */
    var autoStart: Boolean
        get() = sp.getBoolean("auto_start", false)
        set(v) = sp.edit().putBoolean("auto_start", v).apply()

    /**
     * กันข้อมูลซ้ำ: คืน true ถ้ายังไม่เคยเห็น raw payload นี้ (แล้วจำไว้)
     * ใช้เฉพาะ payload ที่มีเวลาจากตัวเครื่อง เพราะ raw จะไม่ซ้ำกันระหว่างการวัดคนละครั้ง
     */
    @Synchronized
    fun rememberRaw(hex: String): Boolean {
        val list = (sp.getString("recent_raw", "") ?: "").split(",").filter { it.isNotEmpty() }
        if (hex in list) return false
        val updated = (list + hex).takeLast(RECENT_LIMIT)
        sp.edit().putString("recent_raw", updated.joinToString(",")).apply()
        return true
    }
}
