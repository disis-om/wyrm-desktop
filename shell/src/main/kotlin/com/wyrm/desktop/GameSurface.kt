package com.wyrm.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import com.wyrm.omrajput.WyrmActivity
import kotlinx.coroutines.isActive
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Rect
import org.jetbrains.skia.FilterMipmap

/**
 * The game, inside Compose.
 *
 * The engine renders into a hidden Vulkan window the size of this box; each
 * finished frame is read back (desktop_bridge.c) and drawn here as an image,
 * once per Compose frame. Compose owns the window, so resizing never flickers:
 * until the engine's next frame at the new size arrives, the last one is drawn
 * scaled into the new box. Mouse and keys go back to the engine as SDL events.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun GameSurface(modifier: Modifier = Modifier, gameKeys: Boolean = true) {
    val frames = remember { FrameReader() }
    var tick by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    val held = remember { HeldInput() }
    val windowFocused = LocalWindowInfo.current.isWindowFocused

    LaunchedEffect(Unit) {
        while (isActive) {
            withFrameNanos { }
            if (frames.pull()) tick++
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // Alt-Tab or a click on another window: no key or button stays down
    // (a boost would otherwise run on until the key is pressed again).
    LaunchedEffect(windowFocused) { if (!windowFocused) { held.releaseAll(); GameKeys.releaseAll() } }
    DisposableEffect(Unit) { onDispose { held.releaseAll(); GameKeys.releaseAll() } }

    Box(
        modifier
            .fillMaxSize()
            .background(Color(0xFF090909))
            .onSizeChanged { WyrmActivity.nativeDesktopResize(it.width, it.height) }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        val p = change.position
                        when (event.type) {
                            PointerEventType.Move, PointerEventType.Enter ->
                                WyrmActivity.nativeDesktopPointer(0, p.x, p.y, 0, 0f)
                            PointerEventType.Press -> {
                                focus.requestFocus()
                                val button = pointerButton(event.button)
                                WyrmActivity.nativeDesktopPointer(0, p.x, p.y, 0, 0f)
                                if (held.buttons.add(button)) WyrmActivity.nativeDesktopPointer(1, p.x, p.y, button, 0f)
                                held.last = p.x to p.y
                            }
                            PointerEventType.Release -> {
                                val button = pointerButton(event.button)
                                if (held.buttons.remove(button)) WyrmActivity.nativeDesktopPointer(2, p.x, p.y, button, 0f)
                            }
                            PointerEventType.Scroll ->
                                WyrmActivity.nativeDesktopPointer(3, p.x, p.y, 0, -change.scrollDelta.y)
                            else -> Unit
                        }
                    }
                }
            }
            // In a match the window takes the keys (GameKeys, Main.kt), whatever
            // has focus. Here only Esc, for the editors (HUD, background size).
            .onPreviewKeyEvent { event ->
                if (event.key != Key.Escape) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyDown) Engine.escape()
                true
            }
            .focusRequester(focus)
            .focusable(),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            tick // redraw on every new engine frame
            val image = frames.image ?: return@Canvas
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawImageRect(
                    image,
                    Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                    Rect.makeWH(size.width, size.height),
                    FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE),
                    null,
                    true,
                )
            }
        }
    }
}

private fun pointerButton(button: PointerButton?): Int = when (button) {
    PointerButton.Secondary -> 2
    PointerButton.Tertiary -> 3
    PointerButton.Back -> 4
    PointerButton.Forward -> 5
    else -> 1
}

/** What is held down right now, so it can all be let go at once. */
private class HeldInput {
    val keys = mutableSetOf<Int>()
    val buttons = mutableSetOf<Int>()
    var last = 0f to 0f

    fun releaseAll() {
        keys.forEach { WyrmActivity.nativeDesktopKey(it, false) }
        keys.clear()
        buttons.forEach { WyrmActivity.nativeDesktopPointer(2, last.first, last.second, it, 0f) }
        buttons.clear()
    }
}

/**
 * An AWT key code as an SDL scancode, for the keys the engine reads
 * (twindow.c `desktop_key_from_scancode`: letters, digits, Space, arrows, F11)
 * plus Enter, Tab and F1-F12 for ImGui. Null for everything else.
 */
internal fun sdlScancode(vk: Int): Int? = when (vk) {
    in java.awt.event.KeyEvent.VK_A..java.awt.event.KeyEvent.VK_Z -> 4 + (vk - java.awt.event.KeyEvent.VK_A)
    in java.awt.event.KeyEvent.VK_1..java.awt.event.KeyEvent.VK_9 -> 30 + (vk - java.awt.event.KeyEvent.VK_1)
    java.awt.event.KeyEvent.VK_0 -> 39
    java.awt.event.KeyEvent.VK_ENTER -> 40
    java.awt.event.KeyEvent.VK_TAB -> 43
    // Either Shift or Ctrl; the engine reads both sides as one key.
    java.awt.event.KeyEvent.VK_SHIFT -> 225
    java.awt.event.KeyEvent.VK_CONTROL -> 224
    java.awt.event.KeyEvent.VK_SPACE -> 44
    in java.awt.event.KeyEvent.VK_F1..java.awt.event.KeyEvent.VK_F12 -> 58 + (vk - java.awt.event.KeyEvent.VK_F1)
    java.awt.event.KeyEvent.VK_RIGHT -> 79
    java.awt.event.KeyEvent.VK_LEFT -> 80
    java.awt.event.KeyEvent.VK_DOWN -> 81
    java.awt.event.KeyEvent.VK_UP -> 82
    else -> null
}

/** Pulls the newest engine frame into a Skia image (one copy each way). */
private class FrameReader {
    private var buffer = ByteArray(0)
    private var seq = -1L
    var image: Image? = null
        private set

    fun pull(): Boolean {
        var packed = WyrmActivity.nativeDesktopCopyFrame(buffer, seq)
        if (packed == 0L) return false
        var width = ((packed ushr 48) and 0xffff).toInt()
        var height = ((packed ushr 32) and 0xffff).toInt()
        var next = packed and 0x7fffffffL
        if (next == seq || width <= 0 || height <= 0) return false
        val needed = width * height * 4
        if (buffer.size < needed) {
            buffer = ByteArray(needed)
            packed = WyrmActivity.nativeDesktopCopyFrame(buffer, seq)
            if (packed == 0L) return false
            width = ((packed ushr 48) and 0xffff).toInt()
            height = ((packed ushr 32) and 0xffff).toInt()
            next = packed and 0x7fffffffL
            if (buffer.size < width * height * 4) return false
        }
        val bgra = (packed and 0x80000000L) != 0L
        val info = ImageInfo(width, height, if (bgra) ColorType.BGRA_8888 else ColorType.RGBA_8888, ColorAlphaType.OPAQUE)
        val made = Image.makeRaster(info, buffer, width * 4)
        image?.close()
        image = made
        seq = next
        return true
    }
}
