package com.wyrm.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wyrm.omrajput.WyrmActivity
import com.wyrm.omrajput.ui.LiquidSlider
import com.wyrm.omrajput.ui.NearOriginalStore
import com.wyrm.omrajput.ui.TeamHudStore
import com.wyrm.omrajput.ui.Wyrm
import com.wyrm.omrajput.ui.WyrmCapsule
import com.wyrm.omrajput.ui.iosSpring
import com.wyrm.omrajput.ui.wyrmRounded
import kotlin.math.roundToInt

/**
 * The arena UI pieces a player can place (Android's ArenaHudTarget: same ids,
 * same defaults). [fixedInNearOriginal]: slither's own HUD stays where slither
 * puts it; the team roster and chat stay movable (Android's `hudEditable`).
 */
internal enum class HudPiece(val prefix: String, val title: String, val fallback: Offset, val fixedInNearOriginal: Boolean) {
    MINIMAP("hud.minimap", "MINIMAP", Offset(0.095f, 0.205f), true),
    LEADERBOARD("hud.leaderboard", "LEADERBOARD", Offset(0.905f, 0.155f), true),
    STATS("hud.stats", "STATS", Offset(0.945f, 0.530f), true),
    TEAM("hud.team", "TEAM", Offset(0.095f, 0.610f), false),
    CHAT("hud.chat", "CHAT", Offset(0.790f, 0.075f), false);

    companion object {
        /** The engine settings the options change (Cancel writes them back). */
        val SIZE_IDS = setOf(
            "general.minimap_size", "general.lb_font", "general.stats_font",
            "layout.stats_scale", "layout.stats_opacity",
            "layout.chat_scale", "layout.chat_opacity",
        )

        fun editable(piece: HudPiece): Boolean = !(NearOriginalStore.on && piece.fixedInNearOriginal)
    }
}

/** One row in a piece's options, as Android's LayoutSlider / LayoutColours. */
private sealed interface HudOption {
    val label: String
}

/** An engine setting (`EngineSettings`). */
private class EngineSlider(
    override val label: String,
    val id: String,
    val range: ClosedFloatingPointRange<Float>,
    val fallback: Float,
    val steps: Int = 0,
    val shown: (Float) -> String,
) : HudOption

/** A team HUD value (`TeamHudStore`, `teamhud.<key>`). */
private class TeamSlider(override val label: String, val key: String, val shown: (Float) -> String) : HudOption

/** One of the engine's nine text colours for the roster or chat. */
private class TeamColours(override val label: String, val key: String) : HudOption

private val percent: (Float) -> String = { "${(it * 100).roundToInt()}%" }
private val pixels: (Float) -> String = { "${it.roundToInt()} px" }

/** Android's and iOS's option lists, piece for piece (LayoutEditors.kt, WyrmSettingsPages.swift). */
private fun options(piece: HudPiece): List<HudOption> = when (piece) {
    HudPiece.MINIMAP -> listOf(EngineSlider("SIZE", "general.minimap_size", 128f..512f, 300f, shown = pixels))
    HudPiece.LEADERBOARD -> listOf(
        EngineSlider("TEXT SIZE", "general.lb_font", 0f..2f, 1f, steps = 1) {
            listOf("Small", "Medium", "Large")[it.roundToInt().coerceIn(0, 2)]
        },
    )
    HudPiece.STATS -> listOf(
        EngineSlider("SIZE", "layout.stats_scale", 0.65f..1.60f, 1f, shown = percent),
        EngineSlider("OPACITY", "layout.stats_opacity", 0.05f..1f, 1f, shown = percent),
        // BACK (OM, 2026-10-05): the white plate only; the text keeps OPACITY.
        TeamSlider("BACK", "stats_panel", percent),
        // TEXT (OM, 2026-10-11): the server line, labels and values; all three apps.
        TeamColours("TEXT", "stats_text"),
    )
    HudPiece.TEAM -> listOf(
        TeamSlider("SIZE", "team_scale", percent),
        TeamSlider("OPACITY", "team_opacity", percent),
        TeamSlider("BACK", "team_panel", percent),
        TeamSlider("WIDTH", "team_width", pixels),
        TeamSlider("HEIGHT", "team_height", pixels),
        TeamColours("PLAYER NAME AND SCORE", "team_name"),
        TeamColours("KEY NAME AND SERVER", "team_data"),
    )
    HudPiece.CHAT -> listOf(
        EngineSlider("SIZE", "layout.chat_scale", 0.65f..1.60f, 1f, shown = percent),
        EngineSlider("OPACITY", "layout.chat_opacity", 0.05f..1f, 1f, shown = percent),
        TeamSlider("BACK", "chat_panel", percent),
        TeamSlider("WIDTH", "chat_width", pixels),
        TeamSlider("HEIGHT", "chat_height", pixels),
        TeamColours("PLAYER NAME", "chat_name"),
        TeamColours("MESSAGES", "chat_text"),
    )
}

/**
 * Settings › Display › Arrange arena UI: Android's arena layout editor for a
 * mouse (D2, OM 2026-10-11). The engine runs the AI arena and draws the real
 * minimap, leaderboard, stats, team roster and chat window where their
 * settings say; nothing is drawn over them. Drag any piece (written live),
 * right-click it for its options (Android's long-press popup: size, opacity,
 * back plate, width, height, colours), Esc or Cancel puts everything back,
 * Save keeps it. In Near Original only the roster and the chat move.
 */
@Composable
fun HudEditor() {
    var canvas by remember { mutableStateOf(Offset.Zero) }
    var options by remember { mutableStateOf<HudPiece?>(null) }
    val rise = remember { Animatable(0f) }
    LaunchedEffect(Unit) { rise.animateTo(1f, iosSpring(0.5f, 0.84f)) }
    // The roster and chat window's boxes, as the engine drew them this frame.
    var teamRects by remember { mutableStateOf(FloatArray(8)) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { }
            runCatching { WyrmActivity.nativeDesktopTeamRects() }.getOrNull()?.takeIf { it.size == 8 }?.let {
                if (!it.contentEquals(teamRects)) teamRects = it
            }
        }
    }

    Box(Modifier.fillMaxSize().onSizeChanged { canvas = Offset(it.width.toFloat(), it.height.toFloat()) }) {
        GameSurface(Modifier.fillMaxSize(), gameKeys = false)
        if (canvas.x > 0f) DirectHandles(canvas, teamRects) { options = it }
        // The bar: what to do, and the three ways out.
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 26.dp)
                .graphicsLayer {
                    translationY = (1f - rise.value) * 50.dp.toPx()
                    alpha = rise.value.coerceIn(0f, 1f)
                }
                .paperPanel(wyrmRounded(28.dp), fill = 0.94f)
                .padding(start = 22.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.padding(end = 18.dp)) {
                Text("Drag the arena UI into place", fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Wyrm.Ink)
                Text(
                    if (NearOriginalStore.on) "Near Original: the team and chat move · right-click for options"
                    else "Right-click any piece for its size, opacity and more",
                    fontFamily = Wyrm.Body, fontSize = 12.sp, color = Wyrm.Mute,
                )
            }
            EditorPill("Cancel", filled = false) { Engine.closeHudEditor(save = false) }
            EditorPill("Reset", filled = false) { EngineSettings.resetHud() }
            EditorPill("Save", filled = true) { Engine.closeHudEditor(save = true) }
        }
        AnimatedVisibility(
            visible = options != null,
            enter = fadeIn(iosSpring(0.4f, 0.9f)) + scaleIn(iosSpring(0.45f, 0.8f), initialScale = 0.92f),
            exit = fadeOut(iosSpring(0.3f, 1f)) + scaleOut(iosSpring(0.3f, 1f), targetScale = 0.95f),
            modifier = Modifier.align(Alignment.Center),
        ) {
            val shown = remember { mutableStateOf(HudPiece.MINIMAP) }
            options?.let { shown.value = it }
            PieceOptions(shown.value) { options = null }
        }
    }
}

/** A team rect from the engine's eight numbers, or null when it was not drawn. */
private fun engineRect(rects: FloatArray, at: Int): Rect? {
    val w = rects[at + 2]
    val h = rects[at + 3]
    if (w <= 1f || h <= 1f) return null
    return Rect(rects[at], rects[at + 1], rects[at] + w, rects[at + 1] + h)
}

/**
 * Where the engine draws a piece, in pixels. The roster and the chat window are
 * read straight from the engine (`nativeDesktopTeamRects`). The others are
 * centred on their saved position, pushed back inside the 16 px edge as the
 * engine clamps them; the minimap is exactly its setting, the board and stats
 * are measured from the engine at Medium text and scaled by their text size.
 */
private fun pieceRect(piece: HudPiece, canvas: Offset, teamRects: FloatArray): Rect {
    engineRect(teamRects, if (piece == HudPiece.TEAM) 0 else 4)
        ?.takeIf { piece == HudPiece.TEAM || piece == HudPiece.CHAT }
        ?.let { return it }
    fun font(id: String) = listOf(0.84f, 1f, 1.18f)[EngineSettings.number(id, 1f).roundToInt().coerceIn(0, 2)]
    val size = when (piece) {
        HudPiece.MINIMAP -> EngineSettings.number("general.minimap_size", 300f).let { Offset(it, it) }
        HudPiece.LEADERBOARD -> Offset(464f, 378f) * font("general.lb_font")
        HudPiece.STATS -> Offset(330f, 342f) * (font("general.stats_font") * EngineSettings.number("layout.stats_scale", 1f))
        HudPiece.TEAM -> TeamHudStore.value("team_scale").let { Offset(TeamHudStore.value("team_width") * it, TeamHudStore.value("team_height") * it) }
        HudPiece.CHAT -> EngineSettings.number("layout.chat_scale", 1f).let { Offset(TeamHudStore.value("chat_width") * it, TeamHudStore.value("chat_height") * it) }
    }
    val edge = 16f
    val x = EngineSettings.number("${piece.prefix}_x", piece.fallback.x).coerceIn(0f, 1f) * canvas.x - size.x / 2f
    val y = EngineSettings.number("${piece.prefix}_y", piece.fallback.y).coerceIn(0f, 1f) * canvas.y - size.y / 2f
    val left = x.coerceIn(edge, (canvas.x - edge - size.x).coerceAtLeast(edge))
    val top = y.coerceIn(edge, (canvas.y - edge - size.y).coerceAtLeast(edge))
    return Rect(left, top, left + size.x, top + size.y)
}

/** The piece under [at]: the chat and roster first (drawn last, on top), the map last. */
private fun pieceAt(at: Offset, canvas: Offset, teamRects: FloatArray): HudPiece? =
    listOf(HudPiece.CHAT, HudPiece.TEAM, HudPiece.STATS, HudPiece.LEADERBOARD, HudPiece.MINIMAP)
        .filter(HudPiece::editable)
        .firstOrNull { piece ->
            val r = pieceRect(piece, canvas, teamRects).inflate(8f)
            if (piece == HudPiece.MINIMAP) (at - r.center).getDistance() <= r.width / 2f else r.contains(at)
        }

@Composable
private fun DirectHandles(canvas: Offset, teamRects: FloatArray, onOptions: (HudPiece) -> Unit) {
    var hover by remember { mutableStateOf<HudPiece?>(null) }
    var dragging by remember { mutableStateOf<HudPiece?>(null) }
    val move = remember { PointerIcon(java.awt.Cursor(java.awt.Cursor.MOVE_CURSOR)) }
    val currentCanvas by rememberUpdatedState(canvas)
    val rects by rememberUpdatedState(teamRects)
    val options by rememberUpdatedState(onOptions)
    Box(
        Modifier
            .fillMaxSize()
            .pointerHoverIcon(if (hover != null || dragging != null) move else PointerIcon.Default)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    // Where the pointer caught the piece, from the piece's centre,
                    // so the piece moves with the pointer instead of jumping to it.
                    var grab = Offset.Zero
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        val at = change.position
                        when (event.type) {
                            PointerEventType.Press -> {
                                val piece = pieceAt(at, currentCanvas, rects) ?: continue
                                change.consume()
                                if (event.buttons.isSecondaryPressed) {
                                    // Right-click: its options (Android's long-press).
                                    dragging = null
                                    options(piece)
                                } else if (event.buttons.isPrimaryPressed) {
                                    dragging = piece
                                    grab = at - pieceRect(piece, currentCanvas, rects).center
                                }
                            }
                            PointerEventType.Move -> {
                                val piece = dragging
                                if (piece != null) {
                                    change.consume()
                                    val c = currentCanvas
                                    val centre = at - grab
                                    EngineSettings.writePair(
                                        piece.prefix,
                                        (centre.x / c.x).coerceIn(0f, 1f),
                                        (centre.y / c.y).coerceIn(0f, 1f),
                                    )
                                } else {
                                    hover = pieceAt(at, currentCanvas, rects)
                                }
                            }
                            PointerEventType.Release -> {
                                if (dragging != null) change.consume()
                                dragging = null
                                hover = pieceAt(at, currentCanvas, rects)
                            }
                            PointerEventType.Exit -> if (dragging == null) hover = null
                            else -> Unit
                        }
                    }
                }
            },
    )
}

/** A piece's options, Android's EditorOptionsPopup for a mouse. Esc or Done closes it. */
@Composable
private fun PieceOptions(piece: HudPiece, onDone: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(piece) { runCatching { focus.requestFocus() } }
    Column(
        Modifier
            .width(360.dp)
            .heightIn(max = 560.dp)
            .paperPanel(wyrmRounded(24.dp))
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) { onDone(); true } else false
            }
            .focusRequester(focus)
            .focusable()
            .verticalScroll(rememberScrollState())
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(piece.title, fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 1.4.sp, color = Wyrm.Ink)
        options(piece).forEach { option ->
            when (option) {
                is EngineSlider -> {
                    var value by remember(piece, option.id) { mutableStateOf(EngineSettings.number(option.id, option.fallback)) }
                    OptionHeader(option.label, option.shown(value))
                    LiquidSlider(
                        value = value,
                        onValueChange = { next ->
                            val stepped = if (option.steps > 0) next.roundToInt().toFloat() else next
                            // A stepped value (text size) goes to the engine only when it changes.
                            if (option.steps == 0 || stepped != value) {
                                value = stepped
                                EngineSettings.write(option.id, stepped)
                            }
                        },
                        valueRange = option.range,
                        steps = option.steps,
                    )
                }
                is TeamSlider -> {
                    var value by remember(piece, option.key) { mutableStateOf(TeamHudStore.value(option.key)) }
                    OptionHeader(option.label, option.shown(value))
                    LiquidSlider(
                        value = value,
                        onValueChange = { next ->
                            value = next
                            TeamHudStore.apply("teamhud.${option.key}", next)
                        },
                        valueRange = TeamHudStore.range(option.key),
                    )
                }
                is TeamColours -> {
                    var selected by remember(piece, option.key) { mutableStateOf(TeamHudStore.value(option.key).toInt()) }
                    OptionHeader(option.label, TeamHudStore.COLOUR_NAMES.getOrElse(selected) { "Theme" })
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TeamHudStore.COLOUR_SWATCHES.forEachIndexed { index, swatch ->
                            val fill = if (swatch == Color.Unspecified) Wyrm.Ink else swatch
                            Box(
                                Modifier
                                    .size(28.dp)
                                    .border(if (index == selected) 2.dp else 1.dp, if (index == selected) Wyrm.Ink else Wyrm.Rule, CircleShape)
                                    .padding(3.dp)
                                    .clip(CircleShape)
                                    .background(fill)
                                    .pointerHoverIcon(PointerIcon.Hand)
                                    .clickable {
                                        selected = index
                                        TeamHudStore.apply("teamhud.${option.key}", index.toFloat())
                                    },
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { EditorPill("Done", filled = true, onClick = onDone) }
    }
}

@Composable
private fun OptionHeader(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 10.5.sp, letterSpacing = 1.sp, color = Wyrm.Quiet, modifier = Modifier.weight(1f))
        Text(value, fontFamily = Wyrm.Body, fontSize = 13.sp, color = Wyrm.Mute)
    }
}

@Composable
private fun EditorPill(label: String, filled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier
            .height(38.dp)
            .clip(WyrmCapsule)
            .background(
                when {
                    filled -> if (hovered) Wyrm.Ink.copy(alpha = 0.86f) else Wyrm.Ink
                    hovered -> Wyrm.Ink.copy(alpha = 0.07f)
                    else -> Color.Transparent
                },
            )
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = if (filled) Wyrm.OnInk else Wyrm.Ink)
    }
}
