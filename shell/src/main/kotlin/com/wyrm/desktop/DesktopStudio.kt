package com.wyrm.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wyrm.omrajput.ui.IosPageChrome
import com.wyrm.omrajput.ui.IosSpinner
import com.wyrm.omrajput.ui.LiquidSegmented
import com.wyrm.omrajput.ui.LiquidSlider
import com.wyrm.omrajput.ui.TrailAdjust
import com.wyrm.omrajput.ui.TrailLook
import com.wyrm.omrajput.ui.TrailLooks
import com.wyrm.omrajput.ui.TrailsStore
import com.wyrm.omrajput.ui.Wyrm
import com.wyrm.omrajput.ui.WyrmCapsule
import com.wyrm.omrajput.ui.wyrmRounded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * The trail studio made for a computer (OM, 2026-10-12). The phone's studio
 * (TrailStudio.kt: camera, touch gestures, StaticLayout export) cannot run
 * here; this one keeps its kinds and its words — Photo, Text, Canvas; crop
 * shapes; Android's looks (TrailLooks, shared) and Adjust; words in Clean or
 * Serif with Fill, Glow and Outline; Pen, Marker and Beads; the same 32
 * stickers and palette — laid out for a mouse: the picture on the left, the
 * tools on the right, the caption and Post under them.
 *
 * What you see is what goes up: the export draws the very same composable
 * (`StudioComposition`) into an ImageComposeScene at 1080 px wide, then
 * TrailsStore.post does Android's resize and JPEG. No video (OM).
 */

private enum class StudioMode(val label: String) { PHOTO("Photo"), TEXT("Text"), CANVAS("Canvas") }

private enum class StudioShape(val label: String, val ratio: Float?) {
    ORIGINAL("Original", null), SQUARE("1:1", 1f), PORTRAIT("4:5", 4f / 5f), STORY("9:16", 9f / 16f), WIDE("16:9", 16f / 9f),
}

private enum class StudioTool(val label: String) { CROP("Crop"), LOOKS("Looks"), ADJUST("Adjust"), WORDS("Words"), DRAW("Draw"), STICKERS("Stickers") }

private enum class Brush2(val label: String) { PEN("Pen"), MARKER("Marker"), BEADS("Beads") }

/** The phone studio's palette (StudioPalette), the same twelve. */
private val PALETTE = listOf(
    0xFFFFFF, 0x111111, 0xF2B84B, 0xFF5E5B, 0xFF8FC0, 0xAA7DF0,
    0x3D5AFE, 0x00B8D9, 0x2FA45E, 0xB8F2E6, 0xF6E4C4, 0x1E2F5C,
)

/** The phone studio's sticker sheet (STUDIO_EMOJIS): Wyrm's moments first. */
private val STICKERS = listOf(
    "🐍", "🔥", "👑", "🏆", "💀", "⚡", "💯", "🎯", "😂", "😍", "😎", "🤯",
    "😈", "😭", "🥶", "🥇", "✨", "💥", "❤️", "⭐", "🌈", "🎮", "🕹️", "👀",
    "🙌", "🤝", "🫡", "🙏", "💪", "🎉", "🍕", "🌙",
)

private fun rgbColor(rgb: Int) = Color(0xFF000000.toInt() or rgb)
private fun contrastOn(rgb: Int): Int {
    val r = (rgb shr 16) and 0xFF; val g = (rgb shr 8) and 0xFF; val b = rgb and 0xFF
    return if (0.299 * r + 0.587 * g + 0.114 * b > 150) 0x111111 else 0xFFFFFF
}

/* ------------------------------------------------------------------ model */

/** A stroke, its points in 0..1 of the canvas. */
private class StudioLine(val points: MutableList<Offset>, val rgb: Int, val width: Float, val brush: Brush2)

/** Words on the picture; [size] is the type's height as a share of the canvas width. */
private data class StudioWords(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val rgb: Int,
    val serif: Boolean,
    val filled: Boolean,
    val glow: Boolean,
    val outline: Boolean,
    val center: Offset,
    val size: Float = 0.07f,
    val rotation: Float = 0f,
)

private data class StudioSticker(
    val id: String = UUID.randomUUID().toString(),
    val emoji: String,
    val center: Offset,
    val size: Float = 0.16f,
    val rotation: Float = 0f,
)

private class StudioDraft {
    var mode by mutableStateOf(StudioMode.PHOTO)
    var photo by mutableStateOf<ImageBitmap?>(null)
    var shape by mutableStateOf(StudioShape.ORIGINAL)
    var zoom by mutableFloatStateOf(1f)
    /** -1..1 of the room the zoomed photo has to move in. */
    var pan by mutableStateOf(Offset.Zero)
    var background by mutableIntStateOf(0x1E2F5C)
    var look by mutableStateOf(TrailLooks.all.first())
    var adjust by mutableStateOf(TrailAdjust())
    val lines = mutableStateListOf<StudioLine>()
    val words = mutableStateListOf<StudioWords>()
    val stickers = mutableStateListOf<StudioSticker>()
    var selected by mutableStateOf<String?>(null)
    var caption by mutableStateOf("")
    var textTrail by mutableStateOf("")
    /** Undo: what was added, newest last (lines, words, stickers). */
    val history = mutableStateListOf<String>()

    /** The canvas's width / height. */
    val aspect: Float
        get() {
            shape.ratio?.let { return it }
            val p = photo
            return if (mode == StudioMode.PHOTO && p != null) (p.width.toFloat() / p.height).coerceIn(0.5f, 2f) else 4f / 5f
        }

    fun undo() {
        val last = history.removeLastOrNull() ?: return
        when {
            last.startsWith("line:") -> lines.removeLastOrNull()
            else -> {
                words.removeAll { it.id == last }
                stickers.removeAll { it.id == last }
            }
        }
        if (selected == last) selected = null
    }
}

/* ------------------------------------------------------------ the picture */

/**
 * The trail as it will be posted, drawn at whatever size it is given (the
 * editor's preview, or 1080 px for the export). [editing] draws selection
 * outlines and takes pointer input; the export passes false.
 */
@Composable
private fun StudioComposition(draft: StudioDraft, editing: Boolean, tool: StudioTool, brush: Brush2, ink: Int, width: Float,
                              onAddLine: (StudioLine) -> Unit = {}) {
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val density = androidx.compose.ui.platform.LocalDensity.current
        // The ground: the photo (cropped by zoom and pan, with the look), or the canvas colour.
        val photo = draft.photo
        if (draft.mode == StudioMode.PHOTO && photo != null) {
            val cover = max(w / photo.width, h / photo.height) * draft.zoom
            val dw = photo.width * cover
            val dh = photo.height * cover
            val x = (w - dw) / 2f + draft.pan.x * (dw - w) / 2f
            val y = (h - dh) / 2f + draft.pan.y * (dh - h) / 2f
            val filter = if (TrailLooks.isIdentity(draft.look, draft.adjust)) null else TrailLooks.composeFilter(draft.look, draft.adjust)
            Image(
                photo, null,
                contentScale = ContentScale.FillBounds,
                colorFilter = filter,
                modifier = Modifier
                    .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                    .size(with(density) { dw.toDp() }, with(density) { dh.toDp() }),
            )
        } else {
            Box(Modifier.fillMaxSize().background(rgbColor(draft.background)))
        }
        // Strokes.
        Canvas(Modifier.fillMaxSize()) {
            for (line in draft.lines) drawLine(line, w, h)
        }
        // Words and stickers.
        for (item in draft.words) WordsItem(draft, item, editing && tool == StudioTool.WORDS, w, h, width)
        for (item in draft.stickers) StickerItem(draft, item, editing && tool == StudioTool.STICKERS, w, h)
        // Drawing (Draw tool) and moving the photo (Crop tool).
        if (editing && tool == StudioTool.DRAW) {
            var current by remember { mutableStateOf<StudioLine?>(null) }
            Box(Modifier.fillMaxSize().pointerInput(brush, ink) {
                detectDragGestures(
                    onDragStart = { at ->
                        val line = StudioLine(mutableListOf(Offset(at.x / w, at.y / h)), ink,
                            when (brush) { Brush2.PEN -> 0.008f; Brush2.MARKER -> 0.022f; Brush2.BEADS -> 0.024f }, brush)
                        current = line
                        draft.lines.add(line)
                    },
                    onDrag = { change, _ ->
                        current?.let { line ->
                            line.points.add(Offset(change.position.x / w, change.position.y / h))
                            // Redraw: replace the list entry with itself.
                            val i = draft.lines.indexOf(line)
                            if (i >= 0) draft.lines[i] = line
                        }
                        change.consume()
                    },
                    onDragEnd = { current?.let(onAddLine); current = null },
                )
            })
        }
        if (editing && tool == StudioTool.CROP && draft.mode == StudioMode.PHOTO && draft.photo != null) {
            CropSurface(draft, w, h)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun CropSurface(draft: StudioDraft, w: Float, h: Float) {
    Box(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures { change, delta ->
                    val photo = draft.photo ?: return@detectDragGestures
                    val cover = max(w / photo.width, h / photo.height) * draft.zoom
                    val roomX = (photo.width * cover - w).coerceAtLeast(1f) / 2f
                    val roomY = (photo.height * cover - h).coerceAtLeast(1f) / 2f
                    draft.pan = Offset(
                        (draft.pan.x + delta.x / roomX).coerceIn(-1f, 1f),
                        (draft.pan.y + delta.y / roomY).coerceIn(-1f, 1f),
                    )
                    change.consume()
                }
            }
            .onPointerEvent(PointerEventType.Scroll) { event ->
                val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                draft.zoom = (draft.zoom * if (dy < 0) 1.08f else 1f / 1.08f).coerceIn(1f, 4f)
            },
    ) {
        // Rule of thirds while cropping.
        Canvas(Modifier.fillMaxSize()) {
            val line = Color.White.copy(alpha = 0.45f)
            for (i in 1..2) {
                drawLine(line, Offset(size.width * i / 3f, 0f), Offset(size.width * i / 3f, size.height), 1f)
                drawLine(line, Offset(0f, size.height * i / 3f), Offset(size.width, size.height * i / 3f), 1f)
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLine(line: StudioLine, w: Float, h: Float) {
    if (line.points.isEmpty()) return
    val colour = rgbColor(line.rgb)
    val width = line.width * w
    when (line.brush) {
        Brush2.BEADS -> {
            // Slither's beads along the stroke, lit from the top left.
            var last: Offset? = null
            for (p in line.points) {
                val at = Offset(p.x * w, p.y * h)
                if (last == null || (at - last).getDistance() >= width * 0.9f) {
                    drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f), colour, colour.copy(alpha = 0.85f)),
                        center = Offset(at.x - width * 0.18f, at.y - width * 0.2f), radius = width * 0.8f), width / 2f, at)
                    last = at
                }
            }
        }
        else -> {
            val path = Path()
            line.points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x * w, p.y * h) else path.lineTo(p.x * w, p.y * h) }
            if (line.points.size == 1) path.lineTo(line.points[0].x * w + 0.5f, line.points[0].y * h)
            drawPath(path, if (line.brush == Brush2.MARKER) colour.copy(alpha = 0.55f) else colour,
                style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
private fun WordsItem(draft: StudioDraft, item: StudioWords, interactive: Boolean, w: Float, h: Float, width: Float) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val px = item.size * w
    val fontSize = with(density) { px.toSp() }
    val ink = rgbColor(if (item.filled) contrastOn(item.rgb) else item.rgb)
    val style = TextStyle(
        fontFamily = if (item.serif) Wyrm.Display else Wyrm.Body,
        fontWeight = if (item.serif) FontWeight.SemiBold else FontWeight.Bold,
        fontSize = fontSize,
        color = ink,
        textAlign = TextAlign.Center,
        shadow = when {
            item.glow -> Shadow(rgbColor(item.rgb), blurRadius = px * 0.45f)
            item.outline -> Shadow(Color.Black.copy(alpha = 0.85f), Offset(0f, 0f), blurRadius = px * 0.08f)
            else -> null
        },
    )
    var size by remember(item.id) { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val selected = interactive && draft.selected == item.id
    Box(
        Modifier
            .offset { IntOffset((item.center.x * w - size.width / 2f).roundToInt(), (item.center.y * h - size.height / 2f).roundToInt()) }
            .onSizeChanged { size = it }
            .rotate(item.rotation)
            .then(if (item.filled) Modifier.clip(wyrmRounded(with(density) { (px * 0.3f).toDp() })).background(rgbColor(item.rgb)) else Modifier)
            .then(if (selected) Modifier.border(1.5.dp, Color.White, wyrmRounded(6.dp)) else Modifier)
            .then(if (interactive) Modifier.pointerInput(item.id) {
                detectDragGestures(onDragStart = { draft.selected = item.id }) { change, delta ->
                    val i = draft.words.indexOfFirst { it.id == item.id }
                    if (i >= 0) {
                        val cur = draft.words[i]
                        draft.words[i] = cur.copy(center = Offset((cur.center.x + delta.x / w).coerceIn(0f, 1f), (cur.center.y + delta.y / h).coerceIn(0f, 1f)))
                    }
                    change.consume()
                }
            }.pointerInput(item.id) { detectTapGestures { draft.selected = item.id } } else Modifier)
            .padding(horizontal = with(density) { (px * 0.28f).toDp() }, vertical = with(density) { (px * 0.12f).toDp() }),
    ) {
        Text(item.text, style = style, modifier = Modifier.widthIn(max = with(density) { (w * 0.9f).toDp() }))
    }
}

@Composable
private fun StickerItem(draft: StudioDraft, item: StudioSticker, interactive: Boolean, w: Float, h: Float) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val px = item.size * w
    val selected = interactive && draft.selected == item.id
    Box(
        Modifier
            .offset { IntOffset((item.center.x * w - px / 2f).roundToInt(), (item.center.y * h - px / 2f).roundToInt()) }
            .size(with(density) { px.toDp() })
            .rotate(item.rotation)
            .then(if (selected) Modifier.border(1.5.dp, Color.White, CircleShape) else Modifier)
            .then(if (interactive) Modifier.pointerInput(item.id) {
                detectDragGestures(onDragStart = { draft.selected = item.id }) { change, delta ->
                    val i = draft.stickers.indexOfFirst { it.id == item.id }
                    if (i >= 0) {
                        val cur = draft.stickers[i]
                        draft.stickers[i] = cur.copy(center = Offset((cur.center.x + delta.x / w).coerceIn(0f, 1f), (cur.center.y + delta.y / h).coerceIn(0f, 1f)))
                    }
                    change.consume()
                }
            }.pointerInput(item.id) { detectTapGestures { draft.selected = item.id } } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(item.emoji, fontSize = with(density) { (px * 0.78f).toSp() })
    }
}

/* ----------------------------------------------------------------- export */

/** The trail at 1080 px wide, drawn by the same composable the editor shows. */
private fun renderTrail(draft: StudioDraft): android.graphics.Bitmap {
    val width = 1080
    val height = (width / draft.aspect).roundToInt().coerceIn(540, 1920)
    val scene = ImageComposeScene(width, height, Density(1f)) {
        Box(Modifier.fillMaxSize()) {
            StudioComposition(draft, editing = false, tool = StudioTool.CROP, brush = Brush2.PEN, ink = 0, width = width.toFloat())
        }
    }
    try {
        scene.render(0)
        val image = scene.render(16_000_000L)
        return android.graphics.Bitmap(image.toComposeImageBitmap().toAwtImage())
    } finally {
        scene.close()
    }
}

/* ------------------------------------------------------------------- page */

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DesktopTrailStudio(onClose: () -> Unit) {
    val draft = remember { StudioDraft() }
    var tool by remember { mutableStateOf(StudioTool.CROP) }
    var brush by remember { mutableStateOf(Brush2.PEN) }
    var ink by remember { mutableIntStateOf(0xFFFFFF) }
    var error by remember { mutableStateOf("") }
    var posting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val tools = when (draft.mode) {
        StudioMode.PHOTO -> StudioTool.entries
        StudioMode.CANVAS -> listOf(StudioTool.WORDS, StudioTool.DRAW, StudioTool.STICKERS)
        StudioMode.TEXT -> emptyList()
    }
    if (tool !in tools && tools.isNotEmpty()) tool = tools.first()

    fun choosePhoto() {
        val file = DesktopFiles.chooseImage("Choose a photo") ?: return
        val image = runCatching { javax.imageio.ImageIO.read(file) }.getOrNull()
        if (image == null) { error = "That image couldn't be opened. Try another."; return }
        // Very large photos are drawn at most 2400 px on the long side.
        val scale = minOf(1f, 2400f / maxOf(image.width, image.height))
        val sized = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(android.graphics.Bitmap(image),
            (image.width * scale).roundToInt(), (image.height * scale).roundToInt(), true).image() else image
        draft.photo = sized.toComposeImageBitmap()
        draft.zoom = 1f
        draft.pan = Offset.Zero
        error = ""
    }

    val canPost = !posting && !TrailsStore.posting.busy && when (draft.mode) {
        StudioMode.PHOTO -> draft.photo != null
        StudioMode.TEXT -> draft.textTrail.isNotBlank()
        StudioMode.CANVAS -> true
    }

    fun post() {
        if (!canPost) return
        posting = true
        scope.launch {
            try {
                when (draft.mode) {
                    StudioMode.TEXT -> TrailsStore.post(null, draft.textTrail.trim())
                    else -> {
                        draft.selected = null
                        val image = withContext(Dispatchers.Default) { renderTrail(draft) }
                        TrailsStore.post(image, draft.caption.trim())
                    }
                }
                onClose()
                Social.closeAll()
                ShellNav.tab.value = Tab.Trails
            } catch (failure: Exception) {
                error = "The trail couldn't be prepared. Try again."
            } finally {
                posting = false
            }
        }
    }

    IosPageChrome(title = "New trail", insetTop = PageInsetTop, onBack = onClose) {
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 10.dp)) {
            Box(Modifier.widthIn(max = 420.dp)) {
                LiquidSegmented(StudioMode.entries.map { it.label }, draft.mode.ordinal, { draft.mode = StudioMode.entries[it]; draft.selected = null })
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                // The picture.
                BoxWithConstraints(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    val boxW = maxWidth
                    val boxH = maxHeight
                    val aspect = if (draft.mode == StudioMode.TEXT) 4f / 5f else draft.aspect
                    val fitW = if (boxW / boxH > aspect) boxH * aspect else boxW
                    val fitH = fitW / aspect
                    Box(Modifier.size(fitW, fitH).clip(wyrmRounded(18.dp)).background(Wyrm.Card).border(1.dp, Wyrm.Rule, wyrmRounded(18.dp))) {
                        when {
                            draft.mode == StudioMode.TEXT -> Box(Modifier.fillMaxSize().background(Wyrm.Card).padding(28.dp), contentAlignment = Alignment.Center) {
                                StudioField(draft.textTrail, { draft.textTrail = it.take(500) }, "What's on your mind?", big = true)
                            }
                            draft.mode == StudioMode.PHOTO && draft.photo == null -> Column(
                                Modifier.fillMaxSize().clickable { choosePhoto() },
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                com.wyrm.omrajput.ui.WyrmMark(size = 46.dp, ink = Wyrm.Ink.copy(alpha = 0.18f))
                                Spacer(Modifier.height(12.dp))
                                Text("Choose a photo", fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Wyrm.Ink)
                                Text("PNG or JPG from this computer", fontFamily = Wyrm.Body, fontSize = 12.5.sp, color = Wyrm.Quiet)
                            }
                            else -> StudioComposition(draft, editing = true, tool = tool, brush = brush, ink = ink,
                                width = 0f, onAddLine = { draft.history.add("line:" + it.hashCode()) })
                        }
                    }
                }
                // The tools.
                Column(Modifier.width(340.dp).fillMaxHeight()) {
                    if (tools.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            tools.forEach { StudioChip(it.label, it == tool) { tool = it } }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                        AnimatedContent(targetState = if (draft.mode == StudioMode.TEXT) null else tool,
                            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) }, label = "studio-tool") { shown ->
                            when (shown) {
                                null -> ToolNote("A text trail is your words on Wyrm's paper. Up to 500 characters.")
                                StudioTool.CROP -> CropPanel(draft, ::choosePhoto)
                                StudioTool.LOOKS -> LooksPanel(draft)
                                StudioTool.ADJUST -> AdjustPanel(draft)
                                StudioTool.WORDS -> WordsPanel(draft, ink) { ink = it }
                                StudioTool.DRAW -> DrawPanel(draft, brush, ink, { brush = it }, { ink = it })
                                StudioTool.STICKERS -> StickersPanel(draft)
                            }
                        }
                        if (draft.mode == StudioMode.CANVAS) {
                            ToolLabel("Background")
                            Swatches(draft.background) { draft.background = it }
                        }
                    }
                    if (draft.mode != StudioMode.TEXT) {
                        ToolLabel("Caption")
                        StudioField(draft.caption, { draft.caption = it.take(500) }, "Write a caption…")
                    }
                    if (error.isNotEmpty()) Text(error, fontFamily = Wyrm.Body, fontSize = 12.sp, color = Wyrm.Badge, modifier = Modifier.padding(top = 6.dp))
                    Spacer(Modifier.height(10.dp))
                    PostButton(enabled = canPost, busy = posting, onClick = ::post)
                }
            }
        }
    }
}

/* ---------------------------------------------------------------- panels */

@Composable
private fun ToolLabel(text: String) {
    Text(text.uppercase(), fontFamily = Wyrm.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.9.sp,
        color = Wyrm.Quiet, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
}

@Composable
private fun ToolNote(text: String) {
    Text(text, fontFamily = Wyrm.Body, fontSize = 12.5.sp, color = Wyrm.Quiet, modifier = Modifier.padding(vertical = 4.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CropPanel(draft: StudioDraft, onChoose: () -> Unit) {
    Column {
        ToolLabel("Shape")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StudioShape.entries.forEach { s -> StudioChip(s.label, s == draft.shape) { draft.shape = s; draft.pan = Offset.Zero } }
        }
        ToolLabel("Zoom")
        LiquidSlider(draft.zoom, { draft.zoom = it }, valueRange = 1f..4f)
        ToolNote("Drag the picture to move it; the mouse wheel zooms.")
        Spacer(Modifier.height(6.dp))
        StudioChip("Change photo", false, onChoose)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LooksPanel(draft: StudioDraft) {
    val photo = draft.photo ?: return
    Column {
        ToolLabel("Looks")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TrailLooks.all.forEach { look -> LookSwatch(photo, look, look == draft.look) { draft.look = look } }
        }
    }
}

@Composable
private fun LookSwatch(photo: ImageBitmap, look: TrailLook, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(98.dp).clickable(onClick = onClick)) {
        Image(
            photo, look.name,
            contentScale = ContentScale.Crop,
            colorFilter = if (look.name == "Normal") null else TrailLooks.composeFilter(look),
            modifier = Modifier.size(98.dp, 74.dp).clip(wyrmRounded(10.dp))
                .border(if (selected) 2.dp else 1.dp, if (selected) Wyrm.Ink else Wyrm.Rule, wyrmRounded(10.dp)),
        )
        Text(look.name, fontFamily = Wyrm.Body, fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold, fontSize = 12.sp,
            color = if (selected) Wyrm.Ink else Wyrm.Mute, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun AdjustPanel(draft: StudioDraft) {
    Column {
        fun row(label: String, value: Float, set: (Float) -> Unit): @Composable () -> Unit = {
            ToolLabel(label)
            LiquidSlider(value, set, valueRange = -1f..1f)
        }
        row("Brightness", draft.adjust.brightness) { draft.adjust = draft.adjust.copy(brightness = it) }()
        row("Contrast", draft.adjust.contrast) { draft.adjust = draft.adjust.copy(contrast = it) }()
        row("Saturation", draft.adjust.saturation) { draft.adjust = draft.adjust.copy(saturation = it) }()
        row("Warmth", draft.adjust.warmth) { draft.adjust = draft.adjust.copy(warmth = it) }()
        Spacer(Modifier.height(8.dp))
        StudioChip("Reset", false) { draft.adjust = TrailAdjust() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WordsPanel(draft: StudioDraft, ink: Int, onInk: (Int) -> Unit) {
    val selected = draft.words.firstOrNull { it.id == draft.selected }
    var typed by remember(selected?.id) { mutableStateOf(selected?.text.orEmpty()) }
    var serif by remember(selected?.id) { mutableStateOf(selected?.serif ?: false) }
    var filled by remember(selected?.id) { mutableStateOf(selected?.filled ?: false) }
    var glow by remember(selected?.id) { mutableStateOf(selected?.glow ?: false) }
    var outline by remember(selected?.id) { mutableStateOf(selected?.outline ?: true) }
    fun update(change: (StudioWords) -> StudioWords) {
        val i = draft.words.indexOfFirst { it.id == draft.selected }
        if (i >= 0) draft.words[i] = change(draft.words[i])
    }
    Column {
        ToolLabel(if (selected == null) "Add words" else "Edit words")
        StudioField(typed, {
            typed = it.take(160)
            update { w -> w.copy(text = typed) }
        }, "Type here…")
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StudioChip("Clean", !serif) { serif = false; update { it.copy(serif = false) } }
            StudioChip("Serif", serif) { serif = true; update { it.copy(serif = true) } }
            StudioChip("Fill", filled) { filled = !filled; update { it.copy(filled = filled) } }
            StudioChip("Glow", glow) { glow = !glow; update { it.copy(glow = glow) } }
            StudioChip("Outline", outline) { outline = !outline; update { it.copy(outline = outline) } }
        }
        ToolLabel("Colour")
        Swatches(selected?.rgb ?: ink) { rgb -> onInk(rgb); update { it.copy(rgb = rgb) } }
        if (selected != null) {
            ToolLabel("Size")
            LiquidSlider(selected.size, { v -> update { it.copy(size = v) } }, valueRange = 0.03f..0.2f)
            ToolLabel("Turn")
            LiquidSlider(selected.rotation, { v -> update { it.copy(rotation = v) } }, valueRange = -45f..45f)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (selected == null) {
                StudioChip("Add", false) {
                    if (typed.isNotBlank()) {
                        val item = StudioWords(text = typed.trim(), rgb = ink, serif = serif, filled = filled, glow = glow,
                            outline = outline, center = Offset(0.5f, 0.5f))
                        draft.words.add(item)
                        draft.history.add(item.id)
                        draft.selected = item.id
                    }
                }
            } else {
                StudioChip("Done", false) { draft.selected = null }
                StudioChip("Delete", false) { draft.words.removeAll { it.id == selected.id }; draft.selected = null }
            }
        }
        ToolNote("Drag words on the picture to place them; click a word to change it.")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DrawPanel(draft: StudioDraft, brush: Brush2, ink: Int, onBrush: (Brush2) -> Unit, onInk: (Int) -> Unit) {
    Column {
        ToolLabel("Brush")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Brush2.entries.forEach { b -> StudioChip(b.label, b == brush) { onBrush(b) } }
        }
        ToolLabel("Colour")
        Swatches(ink, onInk)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StudioChip("Undo", false) { draft.undo() }
            StudioChip("Clear drawing", false) { draft.lines.clear(); draft.history.removeAll { it.startsWith("line:") } }
        }
        ToolNote("Draw on the picture with the mouse.")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StickersPanel(draft: StudioDraft) {
    val selected = draft.stickers.firstOrNull { it.id == draft.selected }
    Column {
        ToolLabel("Stickers")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            STICKERS.forEach { emoji ->
                val interaction = remember { MutableInteractionSource() }
                val hovered by interaction.collectIsHoveredAsState()
                Box(
                    Modifier.size(46.dp).clip(wyrmRounded(10.dp)).background(if (hovered) Wyrm.Well else Color.Transparent)
                        .hoverable(interaction).clickable(interactionSource = interaction, indication = null) {
                            val item = StudioSticker(emoji = emoji, center = Offset(0.5f, 0.42f))
                            draft.stickers.add(item)
                            draft.history.add(item.id)
                            draft.selected = item.id
                        },
                    contentAlignment = Alignment.Center,
                ) { Text(emoji, fontSize = 24.sp) }
            }
        }
        if (selected != null) {
            fun update(change: (StudioSticker) -> StudioSticker) {
                val i = draft.stickers.indexOfFirst { it.id == selected.id }
                if (i >= 0) draft.stickers[i] = change(draft.stickers[i])
            }
            ToolLabel("Size")
            LiquidSlider(selected.size, { v -> update { it.copy(size = v) } }, valueRange = 0.06f..0.45f)
            ToolLabel("Turn")
            LiquidSlider(selected.rotation, { v -> update { it.copy(rotation = v) } }, valueRange = -60f..60f)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StudioChip("Done", false) { draft.selected = null }
                StudioChip("Delete", false) { draft.stickers.removeAll { it.id == selected.id }; draft.selected = null }
            }
        }
        ToolNote("Drag a sticker on the picture to place it.")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Swatches(selected: Int, onPick: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PALETTE.forEach { rgb ->
            val chosen = rgb == selected
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(rgbColor(rgb))
                    .border(if (chosen) 2.5.dp else 1.dp, if (chosen) Wyrm.Ink else Wyrm.Rule, CircleShape)
                    .clickable { onPick(rgb) },
            )
        }
    }
}

@Composable
private fun StudioChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val fill by animateColorAsState(if (selected) Wyrm.Ink else if (hovered) Wyrm.Well else Wyrm.Card, tween(140), label = "chip")
    Box(
        Modifier.clip(WyrmCapsule).background(fill).border(1.dp, if (selected) Wyrm.Ink else Wyrm.Rule, WyrmCapsule)
            .hoverable(interaction).clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(label, fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 12.5.sp, color = if (selected) Wyrm.OnInk else Wyrm.Ink)
    }
}

@Composable
private fun StudioField(value: String, onValue: (String) -> Unit, placeholder: String, big: Boolean = false) {
    val style = TextStyle(
        fontFamily = if (big) Wyrm.Display else Wyrm.Body,
        fontSize = if (big) 28.sp else 14.5.sp,
        color = Wyrm.Ink,
        textAlign = if (big) TextAlign.Center else TextAlign.Start,
    )
    BasicTextField(
        value = value,
        onValueChange = onValue,
        textStyle = style,
        cursorBrush = SolidColor(Wyrm.Link),
        modifier = if (big) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().heightIn(min = 46.dp).clip(wyrmRounded(14.dp))
            .background(Wyrm.Card).border(1.dp, Wyrm.Rule, wyrmRounded(14.dp)).padding(12.dp),
        decorationBox = { inner ->
            Box(contentAlignment = if (big) Alignment.Center else Alignment.TopStart) {
                if (value.isEmpty()) Text(placeholder, style = style.copy(color = Wyrm.Quiet.copy(alpha = 0.8f)))
                inner()
            }
        },
    )
}

@Composable
private fun PostButton(enabled: Boolean, busy: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(46.dp).clip(WyrmCapsule)
            .background(if (enabled) Wyrm.Ink else Wyrm.Ink.copy(alpha = 0.35f))
            .clickable(enabled = enabled, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) IosSpinner(size = 16.dp, colour = Wyrm.OnInk) else
            Text("Post trail", fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 14.5.sp, color = Wyrm.OnInk)
    }
}

/** Offscreen check (pageShot STUDIO_EXPORT): a Canvas trail with words, a sticker and a stroke, exported. */
internal fun studioExportProbe(): java.awt.image.BufferedImage {
    val draft = StudioDraft()
    draft.mode = StudioMode.CANVAS
    draft.background = 0x1E2F5C
    draft.words.add(StudioWords(text = "gg Wyrm", rgb = 0xF2B84B, serif = true, filled = false, glow = true, outline = false, center = Offset(0.5f, 0.3f), size = 0.1f))
    draft.words.add(StudioWords(text = "new best", rgb = 0xFFFFFF, serif = false, filled = true, glow = false, outline = false, center = Offset(0.5f, 0.75f)))
    draft.stickers.add(StudioSticker(emoji = "🐍", center = Offset(0.25f, 0.55f), size = 0.2f, rotation = -12f))
    draft.lines.add(StudioLine(MutableList(30) { Offset(0.55f + it * 0.012f, 0.55f + kotlin.math.sin(it / 4f) * 0.05f) }, 0xFF5E5B, 0.024f, Brush2.BEADS))
    return renderTrail(draft).image()
}
