package com.wyrm.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wyrm.omrajput.WyrmActivity
import com.wyrm.omrajput.ui.LocalWyrmPalette
import com.wyrm.omrajput.ui.TileGrid
import com.wyrm.omrajput.ui.Wyrm
import com.wyrm.omrajput.ui.wyrmRounded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.UUID
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * Your own photo as the arena floor, on the computer (Android's CustomFloors,
 * OM 2026-11-08; Wyrm Desktop 2026-11-11). A picture from the computer's Open
 * dialog is shrunk (long side 2048 px), saved as a JPEG under
 * %APPDATA%\Wyrm\floors and listed above the built-in floors in Skin ›
 * Background; the engine draws the chosen one as background [INDEX]
 * (game/wyrm_extras.c). The × on a photo deletes it.
 *
 * This computer only: never uploaded, prefs `wyrm_custom_floors` are WIPE in
 * the account registry, and the shared document never carries index 200.
 */
internal object DesktopFloors {
    const val INDEX = 200
    private const val MAX_SIDE = 2048
    private const val PREFS = "wyrm_custom_floors"

    data class Floor(val id: String, val path: String, val width: Int, val height: Int)

    val floors = mutableStateListOf<Floor>()
    var chosen by mutableStateOf<String?>(null)
        private set

    private val dir: File get() =
        File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "Wyrm/floors").apply { mkdirs() }

    /** At engine start and after an account restore or log out. */
    fun reload() {
        val prefs = DesktopContext.getSharedPreferences(PREFS, 0)
        val list = runCatching { JSONArray(prefs.getString("floors", "[]")) }.getOrDefault(JSONArray())
        val loaded = (0 until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            Floor(o.optString("id"), o.optString("path"), o.optInt("w"), o.optInt("h"))
                .takeIf { it.id.isNotBlank() && File(it.path).isFile && it.width > 0 && it.height > 0 }
        }
        floors.clear(); floors.addAll(loaded)
        // Pictures nobody lists any more (a log out cleared the list) go.
        val keep = loaded.map { File(it.path).name }.toSet()
        dir.listFiles()?.forEach { if (it.name !in keep) it.delete() }
        chosen = prefs.getString("chosen", null)?.takeIf { id -> loaded.any { it.id == id } }
        publish()
    }

    private fun save() {
        val list = JSONArray()
        floors.forEach { list.put(JSONObject().put("id", it.id).put("path", it.path).put("w", it.width).put("h", it.height)) }
        DesktopContext.getSharedPreferences(PREFS, 0).edit()
            .putString("floors", list.toString()).putString("chosen", chosen).commit()
    }

    private fun publish() {
        val floor = floors.firstOrNull { it.id == chosen }
        runCatching { WyrmActivity.setCustomFloor(floor?.path.orEmpty(), floor?.width ?: 0, floor?.height ?: 0) }
    }

    /** Shrinks and saves a picture; null when it could not be read. Off the window's thread. */
    fun add(source: File): Floor? = runCatching {
        val read = ImageIO.read(source) ?: return null
        val scale = minOf(1.0, MAX_SIDE.toDouble() / maxOf(read.width, read.height))
        val w = maxOf(1, (read.width * scale).toInt())
        val h = maxOf(1, (read.height * scale).toInt())
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.color = java.awt.Color.BLACK
        g.fillRect(0, 0, w, h)
        g.drawImage(read, 0, 0, w, h, null)
        g.dispose()
        val id = UUID.randomUUID().toString()
        val file = File(dir, "$id.jpg")
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        try {
            ImageIO.createImageOutputStream(file).use { stream ->
                writer.output = stream
                val param = writer.defaultWriteParam.apply {
                    compressionMode = ImageWriteParam.MODE_EXPLICIT
                    compressionQuality = 0.88f
                }
                writer.write(null, IIOImage(out, null, null), param)
            }
        } finally { writer.dispose() }
        Floor(id, file.absolutePath, w, h)
    }.getOrNull()

    fun keep(floor: Floor) { floors.add(floor); save() }

    fun choose(id: String) { chosen = id; save(); publish() }

    /** Deletes one photo; true when it was the worn one. */
    fun delete(id: String): Boolean {
        val gone = floors.filter { it.id == id }
        gone.forEach { File(it.path).delete() }
        floors.removeAll(gone)
        val worn = chosen == id
        if (worn) chosen = null
        save(); publish()
        return worn
    }

    fun thumbnail(floor: Floor): ImageBitmap? = runCatching {
        val read = ImageIO.read(File(floor.path)) ?: return null
        val scale = minOf(1.0, 320.0 / maxOf(read.width, read.height))
        val w = maxOf(1, (read.width * scale).toInt()); val h = maxOf(1, (read.height * scale).toInt())
        val small = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = small.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(read, 0, 0, w, h, null); g.dispose()
        small.toComposeImageBitmap().also { it.asSkiaBitmap().setImmutable() }
    }.getOrNull()
}

/** Skin › Background on the computer: your photos and the Add tile (SkinScreenHooks.floorPhotos). */
@Composable
internal fun DesktopFloorPhotos(worn: Int, wear: (Int) -> Unit) {
    val palette = LocalWyrmPalette.current ?: Wyrm.currentPalette
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val photos = DesktopFloors.floors
    TileGrid(minimum = 104.dp, count = photos.size + 1, aspect = null, fixedColumns = 4) { index ->
        val shape = wyrmRounded(16.dp)
        if (index < photos.size) {
            val floor = photos[index]
            val selected = worn == DesktopFloors.INDEX && DesktopFloors.chosen == floor.id
            val image by produceState<ImageBitmap?>(null, floor.id) {
                value = withContext(Dispatchers.IO) { DesktopFloors.thumbnail(floor) }
            }
            Column(
                Modifier.fillMaxWidth().clip(shape).background(palette.card.copy(alpha = 0.92f))
                    .border(if (selected) 2.dp else 1.dp, if (selected) palette.ink else palette.rule, shape)
                    .clickable(remember { MutableInteractionSource() }, indication = null) {
                        DesktopFloors.choose(floor.id); wear(DesktopFloors.INDEX)
                    }.padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.fillMaxWidth().height(78.dp).clip(wyrmRounded(13.dp)).background(palette.ink)) {
                    image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(22.dp).clip(CircleShape)
                        .background(Color.Black.copy(alpha = .55f))
                        .clickable { if (DesktopFloors.delete(floor.id) && worn == DesktopFloors.INDEX) wear(0) },
                        contentAlignment = Alignment.Center) {
                        Text("×", color = Color.White, fontSize = 15.sp, fontFamily = Wyrm.Body)
                    }
                }
                Text("Your photo", fontFamily = Wyrm.Body, fontSize = 12.5.sp, color = palette.ink, maxLines = 1,
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }
        } else {
            Column(
                Modifier.fillMaxWidth().clip(shape).background(palette.card.copy(alpha = 0.92f))
                    .border(1.dp, palette.rule, shape)
                    .clickable(remember { MutableInteractionSource() }, indication = null) {
                        if (busy) return@clickable
                        val file = DesktopFiles.chooseImage("Your photo as the arena floor") ?: return@clickable
                        busy = true; failed = false
                        scope.launch {
                            val floor = withContext(Dispatchers.IO) { DesktopFloors.add(file) }
                            busy = false
                            if (floor == null) failed = true else {
                                DesktopFloors.keep(floor)
                                DesktopFloors.choose(floor.id)
                                wear(DesktopFloors.INDEX)
                            }
                        }
                    }.padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.fillMaxWidth().height(78.dp).clip(wyrmRounded(13.dp)).background(palette.ink.copy(alpha = .06f)),
                    contentAlignment = Alignment.Center) {
                    Text(if (busy) "…" else "+", fontFamily = Wyrm.Body, fontSize = 30.sp, color = palette.ink)
                }
                Text(if (failed) "Could not read it" else "Add your photo", fontFamily = Wyrm.Body, fontSize = 12.5.sp,
                    color = if (failed) Color(0xFFE5484D) else palette.ink, maxLines = 1,
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }
        }
    }
    Spacer(Modifier.height(10.dp))
}
