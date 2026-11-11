package androidx.compose.ui.graphics

/*
 * Wyrm Desktop: Compose's Android-only bridges between android.graphics.Bitmap
 * and ImageBitmap, over the desktop Bitmap shim (a BufferedImage).
 */

/** Immutable (OM, 2026-11-11): Compose draws through Skia's makeFromBitmap,
 *  which copies and re-uploads a mutable bitmap on every draw. The picture is
 *  a fresh copy of the shim's pixels, never drawn into afterwards. */
fun android.graphics.Bitmap.asImageBitmap(): ImageBitmap =
    image().toComposeImageBitmap().also { it.asSkiaBitmap().setImmutable() }

fun ImageBitmap.asAndroidBitmap(): android.graphics.Bitmap = android.graphics.Bitmap(toAwtImage())
