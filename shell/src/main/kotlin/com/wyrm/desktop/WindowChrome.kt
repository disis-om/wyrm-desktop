package com.wyrm.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wyrm.omrajput.ui.Wyrm
import com.wyrm.omrajput.ui.wyrmRounded
import java.awt.Cursor
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Rectangle

/*
 * Wyrm's own window frame (OM, 2026-10-12): Windows' grey title bar is gone
 * (the window is undecorated); a slim strip in the theme's paper takes its
 * place, with Wyrm's icon and name on the left and three minimal buttons on
 * the right (minimise, maximise, close: thin glyphs in the theme's ink, a soft
 * well on hover, close fills with ink; no coloured dots). The strip drags the window; a
 * double-click maximises (to the screen's work area, so the taskbar stays);
 * the window's edges resize it. In a match, in the arena editors and in full
 * screen the strip is gone and the game has the whole window.
 */

/** How tall Wyrm's title strip is. Pages sit below it. */
val TitleStripHeight: Dp = 38.dp

internal object WindowChrome {
    /** Maximised by Wyrm (to the work area); the size to go back to. */
    var maximized by mutableStateOf(false)
        private set
    private var restore: Rectangle? = null

    fun minimize(frame: Frame) {
        frame.extendedState = frame.extendedState or Frame.ICONIFIED
    }

    fun toggleMaximize(frame: Frame) {
        if (maximized) {
            restore?.let { frame.bounds = it }
            maximized = false
        } else {
            restore = frame.bounds
            frame.bounds = workArea(frame)
            maximized = true
        }
    }

    /** The screen the window is on, without the taskbar. */
    private fun workArea(frame: Frame): Rectangle {
        val config = frame.graphicsConfiguration ?: return GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        val insets = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(config)
        val b = config.bounds
        return Rectangle(b.x + insets.left, b.y + insets.top, b.width - insets.left - insets.right, b.height - insets.top - insets.bottom)
    }

    /** A drag that starts on a maximised window first puts it back to its size, under the pointer. */
    fun unmaximizeForDrag(frame: Frame, pointerX: Int, pointerY: Int) {
        if (!maximized) return
        val old = restore ?: return
        val fraction = (pointerX - frame.x).toDouble() / frame.width.coerceAtLeast(1)
        maximized = false
        frame.bounds = Rectangle((pointerX - old.width * fraction).toInt(), pointerY - 18, old.width, old.height)
    }

    /** Leaving the app's size (sign-in shrinks it): no stale maximise. */
    fun forget() {
        maximized = false
        restore = null
    }
}

/**
 * The strip itself. [compact] is the sign-in card: no name, only the close
 * and minimise buttons (that window does not grow).
 */
@Composable
internal fun TitleStrip(frame: Frame, compact: Boolean, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val icon = remember { DesktopToasts.icon()?.toComposeImageBitmap() }
    Box(modifier.fillMaxWidth().height(TitleStripHeight)) {
        // The whole strip drags the window; the buttons sit on top of it.
        Box(Modifier.fillMaxSize().windowDrag(frame, allowMaximize = !compact))
        Row(
            Modifier.align(Alignment.CenterStart).padding(start = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The sign-in card shows only the buttons (OM, 2026-10-12): its own W is the mark there.
            if (!compact) {
                if (icon != null) Image(icon, "Wyrm", modifier = Modifier.size(18.dp).clip(wyrmRounded(5.dp)))
                Text("Wyrm", fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 12.5.sp, letterSpacing = 0.2.sp, color = Wyrm.Mute)
            }
        }
        WindowBeads(
            frame = frame,
            showMaximize = !compact,
            onClose = onClose,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
        )
    }
}

/**
 * Minimise, maximise, close: three quiet glyphs in the theme's ink (OM, 2026-10-12:
 * minimal, Wyrm's own look, no coloured dots). Hover lays a soft well under one;
 * close fills with ink.
 */
@Composable
private fun WindowBeads(frame: Frame, showMaximize: Boolean, onClose: () -> Unit, modifier: Modifier) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Bead(BeadKind.MINIMIZE) { WindowChrome.minimize(frame) }
        if (showMaximize) Bead(if (WindowChrome.maximized) BeadKind.RESTORE else BeadKind.MAXIMIZE) { WindowChrome.toggleMaximize(frame) }
        Bead(BeadKind.CLOSE, onClose)
    }
}

private enum class BeadKind { MINIMIZE, MAXIMIZE, RESTORE, CLOSE }

/** One window button: a thin glyph; hover gives it a rounded well (close: ink, glyph on ink). */
@Composable
private fun Bead(kind: BeadKind, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val close = kind == BeadKind.CLOSE
    val well by animateColorAsState(
        when {
            !hovered && !pressed -> Wyrm.Well.copy(alpha = 0f)
            close -> if (pressed) Wyrm.Ink.copy(alpha = 0.82f) else Wyrm.Ink
            else -> if (pressed) Wyrm.Rule else Wyrm.Well
        },
        tween(140), label = "window-button-well",
    )
    val ink by animateColorAsState(
        when {
            close && (hovered || pressed) -> Wyrm.OnInk
            hovered || pressed -> Wyrm.Ink
            else -> Wyrm.Mute
        },
        tween(140), label = "window-button-ink",
    )
    Box(
        Modifier
            .size(width = 36.dp, height = 26.dp)
            .clip(wyrmRounded(9.dp))
            .background(well)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(10.dp)) {
            val s = size.minDimension
            val line = 1.3.dp.toPx()
            val stroke = Stroke(width = line, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
            when (kind) {
                BeadKind.CLOSE -> {
                    drawLine(ink, Offset(0f, 0f), Offset(s, s), line, StrokeCap.Round)
                    drawLine(ink, Offset(0f, s), Offset(s, 0f), line, StrokeCap.Round)
                }
                BeadKind.MINIMIZE -> drawLine(ink, Offset(0f, s / 2f), Offset(s, s / 2f), line, StrokeCap.Round)
                BeadKind.MAXIMIZE -> drawRoundRect(
                    ink, size = androidx.compose.ui.geometry.Size(s, s),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.5.dp.toPx()), style = stroke,
                )
                BeadKind.RESTORE -> {
                    val d = s * 0.78f
                    val o = s - d
                    drawRoundRect(
                        ink, topLeft = Offset(0f, o), size = androidx.compose.ui.geometry.Size(d, d),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()), style = stroke,
                    )
                    drawLine(ink, Offset(o + line, 0f), Offset(s, 0f), line, StrokeCap.Round)
                    drawLine(ink, Offset(s, 0f), Offset(s, d - line), line, StrokeCap.Round)
                }
            }
        }
    }
}


/** Drags the window by the pointer's screen position (smooth at any DPI); double-click maximises. */
private fun Modifier.windowDrag(frame: Frame, allowMaximize: Boolean): Modifier = pointerInput(frame, allowMaximize) {
    var lastDown = 0L
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val now = System.currentTimeMillis()
        if (allowMaximize && now - lastDown < 350) {
            lastDown = 0L
            WindowChrome.toggleMaximize(frame)
            return@awaitEachGesture
        }
        lastDown = now
        val start = MouseInfo.getPointerInfo()?.location ?: return@awaitEachGesture
        var origin = frame.location
        var moved = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            val point = MouseInfo.getPointerInfo()?.location ?: continue
            if (!moved && (kotlin.math.abs(point.x - start.x) > 3 || kotlin.math.abs(point.y - start.y) > 3)) {
                moved = true
                if (WindowChrome.maximized) {
                    WindowChrome.unmaximizeForDrag(frame, point.x, point.y)
                    origin = frame.location
                    start.setLocation(point)
                }
            }
            if (moved) frame.setLocation(origin.x + point.x - start.x, origin.y + point.y - start.y)
            change.consume()
        }
    }
}

/**
 * The window's edges and corners resize it (an undecorated window has no
 * border of its own). Thin enough to never take a click meant for a page.
 */
@Composable
internal fun BoxScope.ResizeEdges(frame: Frame, minWidth: Int, minHeight: Int) {
    val edge = 5.dp
    val corner = 12.dp
    @Composable fun handle(dx: Int, dy: Int, cursor: Int, modifier: Modifier) = Box(
        modifier
            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(cursor)))
            .pointerInput(frame, dx, dy) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (WindowChrome.maximized) return@awaitEachGesture
                    val start = MouseInfo.getPointerInfo()?.location ?: return@awaitEachGesture
                    val from = frame.bounds
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val p = MouseInfo.getPointerInfo()?.location ?: continue
                        val ddx = p.x - start.x
                        val ddy = p.y - start.y
                        var x = from.x
                        var y = from.y
                        var w = from.width
                        var h = from.height
                        if (dx > 0) w = (from.width + ddx).coerceAtLeast(minWidth)
                        if (dx < 0) { w = (from.width - ddx).coerceAtLeast(minWidth); x = from.x + from.width - w }
                        if (dy > 0) h = (from.height + ddy).coerceAtLeast(minHeight)
                        if (dy < 0) { h = (from.height - ddy).coerceAtLeast(minHeight); y = from.y + from.height - h }
                        frame.setBounds(x, y, w, h)
                        change.consume()
                    }
                }
            },
    )
    handle(0, -1, Cursor.N_RESIZE_CURSOR, Modifier.align(Alignment.TopCenter).fillMaxWidth().height(edge).padding(horizontal = corner))
    handle(0, 1, Cursor.S_RESIZE_CURSOR, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(edge).padding(horizontal = corner))
    handle(-1, 0, Cursor.W_RESIZE_CURSOR, Modifier.align(Alignment.CenterStart).fillMaxHeight().width(edge).padding(vertical = corner))
    handle(1, 0, Cursor.E_RESIZE_CURSOR, Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(edge).padding(vertical = corner))
    handle(-1, -1, Cursor.NW_RESIZE_CURSOR, Modifier.align(Alignment.TopStart).size(corner))
    handle(1, -1, Cursor.NE_RESIZE_CURSOR, Modifier.align(Alignment.TopEnd).size(corner))
    handle(-1, 1, Cursor.SW_RESIZE_CURSOR, Modifier.align(Alignment.BottomStart).size(corner))
    handle(1, 1, Cursor.SE_RESIZE_CURSOR, Modifier.align(Alignment.BottomEnd).size(corner))
}

/** The strip comes and goes with a short slide (a match starting, full screen). */
@Composable
internal fun BoxScope.TitleStripHost(visible: Boolean, frame: Frame, compact: Boolean, onClose: () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)) + slideInVertically(tween(220)) { -it },
        exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { -it },
        modifier = Modifier.align(Alignment.TopCenter),
    ) {
        TitleStrip(frame, compact, onClose)
    }
}
