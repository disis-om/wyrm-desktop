package com.wyrm.desktop

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.wyrm.omrajput.ui.SkinCatalog
import com.wyrm.omrajput.ui.SkinTextures
import com.wyrm.omrajput.ui.WyrmLookSpec
import com.wyrm.omrajput.ui.drawSkinStrip
import com.wyrm.omrajput.ui.skinStripSpan
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The 66 default skins as pictures (OM, 2026-10-02): drawn at BUILD time from
 * the real atlases (the same one-line snake as the preview, as Android's
 * Share-skin sticker is one picture of the real skin), shipped inside the app
 * as `presets/preset-NN.png`, and loaded when the app starts. The list shows
 * only pictures; nothing is drawn bead by bead on the player's computer.
 * Built by the Gradle task `generatePresetPictures` (src/tools).
 */
object PresetPreviews {
    /** Bead size the pictures are drawn at; the list scales them down. */
    const val BEAD = 26f

    /** Beads in a picture: fewer than 256 so the line is thick in a list row. */
    const val BEADS = 228

    val images = mutableStateMapOf<Int, ImageBitmap>()

    fun resourceName(index: Int) = "presets/preset-%02d.png".format(index)

    /** At app start, off the UI thread: every shipped picture into [images]. */
    fun loadBundled() {
        for (index in SkinCatalog.presets.indices) {
            val bytes = javaClass.classLoader.getResourceAsStream(resourceName(index))?.use { it.readBytes() } ?: continue
            val image = runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull() ?: continue
            Snapshot.withMutableSnapshot { images[index] = image }
        }
    }

    /** One preset's picture: the build-time generator calls this. */
    fun draw(t: SkinTextures, index: Int): ImageBitmap {
        val groups = SkinCatalog.presets[index]
        val width = ceil(BEAD * skinStripSpan(BEADS)).toInt() + 4
        val height = ceil(BEAD * 1.7f).toInt() + 4
        val bitmap = ImageBitmap(width, height)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(width.toFloat(), height.toFloat())) {
            drawSkinStrip(t, List(BEADS) { groups[it % groups.size] }, List(BEADS) { 0 }, index, false, -1, WyrmLookSpec(), 2f, height / 2f, BEAD, 1f, BEADS) { img, l, top, w, h, tint, alpha ->
                if (w > 0f && h > 0f) {
                    drawImage(
                        image = img,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(img.width, img.height),
                        dstOffset = IntOffset(l.roundToInt(), top.roundToInt()),
                        dstSize = IntSize(maxOf(1, w.roundToInt()), maxOf(1, h.roundToInt())),
                        alpha = alpha,
                        colorFilter = tint?.let { ColorFilter.tint(it, BlendMode.Modulate) },
                    )
                }
            }
        }
        return bitmap
    }

    fun png(bitmap: ImageBitmap): ByteArray =
        Image.makeFromBitmap(bitmap.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!.bytes
}
