package th.bms.bpgateway

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** ส่ง HTTP POST JSON ในรูปแบบเดียวกับ api_client.py เดิม (ต้องเรียกจาก background thread) */
object ApiClient {

    data class Result(val ok: Boolean, val message: String)

    fun post(r: BpReading, apiUrl: String, token: String = "", timeoutMs: Int = 5000): Result {
        if (apiUrl.isBlank()) return Result(false, "ยังไม่ได้ตั้งค่า API URL")

        val payload = JSONObject()
            .put("device_id", r.deviceId.ifBlank { AppSettings.DEFAULT_DEVICE_ID })
            .put("sys", r.sys)
            .put("dia", r.dia)
            .put("pulse", r.pulse)
            .put("timestamp", r.timestamp)

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val body = runCatching {
                (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
            }.getOrDefault("").take(200)
            if (code in 200..299) Result(true, "HTTP $code $body")
            else Result(false, "HTTP $code $body")
        } catch (e: Exception) {
            Result(false, "${e.javaClass.simpleName}: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }
}
