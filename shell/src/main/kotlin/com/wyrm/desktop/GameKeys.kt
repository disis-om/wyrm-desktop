package com.wyrm.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import com.wyrm.omrajput.WyrmActivity

/**
 * The keyboard in a match (OM, 2026-10-11: "no key works"). Keys used to go
 * to the engine only from the game surface's own key handler, so they reached
 * it only while that surface held Compose focus. They are taken at the window
 * now (Main.kt `onPreviewKeyEvent`), before any focus, whenever a match is on
 * screen and nothing is typing. What each key does is the player's binding
 * (Settings › Controls); this only carries the keys there.
 */
internal object GameKeys {
    /** A text field in a match (the team chat composer) has the keys. */
    var typing by mutableStateOf(false)

    private val held = mutableSetOf<Int>()
    private var logged = 0

    /** A match is on screen and owns the keyboard. */
    val active: Boolean
        get() = Engine.screen == Engine.SCREEN_PLAYING &&
            Engine.hudEditor == null && Engine.backgroundEditor == null &&
            !typing && !AppGate.signingIn

    /** The window's key hook. True when the key went to the game. */
    fun handle(event: KeyEvent): Boolean {
        if (!active) {
            releaseAll()
            return false
        }
        val down = when (event.type) {
            KeyEventType.KeyDown -> true
            KeyEventType.KeyUp -> false
            else -> return false
        }
        // awtEventOrNull, never `nativeKeyEvent as? java.awt.event.KeyEvent`: in
        // Compose 1.9 nativeKeyEvent is Compose's own InternalKeyEvent, so that
        // cast was always null and no key ever reached the engine.
        val awt = event.awtEventOrNull?.keyCode ?: return false
        if (awt == java.awt.event.KeyEvent.VK_ESCAPE) {
            if (down) Engine.escape()
            return true
        }
        // Enter in an online match with a team: the team chat box (D2).
        // In the AI arena Enter still goes to the engine (it closes its card).
        if (awt == java.awt.event.KeyEvent.VK_ENTER && DesktopTeam.canChatInArena) {
            if (down) DesktopTeam.openComposer()
            return true
        }
        val scancode = sdlScancode(awt) ?: return false
        // AWT repeats a held key; the engine counts each down as a press, so
        // only the first down and its up are sent.
        if (down) {
            if (held.add(scancode)) {
                if (logged < 20) { logged++; println("Wyrm keys: shell sent scancode $scancode") }
                WyrmActivity.nativeDesktopKey(scancode, true)
            }
        } else if (held.remove(scancode)) {
            WyrmActivity.nativeDesktopKey(scancode, false)
        }
        return true
    }

    /** Alt-Tab, the end of a match, a text field: nothing stays held down. */
    fun releaseAll() {
        if (held.isEmpty()) return
        held.forEach { WyrmActivity.nativeDesktopKey(it, false) }
        held.clear()
    }
}
