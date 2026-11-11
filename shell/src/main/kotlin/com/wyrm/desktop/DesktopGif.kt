package com.wyrm.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image

/**
 * Chat GIFs and animated WebPs on the desktop (OM, 2026-11-11): Android plays
 * them through ImageDecoder; here Skia's Codec decodes every frame (each over
 * the one before, as the file asks) and WyrmChat's view steps through them.
 * At most 150 frames and 4096 x 4096; a still picture is one frame.
 */
internal object DesktopGif {
    class Frames(val images: List<ImageBitmap>, val durations: List<Int>)

    fun decode(bytes: ByteArray): Frames? = runCatching {
        val codec = Codec.makeFromData(Data.makeFromBytes(bytes))
        val info = codec.imageInfo
        if (info.width <= 0 || info.height <= 0 || info.width > 4096 || info.height > 4096) return null
        val count = codec.frameCount.coerceIn(1, 150)
        val infos = runCatching { codec.framesInfo }.getOrNull()
        val images = ArrayList<ImageBitmap>(count)
        val durations = ArrayList<Int>(count)
        val canvas = Bitmap()
        canvas.allocPixels(info)
        for (frame in 0 until count) {
            codec.readPixels(canvas, frame, if (frame == 0) -1 else frame - 1)
            images += Image.makeFromBitmap(canvas.makeClone().apply { setImmutable() }).toComposeImageBitmap()
            durations += infos?.getOrNull(frame)?.duration ?: 100
        }
        Frames(images, durations)
    }.getOrNull()
}
