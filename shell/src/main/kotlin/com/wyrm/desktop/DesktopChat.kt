package com.wyrm.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import com.wyrm.omrajput.BuildConfig
import com.wyrm.omrajput.data.ApiPlayer
import com.wyrm.omrajput.data.ChatMessage
import com.wyrm.omrajput.data.ChatReaction
import com.wyrm.omrajput.data.FriendPresence
import com.wyrm.omrajput.data.LiveInbox
import com.wyrm.omrajput.data.reactions
import com.wyrm.omrajput.data.toChatMessage
import com.wyrm.omrajput.data.toDirectMessage
import com.wyrm.omrajput.ui.ChatActions
import com.wyrm.omrajput.ui.ChatPerson
import com.wyrm.omrajput.ui.ThreadChatHeader
import com.wyrm.omrajput.ui.WyrmChatScreen
import com.wyrm.omrajput.ui.iosChatError
import com.wyrm.omrajput.ui.readableChatError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/*
 * Chat as Wyrm Android has it (OM, 2026-11-11: bring what Desktop lacks):
 * Android's WyrmChatScreen (Discord-style markdown, link embeds, replies,
 * edits, reactions, pins, GIFs, @mentions from every player, "{n} active",
 * typing) over the desktop's Social lists, plus the live socket's chat events
 * and "Seen" on direct messages (`direct.read`, POST /v1/direct/:id/read).
 */
internal object DesktopChat {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository get() = Account.repository

    var replyingTo by mutableStateOf<ChatMessage?>(null)
    var editing by mutableStateOf<ChatMessage?>(null)
    var canPin by mutableStateOf(false)
    var activeCount by mutableStateOf<Int?>(null)
    var activePeople by mutableStateOf<List<ChatPerson>>(emptyList())
    var typingGlobal by mutableStateOf<Map<String, Pair<String, Long>>>(emptyMap())
    var typingDirect by mutableStateOf<Pair<String, Long>?>(null)
    var typingClock by mutableStateOf(0L)
    /** The live socket (DesktopLive), for "typing". */
    var live: LiveInbox? = null
    /** Wyrm's window is in front (DesktopLive): a message in the open thread is read at once. */
    @Volatile var windowFocused = true

    private val meId get() = Account.player?.id.orEmpty()

    private fun merge(fresh: List<ChatMessage>, shown: List<ChatMessage>): List<ChatMessage> =
        (fresh + shown).distinctBy { it.id }.sortedBy { it.createdAt }

    fun mentionables(): List<Pair<String, String>> =
        Social.globalMessages.asReversed()
            .filter { it.authorUsername.isNotBlank() && it.authorId != meId }
            .map { it.authorUsername to it.authorName.ifBlank { it.authorUsername } }
            .distinctBy { it.first.lowercase() }
            .take(40)

    fun cancelCompose() {
        if (editing != null) Social.draft = ""
        replyingTo = null
        editing = null
    }

    fun send(direct: Boolean) {
        val other = if (direct) Social.threadPlayer ?: return else null
        val body = Social.draft.trim().take(if (direct) 1000 else 280)
        if (body.isEmpty() || Social.sending) return
        editing?.let { target -> return saveEdit(target, body, direct) }
        val replyId = if (direct) null else replyingTo?.id
        Social.draft = ""
        replyingTo = null
        scope.launch {
            Social.sending = true
            runCatching { if (other != null) repository.sendDirect(other.id, body) else repository.sendGlobal(body, replyId) }
                .onSuccess {
                    Social.chatError = ""
                    if (other != null) runCatching { repository.directMessages(other.id) }
                        .onSuccess { if (Social.threadPlayer?.id == other.id) Social.threadMessages = merge(it, Social.threadMessages) }
                    else runCatching { repository.globalMessages() }
                        .onSuccess { Social.globalMessages = merge(it, Social.globalMessages) }
                }
                .onFailure {
                    Social.chatError = if (direct) readableChatError(repository.errorCode(it)) else iosChatError(repository.errorCode(it))
                    if (Social.draft.isEmpty()) Social.draft = body
                }
            Social.sending = false
        }
    }

    private fun saveEdit(target: ChatMessage, body: String, direct: Boolean) {
        Social.draft = ""
        editing = null
        if (body == target.body) return
        scope.launch {
            Social.sending = true
            runCatching { repository.editChatMessage(target.id, body, direct) }
                .onSuccess { Social.chatError = ""; reload(if (direct) "direct.edited" else "chat.edited") }
                .onFailure { Social.chatError = "Couldn't save that edit. Try again."; if (Social.draft.isEmpty()) Social.draft = body }
            Social.sending = false
        }
    }

    /** Someone edited a message: the open list is read again. */
    fun reload(type: String) {
        scope.launch {
            if (type == "chat.edited") {
                runCatching { repository.globalMessages() }.onSuccess { Social.globalMessages = merge(it, Social.globalMessages) }
            } else {
                val other = Social.threadPlayer ?: return@launch
                runCatching { repository.directMessages(other.id) }.onSuccess { Social.threadMessages = merge(it, Social.threadMessages) }
            }
        }
    }

    fun drop(id: String) {
        Social.globalMessages = Social.globalMessages.filterNot { it.id == id }
        Social.threadMessages = Social.threadMessages.filterNot { it.id == id }
    }

    fun delete(message: ChatMessage, direct: Boolean) {
        scope.launch {
            runCatching { repository.deleteChatMessage(message.id, direct) }
                .onSuccess { drop(message.id); Social.chatError = ""; Social.refreshConversations() }
                .onFailure { Social.chatError = "Couldn't delete that message. Try again." }
        }
    }

    fun react(message: ChatMessage, emoji: String, direct: Boolean) {
        fun local(list: List<ChatMessage>) = list.map { m ->
            if (m.id != message.id) m else {
                val had = m.reactions.firstOrNull { it.emoji == emoji }
                m.copy(reactions = when {
                    had == null -> m.reactions + ChatReaction(emoji, 1, true)
                    had.mine -> m.reactions.map { if (it.emoji == emoji) it.copy(count = it.count - 1, mine = false) else it }.filter { it.count > 0 }
                    else -> m.reactions.map { if (it.emoji == emoji) it.copy(count = it.count + 1, mine = true) else it }
                })
            }
        }
        fun put(reactions: List<ChatReaction>, list: List<ChatMessage>) = list.map { if (it.id == message.id) it.copy(reactions = reactions) else it }
        if (direct) Social.threadMessages = local(Social.threadMessages) else Social.globalMessages = local(Social.globalMessages)
        scope.launch {
            runCatching { repository.react(message.id, emoji, direct) }.onSuccess { r ->
                if (direct) Social.threadMessages = put(r, Social.threadMessages) else Social.globalMessages = put(r, Social.globalMessages)
            }.onFailure { Social.chatError = "Couldn't react to that message." }
        }
    }

    fun sendGif(url: String, direct: Boolean) {
        scope.launch {
            Social.sending = true
            runCatching {
                if (direct) Social.threadPlayer?.let { repository.sendDirect(it.id, url) }
                else repository.sendGlobal(url, replyingTo?.id)
            }.onSuccess { replyingTo = null; Social.chatError = ""; reload(if (direct) "direct.edited" else "chat.edited") }
                .onFailure { Social.chatError = iosChatError(repository.errorCode(it)) }
            Social.sending = false
        }
    }

    fun actions(direct: Boolean, onBack: () -> Unit) = ChatActions(
        onSend = { send(direct) },
        onSendGif = { sendGif(it, direct) },
        onDraftChange = { Social.draft = it },
        onTyping = { live?.sendTyping(direct, Social.threadPlayer?.id.orEmpty()) },
        onBack = onBack,
        onAuthor = Social::openPlayer,
        onReply = { editing = null; replyingTo = it },
        onEdit = { replyingTo = null; editing = it; Social.draft = it.body },
        onCancelCompose = ::cancelCompose,
        onDelete = { delete(it, direct) },
        onReport = { Social.reportMessage(it, "Reported from chat") },
        onReact = { m, emoji -> react(m, emoji, direct) },
        onPin = { m, on ->
            scope.launch {
                runCatching { repository.setPinned(m.id, on) }
                    .onSuccess { Social.globalMessages = Social.globalMessages.map { if (it.id == m.id) it.copy(pinned = if (on) "now" else "") else it } }
                    .onFailure { Social.chatError = "Couldn't change that pin." }
            }
        },
        search = { q -> repository.searchChat(q, if (direct) Social.threadPlayer?.id else null) },
        gifs = { stickers, q -> repository.gifs(stickers, q) },
        pins = { repository.chatPins().also { canPin = it.second } },
        who = { m -> repository.whoReacted(m.id, direct) },
        mentionSearch = { q ->
            repository.searchPlayers(q).filter { !it.username.isNullOrBlank() && it.id != meId }
                .map { it.username.orEmpty() to it.displayName.ifBlank { it.username.orEmpty() } }
        },
        linkPreview = { url -> repository.linkPreview(url) },
    )

    /** New messages, reads, reactions, pins and typing from the live socket. */
    fun onEvent(event: JSONObject) {
        val me = meId
        if (me.isEmpty()) return
        val base = BuildConfig.WYRM_API_URL.trimEnd('/')
        when (event.optString("type")) {
            "chat.message" -> event.optJSONObject("message")?.let { json ->
                val message = json.toChatMessage(base, me)
                Social.globalMessages = merge(listOf(message), Social.globalMessages)
                typingGlobal = typingGlobal - message.authorId
            }
            "direct.message" -> event.optJSONObject("directMessage")?.let { json ->
                val message = json.toDirectMessage(me)
                val other = Social.threadPlayer?.id
                val between = setOf(json.optString("senderId"), json.optString("recipientId"))
                if (other != null && other in between) {
                    Social.threadMessages = merge(listOf(message), Social.threadMessages)
                    if (message.authorId == other) typingDirect = null
                }
                // In the open thread with Wyrm in front: read now (Android, 2026-11-10).
                if (other != null && message.authorId == other && windowFocused && Social.section == Social.Section.MESSAGES) {
                    markRead(other)
                } else Social.refreshConversations()
            }
            "direct.read" -> {
                val reader = event.optString("readerId")
                val sender = event.optString("senderId")
                val readAt = event.optString("readAt")
                if (sender == me && reader == Social.threadPlayer?.id && readAt.isNotBlank()) {
                    Social.threadMessages = Social.threadMessages.map {
                        if (it.authorId == me && it.readAt.isBlank() && it.createdAt <= readAt) it.copy(readAt = readAt) else it
                    }
                }
                if (reader == me) Social.refreshConversations()
            }
            "chat.reaction" -> {
                val id = event.optString("messageId")
                val reactions = event.optJSONArray("reactions").reactions(me)
                fun swap(list: List<ChatMessage>) = list.map { if (it.id == id) it.copy(reactions = reactions) else it }
                if (event.optString("room") == "direct") Social.threadMessages = swap(Social.threadMessages)
                else Social.globalMessages = swap(Social.globalMessages)
            }
            "chat.pins" -> reload("chat.edited")
            "typing" -> {
                val who = event.optString("playerId")
                if (who.isBlank() || who == me) return
                val until = System.currentTimeMillis() + 6_000
                if (event.optString("room") == "direct") {
                    if (Social.threadPlayer?.id == who) typingDirect = event.optString("name") to until
                } else typingGlobal = typingGlobal + (who to (event.optString("name") to until))
                typingClock = System.currentTimeMillis()
                scope.launch {
                    delay(6_100)
                    val now = System.currentTimeMillis()
                    typingClock = now
                    typingGlobal = typingGlobal.filterValues { it.second > now }
                    if ((typingDirect?.second ?: 0L) <= now) typingDirect = null
                }
            }
        }
    }

    /** A conversation was read on this computer: the server tells the sender ("Seen"). */
    fun markRead(other: String) {
        scope.launch {
            runCatching { repository.markDirectRead(other) }
            Social.refreshConversations()
        }
    }

    fun refreshActive() {
        scope.launch {
            runCatching { repository.onlineNow() }.onSuccess { (count, rows) ->
                activeCount = count
                activePeople = rows.map { (id, p) ->
                    ChatPerson(id, p.displayName.ifBlank { p.ingameName }.ifBlank { p.username }, p.username, p.arena)
                }
            }
        }
    }

    fun loadPins() {
        scope.launch { runCatching { repository.chatPins() }.onSuccess { canPin = it.second } }
    }
}

@Composable
internal fun DesktopGlobalChat(insetTop: Dp, insetBottom: Dp, onBack: () -> Unit) {
    val me = Account.player
    LaunchedEffect(Unit) { DesktopChat.loadPins() }
    LaunchedEffect(Unit) {
        while (true) {
            DesktopChat.refreshActive()
            delay(10_000)
        }
    }
    WyrmChatScreen(
        title = "Global chat",
        subtitle = DesktopChat.activeCount?.let { "$it active" } ?: "Everyone on Wyrm · messages stay 7 days",
        activePeople = DesktopChat.activePeople,
        direct = false,
        peer = null,
        messages = Social.globalMessages,
        meId = me?.id.orEmpty(),
        meUsername = me?.username.orEmpty(),
        meName = me?.ingameName?.takeIf { it.isNotBlank() } ?: me?.displayName.orEmpty(),
        meAvatarUrl = me?.avatarUrl.orEmpty(),
        meAvatarKey = me?.avatarKey ?: "mono-ink",
        draft = Social.draft,
        sending = Social.sending,
        error = Social.chatError,
        typing = DesktopChat.typingGlobal.filterValues { it.second > DesktopChat.typingClock - 6_000 }.values.map { it.first },
        replying = DesktopChat.replyingTo,
        editing = DesktopChat.editing,
        canPin = DesktopChat.canPin,
        mentionables = DesktopChat.mentionables(),
        insetTop = insetTop,
        insetBottom = insetBottom,
        actions = DesktopChat.actions(direct = false, onBack = onBack),
    )
}

@Composable
internal fun DesktopThreadChat(peer: ApiPlayer, insetTop: Dp, insetBottom: Dp, onBack: () -> Unit) {
    val me = Account.player
    // Opening a conversation reads it (the sender sees "Seen").
    LaunchedEffect(peer.id) { DesktopChat.markRead(peer.id) }
    val activity = FriendPresence.of(peer.id)
    WyrmChatScreen(
        title = peer.displayName,
        subtitle = "",
        direct = true,
        peer = peer,
        messages = Social.threadMessages,
        meId = me?.id.orEmpty(),
        meUsername = me?.username.orEmpty(),
        meName = me?.ingameName?.takeIf { it.isNotBlank() } ?: me?.displayName.orEmpty(),
        meAvatarUrl = me?.avatarUrl.orEmpty(),
        meAvatarKey = me?.avatarKey ?: "mono-ink",
        draft = Social.draft,
        sending = Social.sending,
        error = Social.chatError,
        typing = listOfNotNull(DesktopChat.typingDirect?.takeIf { it.second > DesktopChat.typingClock - 6_000 }?.first),
        replying = null,
        editing = DesktopChat.editing,
        canPin = false,
        mentionables = emptyList(),
        insetTop = insetTop,
        insetBottom = insetBottom,
        actions = DesktopChat.actions(direct = true, onBack = onBack),
        header = { ThreadChatHeader(peer, activity, onOpen = { Social.openPlayer(peer.id) }, onJoin = Social::joinFriendArena) },
    )
}
