package com.wyrm.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.Alignment
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding

fun main(args: Array<String>) {
    // The engine's files and Wyrm's dlls live in the app's own resources (D8):
    // the same for `gradlew run` and an installed Wyrm.
    PackagedPaths.apply()
    // Wyrm's own logs on disk, then the crash and drop watches (D6): before
    // anything else, so a crash anywhere is caught and the last launch's is found.
    DesktopLog.install()
    // Wyrm's own taskbar identity (icon, group) before any window exists.
    WinApi.load()
    WinApi.claimTaskbarIdentity()
    com.wyrm.omrajput.data.CrashWatch.install(DesktopContext)
    com.wyrm.omrajput.data.DropWatch.install(DesktopContext)
    com.wyrm.omrajput.data.DropWatch.watchNetwork(DesktopContext, true)
    // Wyrm Setup (D8): WyrmSetup.exe starts `--install` / `--update`; Windows' Apps list `--uninstall`.
    val setup = when {
        "--install" in args -> SetupMode.INSTALL
        "--update" in args -> SetupMode.UPDATE
        "--uninstall" in args -> SetupMode.UNINSTALL
        else -> null
    }
    if (setup != null) {
        EngineSettings.loadTheme()
        runSetup(setup)
        return
    }
    Setup.cleanLeftovers()
    // The saved theme and frame policy, before the first frame (as Android's init).
    EngineSettings.loadTheme()
    EngineSettings.loadPerformance()
    DesktopWindow.load()
    // The Skin page's atlases, decoded while the sign-in or Home is up, so the
    // first visit to Skin opens at once instead of waiting on them.
    Thread({ kotlinx.coroutines.runBlocking { com.wyrm.omrajput.ui.SkinTextures.load() } }, "wyrm-skin-textures")
        .apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    // The default skins' pictures ship inside the app; load them at start.
    Thread({ PresetPreviews.loadBundled() }, "wyrm-presets").apply { isDaemon = true }.start()
    // Updates (D8): checked 8 s after launch, then every 6 hours, and from Settings › Version.
    DesktopUpdater.start()
    runApp()
}

/**
 * Full screen (F11, Alt+Enter, Settings › Performance), kept on this computer.
 * Never while signing in: that window is the small fixed card.
 */
internal object DesktopWindow {
    private val prefs by lazy { DesktopContext.getSharedPreferences("wyrm_desktop_window", android.content.Context.MODE_PRIVATE) }
    var fullscreen by androidx.compose.runtime.mutableStateOf(false)
        private set

    fun load() {
        fullscreen = prefs.getBoolean("fullscreen", false)
    }

    fun applyFullscreen(on: Boolean) {
        if (AppGate.signingIn || on == fullscreen) return
        fullscreen = on
        prefs.edit().putBoolean("fullscreen", on).apply()
    }

    fun toggle() = applyFullscreen(!fullscreen)

    /** The app's window, once it exists (a clicked toast brings it up). */
    var frame: java.awt.Frame? = null

    fun bringToFront() {
        val window = frame ?: return
        if (window.extendedState and java.awt.Frame.ICONIFIED != 0) window.extendedState = window.extendedState and java.awt.Frame.ICONIFIED.inv()
        window.isVisible = true
        window.toFront()
        window.requestFocus()
    }
}

/** The sign-in window, in dp: a wide card. */
private const val SIGN_IN_W = 860
private const val SIGN_IN_H = 540
private const val APP_W = 1280
private const val APP_H = 800

/**
 * Between the sign-in card (0) and the app (1), around the window's centre so
 * it grows (and shrinks) on all four sides. [centreOnScreen] places it in the
 * middle of the screen instead (the first time it shows).
 */
private fun applyWindowSize(
    window: java.awt.Window,
    state: androidx.compose.ui.window.WindowState,
    t: Float,
    centreOnScreen: Boolean = false,
) {
    val w = (SIGN_IN_W + (APP_W - SIGN_IN_W) * t).toDouble()
    val h = (SIGN_IN_H + (APP_H - SIGN_IN_H) * t).toDouble()
    // AWT's window coordinates are already in logical units (= dp) on Windows.
    val bounds = window.graphicsConfiguration?.bounds
        ?: java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    val work = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    val centreX = if (centreOnScreen) work.x + work.width / 2.0 else window.x + window.width / 2.0
    val centreY = if (centreOnScreen) work.y + work.height / 2.0 else window.y + window.height / 2.0
    state.size = DpSize(w.dp, h.dp)
    // Keep it on the screen it is on.
    val x = (centreX - w / 2).coerceIn(bounds.x.toDouble(), (bounds.x + bounds.width - w).coerceAtLeast(bounds.x.toDouble()))
    val y = (centreY - h / 2).coerceIn(bounds.y.toDouble(), (bounds.y + bounds.height - h).coerceAtLeast(bounds.y.toDouble()))
    state.position = androidx.compose.ui.window.WindowPosition(x.dp, y.dp)
}

private fun runApp() = application {
    // Wyrm always opens as the small card in the middle of the screen. Sign-in
    // and sign-up stay that size; as soon as the account is in (the "Syncing
    // your Wyrm…" W, or the sign-in's own finish) it grows on all four sides to
    // the app's size; log out shrinks it back the same way.
    val state = rememberWindowState(
        size = DpSize(SIGN_IN_W.dp, SIGN_IN_H.dp),
        position = WindowPosition(Alignment.Center),
    )
    val signingIn = AppGate.signingIn
    var open by remember { mutableStateOf(true) }
    val closeScope = androidx.compose.runtime.rememberCoroutineScope()
    // Closing: the window goes at once; the account's copy of the settings is
    // saved (at most 4 s), then the engine stops and Wyrm exits (D6).
    fun close() {
        if (!open) return
        open = false
        closeScope.launch {
            kotlinx.coroutines.withTimeoutOrNull(4_000L) { DesktopAccountSync.save(force = true) }
            DesktopLive.stop()
            DesktopToasts.remove()
            Engine.quit()
            exitApplication()
        }
    }
    // Update now: the new setup runs, then this Wyrm closes the same way.
    DesktopUpdater.quit = ::close
    Window(
        onCloseRequest = ::close,
        visible = open,
        // Windows' own title bar is gone: Wyrm draws its strip (WindowChrome.kt).
        undecorated = true,
        state = state,
        title = "Wyrm",
        icon = remember { DesktopToasts.icon()?.let { androidx.compose.ui.graphics.painter.BitmapPainter(it.toComposeImageBitmap()) } },
        resizable = !signingIn,
        onPreviewKeyEvent = { event ->
            val isToggle = event.key == Key.F11 || (event.isAltPressed && event.key == Key.Enter)
            if (isToggle && event.type == KeyEventType.KeyDown) DesktopWindow.toggle()
            // In a match every other key goes to the game here, before focus.
            isToggle || GameKeys.handle(event)
        },
    ) {
        // Resizing: the window's own fill shows wherever Compose has not drawn the
        // new size yet. Paper there, not white or black (OM: flicker on resize).
        // Rounded corners (Windows 11) and Wyrm's icon at every size the taskbar asks for.
        LaunchedEffect(window) {
            WinApi.roundCorners(window.windowHandle)
            DesktopToasts.icon()?.let { big -> window.iconImages = WindowIcons.sizes(big) }
        }
        // The window, for toasts; focus for presence, toasts and the account's save.
        androidx.compose.runtime.DisposableEffect(window) {
            DesktopWindow.frame = window
            val listener = object : java.awt.event.WindowAdapter() {
                override fun windowGainedFocus(e: java.awt.event.WindowEvent) = DesktopLive.onWindowFocus(true)
                override fun windowLostFocus(e: java.awt.event.WindowEvent) {
                    DesktopLive.onWindowFocus(false)
                    // Android saves when the app goes to the background; here, when Wyrm loses focus.
                    closeScope.launch { DesktopAccountSync.save() }
                }
            }
            window.addWindowFocusListener(listener)
            onDispose { window.removeWindowFocusListener(listener) }
        }
        LaunchedEffect(Unit) {
            val paper = java.awt.Color(com.wyrm.omrajput.ui.Wyrm.Paper.toArgb(), true)
            window.background = paper
            window.contentPane.background = paper
            window.rootPane.background = paper
            window.layeredPane.background = paper
        }
        val grow = remember { Animatable(0f) }
        val placed = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
        var grown by remember { mutableStateOf(false) }
        LaunchedEffect(signingIn) {
            if (placed.compareAndSet(false, true)) {
                // First show: the card, in the middle of the screen.
                applyWindowSize(window, state, grow.value, centreOnScreen = true)
                kotlinx.coroutines.delay(260)
            }
            window.minimumSize = if (signingIn) java.awt.Dimension(SIGN_IN_W, SIGN_IN_H) else java.awt.Dimension(1000, 640)
            val target = if (signingIn) 0f else 1f
            // Out of full screen before the window shrinks back to the card.
            if (signingIn) state.placement = WindowPlacement.Floating
            if (grow.value != target) {
                grow.animateTo(target, tween(520, easing = FastOutSlowInEasing)) {
                    applyWindowSize(window, state, value)
                }
            }
            grown = !signingIn
        }
        // Full screen once the app has its size (a saved choice applies after sign-in).
        LaunchedEffect(DesktopWindow.fullscreen, grown) {
            if (!grown) return@LaunchedEffect
            state.placement = if (DesktopWindow.fullscreen) WindowPlacement.Fullscreen else WindowPlacement.Floating
        }
        LaunchedEffect(Unit) { Engine.start(1280, 800) }
        // The game has the whole window in a match, in the arena editors and in full screen.
        val gameOwnsWindow = Engine.screen == Engine.SCREEN_PLAYING || Engine.hudEditor != null ||
            Engine.backgroundEditor != null || DesktopWindow.fullscreen
        val strip = !gameOwnsWindow
        // The sign-in card keeps its size: the strip floats over its empty top.
        val stripSpace by androidx.compose.animation.core.animateDpAsState(
            if (strip && !signingIn) TitleStripHeight else 0.dp, tween(220), label = "strip-space",
        )
        LaunchedEffect(signingIn) { if (signingIn) WindowChrome.forget() }
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().padding(top = stripSpace)) { WyrmApp() }
            // A new version's card, over the app (never over the sign-in or a match).
            if (!signingIn && !gameOwnsWindow) UpdateCardHost(exit = ::close)
            TitleStripHost(visible = strip, frame = window, compact = signingIn, onClose = ::close)
            if (!signingIn && !DesktopWindow.fullscreen) ResizeEdges(window, minWidth = 1000, minHeight = 640)
        }
    }
}

/** Wyrm's icon drawn at each size Windows uses (title, taskbar, Alt+Tab), smooth rather than scaled on the fly. */
internal object WindowIcons {
    fun sizes(source: java.awt.image.BufferedImage): List<java.awt.Image> = listOf(16, 20, 24, 32, 40, 48, 64, 128, 256).map { size ->
        java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_ARGB).also { out ->
            val g = out.createGraphics()
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY)
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
            g.drawImage(source.getScaledInstance(size, size, java.awt.Image.SCALE_SMOOTH), 0, 0, null)
            g.dispose()
        }
    }
}

/** Where the engine's files are: the app's resources (prepareWyrmPayload), else what the JVM was given. */
internal object PackagedPaths {
    fun apply() {
        val res = System.getProperty("compose.application.resources.dir")?.let { java.io.File(it) } ?: return
        val root = java.io.File(res, "wyrm")
        if (java.io.File(root, "app/res").isDirectory) System.setProperty("wyrm.android.root", root.absolutePath)
        val bin = java.io.File(res, "bin")
        if (java.io.File(bin, "wyrm.dll").isFile) System.setProperty("wyrm.native.bin", bin.absolutePath)
    }
}
