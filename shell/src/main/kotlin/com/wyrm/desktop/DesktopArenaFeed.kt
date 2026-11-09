package com.wyrm.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wyrm.omrajput.WyrmActivity
import com.wyrm.omrajput.data.ChatMessage
import com.wyrm.omrajput.data.FriendPresence
import com.wyrm.omrajput.data.OnlinePlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.swing.SwingUtilities

/**
 * The arena chat window's GLOBAL and ONLINE tabs on a computer (OM,
 * 2026-10-19), as Wyrm Android's `startArenaFeed`: while a match is on
 * screen, Wyrm's global chat every 4 s and the players online every 16 s,
 * packed for the engine the same way (`nativeSetGlobalChat`,
 * `nativeSetOnlinePlayers`). The TEAM tab is DesktopTeam's.
 */
internal object DesktopArenaFeed {
    private val ui = java.util.concurrent.Executor { SwingUtilities.invokeLater(it) }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + ui)
    private var job: Job? = null
    private val seen = HashSet<String>()
    private var total = 0L
    var sending by mutableStateOf(false)
        private set
    var error by mutableStateOf("")
        private set

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            var tick = 0
            while (isActive) {
                if (Account.hasSession && !Engine.inAiArena) {
                    pushGlobal()
                    if (tick % 4 == 0) {
                        runCatching { Account.repository.onlinePlayers() }
                            .onSuccess { list -> runCatching { WyrmActivity.nativeSetOnlinePlayers(packOnline(list)) } }
                    }
                }
                tick++
                delay(4000)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun pushGlobal() {
        runCatching { Account.repository.globalMessages() }.onSuccess { list ->
            val shown = list.sortedBy { it.createdAt }
            var fresh = 0
            for (message in shown) if (seen.add(message.id)) fresh++
            total += fresh
            runCatching { WyrmActivity.nativeSetGlobalChat(packGlobal(shown)) }
        }
    }

    /** The GLOBAL tab's box: straight to Wyrm's global chat. */
    fun send(text: String, done: (Boolean) -> Unit = {}) {
        val body = text.trim().take(280)
        if (body.isEmpty() || sending) return
        if (!Account.hasSession) { error = "Sign in to send a message."; done(false); return }
        sending = true
        error = ""
        scope.launch {
            val result = runCatching { Account.repository.sendGlobal(body) }
            result.onSuccess { pushGlobal() }.onFailure { error = "Couldn't send. Check your connection and try again." }
            sending = false
            done(result.isSuccess)
        }
    }

    /** In-game name first, else the username, else the account name. */
    private fun name(ingame: String, username: String, display: String): String =
        ingame.takeIf { it.isNotBlank() } ?: username.takeIf { it.isNotBlank() } ?: display.ifBlank { "Player" }

    private fun packGlobal(list: List<ChatMessage>): String = buildString {
        append(total.coerceAtLeast(list.size.toLong()))
        list.takeLast(80).forEach { message ->
            append('\n')
            append(name(message.authorIngame, message.authorUsername, message.authorName).oneLine())
            append('\t')
            append(message.body.oneLine())
        }
    }

    private fun packOnline(list: List<OnlinePlayer>): String = buildString {
        list.take(120).forEach { player ->
            if (isNotEmpty()) append('\n')
            append(name(player.ingameName, player.username, player.displayName).oneLine())
            append('\t')
            val handle = if (player.username.isNotBlank()) "@${player.username}" else player.displayName
            val server = if (player.arena.isNotBlank()) FriendPresence.arenaName(player.arena) else "Not in an arena"
            append("$handle · $server".oneLine())
        }
    }

    private fun String.oneLine(): String = replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
}
