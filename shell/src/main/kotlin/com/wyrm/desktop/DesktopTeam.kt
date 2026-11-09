package com.wyrm.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.wyrm.omrajput.WyrmActivity
import com.wyrm.omrajput.data.TeamMessage
import com.wyrm.omrajput.data.TeamPresence
import com.wyrm.omrajput.data.TeamProfile
import com.wyrm.omrajput.data.TeamService
import com.wyrm.omrajput.data.TeamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.swing.SwingUtilities

/** A failed poll keeps its line; only acknowledgement of that line removes it. */
internal class TeamOutbox {
    var pending by mutableStateOf<String?>(null)
        private set
    fun queue(body: String): Boolean {
        if (pending != null || body.isBlank()) return false
        pending = body
        return true
    }
    fun acknowledge(body: String?, success: Boolean) {
        if (success && body != null && pending == body) pending = null
    }
    fun cancel(): String? = pending.also { pending = null }
}

/**
 * Team mode on the desktop (D2, OM 2026-10-11): Wyrm Android's team loop
 * (WyrmOverlay `startTeamPolling`, `pack`, `packTeamChat`, the outbox) around
 * Android's own TeamService, unchanged. One request every four seconds
 * carries this player's place up (the engine's `nativeTeamPresence`) and
 * brings the team down: the roster and minimap marks go to the engine
 * (`nativeSetTeamMembers`), the chat to its chat window (`nativeSetTeamChat`),
 * and a line typed here rides along with the next request, as NTL expects.
 * The team keys stay on this computer, sealed by Windows (TeamVaultKey).
 */
internal object DesktopTeam {
    /** The UI thread (Swing's EDT), where Compose state is changed; the app has
        no Dispatchers.Main (no kotlinx-coroutines-swing), so it is made here. */
    private val ui = java.util.concurrent.Executor { SwingUtilities.invokeLater(it) }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + ui)
    private var service: TeamService? = null
    private var job: Job? = null
    private val outbox = TeamOutbox()
    private var teamGeneration = 0L
    private var total = 0L
    private var packed = ""

    /** Team mode on (Android's `teamEnabled`; on by default, not saved, as there). */
    var enabled by mutableStateOf(true)
        private set
    var state by mutableStateOf(TeamState())
        private set
    var messages by mutableStateOf<List<TeamMessage>>(emptyList())
        private set
    var teams by mutableStateOf<List<TeamProfile>>(emptyList())
        private set
    var active by mutableIntStateOf(0)
        private set
    var adding by mutableStateOf(false)
    var draft by mutableStateOf("")
    var sending by mutableStateOf(false)
        private set
    var sendError by mutableStateOf("")
        private set
    var composerTab by mutableIntStateOf(0)
        private set

    /** The arena's message box is open (Enter in a match, or the chat window's box). */
    var composerOpen by mutableStateOf(false)
        private set

    val paused: Boolean get() = TeamService.NTL_SERVICES_DISABLED
    val running: Boolean get() = enabled && teams.isNotEmpty() && !paused

    /** Called once the engine is loaded (TeamVaultKey needs wyrm.dll for DPAPI). */
    fun start() {
        if (paused || job != null) return
        service = runCatching { TeamService(DesktopContext,
            java.util.UUID.randomUUID().toString().replace("-", "").take(8), escapeMessages = true) }.getOrNull() ?: return
        refreshTeams()
        // The arena's chat window shows the same list as the Team page.
        scope.launch {
            snapshotFlow { messages }.collect { list ->
                publishChat()
            }
        }
        job = scope.launch {
            while (isActive) {
                val team = service
                if (team != null && enabled && team.configured) {
                    val nativePresence = TeamPresence.parse(runCatching { WyrmActivity.nativeTeamPresence() }.getOrNull().orEmpty())
                    val presence = nativePresence.copy(nickname = nativePresence.nickname.ifBlank {
                        Engine.savedNickname.ifBlank { HomeState.nickname.ifBlank { "Wyrm Player" } }
                    })
                    val generation = teamGeneration
                    val outgoing = outbox.pending
                    val next = runCatching { team.poll(presence, outgoing) }
                        .getOrElse { TeamState(configured = true, status = "Could not reach the team service") }
                    if (generation != teamGeneration) continue
                    if (outgoing != null) {
                        if (next.messageAcknowledged && outgoing == outbox.pending) {
                            outbox.acknowledge(outgoing, true)
                            sending = false
                            sendError = ""
                            // The service may return the same log for two
                            // consecutive identical messages. Keep both sends.
                            if (next.messages.none { it.from == presence.nickname && it.text == outgoing }) {
                                total++
                                messages = (messages + TeamMessage(presence.nickname.ifBlank { "You" }, outgoing)).takeLast(200)
                            }
                        } else if (!next.connected) {
                            sendError = "${next.status}. Your message is queued; retrying…"
                        } else {
                            sendError = "The team has not confirmed your message yet; retrying…"
                        }
                    }
                    // A failed poll keeps the last team: only a good one replaces it
                    // (Android's rule: one bad answer must not wipe the map).
                    val updated = if (next.connected) next else state.copy(configured = true, connected = false, status = next.status)
                    if (updated != state) state = updated
                    if (next.messages.isNotEmpty()) {
                        total += next.messages.size
                        messages = (messages + next.messages).takeLast(200)
                    }
                    if (next.connected) sendMembers(pack(next, presence.arena))
                    // Every poll, not only on a change: a push made before the
                    // engine's team layer was up is dropped there, and an
                    // unchanged team would otherwise never be sent again.
                    publishChat()
                } else if (state.configured) {
                    state = TeamState()
                    sendMembers("")
                }
                delay(TeamService.POLL_INTERVAL_MS)
            }
        }
    }

    fun applyEnabled(on: Boolean) {
        enabled = on
        // Off leaves the map and the roster empty, not a stale team looking live.
        if (!on) {
            cancelPending()
            state = TeamState()
            messages = emptyList()
            sendMembers("")
        }
    }

    /** Returns a problem to show, or null when the team was saved. */
    fun addTeam(name: String, teamId: String, authKey: String): String? {
        val team = service ?: return "Team mode could not start on this computer."
        val problem = runCatching { team.addTeam(name, teamId, authKey) }.getOrElse { it.message ?: "The team could not be saved." }
        if (problem == null) {
            cancelPending()
            adding = false
            messages = emptyList()
            sendMembers("")
            state = TeamState(configured = true, status = "Connecting…")
            if (!enabled) enabled = true
        } else {
            state = TeamState(status = problem)
        }
        refreshTeams()
        return problem
    }

    fun selectTeam(index: Int) {
        val team = service ?: return
        cancelPending()
        team.selectTeam(index)
        messages = emptyList()
        state = TeamState(configured = true, status = "Connecting…")
        sendMembers("")
        refreshTeams()
        if (!enabled) enabled = true
    }

    fun removeActiveTeam() {
        val team = service ?: return
        cancelPending()
        team.removeTeam(active)
        messages = emptyList()
        state = TeamState()
        sendMembers("")
        refreshTeams()
    }

    /**
     * Queues a line for the next poll. There is no send call in this protocol:
     * a message rides with the next position report, so "Sending" until then
     * is the honest thing to show.
     */
    fun send(text: String = draft): Boolean {
        val body = text.trim().take(280)
        if (body.isEmpty() || sending) return false
        if (!running) { sendError = "Connect to a team first."; return false }
        if (!outbox.queue(body)) return false
        draft = ""
        sending = true
        sendError = ""
        publishChat()
        return true
    }

    val pendingMessage: TeamMessage? get() = outbox.pending?.let {
        TeamMessage("${Engine.savedNickname.ifBlank { "You" }} · sending…", it)
    }

    private fun publishChat() {
        val shown = messages + listOfNotNull(pendingMessage)
        runCatching { WyrmActivity.nativeSetTeamChat(packChat(shown)) }
    }

    private fun cancelPending() {
        teamGeneration++
        outbox.cancel()?.let { draft = it }
        sending = false
        sendError = ""
    }

    fun switchComposerTab() {
        composerTab = if (composerTab == 0) 1 else 0
        WyrmActivity.nativeSetChatTab(composerTab)
    }

    /** In a match, Enter opens the arena's message box (online, with a team). */
    val canChatInArena: Boolean
        get() = (running || Account.hasSession) && !Engine.inAiArena && Engine.screen == Engine.SCREEN_PLAYING

    fun openComposer() {
        if (composerOpen) return
        composerTab = runCatching { WyrmActivity.nativeChatTab() }.getOrDefault(0)
            .let { if (it == 2 || !running) 1 else it }
        WyrmActivity.nativeSetChatTab(composerTab)
        GameKeys.releaseAll()
        GameKeys.typing = true
        composerOpen = true
    }

    /** Closes the box and tells the engine chat is closed (its window stays). */
    fun closeComposer() {
        if (!composerOpen) return
        composerOpen = false
        GameKeys.typing = false
        runCatching { WyrmActivity.nativeCloseTeamChat(0f) }
    }

    /** From the engine thread: its chat window's box was clicked, or chat let go. */
    @JvmStatic
    fun onEngineChat(open: Boolean) = SwingUtilities.invokeLater {
        if (open) {
            // GLOBAL needs no team (OM, 2026-10-19), only an account.
            if (running || Account.hasSession) openComposer() else runCatching { WyrmActivity.nativeCloseTeamChat(0f) }
        } else if (composerOpen) {
            composerOpen = false
            GameKeys.typing = false
        }
    }

    private fun refreshTeams() {
        val team = service ?: return
        teams = runCatching { team.teams }.getOrDefault(emptyList())
        active = runCatching { team.activeTeam }.getOrDefault(0)
    }

    private fun sendMembers(next: String) {
        packed = next
        runCatching { WyrmActivity.nativeSetTeamMembers(next) }
    }

    /** First line: messages ever received (the folded window counts unread from it). */
    private fun packChat(list: List<TeamMessage>): String = buildString {
        append(total.coerceAtLeast(list.size.toLong()))
        list.forEach { message ->
            append('\n')
            append(message.from.replace('\t', ' ').replace('\n', ' '))
            append('\t')
            append(message.text.replace('\t', ' ').replace('\n', ' '))
        }
    }

    /**
     * The team, flattened for the engine (Android's `pack`): only what a marker
     * and a roster row need. `present` means the same arena as this player, so
     * a teammate's coordinates are drawn only where they mean something. You
     * are left out by name, which is all the service knows you by.
     */
    private fun pack(state: TeamState, myArena: String): String {
        return state.members
            .joinToString("\n") { member ->
                val present = member.playing && member.arena == myArena
                listOf(
                    member.name.replace('\t', ' ').replace('\n', ' '),
                    member.x, member.y, member.score, member.rank,
                    if (member.bot) 1 else 0,
                    if (present) 1 else 0,
                    member.snakeId, -1,
                    member.owner.replace('\t', ' ').replace('\n', ' '),
                    if (member.playing) member.arena.replace('\t', ' ').replace('\n', ' ') else "",
                ).joinToString("\t")
            }
    }
}
