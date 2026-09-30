package th.bms.bpgateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class BpParserTest {

    private val bkk = ZoneId.of("Asia/Bangkok")

    @Test
    fun yuwellRealPayload1() {
        // ข้อมูลจริงจาก data.csv เดิม
        val r = BpParser.parse("1e7e0056006300ea0709100e24285200000000".hexToBytes(), bkk)
        assertEquals(126, r.sys)
        assertEquals(86, r.dia)
        assertEquals(82, r.pulse)
        assertEquals("2026-09-16T14:36:40+07:00", r.timestamp)
        assertTrue(r.hasDeviceTime)
    }

    @Test
    fun yuwellRealPayload2() {
        val r = BpParser.parse("1e8b0061006f00ea0709100f16166200010000".hexToBytes(), bkk)
        assertEquals(139, r.sys)
        assertEquals(97, r.dia)
        assertEquals(98, r.pulse)
        assertEquals("2026-09-16T15:22:22+07:00", r.timestamp)
    }

    @Test
    fun noTimestampUsesNow() {
        // flags 0x04: มีแค่ pulse -> pulse อยู่ที่ byte 7
        val fixed = ZonedDateTime.of(2026, 1, 2, 3, 4, 5, 0, bkk)
        val r = BpParser.parse("047800500060004800".hexToBytes(), bkk) { fixed }
        assertEquals(120, r.sys)
        assertEquals(80, r.dia)
        assertEquals(72, r.pulse)
        assertEquals("2026-01-02T03:04:05+07:00", r.timestamp)
        assertFalse(r.hasDeviceTime)
    }

    @Test
    fun kpaIsConvertedUsingSfloat() {
        // 16.0 kPa = mantissa 160, exp -1 -> 0xF0A0 ; 10.7 kPa = 107, exp -1 -> 0xF06B
        val r = BpParser.parse("01a0f06bf00000".hexToBytes(), bkk)
        assertEquals(120, r.sys)   // 16.0 * 7.50062 = 120.01
        assertEquals(80, r.dia)    // 10.7 * 7.50062 = 80.26
    }

    @Test(expected = IllegalArgumentException::class)
    fun tooShort() {
        BpParser.parse("1e7e00".hexToBytes(), bkk)
    }
}
