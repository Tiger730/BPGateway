package th.bms.bpgateway

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object Permissions {
    /** สิทธิ์ที่จำเป็นต้องมีจึงจะทำงานได้ */
    val bluetooth: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    /** สิทธิ์เสริม (แจ้งเตือน) ไม่ได้ก็ยังทำงานได้ */
    val optional: Array<String>
        get() = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()

    fun has(ctx: Context, p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun hasBluetooth(ctx: Context) = bluetooth.all { has(ctx, it) }
}
