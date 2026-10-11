package com.wyrm.desktop

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.wyrm.omrajput.ui.toUploadBytes
import java.io.File

/**
 * Checks the desktop stand-ins for Android's graphics and cache classes that
 * the shared Social / Trails code uses (D5): decode with bounds and sampling,
 * crop through Canvas, JPEG out and back, ImageBitmap bridges, LRU eviction.
 * gradlew shimProbe; prints one line per check and "ALL OK" at the end.
 */
fun main() {
    var ok = true
    fun check(name: String, pass: Boolean, detail: String = "") {
        println("Shim probe: ${if (pass) "ok  " else "FAIL"} $name $detail")
        if (!pass) ok = false
    }
    val png = File(System.getProperty("wyrm.android.root"), "android/app/src/main/res/drawable-nodpi/about_upi_qr.png")
        .takeIf { it.isFile } ?: File("build/resources/main/assets/about_upi_qr.png")
    val bytes = png.readBytes()

    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val none = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    check("bounds", none == null && bounds.outWidth > 0 && bounds.outHeight > 0, "${bounds.outWidth}x${bounds.outHeight}")

    val half = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 })
    check("inSampleSize 2", half != null && half.width == (bounds.outWidth + 1) / 2, "${half?.width}x${half?.height}")

    val full = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
    val out = android.graphics.Bitmap.createBitmap(100, 100, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(out)
    canvas.drawColor(android.graphics.Color.BLACK)
    canvas.save()
    canvas.translate(50f, 50f)
    canvas.scale(100f / full.width, 100f / full.height)
    canvas.drawBitmap(full, -full.width / 2f, -full.height / 2f, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
    canvas.restore()
    check("canvas crop", out.width == 100 && (out.getPixel(0, 0) ushr 24) == 0xFF)

    val jpeg = out.toUploadBytes()
    val back = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
    check("jpeg round trip", jpeg.size > 100 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte() && back?.width == 100, "${jpeg.size} B")

    val scaled = android.graphics.Bitmap.createScaledBitmap(full, 7, 9, true)
    check("scaled", scaled.width == 7 && scaled.height == 9)

    val composed = full.asImageBitmap()
    val again = composed.asAndroidBitmap()
    check("ImageBitmap bridge", composed.width == full.width && again.height == full.height)

    val lru = object : android.util.LruCache<String, ByteArray>(10) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }
    lru.put("a", ByteArray(4)); lru.put("b", ByteArray(4)); lru.get("a"); lru.put("c", ByteArray(4))
    check("lru evicts least recent", lru.get("b") == null && lru.get("a") != null && lru.get("c") != null && lru.size() == 8)

    println(if (ok) "Shim probe: ALL OK" else "Shim probe: FAILED")
}
