package com.wyrm.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import com.wyrm.omrajput.WyrmActivity
import java.awt.event.KeyEvent as AwtKeyEvent

/**
 * A check of the keyboard path with no window and no real input (OM's PC is
 * in use; nothing may touch its cursor or focus). It starts the engine on its
 * hidden SDL window, enters the AI arena, and presses keys the way the window
 * does (GameKeys.handle with real AWT key events), then quits. The engine's
 * log ("Wyrm keys: …") shows what arrived and what it did.
 *
 *   gradlew keyProbe   (runs with its own APPDATA, never the player's files)
 */
fun main() {
    Engine.start(1280, 800)
    waitUntil("engine settings", 60_000) { runCatching { WyrmActivity.nativeSettingsVersion() }.getOrNull().orEmpty().isNotBlank() }
    Thread.sleep(3_000)
    // The engine's whole settings table, for offscreen renders of the real
    // Settings pages (pageShot reads build/probe-settings.txt).
    java.io.File(System.getProperty("wyrm.probe.out", "build"), "probe-settings.txt").writeText(WyrmActivity.nativeSettingsSnapshot())
    java.io.File(System.getProperty("wyrm.probe.out", "build"), "probe-hotkeys.txt").writeText(WyrmActivity.nativeHotkeysSnapshot() + "\u0000" + WyrmActivity.nativeKeyOptions())
    println("Wyrm probe: settings table saved")
    println("Wyrm probe: entering the AI arena")
    WyrmActivity.nativeEnterAiMode("probe")
    waitUntil("the AI arena", 30_000) { Engine.screen == Engine.SCREEN_PLAYING }
    AppGate.state.value = Gate.IN
    Thread.sleep(2_000)
    println("Wyrm probe: GameKeys.active=${GameKeys.active}")

    val source = java.awt.Canvas()
    fun press(code: Int, holdMs: Long = 120) {
        val now = System.currentTimeMillis()
        val down = AwtKeyEvent(source, AwtKeyEvent.KEY_PRESSED, now, 0, code, AwtKeyEvent.CHAR_UNDEFINED)
        val up = AwtKeyEvent(source, AwtKeyEvent.KEY_RELEASED, now + holdMs, 0, code, AwtKeyEvent.CHAR_UNDEFINED)
        val tookDown = GameKeys.handle(composeKey(down, KeyEventType.KeyDown))
        Thread.sleep(holdMs)
        val tookUp = GameKeys.handle(composeKey(up, KeyEventType.KeyUp))
        println("Wyrm probe: ${AwtKeyEvent.getKeyText(code)} handled down=$tookDown up=$tookUp")
        Thread.sleep(400)
    }

    press(AwtKeyEvent.VK_ENTER)                 // closes the AI arena's first card
    press(AwtKeyEvent.VK_H)                     // HUD toggle
    press(AwtKeyEvent.VK_H)
    press(AwtKeyEvent.VK_T)                     // bot toggle
    press(AwtKeyEvent.VK_T)
    press(AwtKeyEvent.VK_K, holdMs = 900)       // assist, held
    press(AwtKeyEvent.VK_SPACE, holdMs = 900)   // boost key
    press(AwtKeyEvent.VK_W, holdMs = 900)       // W boosts while unbound
    press(AwtKeyEvent.VK_A, holdMs = 600)       // turn left
    press(AwtKeyEvent.VK_N)                     // zoom in
    press(AwtKeyEvent.VK_P)                     // names toggle
    press(AwtKeyEvent.VK_P)

    // The mouse: the left button boosts (an SDL button event, as the surface sends).
    WyrmActivity.nativeDesktopPointer(0, 900f, 400f, 0, 0f)
    WyrmActivity.nativeDesktopPointer(1, 900f, 400f, 1, 0f)
    Thread.sleep(700)
    WyrmActivity.nativeDesktopPointer(2, 900f, 400f, 1, 0f)
    Thread.sleep(500)
    println("Wyrm probe: left click sent")

    // D2: the arena UI editor draws the roster and the chat window with
    // placeholder rows; the engine reports where (the editor's hit boxes).
    WyrmActivity.nativeEnterAiLayoutEditor("probe")
    Thread.sleep(3_000)
    val rects = WyrmActivity.nativeDesktopTeamRects()
    println("Wyrm probe: editor rects roster=${rects?.take(4)} chat=${rects?.drop(4)}")
    WyrmActivity.nativeExitAiLayoutEditor()
    Thread.sleep(800)

    // The team vault key: sealed by Windows DPAPI, read back, AES-GCM round trip.
    runCatching {
        val first = com.wyrm.omrajput.data.TeamVaultKey.key(DesktopContext, "probe_vault")
        val file = java.io.File(System.getenv("APPDATA"), "Wyrm/secure/probe_vault.key")
        val reopened = WyrmActivity.nativeDesktopUnprotect(file.readBytes())
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, first)
        val sealed = cipher.doFinal("team-id-and-key".toByteArray())
        val back = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(reopened, "AES"), javax.crypto.spec.GCMParameterSpec(128, cipher.iv))
        }.doFinal(sealed)
        println("Wyrm probe: vault key file ${file.length()} bytes (sealed), reopened=${reopened?.size} matches=${reopened?.contentEquals(first.encoded)} roundtrip=${String(back) == "team-id-and-key"}")
    }.onFailure { println("Wyrm probe: vault FAILED ${it}") }

    // Shims the shared TeamService uses.
    val once = android.text.Html.fromHtml("Hi&amp;nbsp;there &lt;3 <b>x</b>", 0).toString()
    val twice = android.text.Html.fromHtml(once, 0).toString()
    println("Wyrm probe: html once=[$once] twice=[${twice.replace(' ', '_')}]")
    val b64 = android.util.Base64.encodeToString(byteArrayOf(1, 2, 3, -1), android.util.Base64.NO_WRAP)
    println("Wyrm probe: base64 $b64 back=${android.util.Base64.decode(b64, android.util.Base64.NO_WRAP).toList()}")

    println("Wyrm probe: done, quitting")
    WyrmActivity.nativeDesktopQuit()
    Thread.sleep(2_000)
    kotlin.system.exitProcess(0)
}

private fun waitUntil(what: String, ms: Long, ok: () -> Boolean) {
    val end = System.currentTimeMillis() + ms
    while (System.currentTimeMillis() < end) {
        if (ok()) return
        Thread.sleep(100)
    }
    println("Wyrm probe: timed out waiting for $what")
    WyrmActivity.nativeDesktopQuit()
    kotlin.system.exitProcess(2)
}

/** A Compose key event built the way the window builds one, AWT event inside. */
@OptIn(androidx.compose.ui.InternalComposeUiApi::class)
private fun composeKey(awt: AwtKeyEvent, type: KeyEventType): KeyEvent =
    KeyEvent(key = Key(awt.keyCode), type = type, nativeEvent = awt)
