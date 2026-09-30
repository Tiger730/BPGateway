package th.bms.bpgateway

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * บันทึก CSV (คอลัมน์เดียวกับ data.csv เดิม) + คิวข้อมูลที่ส่ง API ไม่สำเร็จ (pending.jsonl)
 * ไฟล์อยู่ที่ Android/data/th.bms.bpgateway/files/
 */
class CsvStorage(ctx: Context) {

    private val dir: File = ctx.getExternalFilesDir(null) ?: ctx.filesDir
    val csvFile = File(dir, "data.csv")
    private val pendingFile = File(dir, "pending.jsonl")

    @Synchronized
    fun appendCsv(r: BpReading): Boolean = try {
        val isNew = !csvFile.exists() || csvFile.length() == 0L
        csvFile.appendText(buildString {
            if (isNew) append("timestamp,device_id,sys,dia,pulse,raw_hex\r\n")
            append(listOf(r.timestamp, csvEscape(r.deviceId), r.sys, r.dia, r.pulse, r.rawHex).joinToString(","))
            append("\r\n")
        })
        true
    } catch (e: Exception) {
        CollectorBus.log("บันทึก CSV ไม่สำเร็จ: ${e.message}")
        false
    }

    @Synchronized
    fun addPending(r: BpReading) {
        try {
            pendingFile.appendText(toJson(r).toString() + "\n")
        } catch (e: Exception) {
            CollectorBus.log("บันทึกคิวค้างส่งไม่สำเร็จ: ${e.message}")
        }
    }

    @Synchronized
    fun pendingCount(): Int =
        if (pendingFile.exists()) pendingFile.readLines().count { it.isNotBlank() } else 0

    /** ลองส่งข้อมูลค้างส่งอีกครั้ง คืนจำนวนที่ส่งสำเร็จ */
    @Synchronized
    fun retryPending(send: (BpReading) -> Boolean): Int {
        if (!pendingFile.exists()) return 0
        val lines = pendingFile.readLines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return 0
        var sent = 0
        val remaining = mutableListOf<String>()
        for (line in lines) {
            val r = runCatching { fromJson(JSONObject(line)) }.getOrNull() ?: continue
            if (send(r)) sent++ else remaining += line
        }
        if (remaining.isEmpty()) pendingFile.delete()
        else pendingFile.writeText(remaining.joinToString("\n", postfix = "\n"))
        return sent
    }

    private fun csvEscape(s: String) =
        if (s.contains(',') || s.contains('"')) "\"" + s.replace("\"", "\"\"") + "\"" else s

    companion object {
        fun toJson(r: BpReading): JSONObject = JSONObject()
            .put("device_id", r.deviceId)
            .put("sys", r.sys)
            .put("dia", r.dia)
            .put("pulse", r.pulse)
            .put("timestamp", r.timestamp)
            .put("raw_hex", r.rawHex)

        fun fromJson(o: JSONObject) = BpReading(
            sys = o.getInt("sys"),
            dia = o.getInt("dia"),
            pulse = o.getInt("pulse"),
            timestamp = o.getString("timestamp"),
            rawHex = o.optString("raw_hex"),
            hasDeviceTime = true,
            deviceId = o.optString("device_id", AppSettings.DEFAULT_DEVICE_ID)
        )
    }
}
