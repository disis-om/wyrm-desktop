package androidx.compose.ui.graphics

/*
 * Wyrm Desktop: Compose's Android-only bridges between android.graphics.Bitmap
 * and ImageBitmap, over the desktop Bitmap shim (a BufferedImage).
 */

fun android.graphics.Bitmap.asImageBitmap(): ImageBitmap = image().toComposeImageBitmap()

fun ImageBitmap.asAndroidBitmap(): android.graphics.Bitmap = android.graphics.Bitmap(toAwtImage())
