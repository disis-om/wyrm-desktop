package com.wyrm.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import com.wyrm.omrajput.WyrmActivity
import com.wyrm.omrajput.data.Hotkey
import com.wyrm.omrajput.data.Setting
import com.wyrm.omrajput.data.SettingType
import com.wyrm.omrajput.data.SettingsCodec
import com.wyrm.omrajput.ui.Wyrm
import com.wyrm.omrajput.ui.WyrmThemeId
import com.wyrm.omrajput.ui.formatSettingNumber
import javax.swing.Timer

/**
 * The engine's settings as the Settings pages see them: Wyrm Android's own
 * snapshot (`nativeSettingsSnapshot`, parsed by `SettingsCodec`) and its own
 * writes (`nativeSetSetting`), so every engine setting is the same one, with
 * the same label, range and meaning, on both. Plus the app settings Android
 * keeps beside the engine: the theme (same prefs file and keys as Android's
 * `wyrm_ui_preferences`) and the desktop's frame policy.
 */
object EngineSettings {
    var settings by mutableStateOf<List<Setting>>(emptyList())
        private set
    var hotkeys by mutableStateOf<List<Hotkey>>(emptyList())
        private set
    var version by mutableStateOf("")
        private set

    fun refresh() {
        if (!Engine.started) return
        runCatching { SettingsCodec.settings(WyrmActivity.nativeSettingsSnapshot()) }.getOrNull()
            ?.takeIf { it.isNotEmpty() }?.let { settings = it }
        runCatching { SettingsCodec.hotkeys(WyrmActivity.nativeHotkeysSnapshot()) }.getOrNull()?.let { hotkeys = it }
        runCatching { WyrmActivity.nativeSettingsVersion() }.getOrNull()?.let { version = it }
    }

    /** Offscreen renders (pageShot): sample keys, as the engine would report them. */
    internal fun showKeysForPreview(rows: List<Hotkey>, options: List<com.wyrm.omrajput.data.KeyOption>) {
        hotkeys = rows
        keyOptions = options
    }

    /** Offscreen renders (pageShot) have no engine: they hand in a sample table. */
    internal fun showForPreview(sample: List<Setting>, sampleVersion: String) {
        settings = sample
        version = sampleVersion
    }

    /** Android's writeSetting: to the engine, and the row shows it at once. */
    fun write(setting: Setting, values: List<Float>) {
        if (!Engine.started || values.isEmpty()) return
        WyrmActivity.nativeSetSetting(
            setting.id,
            values.getOrElse(0) { 0f }, values.getOrElse(1) { 0f },
            values.getOrElse(2) { 0f }, values.getOrElse(3) { 0f },
            values.size,
        )
        val raw = when (setting.type) {
            SettingType.COLOR3, SettingType.COLOR4 -> values.joinToString(",") { formatSettingNumber(it) }
            SettingType.BOOL -> if (values.first() != 0f) "1" else "0"
            else -> formatSettingNumber(values.first())
        }
        settings = settings.map { if (it.id == setting.id) it.copy(raw = raw) else it }
    }

    /** A position pair ("hud.minimap" → hud.minimap_x/_y), as Android's moveArenaHud writes it. */
    fun writePair(prefix: String, x: Float, y: Float) {
        if (!Engine.started) return
        WyrmActivity.nativeSetSetting(prefix, x, y, 0f, 0f, 2)
        settings = settings.map {
            when (it.id) {
                "${prefix}_x" -> it.copy(raw = formatSettingNumber(x))
                "${prefix}_y" -> it.copy(raw = formatSettingNumber(y))
                else -> it
            }
        }
    }

    /** One number, by id (the layout editor's sliders). */
    fun write(id: String, value: Float) {
        val setting = settings.firstOrNull { it.id == id } ?: return
        write(setting, listOf(value))
    }

    fun number(id: String, fallback: Float): Float = settings.firstOrNull { it.id == id }?.raw?.toFloatOrNull() ?: fallback

    // ---- Keys (Settings › Controls): every engine action is a key here ----

    /** The engine's name for every key it can bind (`nativeKeyOptions`). */
    var keyOptions by mutableStateOf<List<com.wyrm.omrajput.data.KeyOption>>(emptyList())
        private set

    fun refreshKeys() {
        if (!Engine.started) return
        runCatching { SettingsCodec.hotkeys(WyrmActivity.nativeHotkeysSnapshot()) }.getOrNull()?.let { hotkeys = it }
        runCatching { SettingsCodec.keyOptions(WyrmActivity.nativeKeyOptions()) }.getOrNull()
            ?.takeIf { it.isNotEmpty() }?.let { keyOptions = it }
    }

    /** What the engine calls a key ("SPACE", "RIGHT CLICK"), or "" when it cannot bind it. */
    fun keyName(key: Int): String = keyOptions.firstOrNull { it.key == key }?.name.orEmpty()

    /**
     * Binds [key] to [action]. A key belongs to one action: the action that
     * had it takes this action's old key (a swap), and the swap is returned
     * so the page can say so. [key] = -1 leaves the action without a key.
     */
    fun setKey(action: Int, key: Int): Hotkey? {
        if (!Engine.started) return null
        val row = hotkeys.firstOrNull { it.action == action } ?: return null
        val other = if (key < 0) null else hotkeys.firstOrNull { it.action != action && it.key == key }
        val next = hotkeys.map {
            when {
                it.action == action -> it.copy(key = key, keyName = keyName(key))
                other != null && it.action == other.action -> it.copy(key = row.key, keyName = row.keyName)
                else -> it
            }
        }
        sendHotkey(next.first { it.action == action })
        other?.let { swapped -> sendHotkey(next.first { it.action == swapped.action }) }
        hotkeys = next
        later(250) { refreshKeys() }
        return other
    }

    /** Toggle (0) or hold (1), for the actions that may choose. */
    fun setMode(action: Int, mode: Int) {
        if (!Engine.started) return
        val row = hotkeys.firstOrNull { it.action == action && !it.fixedMode } ?: return
        val next = row.copy(mode = mode)
        sendHotkey(next)
        hotkeys = hotkeys.map { if (it.action == action) next else it }
        later(250) { refreshKeys() }
    }

    /** Every key back to Wyrm Desktop's defaults (the engine's, with turns on A/D). */
    fun resetKeys() {
        if (!Engine.started) return
        hotkeys.forEach { row ->
            val default = DesktopKeys.defaults[row.action] ?: return@forEach
            sendHotkey(row.copy(key = default.first, mode = if (row.fixedMode) row.mode else default.second))
        }
        later(250) { refreshKeys() }
    }

    /** The row as the engine takes it; place and visibility go back unchanged. */
    private fun sendHotkey(row: Hotkey) {
        WyrmActivity.nativeSetHotkey(row.action, row.key, row.mode, row.visible, row.x, row.y)
    }

    /** Settings action 8: the arena UI back where it started (Android's HUD Reset). */
    fun resetHud() {
        if (!Engine.started) return
        WyrmActivity.nativeSettingsAction(8)
        later(300) { refresh() }
    }

    /** Settings › Reset everything to defaults (Android's settings action 1). */
    fun resetAll() {
        if (!Engine.started) return
        WyrmActivity.nativeSettingsAction(1)
        later(400) { refresh() }
    }

    // ---- Themes (Settings › Accessibility › Themes), Android's keys ----

    private val ui by lazy { DesktopContext.getSharedPreferences("wyrm_ui_preferences", android.content.Context.MODE_PRIVATE) }
    var theme by mutableStateOf(WyrmThemeId.fromStored(null))
        private set
    var themeIntensity by mutableFloatStateOf(0.5f)
        private set

    /** At launch, before the first frame: the saved theme, as Android does in init. */
    fun loadTheme() {
        theme = WyrmThemeId.fromStored(ui.getString("theme", null))
        themeIntensity = ui.getFloat("theme_intensity", 0.5f).coerceIn(0f, 1f)
        Wyrm.applyTheme(theme, themeIntensity)
    }

    fun selectTheme(next: WyrmThemeId) {
        if (next == theme) return
        theme = next
        Wyrm.applyTheme(next, themeIntensity)
        ui.edit().putString("theme", next.storedName).apply()
        publishArenaTheme()
    }

    fun applyThemeIntensity(value: Float) {
        val next = value.coerceIn(0f, 1f)
        if (kotlin.math.abs(next - themeIntensity) < 0.0001f) return
        themeIntensity = next
        Wyrm.applyTheme(theme, next)
        ui.edit().putFloat("theme_intensity", next).apply()
        publishArenaTheme()
    }

    /** The engine's own screens follow the theme (Android's publishArenaTheme). */
    fun publishArenaTheme() {
        if (!Engine.started) return
        val p = Wyrm.currentPalette
        runCatching {
            WyrmActivity.nativeSetArenaTheme(
                intArrayOf(
                    p.paper.toArgb(), p.card.toArgb(), p.ink.toArgb(),
                    p.onInk.toArgb(), p.quiet.toArgb(), p.mute.toArgb(),
                    p.rule.toArgb(), p.live.toArgb(), p.link.toArgb(),
                    p.well.toArgb(), p.track.toArgb(), p.badge.toArgb(),
                ),
                p.dark,
            )
        }
    }

    // ---- Performance (this computer): frame cap and vsync ----

    private val performance by lazy { DesktopContext.getSharedPreferences("wyrm_desktop_performance", android.content.Context.MODE_PRIVATE) }
    /** 0 = no cap. */
    var fpsCap by mutableIntStateOf(120)
        private set
    var vsync by mutableStateOf(true)
        private set

    fun loadPerformance() {
        fpsCap = performance.getInt("fps_cap", 120)
        vsync = performance.getBoolean("vsync", true)
    }

    fun setFramePolicy(vsync: Boolean = this.vsync, cap: Int = fpsCap) {
        this.vsync = vsync
        fpsCap = cap
        performance.edit().putBoolean("vsync", vsync).putInt("fps_cap", cap).apply()
        publishFramePolicy()
    }

    fun publishFramePolicy() {
        if (Engine.started) runCatching { WyrmActivity.nativeSetFramePolicy(vsync, fpsCap) }
    }

    /** Once the engine is up: everything the app keeps that the engine must know. */
    fun publishAll() {
        refresh()
        publishArenaTheme()
        publishFramePolicy()
    }

    private fun later(ms: Int, block: () -> Unit) {
        Timer(ms) { block() }.apply { isRepeats = false; start() }
    }
}
