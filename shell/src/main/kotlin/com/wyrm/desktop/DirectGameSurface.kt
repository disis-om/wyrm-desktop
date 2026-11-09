package com.wyrm.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalWindowInfo
import com.wyrm.omrajput.WyrmActivity
import java.awt.Canvas
import java.awt.Graphics
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent

/**
 * The game in a match, presented straight to the screen (OM, 2026-11-05).
 *
 * [GameSurface] shows the engine as a picture: each frame was read back from
 * the GPU, copied twice on the CPU and drawn by Compose on its own clock. In a
 * match that path is replaced by a native AWT [Canvas] (a real child window of
 * the Wyrm window, hosted with Compose's [SwingPanel]); the engine makes its
 * Vulkan surface on the canvas's HWND and presents every frame directly,
 * paced by the monitor (desktop_bridge.c "Direct presentation").
 *
 * Nothing of Compose can be drawn over a native window, so the two things
 * that were drawn over the game move: the pointer laser and the hint line are
 * drawn by the engine ([WyrmActivity.nativeDesktopOverlay]); the team chat box
 * is its own small window ([ArenaComposerWindow]). The canvas never takes the
 * keyboard (not focusable), so the window's key hook (Main.kt, [GameKeys])
 * keeps every key exactly as before; the mouse goes to the engine from here.
 */
@Composable
internal fun DirectGameSurface(hint: String, modifier: Modifier = Modifier) {
    val canvas = remember { GameCanvas() }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    var hover by remember { mutableStateOf(false) }
    canvas.onHover = { hover = it }

    LaunchedEffect(DesktopCursors.laserInNormal, windowFocused, hover, hint) {
        runCatching { WyrmActivity.nativeDesktopOverlay(DesktopCursors.laserInNormal, windowFocused && hover, hint) }
    }
    LaunchedEffect(DesktopCursors.current()) { canvas.cursor = DesktopCursors.awtCursor() }
    LaunchedEffect(windowFocused) { if (!windowFocused) canvas.releaseAll() }
    DisposableEffect(Unit) {
        onDispose {
            canvas.releaseAll()
            runCatching { WyrmActivity.nativeDesktopOverlay(false, false, "") }
        }
    }

    SwingPanel(
        background = Color(0xFF090909),
        modifier = modifier.onSizeChanged { WyrmActivity.nativeDesktopResize(it.width, it.height) },
        factory = { canvas },
    )
}

/** The native window the engine presents to. */
private class GameCanvas : Canvas() {
    var onHover: (Boolean) -> Unit = {}
    private val buttons = mutableSetOf<Int>()
    private var lastX = 0f
    private var lastY = 0f

    init {
        background = java.awt.Color(0x09, 0x09, 0x09)
        // Vulkan owns every pixel: AWT must never paint or clear this window.
        ignoreRepaint = true
        // The window's key hook keeps the keyboard (Main.kt, GameKeys).
        isFocusable = false
        val mouse = object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) = move(e)
            override fun mouseDragged(e: MouseEvent) = move(e)
            override fun mouseEntered(e: MouseEvent) { onHover(true); move(e) }
            override fun mouseExited(e: MouseEvent) { onHover(false) }
            override fun mousePressed(e: MouseEvent) {
                move(e)
                val button = button(e)
                if (buttons.add(button)) WyrmActivity.nativeDesktopPointer(1, lastX, lastY, button, 0f)
            }
            override fun mouseReleased(e: MouseEvent) {
                move(e)
                val button = button(e)
                if (buttons.remove(button)) WyrmActivity.nativeDesktopPointer(2, lastX, lastY, button, 0f)
            }
            override fun mouseWheelMoved(e: MouseWheelEvent) {
                move(e)
                // Compose sent -scrollDelta.y; AWT's rotation has the same sign as that delta.
                WyrmActivity.nativeDesktopPointer(3, lastX, lastY, 0, (-e.preciseWheelRotation).toFloat())
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
        addMouseWheelListener(mouse)
    }

    /** AWT gives logical (scaled) units; the engine's window is in pixels. */
    private fun move(e: MouseEvent) {
        val scale = graphicsConfiguration?.defaultTransform
        lastX = (e.x * (scale?.scaleX ?: 1.0)).toFloat()
        lastY = (e.y * (scale?.scaleY ?: 1.0)).toFloat()
        WyrmActivity.nativeDesktopPointer(0, lastX, lastY, 0, 0f)
    }

    private fun button(e: MouseEvent): Int = when (e.button) {
        MouseEvent.BUTTON3 -> 2
        MouseEvent.BUTTON2 -> 3
        4 -> 4 // back (X1)
        5 -> 5 // forward (X2)
        else -> 1
    }

    fun releaseAll() {
        buttons.forEach { WyrmActivity.nativeDesktopPointer(2, lastX, lastY, it, 0f) }
        buttons.clear()
    }

    override fun paint(g: Graphics?) {}
    override fun update(g: Graphics?) {}

    override fun addNotify() {
        super.addNotify()
        if (!jawtLoaded) println("Wyrm direct: jawt did not load")
        val hwnd = runCatching { WyrmActivity.nativeDesktopCanvasHwnd(this) }.getOrDefault(0L)
        if (hwnd != 0L) {
            runCatching { WyrmActivity.nativeDesktopSetTarget(hwnd) }
            EngineSettings.publishFramePolicy()
        } else {
            println("Wyrm direct: no HWND for the game canvas")
        }
    }

    override fun removeNotify() {
        // The engine leaves this HWND before Windows destroys it.
        runCatching { WyrmActivity.nativeDesktopSetTarget(0L) }
        EngineSettings.publishFramePolicy()
        super.removeNotify()
    }

    companion object {
        /** JAWT is the JDK's native-window library; wyrm.dll finds it once loaded. */
        private val jawtLoaded: Boolean by lazy { runCatching { System.loadLibrary("jawt") }.isSuccess }
    }
}
