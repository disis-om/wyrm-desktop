package com.wyrm.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.wyrm.omrajput.WyrmActivity
import com.wyrm.omrajput.ui.*
import java.awt.event.KeyEvent as AwtKey

/** The app's key map. Engine actions remain in the engine's account document. */
internal object DesktopShortcuts {
    enum class Action(val title: String, val default: Int) {
        CHAT("Open arena chat", AwtKey.VK_C), SEARCH("Search", AwtKey.VK_F or CTRL),
        CHAT_PANEL("Collapse / expand chat (double press)", AwtKey.VK_C),
        REFRESH("Refresh list", AwtKey.VK_F5), UNDO("Undo", AwtKey.VK_LEFT),
        REDO("Redo", AwtKey.VK_RIGHT), CLEAR("Clear editor…", AwtKey.VK_DELETE),
    }
    const val CTRL = 1 shl 16
    const val ALT = 1 shl 17
    const val SHIFT = 1 shl 18
    private val prefs get() = DesktopContext.getSharedPreferences("wyrm_desktop_shortcuts", 0)
    var revision by mutableIntStateOf(0)
    var capture by mutableStateOf<Action?>(null)
    var captureError by mutableStateOf("")
    var textSessions = 0
    val modals = mutableStateListOf<Any>()
    var undo: (() -> Unit)? = null
    var redo: (() -> Unit)? = null
    var clear: (() -> Unit)? = null
    var search: (() -> Unit)? = null
    var refresh: (() -> Unit)? = null
    private var chatTap: javax.swing.Timer? = null
    private var chatKeyHeld = false
    private fun cancelChatTap() { chatTap?.stop(); chatTap = null }
    fun onFocusLost() { cancelChatTap(); chatKeyHeld = false }
    fun reload() { onFocusLost(); revision++ }
    private fun arenaChatKey(code: Int) {
        if (code == binding(Action.CHAT_PANEL)) {
            if (chatTap != null) {
                cancelChatTap()
                WyrmActivity.nativeToggleChatPanel()
            } else {
                chatTap = javax.swing.Timer(320) {
                    chatTap = null
                    if (code == binding(Action.CHAT) && GameKeys.active && !modal && textSessions == 0 && DesktopTeam.canChatInArena)
                        DesktopTeam.openComposer()
                }.apply { isRepeats = false; start() }
            }
        } else if (DesktopTeam.canChatInArena) DesktopTeam.openComposer()
    }
    private var searchOwner: Any? = null
    fun registerSearch(id: Any, action: (() -> Unit)?) {
        if (action != null) { searchOwner = id; search = action }
        else if (searchOwner === id) { searchOwner = null; search = null }
    }
    val modal: Boolean get() = modals.isNotEmpty()
    fun modalChange(id: Any, open: Boolean) { if (open) { if (id !in modals) modals.add(id) } else modals.remove(id) }
    fun binding(action: Action): Int {
        revision
        val value = prefs.getInt(action.name, action.default)
        return if (valid(action.name, value.toString())) value else action.default
    }
    fun valid(key: String, raw: String): Boolean {
        if (Action.entries.none { it.name == key }) return false
        val value = raw.toIntOrNull() ?: return false
        val code = value and 0xffff
        val allowed = code in AwtKey.VK_A..AwtKey.VK_Z || code in AwtKey.VK_0..AwtKey.VK_9 ||
            code in AwtKey.VK_F1..AwtKey.VK_F12 || code in listOf(AwtKey.VK_LEFT, AwtKey.VK_RIGHT, AwtKey.VK_DELETE, AwtKey.VK_SPACE)
        return value and (0xffff or CTRL or ALT or SHIFT).inv() == 0 && allowed &&
            code != AwtKey.VK_F11 && !(value and CTRL != 0 && (code in AwtKey.VK_1..AwtKey.VK_5 || code in listOf(AwtKey.VK_Z, AwtKey.VK_Y)))
    }
    fun label(code: Int): String = (if (code and CTRL != 0) "Ctrl+" else "") +
        (if (code and ALT != 0) "Alt+" else "") + (if (code and SHIFT != 0) "Shift+" else "") + AwtKey.getKeyText(code and 0xffff)
    fun reset() { prefs.edit().clear().apply(); revision++ }

    fun handle(event: KeyEvent): Boolean {
        val awt = event.awtEventOrNull ?: return false
        if (HomeState.pickerOpen || HomeState.alertsOpen || Social.modalTrail != null) {
            if (awt.keyCode == AwtKey.VK_ESCAPE && event.type == KeyEventType.KeyDown) {
                HomeState.pickerOpen = false; HomeState.alertsOpen = false; Social.closeTrail(); return true
            }
            return false
        }
        if (capture != null) {
            if (event.type != KeyEventType.KeyDown) return true
            if (awt.keyCode == AwtKey.VK_ESCAPE) { capture = null; return true }
            if (awt.keyCode in listOf(AwtKey.VK_CONTROL, AwtKey.VK_ALT, AwtKey.VK_SHIFT, AwtKey.VK_META)) return true
            val code = chord(awt)
            val action = capture!!
            val engineKey = if (code and (CTRL or ALT or SHIFT) == 0) DesktopKeys.fromAwt(awt.keyCode) else null
            val engineConflict = action in setOf(Action.CHAT, Action.CHAT_PANEL) && engineKey != null &&
                EngineSettings.hotkeys.any { it.key == engineKey }
            if (!valid(action.name, code.toString()) ||
                engineConflict ||
                (awt.isControlDown && (awt.keyCode in AwtKey.VK_1..AwtKey.VK_5 || awt.keyCode in listOf(AwtKey.VK_Z, AwtKey.VK_Y))) ||
                Action.entries.any { it != action && binding(it) == code &&
                    setOf(it, action) != setOf(Action.CHAT, Action.CHAT_PANEL) }) {
                captureError = if (engineConflict) "That key is an arena action. Change its arena binding first, or use a modifier."
                    else "That shortcut is already in use. Choose another."
            } else { prefs.edit().putInt(action.name, code).apply(); revision++; capture = null; captureError = "" }
            return true
        }
        // Dialogs receive Esc/Enter themselves, and fields own all typed keys.
        if (event.type == KeyEventType.KeyUp &&
            awt.keyCode in listOf(binding(Action.CHAT) and 0xffff, binding(Action.CHAT_PANEL) and 0xffff)) chatKeyHeld = false
        if (modal || textSessions > 0 || GameKeys.typing || DesktopKeys.capturing || AppGate.signingIn) { cancelChatTap(); return false }
        if (event.type != KeyEventType.KeyDown) return false
        val code = chord(awt)
        if (GameKeys.active) {
            if (code == binding(Action.CHAT) || code == binding(Action.CHAT_PANEL)) {
                if (!chatKeyHeld) { chatKeyHeld = true; arenaChatKey(code) }
                return true
            }
            if (code == AwtKey.VK_TAB) {
                WyrmActivity.nativeSetChatTab((WyrmActivity.nativeChatTab() + 1) % 3)
                return true
            }
            return false
        }
        cancelChatTap()
        if (awt.isControlDown && awt.keyCode in AwtKey.VK_1..AwtKey.VK_5) {
            ShellNav.tab.value = Tab.entries[awt.keyCode - AwtKey.VK_1]; return true
        }
        if (awt.isControlDown && awt.keyCode == AwtKey.VK_TAB) {
            val tabs = Tab.entries
            ShellNav.tab.value = tabs[(ShellNav.tab.value.ordinal + if (awt.isShiftDown) tabs.size - 1 else 1) % tabs.size]; return true
        }
        val action = Action.entries.firstOrNull { binding(it) == code }
            ?: when (code) { AwtKey.VK_Z or CTRL -> Action.UNDO; AwtKey.VK_Y or CTRL -> Action.REDO; else -> null }
        val callback = when (action) { Action.UNDO -> undo; Action.REDO -> redo; Action.CLEAR -> clear; Action.SEARCH -> search; Action.REFRESH -> ({ refreshVisible() }); else -> null }
        if (callback != null) { callback(); return true }
        if (awt.keyCode == AwtKey.VK_ESCAPE) {
            when {
                SettingsProfile.editing -> Social.back()
                Social.modalTrail != null -> Social.closeTrail()
                Social.top != null -> Social.back()
                ShellNav.teamOpen.value -> ShellNav.teamOpen.value = false
                HomeState.lobbyOpen -> HomeState.lobbyOpen = false
                HelpNav.page != HelpPage.MAIN -> HelpNav.page = HelpPage.MAIN
                else -> return false
            }
            return true
        }
        if (code == AwtKey.VK_UP || code == AwtKey.VK_DOWN) {
            val delta = if (code == AwtKey.VK_UP) -1 else 1
            when (ShellNav.tab.value) {
                Tab.Settings -> moveSettings(delta)
                Tab.Social -> {
                    val sections = Social.Section.entries.filter { it != Social.Section.VOICE }
                    Social.openSection(sections[(sections.indexOf(Social.section) + delta + sections.size) % sections.size])
                }
                else -> return false
            }
            return true
        }
        if (code == AwtKey.VK_ENTER && ShellNav.tab.value in listOf(Tab.Settings, Tab.Social)) return true
        return false
    }
    private fun chord(e: AwtKey): Int = e.keyCode or (if (e.isControlDown) CTRL else 0) or
        (if (e.isAltDown) ALT else 0) or (if (e.isShiftDown) SHIFT else 0)

    private fun refreshVisible() {
        when (ShellNav.tab.value) {
            Tab.Trails -> com.wyrm.omrajput.ui.TrailsStore.refresh()
            Tab.Social -> Social.refreshVisible()
            Tab.Settings -> if (SettingsPageShot.open == SettingsPage.PROFILE) Social.refreshProfile(force = true)
            else -> Unit
        }
    }
}

/** Mounted screens register only actions they can actually perform. */
@Composable
internal fun EditorShortcuts(undo: () -> Unit, redo: () -> Unit, clear: () -> Unit) {
    val u by rememberUpdatedState(undo); val r by rememberUpdatedState(redo); val c by rememberUpdatedState(clear)
    DisposableEffect(Unit) {
        val undoAction = { u() }; val redoAction = { r() }; val clearAction = { c() }
        DesktopShortcuts.undo = undoAction; DesktopShortcuts.redo = redoAction; DesktopShortcuts.clear = clearAction
        onDispose {
            if (DesktopShortcuts.undo === undoAction) DesktopShortcuts.undo = null
            if (DesktopShortcuts.redo === redoAction) DesktopShortcuts.redo = null
            if (DesktopShortcuts.clear === clearAction) DesktopShortcuts.clear = null
        }
    }
}

@Composable
internal fun AppShortcutsPage() {
    SettingsSectionLabel("Across Wyrm")
    SettingsCard {
        DesktopShortcuts.Action.entries.forEachIndexed { index, action ->
            SettingsValueRow(action.title, if (DesktopShortcuts.capture == action) "Press a key…" else DesktopShortcuts.label(DesktopShortcuts.binding(action)), first = index == 0,
                onOpen = { DesktopShortcuts.captureError = ""; DesktopShortcuts.capture = action })
        }
        SettingsActionRow("Reset app shortcuts", first = false) { DesktopShortcuts.reset() }
    }
    if (DesktopShortcuts.captureError.isNotBlank()) SettingsCaption(DesktopShortcuts.captureError)
    SettingsCaption("Press the panel key twice within 320 ms to collapse or expand chat. If both chat actions use C: one C opens the composer after that interval; two C presses toggle the panel. Holding the key never counts as a double press.")
    SettingsCaption("Ctrl+1–5: top tabs · Ctrl+Tab / Ctrl+Shift+Tab: next / previous tab. Up / Down: sidebar sections · Enter: open · Esc: back or close. Ctrl+Z / Ctrl+Y: undo / redo. Chat: Tab switches Team / Global while typing; otherwise Team / Global / Online. Enter sends; Esc closes.")
}
