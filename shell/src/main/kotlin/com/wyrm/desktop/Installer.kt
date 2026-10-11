package com.wyrm.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.wyrm.omrajput.BuildConfig
import com.wyrm.omrajput.ui.Wyrm
import com.wyrm.omrajput.ui.WyrmBrandMark
import com.wyrm.omrajput.ui.WyrmCapsule
import com.wyrm.omrajput.ui.pressScale
import com.wyrm.omrajput.ui.wyrmRounded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/*
 * Wyrm Setup (D8, OM 2026-10-12): installing, updating and removing Wyrm,
 * drawn by the app itself in Wyrm's theme. WyrmSetup.exe (native/setup_stub.c)
 * unzips the app image to a temp folder and starts `Wyrm.exe --install` (or
 * `--update`) from there; Windows' "Apps" list starts `Wyrm.exe --uninstall`.
 *
 * Install = copy the app image to %LOCALAPPDATA%\Programs\Wyrm (for this user,
 * no admin), Start menu and (if wanted) desktop shortcuts with Wyrm's taskbar
 * id, an uninstall entry under HKCU, then Open Wyrm. The W fills as the files
 * copy. Update waits for the old Wyrm to close, swaps the folder and opens the
 * new one. Uninstall removes the shortcuts and the entry (and, if asked, the
 * player's data on this computer), then the folder once Wyrm has closed.
 */

internal enum class SetupMode { INSTALL, UPDATE, UNINSTALL }

internal object Setup {
    val installDir: File = File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "Programs/Wyrm")
    private const val UNINSTALL_KEY = """HKCU\Software\Microsoft\Windows\CurrentVersion\Uninstall\Wyrm"""

    /** The app image this Wyrm.exe runs from (jpackage tells us its own path). */
    fun runningImage(): File? = System.getProperty("jpackage.app-path")?.let { File(it).parentFile }

    fun installedExe(): File = File(installDir, "Wyrm.exe")

    private fun startMenuShortcut(): File =
        File(System.getenv("APPDATA") ?: "", "Microsoft/Windows/Start Menu/Programs/Wyrm.lnk")

    /** The real Desktop folder (OneDrive moves it), from the shell's own record. */
    private fun desktopShortcut(): File {
        val folder = runCatching {
            val out = ProcessBuilder("reg", "query", """HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\Shell Folders""", "/v", "Desktop")
                .redirectErrorStream(true).start().inputStream.bufferedReader().readText()
            Regex("""Desktop\s+REG_\w+\s+(.+)""").find(out)?.groupValues?.get(1)?.trim()
        }.getOrNull()
        return File(folder ?: File(System.getProperty("user.home"), "Desktop").path, "Wyrm.lnk")
    }

    private fun sizeOf(dir: File): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Another Wyrm still running from the install folder (the one an update replaces). */
    private fun installedRunning(): Boolean {
        val exe = installedExe().absolutePath
        val me = ProcessHandle.current().pid()
        return ProcessHandle.allProcesses().anyMatch { p ->
            p.pid() != me && p.info().command().map { it.equals(exe, ignoreCase = true) }.orElse(false)
        }
    }

    private fun reg(vararg args: String) {
        runCatching { ProcessBuilder(listOf("reg") + args).redirectErrorStream(true).start().waitFor() }
    }

    /**
     * Copies the app image into place, reporting 0..1 and a step for the screen.
     * Throws with a sentence the player can read.
     */
    fun install(desktop: Boolean, waitForOld: Boolean, progress: (Float, String) -> Unit) {
        val source = runningImage() ?: throw IllegalStateException("Open WyrmSetup.exe to install Wyrm.")
        if (source.canonicalPath.equals(installDir.canonicalPath, ignoreCase = true)) {
            throw IllegalStateException("Wyrm is already installed here.")
        }
        progress(0f, "Getting ready")
        if (waitForOld) {
            val until = System.currentTimeMillis() + 60_000L
            while (installedRunning() && System.currentTimeMillis() < until) {
                progress(0f, "Waiting for Wyrm to close")
                Thread.sleep(400)
            }
            if (installedRunning()) throw IllegalStateException("Wyrm is still open. Close it and try again.")
        }
        val total = sizeOf(source).coerceAtLeast(1L)
        val staging = File(installDir.parentFile, "Wyrm.new")
        staging.deleteRecursively()
        staging.mkdirs()
        var copied = 0L
        source.walkTopDown().forEach { file ->
            val target = File(staging, file.relativeTo(source).path)
            if (file.isDirectory) {
                target.mkdirs()
            } else {
                target.parentFile.mkdirs()
                file.inputStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(1 shl 16)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            copied += n
                            progress((copied.toFloat() / total) * 0.9f, "Copying Wyrm")
                        }
                    }
                }
            }
        }
        progress(0.92f, "Putting Wyrm in place")
        val old = File(installDir.parentFile, "Wyrm.old-${System.currentTimeMillis()}")
        if (installDir.exists() && !installDir.renameTo(old)) {
            staging.deleteRecursively()
            throw IllegalStateException("Wyrm's folder is in use. Close Wyrm and try again.")
        }
        if (!staging.renameTo(installDir)) {
            old.renameTo(installDir)
            throw IllegalStateException("Wyrm could not be put in place. Try again.")
        }
        old.deleteRecursively()
        progress(0.95f, "Adding shortcuts")
        val exe = installedExe()
        WinApi.load()
        startMenuShortcut().parentFile.mkdirs()
        WinApi.shortcut(startMenuShortcut(), exe, "", installDir, exe, "Wyrm")
        if (desktop) WinApi.shortcut(desktopShortcut(), exe, "", installDir, exe, "Wyrm")
        progress(0.98f, "Registering Wyrm")
        reg("add", UNINSTALL_KEY, "/v", "DisplayName", "/t", "REG_SZ", "/d", "Wyrm", "/f")
        reg("add", UNINSTALL_KEY, "/v", "DisplayVersion", "/t", "REG_SZ", "/d", BuildConfig.VERSION_NAME, "/f")
        reg("add", UNINSTALL_KEY, "/v", "Publisher", "/t", "REG_SZ", "/d", "OM Rajput", "/f")
        reg("add", UNINSTALL_KEY, "/v", "DisplayIcon", "/t", "REG_SZ", "/d", exe.absolutePath, "/f")
        reg("add", UNINSTALL_KEY, "/v", "InstallLocation", "/t", "REG_SZ", "/d", installDir.absolutePath, "/f")
        reg("add", UNINSTALL_KEY, "/v", "UninstallString", "/t", "REG_SZ", "/d", "\"${exe.absolutePath}\" --uninstall", "/f")
        reg("add", UNINSTALL_KEY, "/v", "EstimatedSize", "/t", "REG_DWORD", "/d", (total / 1024).toString(), "/f")
        reg("add", UNINSTALL_KEY, "/v", "NoModify", "/t", "REG_DWORD", "/d", "1", "/f")
        reg("add", UNINSTALL_KEY, "/v", "NoRepair", "/t", "REG_DWORD", "/d", "1", "/f")
        progress(1f, "Done")
    }

    fun openInstalled() {
        runCatching { ProcessBuilder(installedExe().absolutePath).directory(installDir).start() }
    }

    /** Uninstall: everything but the folder now; the folder once this Wyrm has closed. */
    fun uninstall(removeData: Boolean, progress: (Float, String) -> Unit) {
        progress(0.2f, "Removing shortcuts")
        startMenuShortcut().delete()
        desktopShortcut().delete()
        progress(0.45f, "Removing Wyrm from Apps")
        reg("delete", UNINSTALL_KEY, "/f")
        if (removeData) {
            progress(0.7f, "Removing your data on this computer")
            File(System.getenv("APPDATA") ?: "", "Wyrm").deleteRecursively()
            File(System.getenv("LOCALAPPDATA") ?: "", "Wyrm").deleteRecursively()
        }
        progress(1f, "Done")
    }

    /** After this process exits, a quiet cmd removes the install folder. */
    fun removeFolderAfterExit() {
        val dir = installDir.absolutePath
        runCatching {
            ProcessBuilder("cmd.exe", "/c", "ping -n 4 127.0.0.1 >nul & rmdir /s /q \"$dir\"").start()
        }
    }

    /** At a normal launch: leftovers of earlier setups (temp copies, old folders). */
    fun cleanLeftovers() {
        Thread({
            runCatching {
                File(System.getProperty("java.io.tmpdir")).listFiles { f -> f.isDirectory && f.name.startsWith("WyrmSetup-") }
                    ?.filter { System.currentTimeMillis() - it.lastModified() > 10 * 60_000L }
                    ?.forEach { it.deleteRecursively() }
                installDir.parentFile?.listFiles { f -> f.isDirectory && f.name.startsWith("Wyrm.old-") }?.forEach { it.deleteRecursively() }
            }
        }, "wyrm-setup-cleanup").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }
}

/* ------------------------------------------------------------------- UI */

internal enum class SetupStage { WELCOME, WORKING, DONE, FAILED }

internal fun runSetup(mode: SetupMode) = application {
    val state = rememberWindowState(size = DpSize(760.dp, 480.dp), position = WindowPosition(Alignment.Center))
    val icon = remember { DesktopToasts.icon()?.toComposeImageBitmap()?.let { BitmapPainter(it) } }
    Window(
        onCloseRequest = ::exitApplication,
        state = state,
        title = when (mode) { SetupMode.UNINSTALL -> "Remove Wyrm"; else -> "Wyrm Setup" },
        icon = icon,
        undecorated = true,
        resizable = false,
    ) {
        LaunchedEffect(window) {
            WinApi.roundCorners(window.windowHandle)
            DesktopToasts.icon()?.let { window.iconImages = WindowIcons.sizes(it) }
            val paper = java.awt.Color(Wyrm.Paper.toArgb(), true)
            window.background = paper
        }
        Box(Modifier.fillMaxSize().background(Wyrm.Paper)) {
            SetupScreen(mode, onClose = ::exitApplication)
            TitleStrip(window, compact = true, onClose = ::exitApplication)
        }
    }
}

@Composable
private fun SetupScreen(mode: SetupMode, onClose: () -> Unit, preview: Pair<SetupStage, Float>? = null) {
    var stage by remember { mutableStateOf(preview?.first ?: if (mode == SetupMode.UPDATE) SetupStage.WORKING else SetupStage.WELCOME) }
    var progress by remember { mutableStateOf(preview?.second ?: 0f) }
    var step by remember { mutableStateOf(if (preview != null) "Copying Wyrm" else "") }
    var error by remember { mutableStateOf("") }
    var desktopShortcut by remember { mutableStateOf(true) }
    var removeData by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun start() {
        stage = SetupStage.WORKING
        error = ""
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    when (mode) {
                        SetupMode.UNINSTALL -> Setup.uninstall(removeData) { p, s -> progress = p; step = s }
                        else -> Setup.install(desktopShortcut, waitForOld = true) { p, s -> progress = p; step = s }
                    }
                }
            }
            result.onSuccess {
                delay(350)
                stage = SetupStage.DONE
                if (mode == SetupMode.UPDATE) {
                    delay(1100)
                    Setup.openInstalled()
                    onClose()
                }
            }.onFailure {
                error = it.message ?: "Something went wrong. Try again."
                stage = SetupStage.FAILED
            }
        }
    }
    LaunchedEffect(Unit) { if (mode == SetupMode.UPDATE && preview == null) start() }

    AnimatedContent(
        targetState = stage,
        transitionSpec = {
            (fadeIn(tween(320)) + scaleIn(tween(360), initialScale = 0.97f) + slideInVertically(tween(360)) { it / 30 }) togetherWith fadeOut(tween(180))
        },
        label = "setup-stage",
    ) { shown ->
        Column(
            Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 44.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            when (shown) {
                SetupStage.WELCOME -> if (mode == SetupMode.UNINSTALL) {
                    MarkIn(dim = true)
                    Spacer(Modifier.height(20.dp))
                    Title("Remove Wyrm?")
                    Line("Your account and everything in it stay safe on Wyrm. This only takes Wyrm off this computer.")
                    Spacer(Modifier.height(18.dp))
                    Choice("Also remove my settings and logs on this computer", removeData) { removeData = it }
                    Spacer(Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SetupButton("Keep Wyrm", primary = false, onClick = onClose)
                        SetupButton("Remove", primary = true, danger = true, onClick = ::start)
                    }
                } else {
                    MarkIn(dim = false)
                    Spacer(Modifier.height(20.dp))
                    Title("Wyrm")
                    Line("The arena and people in it. Version ${BuildConfig.VERSION_NAME}.")
                    Spacer(Modifier.height(18.dp))
                    Choice("Put Wyrm on my desktop", desktopShortcut) { desktopShortcut = it }
                    Spacer(Modifier.height(24.dp))
                    SetupButton("Install Wyrm", primary = true, onClick = ::start)
                    Spacer(Modifier.height(12.dp))
                    Text("Installs for you only, no admin needed · ${Setup.installDir.path}", fontFamily = Wyrm.Body, fontWeight = FontWeight.Medium, fontSize = 11.5.sp,
                        color = Wyrm.Quiet, textAlign = TextAlign.Center)
                }

                SetupStage.WORKING -> {
                    val shown by animateFloatAsState(progress, tween(260, easing = FastOutSlowInEasing), label = "fill")
                    // The W stays whole (OM): only the beads below show the progress.
                    MarkIn(dim = false)
                    Spacer(Modifier.height(22.dp))
                    Title(when (mode) {
                        SetupMode.INSTALL -> "Installing Wyrm"
                        SetupMode.UPDATE -> "Updating Wyrm"
                        SetupMode.UNINSTALL -> "Removing Wyrm"
                    })
                    Spacer(Modifier.height(16.dp))
                    BeadProgress(shown, Modifier.widthIn(max = 420.dp).fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
                        Text(step, fontFamily = Wyrm.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, color = Wyrm.Mute,
                            modifier = Modifier.weight(1f))
                        Text("${(shown * 100).toInt()}%", fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 12.5.sp, color = Wyrm.Ink)
                    }
                }

                SetupStage.DONE -> {
                    DoneTick()
                    Spacer(Modifier.height(20.dp))
                    when (mode) {
                        SetupMode.UNINSTALL -> {
                            Title("Wyrm is removed")
                            Line("Thanks for playing. Your account is waiting whenever you come back.")
                            Spacer(Modifier.height(24.dp))
                            SetupButton("Close", primary = true) { Setup.removeFolderAfterExit(); onClose() }
                        }
                        else -> {
                            Title(if (mode == SetupMode.UPDATE) "Wyrm is up to date" else "Wyrm is ready")
                            Line(if (mode == SetupMode.UPDATE) "Opening Wyrm…" else "Find it in the Start menu any time.")
                            if (mode == SetupMode.INSTALL) {
                                Spacer(Modifier.height(24.dp))
                                SetupButton("Open Wyrm", primary = true) { Setup.openInstalled(); onClose() }
                            }
                        }
                    }
                }

                SetupStage.FAILED -> {
                    MarkIn(dim = true)
                    Spacer(Modifier.height(20.dp))
                    Title("That didn't work")
                    Line(error)
                    Spacer(Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SetupButton("Close", primary = false, onClick = onClose)
                        SetupButton("Try again", primary = true, onClick = ::start)
                    }
                }
            }
        }
    }
}

/** The W arriving the way it does after sign-in: out of a blur, growing a little. */
@Composable
private fun MarkIn(dim: Boolean) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
    WyrmBrandMark(
        size = 96.dp,
        layer = {
            val p = appear.value
            val s = 0.86f + 0.14f * p
            scaleX = s; scaleY = s; alpha = p * if (dim) 0.45f else 1f
            val r = 14.dp.toPx() * (1f - p)
            renderEffect = if (r > 0.5f) androidx.compose.ui.graphics.BlurEffect(r, r, androidx.compose.ui.graphics.TileMode.Decal) else null
        },
    )
}

@Composable
private fun Title(text: String) {
    // One face everywhere in setup (OM): Manrope, only the weight changes.
    Text(text, fontFamily = Wyrm.Body, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, letterSpacing = (-0.4).sp,
        color = Wyrm.Ink, textAlign = TextAlign.Center)
}

@Composable
private fun Line(text: String) {
    Text(text, fontFamily = Wyrm.Body, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = Wyrm.Mute, textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(max = 460.dp).padding(top = 6.dp))
}

/** A row of beads that light up as the work goes (slither's snake, filling). */
@Composable
internal fun BeadProgress(value: Float, modifier: Modifier) {
    val live = Wyrm.Live
    val track = Wyrm.Track
    Canvas(modifier.height(16.dp)) {
        val count = 24
        val gap = size.width / count
        val r = (gap * 0.42f).coerceAtMost(size.height / 2f)
        val lit = value * count
        for (i in 0 until count) {
            val c = Offset(gap * i + gap / 2f, size.height / 2f)
            val amount = (lit - i).coerceIn(0f, 1f)
            drawCircle(track, r, c)
            if (amount > 0f) {
                drawCircle(
                    Brush.radialGradient(listOf(Color.White.copy(alpha = 0.6f), live, live.copy(alpha = 0.9f)), center = Offset(c.x - r * 0.3f, c.y - r * 0.35f), radius = r * 1.4f),
                    r * (0.55f + 0.45f * amount), c, alpha = amount,
                )
            }
        }
    }
}

/** A tick drawn in a ring. */
@Composable
private fun DoneTick() {
    val draw = remember { Animatable(0f) }
    LaunchedEffect(Unit) { draw.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) }
    val live = Wyrm.Live
    Canvas(Modifier.size(96.dp)) {
        val p = draw.value
        val stroke = 7.dp.toPx()
        drawArc(live.copy(alpha = 0.18f), 0f, 360f, false, style = Stroke(stroke))
        drawArc(live, -90f, 360f * p, false, style = Stroke(stroke, cap = StrokeCap.Round))
        val a = Offset(size.width * 0.30f, size.height * 0.52f)
        val b = Offset(size.width * 0.45f, size.height * 0.66f)
        val c = Offset(size.width * 0.72f, size.height * 0.38f)
        val t = ((p - 0.35f) / 0.65f).coerceIn(0f, 1f)
        if (t > 0f) {
            val first = (t * 2f).coerceAtMost(1f)
            drawLine(live, a, Offset(a.x + (b.x - a.x) * first, a.y + (b.y - a.y) * first), stroke, StrokeCap.Round)
            if (t > 0.5f) {
                val second = (t - 0.5f) * 2f
                drawLine(live, b, Offset(b.x + (c.x - b.x) * second, b.y + (c.y - b.y) * second), stroke, StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun Choice(label: String, on: Boolean, onToggle: (Boolean) -> Unit) {
    val fill by animateColorAsState(if (on) Wyrm.Ink else Wyrm.Card, tween(160), label = "check")
    Row(
        Modifier.clip(wyrmRounded(12.dp)).clickable { onToggle(!on) }.padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(20.dp).clip(wyrmRounded(6.dp)).background(fill).border(1.dp, if (on) Wyrm.Ink else Wyrm.Rule, wyrmRounded(6.dp)),
            contentAlignment = Alignment.Center) {
            if (on) Text("✓", color = Wyrm.OnInk, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Text(label, fontFamily = Wyrm.Body, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = Wyrm.Ink)
    }
}

@Composable
private fun SetupButton(label: String, primary: Boolean, danger: Boolean = false, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val fill = when {
        danger -> Wyrm.Badge
        primary -> Wyrm.Ink
        else -> Wyrm.Card
    }
    Box(
        Modifier
            .width(200.dp)
            .height(48.dp)
            .scale(pressScale(pressed))
            .graphicsLayer { alpha = if (hovered) 0.9f else 1f }
            .clip(WyrmCapsule)
            .background(fill)
            .border(1.dp, if (primary || danger) Color.Transparent else Wyrm.Rule, WyrmCapsule)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 15.sp,
            color = if (primary || danger) Wyrm.OnInk else Wyrm.Ink)
    }
}

/** Offscreen renders (pageShot SETUP_*): a setup screen at a stage, no work done. */
@Composable
internal fun SetupPreview(mode: SetupMode, stage: SetupStage, progress: Float, frame: java.awt.Frame?) {
    Box(Modifier.fillMaxSize().background(Wyrm.Paper)) {
        SetupScreen(mode, onClose = {}, preview = stage to progress)
        if (frame != null) TitleStrip(frame, compact = true, onClose = {})
    }
}
