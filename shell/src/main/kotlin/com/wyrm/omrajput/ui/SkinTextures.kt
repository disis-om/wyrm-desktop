package com.wyrm.omrajput.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Wyrm Desktop's SkinTextures: the same class and the same cuts as Wyrm
 * Android's SkinTextures.kt (which decodes with android.graphics), done with
 * ImageIO. The textures are the engine's own (`Wyrm Android/app/res`, the
 * folder Android packages as its assets), so every bead, accessory, tag, look
 * and floor here is the one the arena draws.
 */
class SkinTextures(
    val beads: Map<Int, ImageBitmap>,
    val airBeads: Map<Int, ImageBitmap>,
    val airShadow: ImageBitmap?,
    val airWheel: ImageBitmap?,
    val accessories: Map<Int, ImageBitmap>,
    val accessoryThumbnails: Map<Int, ImageBitmap>,
    val tags: Map<Int, ImageBitmap>,
    val tagThumbnails: Map<Int, ImageBitmap>,
    val backgrounds: Map<Int, ImageBitmap>,
    val wyrmBeads: Map<Int, ImageBitmap> = emptyMap(),
    val looks: Map<Int, ImageBitmap> = emptyMap(),
    val lookThumbnails: Map<Int, ImageBitmap> = emptyMap(),
) {
    companion object {
        private val lock = Mutex()
        @Volatile private var loaded: SkinTextures? = null

        private val res: File get() = File(System.getProperty("wyrm.android.root") ?: ".", "app/res")

        suspend fun load(): SkinTextures? = lock.withLock {
            loaded ?: withContext(Dispatchers.IO) { runCatching { build() }.getOrNull() }?.also { loaded = it }
        }

        fun cached(): SkinTextures? = loaded

        /** Android's decode: subsampled by powers of two until the long side is below maxPixel. */
        private fun decode(path: String, maxPixel: Int): BufferedImage? = runCatching {
            val file = File(res, path)
            ImageIO.createImageInputStream(file).use { stream ->
                val reader = ImageIO.getImageReaders(stream).next()
                try {
                    reader.input = stream
                    val w = reader.getWidth(0)
                    val h = reader.getHeight(0)
                    var sample = 1
                    while (max(w, h) / (sample * 2) >= maxPixel) sample *= 2
                    val param = reader.defaultReadParam
                    if (sample > 1) param.setSourceSubsampling(sample, sample, 0, 0)
                    argb(reader.read(0, param))
                } finally {
                    reader.dispose()
                }
            }
        }.getOrNull()

        private fun argb(image: BufferedImage): BufferedImage {
            if (image.type == BufferedImage.TYPE_INT_ARGB) return image
            val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
            out.createGraphics().apply { drawImage(image, 0, 0, null); dispose() }
            return out
        }

        private fun copy(image: BufferedImage, x: Int, y: Int, w: Int, h: Int): BufferedImage {
            val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            out.createGraphics().apply { drawImage(image, -x, -y, null); dispose() }
            return out
        }

        private fun lookThumbnail(cell: BufferedImage): BufferedImage? {
            val w = cell.width
            val h = cell.height
            var minX = w
            var minY = h
            var maxX = -1
            var maxY = -1
            for (y in 0 until h) for (x in 0 until w) {
                if ((cell.getRGB(x, y) ushr 24) > 16) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
            if (maxX < 0) return null
            val pad = 3
            val left = max(0, minX - pad)
            val top = max(0, minY - pad)
            val cut = copy(cell, left, top, min(w, maxX + pad + 1) - left, min(h, maxY + pad + 1) - top)
            // A quarter turn left, as Android's postRotate(-90).
            val turned = BufferedImage(cut.height, cut.width, BufferedImage.TYPE_INT_ARGB)
            turned.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                transform = AffineTransform().apply {
                    translate(0.0, cut.width.toDouble())
                    rotate(-Math.PI / 2)
                }
                drawImage(cut, 0, 0, null)
                dispose()
            }
            return turned
        }

        private fun crop(image: BufferedImage, x: Double, y: Double, width: Double, height: Double): BufferedImage? {
            val left = floor(x * image.width).toInt()
            val top = floor(y * image.height).toInt()
            val w = max(1, (width * image.width).roundToInt())
            val h = max(1, (height * image.height).roundToInt())
            val right = minOf(image.width, left + w)
            val bottom = minOf(image.height, top + h)
            if (left !in 0 until image.width || top !in 0 until image.height || right <= left || bottom <= top) return null
            return copy(image, left, top, right - left, bottom - top)
        }

        private fun removingSoftShadow(image: BufferedImage): BufferedImage {
            val out = copy(image, 0, 0, image.width, image.height)
            for (y in 0 until out.height) for (x in 0 until out.width) {
                val p = out.getRGB(x, y)
                val alpha = (p ushr 24) and 0xFF
                out.setRGB(
                    x, y,
                    when {
                        alpha < 76 -> 0
                        alpha < 176 -> (((alpha - 76) * 255 / 100).coerceIn(0, 255) shl 24) or (p and 0x00FFFFFF)
                        else -> p
                    },
                )
            }
            return out
        }

        private fun BufferedImage.bitmap(): ImageBitmap = toComposeImageBitmap()

        private fun build(): SkinTextures? {
            val atlas = decode("textures/tex_atlas_8k.png", 2016) ?: return null
            val tagAtlas = decode("textures/wyrm_tags.png", 4096) ?: return null
            val beads = (0 until 42).mapNotNull { id ->
                crop(atlas, (id % 7) / 7.0, (id / 7) / 9.0, 1.0 / 7, 1.0 / 9)?.let { id to it.bitmap() }
            }.toMap()
            val airBeads = (0 until 2).mapNotNull { kind ->
                crop(atlas, (2 + kind) / 7.0, 6.0 / 9, 1.0 / 7, 1.0 / 9)?.let { kind to it.bitmap() }
            }.toMap()
            val airShadow = crop(atlas, 4.0 / 7, 6.0 / 9, 102.0 / 64 / 7, 102.0 / 64 / 9)?.bitmap()
            val wyrmBeads = (0 until WyrmBeads.COUNT).mapNotNull { kind ->
                val r = WyrmBeads.uv(kind)
                crop(atlas, r[0], r[1], r[2], r[3])?.let { kind to it.bitmap() }
            }.toMap()
            val airWheel = decode("textures/air_colour_wheel.png", 768)?.bitmap()
            val accessories = mutableMapOf<Int, ImageBitmap>()
            val accessoryThumbs = mutableMapOf<Int, ImageBitmap>()
            for (id in 0 until 32) {
                val cell = crop(atlas, 5.0 / 7 + (id % 8) / 28.0, 8.0 / 9 + (id / 8) / 36.0, 1.0 / 28, 1.0 / 36) ?: continue
                accessories[id] = cell.bitmap()
                accessoryThumbs[id] = removingSoftShadow(cell).bitmap()
            }
            val tags = mutableMapOf<Int, ImageBitmap>()
            val tagThumbs = mutableMapOf<Int, ImageBitmap>()
            for (tag in SkinCatalog.tags) {
                val cell = crop(
                    tagAtlas, tag.minU.toDouble(), tag.minV.toDouble(),
                    (tag.maxU - tag.minU).toDouble(), (tag.maxV - tag.minV).toDouble(),
                ) ?: continue
                tags[tag.id] = cell.bitmap()
                tagThumbs[tag.id] = removingSoftShadow(cell).bitmap()
            }
            val backgrounds = SkinCatalog.backgrounds.mapNotNull { background ->
                val path = background.asset ?: return@mapNotNull null
                decode(path, 520)?.let { background.id to it.bitmap() }
            }.toMap()
            val lookSheet = decode("textures/wyrm_accessories.png", 2048)
            val lookCells = if (lookSheet == null) emptyMap() else (0 until 40).mapNotNull { cell ->
                crop(lookSheet, (cell % 8) / 8.0, (cell / 8) / 8.0, 1.0 / 8, 1.0 / 8)?.let { cell to it }
            }.toMap()
            val looks = lookCells.mapValues { it.value.bitmap() }
            val lookThumbs = lookCells.mapNotNull { (cell, image) -> lookThumbnail(image)?.let { cell to it.bitmap() } }.toMap()
            return SkinTextures(beads, airBeads, airShadow, airWheel, accessories, accessoryThumbs, tags, tagThumbs, backgrounds, wyrmBeads, looks, lookThumbs)
        }
    }
}

@Composable
fun rememberSkinTextures(): State<SkinTextures?> =
    produceState(initialValue = SkinTextures.cached()) {
        if (value == null) value = SkinTextures.load()
    }
