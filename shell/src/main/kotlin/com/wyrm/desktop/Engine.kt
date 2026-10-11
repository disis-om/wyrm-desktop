package com.wyrm.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wyrm.omrajput.WyrmActivity
import com.wyrm.omrajput.ui.SkinState
import com.wyrm.omrajput.ui.DEFAULT_BG_SCALE
import com.wyrm.omrajput.data.SettingsCodec
import com.wyrm.omrajput.ui.WyrmLookStore
import java.io.File
import javax.swing.SwingUtilities

/**
 * The engine (wyrm.dll, Wyrm Android's own C engine) and what the shell
 * knows about it. The engine runs on its own JVM thread; its callbacks land
 * here and are moved onto the UI thread before they touch Compose state.
 */
object Engine {
    const val SCREEN_TITLE = 0
    const val SCREEN_SKIN_EDITOR = 1
    const val SCREEN_PLAYING = 2
    const val SCREEN_LOBBY = 3

    var screen by mutableIntStateOf(SCREEN_TITLE)
        private set
    var started by mutableStateOf(false)
        private set
    var failure by mutableStateOf<String?>(null)
        private set
    var savedNickname by mutableStateOf("")
        private set
    var savedArena by mutableStateOf("")
        private set
    var notice by mutableStateOf<String?>(null)
    var lastRun by mutableStateOf<Triple<Int, Int, Double>?>(null)
        private set

    private var attempt = 1L
    private var thread: Thread? = null

    /** Loads SDL3 + wyrm.dll and starts the engine thread (idle on Home). */
    fun start(width: Int, height: Int) {
        if (thread != null) return
        val bin = System.getProperty("wyrm.native.bin") ?: "native"
        val root = System.getProperty("wyrm.android.root") ?: "."
        try {
            System.load(File(bin, "SDL3.dll").absolutePath)
            System.load(File(bin, "wyrm.dll").absolutePath)
        } catch (error: Throwable) {
            failure = "The engine could not be loaded: ${error.message}"
            return
        }
        thread = Thread({
            val code = WyrmActivity.nativeDesktopRun(root, width, height)
            ui { if (code != 0) failure = "The engine stopped (code $code)." }
        }, "wyrm-engine").apply {
            isDaemon = true
            start()
        }
        started = true
        // Wyrm looks (hair, ears, glasses): drawn on this computer only, never sent.
        WyrmLookStore.attach(DesktopContext) { hair, hairRgb, ears, glasses ->
            WyrmActivity.nativeSetWyrmLook(hair, hairRgb, ears, glasses)
        }
        // Modes › Assist laser in joystick (the mouse steers like a joystick here).
        com.wyrm.omrajput.ui.JoystickLaserStore.attach(DesktopContext) { on, length ->
            runCatching { WyrmActivity.nativeSetJoystickLaser(on, length) }
        }
        // Settings › Display › Look ahead (Android's play feel; it was shown but never
        // reached the engine or the disk before D6).
        com.wyrm.omrajput.ui.PlayFeelStore.attach(DesktopContext) { originalArrow, lookAhead, zoomStyle ->
            runCatching { WyrmActivity.nativeSetPlayFeel(originalArrow, lookAhead, zoomStyle) }
        }
        com.wyrm.omrajput.data.PlayOrientation.load(DesktopContext)
        // Home › Near Original: slither's own HUD and controls (and the arena's number).
        com.wyrm.omrajput.ui.NearOriginalStore.attach(DesktopContext) { on, server ->
            runCatching { WyrmActivity.nativeSetNearOriginal(on, server) }
        }
        // Its switch is on Home again (OM, 2026-10-11; it was hidden 2026-10-03).
        // Team mode (D2): the roster/chat look, then the NTL team loop.
        com.wyrm.omrajput.ui.TeamHudStore.attach(DesktopContext) { values ->
            runCatching { WyrmActivity.nativeSetTeamHudStyle(values) }
        }
        DesktopTeam.start()
    }

    // ---- Arena background size editor (Android's BACKGROUND_SIZE_EDITOR) ----

    /** Open while the size editor is up: the size now, and what to put back on Cancel. */
    data class BackgroundEditor(val scale: Float, val normal: Float, val assist: Float)

    var backgroundEditor by mutableStateOf<BackgroundEditor?>(null)
        private set

    private fun settingNumber(id: String, fallback: Float): Float =
        SettingsCodec.settings(WyrmActivity.nativeSettingsSnapshot()).firstOrNull { it.id == id }?.number ?: fallback

    /** The engine's AI arena with only its map and board, the slider over it. */
    fun openBackgroundEditor() {
        if (!started || backgroundEditor != null) return
        val normal = settingNumber("normal.bg_scale", DEFAULT_BG_SCALE)
        val assist = settingNumber("assist.bg_scale", normal)
        backgroundEditor = BackgroundEditor(normal, normal, assist)
        WyrmActivity.nativeSetEditorBare(true)
        WyrmActivity.nativeEnterAiLayoutEditor(savedNickname.ifBlank { "Wyrm Player" })
    }

    /** Live, on both modes, as Android writes it. */
    fun setBackgroundScale(scale: Float) {
        val editor = backgroundEditor ?: return
        writeBackgroundScale(scale, scale)
        backgroundEditor = editor.copy(scale = scale)
    }

    fun closeBackgroundEditor(save: Boolean) {
        val editor = backgroundEditor ?: return
        if (!save) writeBackgroundScale(editor.normal, editor.assist)
        backgroundEditor = null
        WyrmActivity.nativeExitAiLayoutEditor()
    }

    private fun writeBackgroundScale(normal: Float, assist: Float) {
        WyrmActivity.nativeSetSetting("normal.bg_scale", normal, 0f, 0f, 0f, 1)
        WyrmActivity.nativeSetSetting("assist.bg_scale", assist, 0f, 0f, 0f, 1)
    }

    // ---- Arena UI layout editor (Android's ARENA_HUD_LAYOUT, mouse-driven) ----

    /** The settings as they were when the editor opened (Cancel writes them back); null when closed. */
    var hudEditor by mutableStateOf<List<com.wyrm.omrajput.data.Setting>?>(null)
        private set

    private var teamHudBefore: Map<String, Float> = emptyMap()

    fun openHudEditor() {
        if (!started || hudEditor != null || backgroundEditor != null) return
        EngineSettings.refresh()
        hudEditor = EngineSettings.settings
        // The roster and chat look lives app side (TeamHudStore): kept too, for Cancel.
        teamHudBefore = com.wyrm.omrajput.ui.TeamHudStore.KEYS.associateWith { com.wyrm.omrajput.ui.TeamHudStore.value(it) }
        WyrmActivity.nativeEnterAiLayoutEditor(savedNickname.ifBlank { "Wyrm Player" })
    }

    fun closeHudEditor(save: Boolean) {
        val snapshot = hudEditor ?: return
        if (!save) {
            // Android's restoreEditorSnapshot, for the pieces this editor moves.
            HudPiece.entries.forEach { piece ->
                val x = snapshot.firstOrNull { it.id == "${piece.prefix}_x" }?.raw?.toFloatOrNull()
                val y = snapshot.firstOrNull { it.id == "${piece.prefix}_y" }?.raw?.toFloatOrNull()
                if (x != null && y != null) WyrmActivity.nativeSetSetting(piece.prefix, x, y, 0f, 0f, 2)
            }
            snapshot.filter { it.id in HudPiece.SIZE_IDS }.forEach { setting ->
                setting.raw.toFloatOrNull()?.let { WyrmActivity.nativeSetSetting(setting.id, it, 0f, 0f, 0f, 1) }
            }
            teamHudBefore.forEach { (key, value) -> com.wyrm.omrajput.ui.TeamHudStore.apply("teamhud.$key", value) }
        }
        teamHudBefore = emptyMap()
        hudEditor = null
        WyrmActivity.nativeExitAiLayoutEditor()
        EngineSettings.refresh()
    }

    /** Esc: Cancel in an editor, else the engine's own back. */
    fun escape() {
        when {
            backgroundEditor != null -> closeBackgroundEditor(save = false)
            hudEditor != null -> closeHudEditor(save = false)
            else -> back()
        }
    }

    // ---- Skin: what is worn, as the engine holds it (Android's skin glue) ----
    var skin by mutableStateOf(SkinState())
        private set
    var arenaBackground by mutableIntStateOf(0)
        private set

    /** The Skin page opened: no engine preview, and tell us what is worn. */
    fun skinOpened() {
        if (!started) return
        WyrmActivity.nativeSkinSetPostcard(false)
        WyrmActivity.nativeSkinSync(false)
    }

    fun skinPreset(index: Int) = skinSaved { WyrmActivity.nativeSkinSetPreset(index) }
    fun skinCode(code: String, colours: IntArray) = skinSaved {
        // Colours are positional, so they go first (as on Android).
        WyrmActivity.nativeSkinSetColors(colours)
        WyrmActivity.nativeSkinSetCode(code)
        WyrmActivity.nativeSkinSetMode(code.isNotEmpty())
    }
    fun skinAccessory(id: Int) = skinSaved { WyrmActivity.nativeSkinSetAccessory(id) }
    fun skinBackground(index: Int) = skinSaved {
        arenaBackground = index
        WyrmActivity.nativeSkinSetBackground(index)
    }

    private fun skinSaved(change: () -> Unit) {
        if (!started) return
        change()
        WyrmActivity.nativeSkinSync(true)
    }

    /** The engine has said what is worn (since then the account's shared copy may carry it). */
    @Volatile var skinKnown = false
        private set

    @JvmStatic fun onSkinState(custom: Boolean, preset: Int, code: String?, accessory: Int, colours: IntArray?, background: Int) = ui {
        skin = SkinState(custom, preset, code.orEmpty(), accessory, colours ?: IntArray(0))
        arenaBackground = background
        skinKnown = true
    }

    /** The match on screen is the AI arena (no team, no team chat there). */
    var inAiArena by mutableStateOf(false)
        private set

    fun play(nickname: String, endpoint: String) {
        inAiArena = false
        notice = null
        // The picker's "Recently joined" (Android's RecentArenas.push on Play).
        runCatching { com.wyrm.omrajput.data.RecentArenas.push(DesktopContext, endpoint) }
        DesktopLive.arena = endpoint
        WyrmActivity.nativeSetNickname(nickname)
        WyrmActivity.nativeEnterArena(nickname, endpoint, attempt++)
    }

    fun playWithAi(nickname: String) {
        inAiArena = true
        notice = null
        WyrmActivity.nativeSetNickname(nickname)
        WyrmActivity.nativeEnterAiMode(nickname)
    }

    /** Esc: the engine's own "back" (leaves the match, as Android's back key). */
    fun back() {
        WyrmActivity.nativeDesktopKey(SDL_SCANCODE_AC_BACK, true)
        WyrmActivity.nativeDesktopKey(SDL_SCANCODE_AC_BACK, false)
    }

    fun quit() {
        if (started) WyrmActivity.nativeDesktopQuit()
    }

    // ---- callbacks from WyrmActivity (engine thread) ----
    @JvmStatic fun onScreen(value: Int) = ui {
        screen = value
        DesktopLive.pushPresence()
    }

    /** The engine's title is up: its settings exist; send what the app keeps. */
    @JvmStatic fun onTitleReady() = ui { EngineSettings.publishAll() }
    @JvmStatic fun onHomeState(nickname: String?, arena: String?, score: Int, kills: Int) = ui {
        if (!nickname.isNullOrEmpty()) savedNickname = nickname
        if (!arena.isNullOrEmpty()) savedArena = arena
    }
    @JvmStatic fun onDeath(active: Boolean, score: Int, kills: Int, autoRespawn: Boolean = false) = ui {
        death = if (active) score to kills else null
    }

    /** The death card: (score, kills) while the engine waits for Play or Home. */
    var death by mutableStateOf<Pair<Int, Int>?>(null)
        private set

    fun deathPlay() { death = null; WyrmActivity.nativeDeathPlay() }
    fun deathHome() { death = null; WyrmActivity.nativeDeathHome() }
    @JvmStatic fun onRefused(endpoint: String?, seconds: Int) = ui {
        notice = "That arena turned us away for $seconds s. Try another one."
    }
    @JvmStatic fun onEnterIgnored() = ui { notice = "Still leaving the last arena, try again in a moment." }
    @JvmStatic fun onPortAvailable(available: Boolean) = Unit

    /**
     * The arena dropped the snake (engine: socket closed while alive, or a
     * refused join): Android's DropWatch takes the snapshot, adds this
     * computer's network facts and probes, and shows its card (or, with
     * "Always send", sends the report). The lobby gets a short line too, so
     * a waiting Play button frees up.
     */
    @JvmStatic fun onDrop(snapshot: String?) = ui {
        val packed = snapshot.orEmpty()
        val endpoint = com.wyrm.omrajput.data.DropWatch.parse(packed)["arena"].orEmpty()
        val arena = HomeState.arenas.firstOrNull { it.endpoint == endpoint }
        com.wyrm.omrajput.data.DropWatch.onNativeDrop(packed, arena, Account.repository)
        notice = "The arena dropped you."
    }
    @JvmStatic fun onRun(score: Int, kills: Int, seconds: Double) = ui {
        lastRun = Triple(score, kills, seconds)
        // To the account (D6): desktop runs count on the boards.
        DesktopLive.onRun(score, kills, seconds)
    }

    private fun ui(block: () -> Unit) = SwingUtilities.invokeLater(block)

    const val SDL_SCANCODE_AC_BACK = 270
}
