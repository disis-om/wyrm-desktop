package com.wyrm.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wyrm.omrajput.BuildConfig
import com.wyrm.omrajput.data.ApiException
import com.wyrm.omrajput.data.ArenaDirectory
import com.wyrm.omrajput.data.LiveInbox
import com.wyrm.omrajput.data.NotificationPreferences
import com.wyrm.omrajput.data.PendingRun
import com.wyrm.omrajput.data.PendingRunStore
import com.wyrm.omrajput.data.ServerNotification
import com.wyrm.omrajput.data.SupportStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.awt.AWTEvent
import java.awt.Toolkit

/**
 * The account, live (D6, 2026-10-12), with Wyrm Android's rules:
 *
 *  - **Live inbox:** `/v1/me/live` (the shared LiveInbox, platform "windows")
 *    while signed in; "something new" refetches the alerts, the DM count or
 *    Help & feedback's replies, so badges move without a refresh. A 15 s poll
 *    backs it up while Alerts is open.
 *  - **Presence:** home / lobby / playing (with the arena) / practice / idle,
 *    sent when it changes; idle after two quiet minutes outside a match.
 *  - **Windows toasts:** while Wyrm's window is not in front and no match is
 *    being played, a new alert of a kind the player kept on (Settings ›
 *    Notifications) shows as a Windows notification; clicking it brings Wyrm
 *    up on that page.
 *  - **Run receipts:** every finished run goes to the account
 *    (`/v1/me/stats/batch`, 25 a request) through the same durable outbox as
 *    Android (`wyrm_pending_runs`), so desktop runs count on the boards.
 */
internal object DesktopLive {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val inbox = LiveInbox(DesktopContext, BuildConfig.WYRM_API_URL, scope, { kind, _ -> onInbox(kind) }, "windows")
    private val runs = PendingRunStore(DesktopContext)
    private var running = false
    private var loop: Job? = null
    private var runJob: Job? = null
    private var runBatchSupported = true

    /** Wyrm's window is the one in front. */
    var windowFocused by mutableStateOf(true)
        private set
    private var lastInputAt = System.currentTimeMillis()
    private var idle = false
    /** The arena being played ("ip:port"), for presence and run receipts. */
    var arena: String = ""

    init {
        // Any key or mouse use in Wyrm's window: not idle.
        runCatching {
            Toolkit.getDefaultToolkit().addAWTEventListener({ onInput() },
                AWTEvent.KEY_EVENT_MASK or AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_MOTION_EVENT_MASK or AWTEvent.MOUSE_WHEEL_EVENT_MASK)
        }
    }

    /** Signed in: open the inbox, say where we are, drain owed run receipts. */
    fun start() {
        if (running || !Account.hasSession) return
        running = true
        lastInputAt = System.currentTimeMillis()
        idle = false
        pushPresence()
        inbox.start()
        flushRuns()
        loop?.cancel()
        loop = scope.launch {
            var tick = 0
            while (isActive && running) {
                delay(15_000L)
                tick++
                val nowIdle = Engine.screen != Engine.SCREEN_PLAYING && System.currentTimeMillis() - lastInputAt > IDLE_AFTER_MS
                if (nowIdle != idle) {
                    idle = nowIdle
                    pushPresence()
                }
                if (HomeState.alertsOpen) Account.refreshNotifications()
            }
        }
    }

    /** Signed out or closing. */
    fun stop() {
        running = false
        loop?.cancel()
        inbox.stop()
    }

    fun onWindowFocus(focused: Boolean) {
        windowFocused = focused
        if (focused) onInput()
    }

    private fun onInput() {
        lastInputAt = System.currentTimeMillis()
        if (idle) {
            idle = false
            if (running) SwingDispatch.later { pushPresence() }
        }
    }

    /** The engine changed screen, or the lobby opened or closed. */
    fun pushPresence() {
        if (!running) return
        val playing = Engine.screen == Engine.SCREEN_PLAYING
        val state = when {
            playing && Engine.inAiArena -> "practice"
            playing -> "playing"
            idle -> "idle"
            Engine.screen == Engine.SCREEN_LOBBY || HomeState.lobbyOpen -> "lobby"
            else -> "home"
        }
        inbox.setPresence(state, if (state == "playing") arena.takeIf { ArenaDirectory.isValidEndpoint(it) } else null)
    }

    /* ----------------------------------------------------------- inbox */

    private fun onInbox(kind: String) {
        scope.launch {
            val before = Account.notifications.map { it.id }.toSet()
            when (kind) {
                "dm" -> {
                    Social.refreshConversations()
                    toastDirectMessage()
                    return@launch
                }
                "support" -> SupportStore.refresh()
                "" -> { Social.refreshConversations(); SupportStore.refresh() }
            }
            Account.refreshNotifications()
            if (kind.isNotEmpty()) toastNew(Account.notifications.filter { it.id !in before && !it.read })
        }
    }

    /** No toast while Wyrm is in front, in a match, or for a kind the player turned off. */
    private fun mayToast(kind: String): Boolean =
        !windowFocused && Engine.screen != Engine.SCREEN_PLAYING &&
            NotificationPreferences.systemEnabled(DesktopContext) &&
            NotificationPreferences.isKindEnabled(DesktopContext, kind)

    private fun toastNew(rows: List<ServerNotification>) {
        val row = rows.maxByOrNull { it.createdAt } ?: return
        if (!mayToast(row.kind)) return
        val body = runCatching { com.wyrm.omrajput.ui.shadeMarkdown(row.body).toString() }.getOrDefault(row.body)
        DesktopToasts.show(row.title.ifBlank { "Wyrm" }, body) { openAlert(row) }
    }

    private suspend fun toastDirectMessage() {
        if (!mayToast("dm")) return
        val rows = runCatching { Account.repository.conversations() }.getOrNull() ?: return
        val newest = rows.filter { it.unread > 0 }.maxByOrNull { it.lastAt } ?: return
        DesktopToasts.show("New message from ${newest.player.displayName}", newest.lastMessage) {
            DesktopWindow.bringToFront()
            Social.start()
            Social.openThread(newest.player)
        }
    }

    /** A clicked toast: Wyrm in front, on the alert's page (Android's openNotificationCard). */
    private fun openAlert(row: ServerNotification) {
        DesktopWindow.bringToFront()
        scope.launch { Account.setRead(row.id, true) }
        Social.start()
        val trail = row.meta["trailId"].orEmpty()
        val actor = row.meta["actorId"].orEmpty()
        when {
            (row.kind == "trail_like" || row.kind == "trail_reply") && trail.isNotEmpty() -> Social.openTrail(trail)
            row.kind == "follow" && actor.isNotEmpty() -> Social.openPlayer(actor)
            row.kind == "rank" -> Social.openLeaderboard()
            row.kind == "support" -> ShellNav.openHelp(HelpPage.REPORTS)
            else -> {
                Social.closeAll()
                ShellNav.tab.value = Tab.Play
                HomeState.alertsOpen = true
            }
        }
    }

    /* ------------------------------------------------------------ runs */

    /** A finished run (the engine's recordRunFromNative): into the outbox, then to the account. */
    fun onRun(score: Int, kills: Int, seconds: Double) {
        val me = Account.player?.id ?: return
        if (!Account.hasSession) return
        val durationMs = if (seconds.isFinite() && seconds >= 0) (seconds * 1000).toLong() else -1L
        runs.enqueue(me, score, kills, durationMs, if (Engine.inAiArena) "" else arena)
        flushRuns()
    }

    /** Android's flushPendingRuns: in order, 25 a request, three short retries, then left for later. */
    private fun flushRuns() {
        val me = Account.player?.id ?: return
        if (!Account.hasSession || runJob?.isActive == true) return
        runJob = scope.launch {
            var failures = 0
            var counted = false
            var stopped = false
            while (Account.hasSession && Account.player?.id == me) {
                val chunk = runs.pendingFor(me).take(RUN_BATCH)
                if (chunk.isEmpty()) break
                val result = runCatching { sendRuns(chunk) }
                if (result.isFailure) {
                    val error = result.exceptionOrNull()!!
                    if (Account.repository.isUnauthorized(error) || ++failures >= 3) { stopped = true; break }
                    delay(Account.repository.retryDelayMs(error, 5_000L * failures) + (0L..2_000L).random())
                    continue
                }
                failures = 0
                counted = true
            }
            if (counted) {
                Social.refreshProfile(force = true)
                Account.refreshNotifications()
                Social.refreshLeaderboards(silent = true, minAgeMs = 20_000L)
            }
            runJob = null
            if (!stopped && runs.pendingFor(me).isNotEmpty()) flushRuns()
        }
    }

    private suspend fun sendRuns(chunk: List<PendingRun>) {
        if (runBatchSupported && chunk.size > 1) {
            val batch = runCatching { Account.repository.reportRuns(chunk) }
            batch.onSuccess { (done, _) ->
                runs.removeAll(done.ifEmpty { chunk.map { it.eventId } })
                return
            }
            val status = (batch.exceptionOrNull() as? ApiException)?.status
            if (status == 404) runBatchSupported = false
            if (status != 404 && status != 400) throw batch.exceptionOrNull()!!
        }
        for (run in chunk) {
            val single = runCatching { Account.repository.reportRun(run) }
            single.onSuccess { runs.remove(run.eventId) }
            val error = single.exceptionOrNull() ?: continue
            // A receipt the server can never take must not block the rest.
            if ((error as? ApiException)?.status == 400) runs.remove(run.eventId) else throw error
        }
    }

    private const val IDLE_AFTER_MS = 120_000L
    private const val RUN_BATCH = 25
}

/** Runs a block on the window's thread. */
internal object SwingDispatch {
    fun later(block: () -> Unit) = javax.swing.SwingUtilities.invokeLater(block)
}

/**
 * Windows toasts through the system tray (Java's TrayIcon balloon, which
 * Windows shows as a notification). The tray icon appears with the first
 * toast and stays while Wyrm runs; clicking a toast runs that toast's action.
 */
internal object DesktopToasts {
    private var tray: java.awt.TrayIcon? = null
    @Volatile private var action: (() -> Unit)? = null

    fun icon(): java.awt.image.BufferedImage? = runCatching {
        DesktopToasts::class.java.getResourceAsStream("/assets/wyrm_icon.png")?.use { javax.imageio.ImageIO.read(it) }
    }.getOrNull()

    private fun ensureTray(): java.awt.TrayIcon? {
        tray?.let { return it }
        if (!java.awt.SystemTray.isSupported()) return null
        return runCatching {
            val picture = icon() ?: return null
            java.awt.TrayIcon(picture, "Wyrm").apply {
                isImageAutoSize = true
                addActionListener { action?.let { run -> SwingDispatch.later(run) } }
                java.awt.SystemTray.getSystemTray().add(this)
            }
        }.getOrNull()?.also { tray = it }
    }

    fun show(title: String, body: String, onClick: () -> Unit) {
        val icon = ensureTray() ?: return
        action = onClick
        icon.displayMessage(title.take(120), body.replace(Regex("\\s+"), " ").trim().take(240), java.awt.TrayIcon.MessageType.NONE)
    }

    /** Wyrm closing: the tray icon goes with it. */
    fun remove() {
        tray?.let { runCatching { java.awt.SystemTray.getSystemTray().remove(it) } }
        tray = null
    }
}
