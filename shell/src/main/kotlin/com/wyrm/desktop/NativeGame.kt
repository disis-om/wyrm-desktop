package com.wyrm.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import com.wyrm.omrajput.WyrmActivity
import com.wyrm.omrajput.data.Arena
import com.wyrm.omrajput.data.ArenaDirectory
import com.wyrm.omrajput.data.RecentArenas
import com.wyrm.omrajput.data.SavedArenas
import com.wyrm.omrajput.ui.IosGlyph
import com.wyrm.omrajput.ui.IosIcon
import com.wyrm.omrajput.ui.NearOriginalStore
import com.wyrm.omrajput.ui.Wyrm
import com.wyrm.omrajput.ui.WyrmMark
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities

/**
 * The game window (OM, 2026-11-12): "lobby ab compose me nahi balki lobby glfw
 * me banegi, and home aane pr compose ka app aayega ... arena me khelte hue
 * koi compose layer nahi aayega ... ui design same rehna chaiye".
 *
 * The Ready Room and the match are the engine's own window on screen
 * (native/desktop_ui.c), with the mouse and keys straight from Windows, as
 * the reference client's window; this Compose window shows only Home and
 * the pages behind it. When the lobby opens, the engine's window appears at
 * this window's place and size, and this one hides once the first frames are
 * on screen; Home brings it back where the game window was.
 *
 * Everything the lobby, its arena picker and the match's chat box *do* stays
 * here, unchanged from DesktopLobby / ArenaPicker / ArenaComposer: Kotlin
 * sends the engine what to show (small text models, a few pictures) and the
 * engine sends back what the player did ([onAction]).
 */
internal object NativeGame {
    private val ui = java.util.concurrent.Executor { SwingUtilities.invokeLater(it) }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + ui)
    private var loop: Job? = null

    /** The engine's window is on screen and this one is hidden (Main.kt). */
    var composeHidden by mutableStateOf(false)
        private set

    /** The engine has been asked to show its window. */
    private var nativeShown = false
    private var shownAt = 0L
    private var hideAfter = 0L

    /** The game window never came up this run: the lobby and match stay in Compose. */
    private var failed by mutableStateOf(false)

    /** The game window owns the lobby and the match (Shell.kt draws paper under them). */
    val wanted: Boolean
        get() = !failed && Engine.started && Engine.failure == null && !AppGate.signingIn &&
            AppGate.state.value == Gate.IN && Account.transitionTitle == null &&
            Engine.hudEditor == null && Engine.backgroundEditor == null &&
            (HomeState.lobbyOpen || Engine.screen == Engine.SCREEN_PLAYING)

    // ---- the lobby's own state (DesktopLobby's `entering`) ----
    private var entering = false
    private var lastNotice: String? = null
    private var lastScreen = -1

    fun start() {
        if (loop != null) return
        loop = scope.launch {
            var tick = 0
            while (isActive) {
                runCatching { step(tick++) }.onFailure { println("Wyrm game window: ${it.message}") }
                delay(33)
            }
        }
    }

    private val models = arrayOfNulls<String>(3)
    private var sentHint: String? = null
    private var sentCursor: String? = null
    private var imagesSent = false
    private val flagsSent = HashSet<String>()
    private val flagsLoading = HashSet<String>()

    private fun step(tick: Int) {
        if (!Engine.started) return
        if (!imagesSent) sendImages()
        // A match ends: its own state goes.
        val screen = Engine.screen
        if (screen != lastScreen) {
            if (screen == Engine.SCREEN_PLAYING) {
                entering = false
                DesktopArenaFeed.start()
            } else if (lastScreen == Engine.SCREEN_PLAYING) {
                DesktopArenaFeed.stop()
                DesktopTeam.closeComposer()
            }
            lastScreen = screen
        }
        if (Engine.notice != lastNotice) {
            lastNotice = Engine.notice
            if (Engine.notice != null) entering = false
        }
        handOff()
        if (!nativeShown) return
        publish(0, lobbyModel())
        publish(1, NativePicker.model())
        publish(2, chatModel())
        val hint = if (DesktopTeam.canChatInArena) "Esc  leave   ·   Enter  chat   ·   F11  full screen" else "Esc  leave   ·   F11  full screen"
        val overlay = "$hint|${DesktopCursors.laserInNormal}"
        if (overlay != sentHint) {
            sentHint = overlay
            runCatching { WyrmActivity.nativeDesktopOverlay(DesktopCursors.laserInNormal, false, hint) }
        }
        val cursor = DesktopCursors.current()
        if (cursor != sentCursor) {
            sentCursor = cursor
            sendCursor(cursor)
        }
        if (tick % 15 == 0) NativePicker.countries().forEach(::sendFlag)
    }

    private fun publish(kind: Int, text: String) {
        if (models[kind] == text) return
        models[kind] = text
        runCatching { WyrmActivity.nativeDesktopUiModel(kind, text) }
    }

    // ---- handing the window over ----

    private fun handOff() {
        val frame = DesktopWindow.frame as? androidx.compose.ui.awt.ComposeWindow ?: return
        val want = wanted
        val now = System.currentTimeMillis()
        if (want && !nativeShown) {
            val scale = frame.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0
            val (max, restore) = WindowChrome.snapshot()
            val r = restore ?: frame.bounds
            fun px(v: Int) = (v * scale).toInt()
            val full = DesktopWindow.fullscreen
            val rest = if (full && restore == null) java.awt.Rectangle(0, 0, 0, 0) else r
            runCatching {
                WyrmActivity.nativeDesktopGameWindow(
                    true, frame.windowHandle, max, px(rest.x), px(rest.y), px(rest.width), px(rest.height), full,
                )
            }
            models.fill(null)
            sentHint = null
            sentCursor = null
            nativeShown = true
            shownAt = now
            hideAfter = 0L
        } else if (want && nativeShown && !composeHidden && now - shownAt > 3_000) {
            // No frame came (a lost device?): the old Compose lobby and match for this run.
            println("Wyrm game window: no frame in 3 s, the lobby stays in the app window")
            runCatching { WyrmActivity.nativeDesktopGameWindow(false, 0L, false, 0, 0, 0, 0, false) }
            nativeShown = false
            failed = true
        } else if (!want && nativeShown) {
            if (hideAfter == 0L) {
                // Back where the game window was, then the game window goes.
                adoptGameWindow(frame)
                composeHidden = false
                frame.isVisible = true
                frame.toFront()
                hideAfter = now + 120
            } else if (now >= hideAfter) {
                runCatching { WyrmActivity.nativeDesktopGameWindow(false, 0L, false, 0, 0, 0, 0, false) }
                nativeShown = false
                hideAfter = 0L
                NativePicker.close()
                DesktopLive.pushPresence()
            }
        }
    }

    /** The game window's place, size, maximise and full screen, on the Compose window. */
    private fun adoptGameWindow(frame: java.awt.Frame) {
        val s = runCatching { WyrmActivity.nativeDesktopGameWindowState() }.getOrNull() ?: return
        if (s.size < 11 || s[2] <= 0 || s[3] <= 0) return
        val scale = frame.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0
        fun pt(v: Int) = (v / scale).toInt()
        val full = s[9] != 0
        val max = s[4] != 0
        val restore = java.awt.Rectangle(pt(s[5]), pt(s[6]), pt(s[7]), pt(s[8]))
        if (full) {
            if (restore.width > 0) frame.bounds = restore
            DesktopWindow.applyFullscreen(true)
        } else {
            DesktopWindow.applyFullscreen(false)
            frame.bounds = java.awt.Rectangle(pt(s[0]), pt(s[1]), pt(s[2]), pt(s[3]))
            WindowChrome.adopt(max, if (max && restore.width > 0) restore else null)
        }
    }

    // ---- what the engine's pages show ----

    private fun clean(text: String) = text.replace('\n', ' ').replace('\r', ' ')

    private fun lobbyModel(): String {
        val arena = HomeState.arenas.firstOrNull { it.endpoint.equals(HomeState.selected, ignoreCase = true) }
        val ready = arena != null || ArenaDirectory.isValidEndpoint(HomeState.selected)
        return buildString {
            append("arena=").append(clean(HomeState.selected)).append('\n')
            append("code=").append(arena?.id?.takeIf { it >= 0 } ?: -1).append('\n')
            append("players=").append(arena?.players ?: -1).append('\n')
            append("nickname=").append(clean(HomeState.nickname)).append('\n')
            Engine.lastRun?.let { (score, kills, seconds) -> append("run=").append(score).append('\t').append(kills).append('\t').append(seconds).append('\n') }
            append("notice=").append(clean(Engine.notice.orEmpty())).append('\n')
            append("entering=").append(if (entering) 1 else 0).append('\n')
            append("ready=").append(if (ready) 1 else 0).append('\n')
            append("canchat=").append(if (DesktopTeam.canChatInArena) 1 else 0).append('\n')
        }
    }

    private fun chatModel(): String {
        val global = DesktopTeam.composerTab == 1
        val busy = if (global) DesktopArenaFeed.sending else DesktopTeam.sending
        val error = if (global) DesktopArenaFeed.error else DesktopTeam.sendError
        return buildString {
            append("open=").append(if (DesktopTeam.composerOpen && Engine.screen == Engine.SCREEN_PLAYING) 1 else 0).append('\n')
            append("global=").append(if (global) 1 else 0).append('\n')
            append("busy=").append(if (busy) 1 else 0).append('\n')
            append("error=").append(clean(error)).append('\n')
            append("hint=").append(if (global) "Message everyone · Enter sends · Esc closes" else "Message the team · Enter sends · Esc closes").append('\n')
            append("draft=").append(clean(DesktopTeam.draft)).append('\n')
        }
    }

    // ---- what the player did (engine thread) ----

    @JvmStatic
    fun onAction(action: Int, text: String?) {
        val value = text.orEmpty()
        SwingUtilities.invokeLater { runCatching { act(action, value) }.onFailure { println("Wyrm game window: action $action: ${it.message}") } }
    }

    private fun act(action: Int, value: String) {
        when (action) {
            1 -> play()
            2 -> if (!entering) { entering = true; Engine.playWithAi(HomeState.nickname) }
            3 -> if (!entering) HomeState.lobbyOpen = false
            4 -> NativePicker.open()
            5 -> {
                HomeState.nickname = value.filterNot { it.isISOControl() }.take(24)
                DesktopContext.getSharedPreferences("wyrm_ui_preferences", 0).edit().putBoolean("nickname_chosen", true).apply()
            }
            6 -> NativePicker.close()
            7 -> NativePicker.pick(value)
            8 -> NativePicker.search = value.replace("\n", "").take(64)
            9 -> NativePicker.showAdd = !NativePicker.showAdd
            10 -> { NativePicker.customAddress = value.replace("\n", "").take(64); NativePicker.addressError = false }
            11 -> NativePicker.saveCustom()
            12 -> NativePicker.removeSaved(value)
            13 -> NativePicker.showAll = !NativePicker.showAll
            14 -> NativePicker.showSaved = !NativePicker.showSaved
            20 -> if (DesktopTeam.canChatInArena) DesktopTeam.openComposer()
            21 -> DesktopTeam.draft = value.replace("\n", "").take(280)
            22 -> submitChat()
            23 -> DesktopTeam.closeComposer()
            24 -> DesktopTeam.switchComposerTab()
            30 -> if (nativeShown && wanted) {
                composeHidden = true
                DesktopWindow.frame?.isVisible = false
            }
            31 -> DesktopUpdater.quit()
            32 -> DesktopWindow.applyFullscreen(value == "1")
        }
    }

    private fun play() {
        val arena = HomeState.arenas.firstOrNull { it.endpoint.equals(HomeState.selected, ignoreCase = true) }
        val ready = arena != null || ArenaDirectory.isValidEndpoint(HomeState.selected)
        if (!ready || entering) return
        entering = true
        if (Engine.started) WyrmActivity.nativeSetNickname(HomeState.nickname)
        startPlay(HomeState.nickname, HomeState.selected)
    }

    /** ArenaComposer's submit: GLOBAL to Wyrm's global chat, TEAM to the team. */
    private fun submitChat() {
        if (DesktopTeam.composerTab == 1) {
            DesktopArenaFeed.send(DesktopTeam.draft) { ok ->
                if (ok) { DesktopTeam.draft = ""; DesktopTeam.closeComposer() }
            }
        } else if (DesktopTeam.send()) {
            DesktopTeam.closeComposer()
        }
    }

    // ---- pictures ----

    private fun scale(): Float =
        (DesktopWindow.frame?.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0).toFloat().coerceAtLeast(1f)

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(px: Int, density: Float, content: @androidx.compose.runtime.Composable () -> Unit): ByteArray? = runCatching {
        val scene = ImageComposeScene(px, px, Density(density)) { Box(Modifier.fillMaxSize()) { content() } }
        try {
            scene.render(0)
            val image = scene.render(16_000_000L)
            val bitmap = org.jetbrains.skia.Bitmap()
            bitmap.allocPixels(org.jetbrains.skia.ImageInfo(px, px, org.jetbrains.skia.ColorType.RGBA_8888, org.jetbrains.skia.ColorAlphaType.UNPREMUL))
            image.readPixels(bitmap, 0, 0)
            bitmap.readPixels()
        } finally {
            scene.close()
        }
    }.getOrNull()

    private fun rgba(image: BufferedImage): ByteArray {
        val w = image.width
        val h = image.height
        val px = image.getRGB(0, 0, w, h, null, 0, w)
        val out = ByteArray(w * h * 4)
        for (i in px.indices) {
            val argb = px[i]
            out[i * 4] = (argb shr 16).toByte()
            out[i * 4 + 1] = (argb shr 8).toByte()
            out[i * 4 + 2] = argb.toByte()
            out[i * 4 + 3] = (argb ushr 24).toByte()
        }
        return out
    }

    private fun image(name: String, px: Int, density: Float, content: @androidx.compose.runtime.Composable () -> Unit) {
        val bytes = render(px, density, content) ?: return
        runCatching { WyrmActivity.nativeDesktopUiImage(name, px, px, bytes) }
    }

    /** White on clear: the engine tints them with the theme's colours. */
    private fun sendImages() {
        imagesSent = true
        val s = scale()
        fun px(dp: Float) = kotlin.math.ceil(dp * s).toInt().coerceAtLeast(1)
        image("mark", px(520f), s) { WyrmMark(size = 520.dp, ink = Color.White, unfilled = Color.Transparent) }
        image("icon.house", px(17f), s) { Icon(painterResource(LucideR.drawable.lucide_ic_house), null, tint = Color.White, modifier = Modifier.fillMaxSize()) }
        image("icon.bot", px(17f), s) { Icon(painterResource(LucideR.drawable.lucide_ic_bot), null, tint = Color.White, modifier = Modifier.fillMaxSize()) }
        image("icon.plus", px(19f), s) { IosIcon(IosGlyph.PLUS, Color.White, size = 19.dp, semibold = true) }
        image("icon.search", px(16f), s) { IosIcon(IosGlyph.MAGNIFIER, Color.White, size = 16.dp, semibold = true) }
        DesktopToasts.icon()?.let { big ->
            val small = WindowIcons.sizes(big).firstOrNull { it.getWidth(null) == 64 } as? BufferedImage
            if (small != null) runCatching { WyrmActivity.nativeDesktopUiImage("app", 64, 64, rgba(small)) }
            val icon = WindowIcons.sizes(big).firstOrNull { it.getWidth(null) == 256 } as? BufferedImage
            if (icon != null) runCatching { WyrmActivity.nativeDesktopUiIcon(rgba(icon), 256, 256) }
        }
    }

    private fun sendCursor(id: String) {
        val style = DesktopCursors.styles.firstOrNull { it.id == id }
        if (style == null) {
            runCatching { WyrmActivity.nativeDesktopUiCursor(null, 0, 0, 0, 0) }
            return
        }
        val side = (32 * scale()).toInt().coerceAtLeast(16)
        val image = DesktopCursors.render(style, side)
        val hot = (4f * side / 32).toInt()
        runCatching { WyrmActivity.nativeDesktopUiCursor(rgba(image), side, side, hot, hot) }
    }

    private fun sendFlag(country: String) {
        val code = country.lowercase()
        if (code.length != 2 || code in flagsSent || code in flagsLoading) return
        flagsLoading += code
        scope.launch {
            val bitmap = FlagImages.load(code)
            flagsLoading -= code
            if (bitmap != null) {
                val awt = bitmap.toAwtImage()
                flagsSent += code
                runCatching { WyrmActivity.nativeDesktopUiImage("flag.$code", awt.width, awt.height, rgba(awt)) }
            }
        }
    }
}

/**
 * The arena picker's logic (ArenaPicker.kt, unchanged) for the game window:
 * LIVE DIRECTORY, a + for a custom address, search by code or IP, BEST FOR
 * YOU, RECENTLY JOINED, the directory by latency (ten, then See all), SAVED
 * ARENAS in a fold. Probes run only while it is open.
 */
internal object NativePicker {
    private val ui = java.util.concurrent.Executor { SwingUtilities.invokeLater(it) }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + ui)
    private var job: Job? = null

    var isOpen by mutableStateOf(false)
        private set
    private val pings = mutableStateMapOf<String, Int>()
    private val customPings = mutableStateMapOf<String, Int>()
    private var loading = false
    private var error: String? = null
    var search = ""
    var showAll = false
    var showSaved = false
    var showAdd = false
    var customAddress = ""
    var addressError = false
    private var saved: List<String> = emptyList()
    private var recent: List<String> = emptyList()

    private fun Arena.code(): String = "%04d".format(id % 10_000)
    private fun Arena.title(): String = if (id < 0) "Custom arena" else "Arena ${code()}"

    private fun customArena(raw: String): Arena? {
        val withPort = ArenaDirectory.withDefaultPort(raw.trim())
        if (!ArenaDirectory.isValidEndpoint(withPort)) return null
        val address = withPort.substringBeforeLast(':')
        val port = withPort.substringAfterLast(':').toIntOrNull() ?: return null
        return Arena(address = address, port = port, players = 0, id = -1, cluster = 0)
    }

    fun open() {
        if (isOpen) return
        isOpen = true
        search = ""
        showAll = false
        showSaved = false
        showAdd = false
        customAddress = ""
        addressError = false
        error = null
        loading = !HomeState.directoryLoaded
        saved = SavedArenas.load(DesktopContext)
        recent = RecentArenas.load(DesktopContext)
        ArenaDirectory.openPicker()
        job = scope.launch {
            runCatching { ArenaDirectory.load() }
                .onSuccess { list ->
                    HomeState.arenas = list
                    HomeState.directoryLoaded = true
                    loading = false
                    runCatching { ArenaDirectory.loadCountries(list.map { it.address }) }
                    measureCustom()
                    ArenaDirectory.pingAll(list) { endpoint, ms -> pings[endpoint] = ms }
                }
                .onFailure {
                    loading = false
                    error = "The arena list could not be reached."
                }
        }
    }

    private suspend fun measureCustom() {
        (saved + HomeState.selected).distinct().mapNotNull(::customArena)
            .filter { custom -> HomeState.arenas.none { it.endpoint == custom.endpoint } && custom.endpoint !in customPings }
            .forEach { custom -> customPings[custom.endpoint] = runCatching { ArenaDirectory.ping(custom) }.getOrDefault(-1) }
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        job?.cancel()
        job = null
        ArenaDirectory.closePicker()
    }

    fun pick(endpoint: String) {
        val arena = HomeState.arenas.firstOrNull { it.endpoint == endpoint } ?: customArena(endpoint) ?: return
        HomeState.selected = arena.endpoint
        NearOriginalStore.applyServer(arena.id.coerceAtLeast(0))
        close()
    }

    fun saveCustom() {
        val arena = customArena(customAddress)
        if (arena == null) {
            addressError = true
            return
        }
        addressError = false
        saved = SavedArenas.save(DesktopContext, arena.endpoint)
        showSaved = true
        showAdd = false
        customAddress = ""
        scope.launch { measureCustom() }
    }

    fun removeSaved(endpoint: String) {
        saved = SavedArenas.remove(DesktopContext, endpoint)
    }

    /** Countries on screen, for their flags. */
    fun countries(): List<String> = if (!isOpen) emptyList() else
        HomeState.arenas.map { ArenaDirectory.countryOf(it.address) }.filter { it.length == 2 }.distinct()

    private fun hex(color: Color): String = "%08X".format(color.toArgb())

    fun model(): String {
        if (!isOpen) return "open=0\n"
        val live = HomeState.arenas
        val term = search.trim()
        fun matches(arena: Arena) = term.isEmpty() || arena.endpoint.contains(term) || arena.title().contains(term, ignoreCase = true)
        fun latency(arena: Arena): Int? = if (arena.id < 0) customPings[arena.endpoint] else pings[arena.endpoint]
        val filtered = live.filter(::matches).sortedWith(
            compareBy<Arena> { a -> pings[a.endpoint]?.takeIf { it > 0 } ?: Int.MAX_VALUE }.thenBy { it.code() },
        )
        val recentRows = recent.mapNotNull { endpoint ->
            live.firstOrNull { it.endpoint == endpoint } ?: if (endpoint in saved) customArena(endpoint) else null
        }.filter(::matches).sortedWith(compareBy { a -> latency(a)?.takeIf { it > 0 } ?: Int.MAX_VALUE })
        val best = if (term.isEmpty()) live.filter { (pings[it.endpoint] ?: 0) > 0 }.minByOrNull { pings[it.endpoint] ?: Int.MAX_VALUE } else null
        val ranked = filtered.filter { it.endpoint !in recent }
        val positive = pings.values.filter { it > 0 }
        val low = positive.minOrNull()
        val high = positive.maxOrNull()
        fun latencyText(arena: Arena): String {
            val value = latency(arena) ?: return "—"
            return if (value > 0) "${value}ms" else "Unavailable"
        }
        fun latencyColour(arena: Arena): Color {
            val value = latency(arena) ?: return Wyrm.Quiet
            if (value <= 0) return Color(0.75f, 0.25f, 0.22f)
            val lo = low ?: value
            val hi = high ?: value
            val ratio = if (hi == lo) 0f else ((value - lo).toFloat() / (hi - lo)).coerceIn(0f, 1f)
            return Color(0.18f + 0.68f * ratio, 0.68f - 0.45f * ratio, 0.27f - 0.08f * ratio)
        }
        val out = StringBuilder()
        fun line(key: String, value: Any) { out.append(key).append('=').append(value.toString().replace('\n', ' ')).append('\n') }
        fun row(vararg fields: Any) = line("row", fields.joinToString("\t") { it.toString().replace('\t', ' ') })
        fun arenaRow(arena: Arena, tag: String = "", removable: Boolean = false) = row(
            "A", arena.title(), arena.endpoint, if (arena.id >= 0) ArenaDirectory.countryOf(arena.address) else "",
            latencyText(arena), hex(latencyColour(arena)), tag,
            if (HomeState.selected == arena.endpoint) 1 else 0, if (removable) 1 else 0,
        )
        line("open", 1)
        line("status", when {
            loading -> "Loading…"
            error != null -> error!!
            else -> "${live.size} live"
        })
        line("add", if (showAdd) 1 else 0)
        line("addressError", if (addressError) 1 else 0)
        line("search", search)
        best?.let { top ->
            row("L", "BEST FOR YOU")
            arenaRow(top, tag = "Lowest ping")
        }
        if (recentRows.isNotEmpty()) {
            row("L", "RECENTLY JOINED")
            recentRows.forEach { arenaRow(it) }
        }
        row("L", if (term.isEmpty()) "ARENAS" else "SEARCH RESULTS")
        val shown = if (showAll || term.isNotEmpty()) ranked else ranked.take(10)
        shown.forEach { arenaRow(it) }
        if (term.isEmpty() && ranked.size > 10) row("S", if (showAll) "Show less" else "See all")
        if (saved.isNotEmpty()) {
            row("V", saved.size, if (showSaved) 1 else 0)
            if (showSaved) saved.mapNotNull(::customArena).filter(::matches).forEach { arenaRow(it, removable = true) }
        }
        if (filtered.isEmpty() && recentRows.isEmpty() && saved.isEmpty()) {
            row("E", if (loading) "Loading the live directory…" else "No active arenas right now. Try again in a moment or add a custom IP.")
        }
        return out.toString()
    }
}
