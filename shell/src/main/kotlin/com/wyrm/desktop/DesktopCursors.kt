package com.wyrm.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wyrm.omrajput.ui.*
import java.awt.BasicStroke
import java.awt.Cursor
import java.awt.GradientPaint
import java.awt.Point
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.awt.Color as Ink

/** Native window-local pointers. No Windows cursor scheme or global hook. */
internal object DesktopCursors {
    data class Style(val id: String, val name: String, val top: Int, val bottom: Int,
                     val edge: Int = 0x171D1B, val shape: Int = 0, val accent: Int = 0xFFFFFF)
    val styles = listOf(
        Style("ivory", "Ivory Blade", 0xFFF9DD, 0xBEAD82),
        Style("venom", "Venom", 0x75FF98, 0x064D2A, accent = 0x00ED67),
        Style("carbon", "Carbon", 0x575D58, 0x121714, accent = 0xCBF52D),
        Style("ember", "Ember", 0xFFB04B, 0xA62C09, accent = 0xFF7622),
        Style("chrome", "Chrome", 0xF7FCFF, 0x65777D, accent = 0xFFFFFF),
        Style("frost", "Frost", 0xF3FCFF, 0x55BDFA, edge = 0x3C7D9A),
        Style("gold", "Gold Fang", 0xFFEBAC, 0xA56C13),
        Style("split", "Ink Split", 0xFFFFFF, 0xFFFFFF, accent = 0x151B19),
        Style("arcade", "Arcade", 0x00DCE9, 0x006F80, shape = 3),
        Style("fin", "Wyrm Fin", 0xA1D08C, 0x244C31, shape = 4),
        Style("rose", "Rose Steel", 0xDF6C7D, 0x63232F, accent = 0xD8E3E5),
        Style("neon", "Neon Outline", 0x17211B, 0x080D0A, edge = 0xB7F62A),
        Style("marshmallow", "Marshmallow", 0xFFFDF1, 0xE1D5BE, edge = 0x4F3C2F, shape = 1),
        Style("matcha", "Matcha Mochi", 0xDFEDBD, 0x9CBB79, edge = 0x43523B, shape = 1),
        Style("peach", "Peach Jelly", 0xFFD5B5, 0xF2917D, edge = 0x974E3B, shape = 1),
        Style("cloud", "Cloud Puff", 0xFFFFFF, 0xC5DFEE, edge = 0x466677, shape = 5),
        Style("mint", "Mint Bean", 0xE5F5D7, 0x72C7B0, edge = 0x34574C, shape = 4),
        Style("honey", "Honey Drop", 0xFFE7A3, 0xEAB142, edge = 0x79501E, shape = 1),
        Style("berry", "Berry Milk", 0xFFE4E7, 0xEAA1B1, edge = 0x77474C, shape = 1),
        Style("bubblegum", "Bubblegum", 0xF8B6D2, 0x66CDD0, edge = 0x3F6F72, shape = 1),
        Style("sleepy", "Sleepy Wyrm", 0xD4E9A8, 0x79B788, edge = 0x42553D, shape = 4),
        Style("pebble", "Blue Pebble", 0xD6EEFF, 0x8EBCE2, edge = 0x465D73, shape = 1),
        Style("pudding", "Pudding", 0xCA8C4A, 0xFFE4A1, edge = 0x78502E, shape = 1),
        Style("softink", "Soft Ink", 0x615B53, 0xE7DBC5, edge = 0x2B2926, shape = 1),
        Style("paper", "Paper", 0xFCFCF7, 0xFCFCF7, shape = 2),
        Style("graphite", "Graphite", 0x303533, 0x303533, edge = 0xF7F7F0, shape = 2),
        Style("sand", "Sand", 0xE9DFC7, 0xE9DFC7, shape = 2),
        Style("mist", "Mist", 0xCDD5D9, 0xCDD5D9, shape = 2),
        Style("sage", "Sage", 0xB6C8AD, 0xB6C8AD, shape = 2),
        Style("sky", "Sky", 0xB6D9ED, 0xB6D9ED, shape = 2),
        Style("pulse", "Pulse", 0x253E44, 0x0C1A20, edge = 0x61EDFC, shape = 2, accent = 0x61EDFC),
        Style("circuit", "Circuit", 0x384632, 0x111B13, accent = 0xB2ED45, shape = 2),
        Style("drift", "Drift", 0xFFFFFF, 0xD9DCE1, accent = 0xF77767, shape = 2),
        Style("stealth", "Stealth", 0x333D45, 0x10161B, edge = 0xC5D1D6, shape = 2),
        Style("nova", "Nova", 0xF8FCFF, 0xAFCDE8, shape = 2, accent = 0x248EF5),
        Style("volt", "Volt", 0x3B3B35, 0x151A17, edge = 0xF7C74D, accent = 0xFFCC44, shape = 2),
    )
    private val prefs get() = DesktopContext.getSharedPreferences("wyrm_desktop_cursor", 0)
    private var selected by mutableStateOf(prefs.getString("style", "system").orEmpty())
    var laserInNormal by mutableStateOf(prefs.getBoolean("laser_normal", false))
        private set
    private val cache = mutableMapOf<String, PointerIcon>()
    fun current(): String = selected.takeIf { id -> styles.any { it.id == id } } ?: "system"
    fun reload() {
        selected = prefs.getString("style", "system").orEmpty()
        laserInNormal = prefs.getBoolean("laser_normal", false)
    }
    fun setNormalLaser(on: Boolean) { laserInNormal = on; prefs.edit().putBoolean("laser_normal", on).apply() }
    fun select(id: String) {
        if (id != "system" && styles.none { it.id == id }) return
        selected = id
        prefs.edit().putString("style", id).apply()
    }
    fun icon(): PointerIcon {
        val id = current()
        if (styles.none { it.id == id }) return PointerIcon.Default
        return cache.getOrPut(id) { PointerIcon(awtCursor()) }
    }

    /** The same cursor as an AWT cursor, for the native game canvas (DirectGameSurface). */
    fun awtCursor(): Cursor {
        val id = current()
        val style = styles.firstOrNull { it.id == id } ?: return Cursor.getDefaultCursor()
        return awtCache.getOrPut(id) {
            runCatching {
                val toolkit = Toolkit.getDefaultToolkit()
                val best = toolkit.getBestCursorSize(32, 32)
                require(best.width > 0 && best.height > 0)
                val side = minOf(best.width, best.height)
                val image = BufferedImage(best.width, best.height, BufferedImage.TYPE_INT_ARGB)
                val g = image.createGraphics()
                g.drawImage(render(style, side), 0, 0, null); g.dispose()
                toolkit.createCustomCursor(image, Point((4f * side / 32).toInt(), (4f * side / 32).toInt()), "Wyrm $id")
            }.getOrDefault(Cursor.getDefaultCursor())
        }
    }
    private val awtCache = mutableMapOf<String, Cursor>()

    /** The previews and native cursor use the exact same paths and hotspot. */
    fun render(style: Style, pixels: Int = 96): BufferedImage {
        val image = BufferedImage(pixels, pixels, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.scale(pixels / 32.0, pixels / 32.0)
        val p = Path2D.Float().apply {
            moveTo(4.0, 4.0)
            when (style.shape) {
                1 -> { curveTo(6.0,2.0,22.0,13.0,24.0,18.0); curveTo(25.0,21.0,22.0,22.0,18.0,22.0)
                    curveTo(24.0,29.0,17.0,30.0,14.0,24.0); curveTo(8.0,31.0,4.0,26.0,4.0,21.0); curveTo(3.0,14.0,2.0,7.0,4.0,4.0) }
                2 -> { curveTo(5.5,3.0,7.0,4.0,9.0,5.0); lineTo(25.0,18.0)
                    curveTo(27.0,20.0,26.0,22.0,23.0,22.0); curveTo(16.0,22.0,11.0,24.0,8.0,27.0)
                    curveTo(6.0,29.0,4.0,28.0,4.0,25.0); lineTo(3.0,8.0); quadTo(3.0,5.0,4.0,4.0) }
                3 -> { lineTo(8.0,4.0); lineTo(8.0,7.0); lineTo(12.0,7.0); lineTo(12.0,11.0)
                    lineTo(16.0,11.0); lineTo(16.0,15.0); lineTo(20.0,15.0); lineTo(20.0,19.0)
                    lineTo(24.0,19.0); lineTo(24.0,22.0); lineTo(17.0,22.0); lineTo(21.0,28.0)
                    lineTo(16.0,30.0); lineTo(12.0,24.0); lineTo(5.0,28.0) }
                4 -> { curveTo(10.0,4.0,26.0,14.0,25.0,21.0); curveTo(20.0,18.0,17.0,19.0,19.0,23.0)
                    curveTo(25.0,31.0,14.0,31.0,12.0,24.0); curveTo(6.0,28.0,4.0,26.0,4.0,22.0); lineTo(3.0,7.0) }
                5 -> { curveTo(4.0,0.0,13.0,5.0,14.0,9.0); curveTo(20.0,7.0,25.0,13.0,23.0,17.0)
                    curveTo(31.0,22.0,23.0,30.0,18.0,26.0); curveTo(12.0,33.0,8.0,27.0,9.0,24.0)
                    curveTo(2.0,28.0,2.0,18.0,4.0,4.0) }
                else -> { lineTo(26.0,22.0); lineTo(17.0,21.0); lineTo(22.0,29.0)
                    lineTo(16.0,31.0); lineTo(12.0,23.0); lineTo(5.0,28.0) }
            }
            closePath()
        }
        g.stroke = BasicStroke(if (style.shape == 2) 1.2f else 1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.paint = GradientPaint(5f,5f,Ink(style.top),23f,29f,Ink(style.bottom)); g.fill(p)
        g.color = Ink(style.edge); g.draw(p)
        g.clip = p
        if (style.shape == 0 || style.id in listOf("pulse", "drift", "volt", "circuit", "nova", "stealth")) {
            g.color = Ink(style.accent); g.stroke = BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.drawLine(7,9,12,19)
            if (style.id == "circuit") g.drawLine(12,19,18,20)
            if (style.id == "split" || style.id == "nova") {
                val split = Path2D.Float().apply { moveTo(4.0,4.0); lineTo(12.0,23.0); lineTo(26.0,22.0); closePath() }
                g.fill(split)
            }
        } else if (style.shape != 2 && style.shape != 3) {
            g.color = Ink(255,255,255,140); g.fillOval(6,7,4,3)
            if (style.id == "sleepy") { g.color = Ink(style.edge); g.fillOval(8,12,2,3); g.fillOval(13,13,2,3) }
        }
        g.dispose()
        return image
    }
}

@Composable
internal fun CursorStylesPage() {
    SettingsSectionLabel("Mouse pointer")
    SettingsCard {
        SettingsBoolRow("Front laser in Normal mode", "Same pointer-synced line as Assist", DesktopCursors.laserInNormal, first = true, onToggle = DesktopCursors::setNormalLaser)
        SettingsValueRow("Use system pointer", if (DesktopCursors.current() == "system") "Selected" else "Default", first = false,
            onOpen = { DesktopCursors.select("system") })
    }
    SettingsCaption("Only inside Wyrm. Text, links and resize handles keep their useful pointers. Outside the window, your Windows pointer returns automatically.")
    listOf("Signature", "Soft", "Essential", "Gaming").forEachIndexed { section, title ->
        val range = when (section) { 0 -> 0 until 12; 1 -> 12 until 24; 2 -> 24 until 30; else -> 30 until 36 }
        SettingsSectionLabel(title)
        range.map { DesktopCursors.styles[it] }.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { style ->
                    val selected = DesktopCursors.current() == style.id
                    val picture = remember(style.id) { DesktopCursors.render(style).toComposeImageBitmap() }
                    Row(Modifier.weight(1f).clip(wyrmRounded(13.dp)).background(if (selected) Wyrm.Well else Wyrm.Card)
                        .border(if (selected) 2.dp else 1.dp, if (selected) Wyrm.Ink else Wyrm.Rule, wyrmRounded(13.dp))
                        .pointerHoverIcon(PointerIcon.Hand).clickable { DesktopCursors.select(style.id) }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Image(picture, contentDescription = style.name, modifier = Modifier.size(42.dp))
                        Text(style.name, fontFamily = Wyrm.Body, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = Wyrm.Ink, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        if (selected) Text("✓", color = Wyrm.Live)
                    }
                }
            }
        }
    }
}
