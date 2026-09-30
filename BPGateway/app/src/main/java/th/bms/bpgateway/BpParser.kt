package th.bms.bpgateway

import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** ผลการวัด 1 รายการ */
data class BpReading(
    val sys: Int,
    val dia: Int,
    val pulse: Int,
    val timestamp: String,       // ISO-8601 พร้อม offset เช่น 2026-09-16T14:36:40+07:00
    val rawHex: String,
    val hasDeviceTime: Boolean,  // true = เวลามาจากตัวเครื่องวัด
    val deviceId: String = ""
)

/**
 * ถอดรหัส GATT Blood Pressure Measurement (0x2A35) ตามมาตรฐาน Bluetooth SIG
 *
 * byte[0]  Flags: bit0 หน่วย (0=mmHg, 1=kPa), bit1 มี timestamp, bit2 มี pulse,
 *                 bit3 มี User ID, bit4 มี Measurement Status
 * byte[1-2] SYS  (SFLOAT)
 * byte[3-4] DIA  (SFLOAT)
 * byte[5-6] MAP  (SFLOAT)
 * byte[7-13] Timestamp (ถ้ามี) : ปี(2) เดือน วัน ชม. นาที วินาที
 * ถัดไป    Pulse (SFLOAT, ถ้ามี)
 *
 * สำหรับ Yuwell YE680B (flags = 0x1E) pulse จะอยู่ที่ byte[14-15] ตรงกับโค้ด Python เดิม
 * ต่างจากเดิมตรงที่อ่านค่าแบบ SFLOAT จริง จึงแปลงหน่วย kPa ได้ถูกต้อง
 */
object BpParser {

    private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    /** IEEE-11073 16-bit SFLOAT; คืน null ถ้าเป็นค่าพิเศษ (NaN, NRes, +/-INF) */
    fun sfloat(raw: Int): Double? {
        var mantissa = raw and 0x0FFF
        if (mantissa in 0x07FE..0x0802) return null
        if (mantissa >= 0x0800) mantissa -= 0x1000
        var exponent = (raw shr 12) and 0x0F
        if (exponent >= 0x08) exponent -= 0x10
        return mantissa * Math.pow(10.0, exponent.toDouble())
    }

    private fun u16(d: ByteArray, i: Int): Int =
        (d[i].toInt() and 0xFF) or ((d[i + 1].toInt() and 0xFF) shl 8)

    private fun u8(d: ByteArray, i: Int): Int = d[i].toInt() and 0xFF

    fun parse(
        data: ByteArray,
        zone: ZoneId = ZoneId.systemDefault(),
        now: () -> ZonedDateTime = { ZonedDateTime.now(zone) }
    ): BpReading {
        require(data.size >= 7) { "Payload สั้นเกินไป: ${data.size} bytes (${data.toHex()})" }

        val flags = u8(data, 0)
        val isKpa = (flags and 0x01) != 0
        val hasTime = (flags and 0x02) != 0
        val hasPulse = (flags and 0x04) != 0

        var sys = sfloat(u16(data, 1)) ?: throw IllegalArgumentException("SYS ไม่ถูกต้อง")
        var dia = sfloat(u16(data, 3)) ?: throw IllegalArgumentException("DIA ไม่ถูกต้อง")

        var offset = 7
        var deviceTime: ZonedDateTime? = null
        if (hasTime) {
            if (data.size >= offset + 7) {
                val year = u16(data, offset)
                val month = u8(data, offset + 2)
                val day = u8(data, offset + 3)
                val hour = u8(data, offset + 4)
                val minute = u8(data, offset + 5)
                val second = u8(data, offset + 6)
                if (year in 2000..2100 && month in 1..12 && day in 1..31) {
                    deviceTime = runCatching {
                        ZonedDateTime.of(year, month, day, hour, minute, second, 0, zone)
                    }.getOrNull()
                }
            }
            offset += 7
        }

        var pulse = 0.0
        if (hasPulse && data.size >= offset + 2) {
            pulse = sfloat(u16(data, offset)) ?: 0.0
        }

        if (isKpa) {
            sys *= 7.50062
            dia *= 7.50062
        }

        val ts = (deviceTime ?: now()).withNano(0).format(ISO)
        return BpReading(
            sys = sys.roundToInt(),
            dia = dia.roundToInt(),
            pulse = pulse.roundToInt(),
            timestamp = ts,
            rawHex = data.toHex(),
            hasDeviceTime = deviceTime != null
        )
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()
