package com.wyrm.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wyrm.omrajput.data.Hotkey
import com.wyrm.omrajput.ui.SettingsActionRow
import com.wyrm.omrajput.ui.SettingsCaption
import com.wyrm.omrajput.ui.SettingsCard
import com.wyrm.omrajput.ui.SettingsHairline
import com.wyrm.omrajput.ui.SettingsSectionLabel
import com.wyrm.omrajput.ui.SettingsValueRow
import com.wyrm.omrajput.ui.Wyrm
import java.awt.event.KeyEvent

/**
 * Settings › Controls on a computer (OM, 2026-10-11): the phone's on-screen
 * buttons are keys here. Every engine action has one; the defaults ship and
 * the player can keep them or bind any key or mouse button to any action.
 * The keys live in the engine's settings file (actions 0-14 in user.dat as
 * always, Eyes Back at the end of its ext block on the desktop).
 */
internal object DesktopKeys {
    /** Engine key codes (GLFW's, the mouse's buttons above GLFW's last key). */
    const val NONE = -1
    private const val MOUSE_RIGHT = 401
    private const val MOUSE_MIDDLE = 402
    private const val MOUSE_BACK = 403
    private const val MOUSE_FORWARD = 404

    /** action → (key, mode). The engine's own defaults, with turns on A / D. */
    val defaults: Map<Int, Pair<Int, Int>> = mapOf(
        0 to (72 to 0),   // HUD: H
        1 to (80 to 0),   // Show names: P
        2 to (70 to 0),   // Big food: F
        3 to (75 to 1),   // Assist: K, held
        4 to (84 to 0),   // Bot: T
        5 to (90 to 0),   // Hotkey menu: Z
        6 to (82 to 1),   // Restart: R
        7 to (81 to 1),   // Quit: Q
        8 to (78 to 0),   // Zoom in: N
        9 to (77 to 0),   // Zoom out: M
        10 to (32 to 0),  // Boost: Space
        11 to (65 to 0),  // Turn left: A
        12 to (68 to 0),  // Turn right: D
        14 to (86 to 0),  // Auto restart: V
        15 to (69 to 0),  // Eyes back: E
    )

    /** The page's groups, in order: (heading, actions with the names shown). */
    val groups: List<Pair<String, List<Pair<Int, String>>>> = listOf(
        "Moving" to listOf(10 to "Boost", 11 to "Turn left", 12 to "Turn right", 8 to "Zoom in", 9 to "Zoom out"),
        "In a match" to listOf(
            15 to "Eyes back", 14 to "Auto restart", 6 to "Restart", 7 to "Quit to the lobby",
            4 to "Bot", 3 to "Assist",
        ),
        "What you see" to listOf(0 to "HUD", 1 to "Show names", 2 to "Big food", 5 to "Hotkey menu"),
    )

    /** What a pressed key binds to; null for a key that cannot be bound. */
    fun fromAwt(code: Int): Int? = when (code) {
        in KeyEvent.VK_A..KeyEvent.VK_Z -> code            // 65-90, the same in GLFW
        in KeyEvent.VK_0..KeyEvent.VK_9 -> code            // 48-57
        in KeyEvent.VK_F1..KeyEvent.VK_F10 -> 290 + (code - KeyEvent.VK_F1)
        KeyEvent.VK_F12 -> 301
        KeyEvent.VK_SPACE -> 32
        KeyEvent.VK_RIGHT -> 262
        KeyEvent.VK_LEFT -> 263
        KeyEvent.VK_DOWN -> 264
        KeyEvent.VK_UP -> 265
        KeyEvent.VK_TAB -> 258
        KeyEvent.VK_SHIFT -> 340
        KeyEvent.VK_CONTROL -> 341
        else -> null
    }

    /** Why a key cannot be bound, for the keys the window keeps. */
    fun reserved(code: Int): String? = when (code) {
        KeyEvent.VK_F11 -> "F11 is full screen."
        KeyEvent.VK_ENTER -> "Enter is kept for chat."
        KeyEvent.VK_ALT, KeyEvent.VK_WINDOWS, KeyEvent.VK_META -> "That key belongs to Windows."
        else -> null
    }

    @OptIn(ExperimentalComposeUiApi::class)
    fun fromMouse(button: PointerButton?): Int? = when (button) {
        PointerButton.Secondary -> MOUSE_RIGHT
        PointerButton.Tertiary -> MOUSE_MIDDLE
        PointerButton.Back -> MOUSE_BACK
        PointerButton.Forward -> MOUSE_FORWARD
        else -> null
    }

    /** The engine's key name ("SPACE", "RIGHT CLICK") as the page says it. */
    fun label(row: Hotkey?): String {
        if (row == null || row.key < 0) return "Not set"
        val name = row.keyName.ifBlank { EngineSettings.keyName(row.key) }
        return when (name) {
            "" , "KEY" -> "Key ${row.key}"
            "SPACE" -> "Space"
            "LEFT" -> "Left arrow"
            "RIGHT" -> "Right arrow"
            "UP" -> "Up arrow"
            "DOWN" -> "Down arrow"
            "TAB" -> "Tab"
            "SHIFT" -> "Shift"
            "CTRL" -> "Ctrl"
            "RIGHT CLICK" -> "Right click"
            "MIDDLE CLICK" -> "Middle click"
            "MOUSE 4" -> "Mouse 4"
            "MOUSE 5" -> "Mouse 5"
            else -> name
        }
    }
}

/** Settings › Controls: the fixed mouse controls, then every action's key. */
@Composable
internal fun DesktopControlsPage() {
    LaunchedEffect(Unit) { EngineSettings.refreshKeys() }
    var listening by remember { mutableStateOf<Int?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    val rows = EngineSettings.hotkeys.associateBy { it.action }

    SettingsSectionLabel("Mouse", top = 14.dp)
    SettingsCard {
        SettingsValueRow("Steer", "Mouse", first = true)
        SettingsValueRow("Boost", "Left click", first = false)
        SettingsValueRow("Zoom", "Mouse wheel", first = false)
        SettingsValueRow("Leave the match", "Esc", first = false)
        SettingsValueRow("Full screen", "F11 · Alt+Enter", first = false)
    }
    SettingsCaption("Your snake heads for the pointer, measured from the middle of the arena.")

    if (rows.isEmpty()) {
        SettingsCaption("Your keys appear here once the arena has started.")
        return
    }

    DesktopKeys.groups.forEach { (heading, actions) ->
        SettingsSectionLabel(heading)
        SettingsCard {
            actions.forEachIndexed { index, (action, title) ->
                val row = rows[action] ?: return@forEachIndexed
                KeyRow(
                    title = title,
                    row = row,
                    first = index == 0,
                    listening = listening == action,
                    onListen = { note = null; listening = action },
                    onCancel = { if (listening == action) listening = null },
                    onBind = { key ->
                        listening = null
                        val swapped = EngineSettings.setKey(action, key)
                        note = when {
                            swapped != null -> "${DesktopKeys.label(row.copy(key = key, keyName = ""))} was " +
                                "${titleOf(swapped.action)}'s key, so ${titleOf(swapped.action)} " +
                                "now has ${DesktopKeys.label(row)}."
                            key < 0 -> "$title has no key now."
                            else -> null
                        }
                    },
                    onReserved = { reason -> note = reason },
                    onMode = { mode -> EngineSettings.setMode(action, mode) },
                )
            }
        }
        if (heading == "Moving") {
            SettingsCaption("W and the Up arrow boost too, while no other action uses them.")
        }
    }
    note?.let {
        Text(
            it,
            fontFamily = Wyrm.Body,
            fontSize = 13.sp,
            color = Wyrm.Ink,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
        )
    }
    SettingsCaption(
        "Click a key, then press the new one or a mouse button (right, middle, side). " +
            "Esc cancels, Backspace leaves the action without a key. " +
            "Toggle turns a helper on and off; Hold keeps it on while the key is down.",
    )
    SettingsSectionLabel("Defaults")
    SettingsCard {
        SettingsActionRow("Reset keys to defaults", first = true) {
            listening = null
            note = "Keys are back to their defaults."
            EngineSettings.resetKeys()
        }
    }
}

private fun titleOf(action: Int): String =
    DesktopKeys.groups.flatMap { it.second }.firstOrNull { it.first == action }?.second ?: "Another action"

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun KeyRow(
    title: String,
    row: Hotkey,
    first: Boolean,
    listening: Boolean,
    onListen: () -> Unit,
    onCancel: () -> Unit,
    onBind: (Int) -> Unit,
    onReserved: (String) -> Unit,
    onMode: (Int) -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(listening) { if (listening) runCatching { focus.requestFocus() } }
    Column {
        if (!first) SettingsHairline()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, fontFamily = Wyrm.Body, fontSize = 15.5.sp, color = Wyrm.Ink, modifier = Modifier.weight(1f))
            if (!row.fixedMode) {
                Chip(
                    text = if (row.mode == 1) "Hold" else "Toggle",
                    strong = false,
                    modifier = Modifier.pointerInput(row.mode) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type == PointerEventType.Press && event.button == PointerButton.Primary) {
                                    onMode(if (row.mode == 1) 0 else 1)
                                }
                            }
                        }
                    },
                )
                Spacer(Modifier.width(8.dp))
            }
            Chip(
                text = if (listening) "Press a key…" else DesktopKeys.label(row),
                strong = listening,
                modifier = Modifier
                    .widthIn(min = 112.dp)
                    // Before focusable(), so the chip's own key events reach it.
                    .onPreviewKeyEvent { event ->
                        if (!listening) return@onPreviewKeyEvent false
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                        val code = event.awtEventOrNull?.keyCode ?: return@onPreviewKeyEvent true
                        val reason = DesktopKeys.reserved(code)
                        when {
                            code == KeyEvent.VK_ESCAPE -> onCancel()
                            code == KeyEvent.VK_BACK_SPACE || code == KeyEvent.VK_DELETE -> onBind(DesktopKeys.NONE)
                            reason != null -> onReserved(reason)
                            else -> DesktopKeys.fromAwt(code)?.let(onBind)
                        }
                        true
                    }
                    .focusRequester(focus)
                    .onFocusChanged { if (!it.isFocused) onCancel() }
                    .focusable()
                    .pointerInput(listening) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type != PointerEventType.Press) continue
                                event.changes.forEach { it.consume() }
                                if (!listening) {
                                    if (event.button == PointerButton.Primary) onListen()
                                } else {
                                    val key = DesktopKeys.fromMouse(event.button)
                                    if (key != null) onBind(key) else onCancel()
                                }
                            }
                        }
                    },
            )
        }
    }
}

@Composable
private fun Chip(text: String, strong: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Text(
        text = text,
        fontFamily = Wyrm.Body,
        fontSize = 13.5.sp,
        textAlign = TextAlign.Center,
        color = if (strong) Wyrm.OnInk else Wyrm.Ink,
        modifier = modifier
            .pointerHoverIcon(PointerIcon.Hand)
            .clip(shape)
            .background(if (strong) Wyrm.Ink else Wyrm.Well)
            .border(1.dp, if (strong) Wyrm.Ink else Wyrm.Rule, shape)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}
