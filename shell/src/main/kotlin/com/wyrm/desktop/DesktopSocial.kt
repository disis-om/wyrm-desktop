package com.wyrm.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wyrm.omrajput.data.ApiPlayer
import com.wyrm.omrajput.data.BadgeStore
import com.wyrm.omrajput.data.ChatMessage
import com.wyrm.omrajput.data.Conversation
import com.wyrm.omrajput.data.FriendPresence
import com.wyrm.omrajput.data.RenameAllowance
import com.wyrm.omrajput.data.SocialCache
import com.wyrm.omrajput.ui.AvatarImages
import com.wyrm.omrajput.ui.TrailImages
import com.wyrm.omrajput.ui.TrailsStore
import com.wyrm.omrajput.ui.iosChatError
import com.wyrm.omrajput.ui.readableChatError
import com.wyrm.omrajput.ui.readablePhotoError
import com.wyrm.omrajput.ui.readableProfileError
import com.wyrm.omrajput.ui.toUploadBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Social on the desktop (D5, OM 2026-10-11): the state and the calls behind
 * Wyrm Android's Social screens, which the desktop compiles as they are
 * (SocialScreen, IosSocialScreens, IosProfile, Trails). Every rule here is
 * Android's own (WyrmOverlay.kt), named after it:
 *
 *  - stale-while-revalidate: boards, profiles, trail grids and badges paint
 *    from the per-account [SocialCache] at once, the server answer replaces
 *    them in place, and a failed refresh keeps what is shown;
 *  - chat polls only while a chat page is open (conversations each tick,
 *    the open thread every 4 s, Global every 3 s), Global's unread count is
 *    checked every 45 s and never in a match;
 *  - friends' activity is fresh whenever a list of friends opens, then every
 *    30 s while one stays open;
 *  - one in-flight download per avatar / trail photo, LRU in memory and on
 *    disk (AvatarImages, TrailImages: %LOCALAPPDATA%\Wyrm\cache);
 *  - signing out clears every store and the cache, so nothing of one
 *    account is shown to the next.
 *
 * Pages open over the tabs as a stack ([pages]); Back pops one.
 */
internal object Social {
    sealed interface Page {
        data object Leaderboard : Page
        data object Messages : Page
        data object Global : Page
        data class Thread(val player: ApiPlayer) : Page
        data class Player(val id: String) : Page
        data object Profile : Page
        data object EditProfile : Page
        data class Crop(val photo: android.graphics.Bitmap) : Page
        data class Connections(val playerId: String, val kind: String) : Page
        data class Trail(val id: String) : Page
        data object Studio : Page
        /** Voice rooms come with D7; Social's row says so meanwhile. */
        data object VoiceSoon : Page
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository get() = Account.repository
    val cache = SocialCache(DesktopContext)
    private val globalChatPrefs = DesktopContext.getSharedPreferences("wyrm_global_chat", 0)

    /**
     * The Social tab's sections (its left sidebar on the desktop, Android's
     * Social hub rows). Pages ([pages]) open over the section in the same
     * main area; picking a section closes them.
     */
    enum class Section(val title: String) {
        MESSAGES("Messages"), GLOBAL("Global chat"), LEADERBOARD("Leaderboard"),
        CONNECTIONS("Connections"), PROFILE("Your profile"), VOICE("Voice rooms"),
    }

    var section by mutableStateOf(Section.MESSAGES)
        private set
    val pages = mutableStateListOf<Page>()
    val top: Page? get() = pages.lastOrNull()
    /** A trail open in its window over the page (Instagram's post view on the web). */
    var modalTrail by mutableStateOf<String?>(null)
        private set
    /** Connections' lists while the section shows your own. */
    var ownConnectionsLoaded = false
        private set

    // Boards
    var scoreBoard by mutableStateOf<List<ApiPlayer>>(emptyList()); private set
    var killBoard by mutableStateOf<List<ApiPlayer>>(emptyList()); private set
    var boardSort by mutableStateOf(0)
    var boardLoading by mutableStateOf(false); private set
    private var boardUpdatedAt = 0L
    private var boardGen = 0

    // Chats
    var conversations by mutableStateOf<List<Conversation>>(emptyList()); private set
    var unreadDm by mutableStateOf(0L); private set
    var globalMessages by mutableStateOf<List<ChatMessage>>(emptyList()); private set
    var globalUnread by mutableStateOf(0); private set
    var threadPlayer by mutableStateOf<ApiPlayer?>(null); private set
    var threadMessages by mutableStateOf<List<ChatMessage>>(emptyList()); private set
    var draft by mutableStateOf("")
    var sending by mutableStateOf(false); private set
    var chatError by mutableStateOf(""); private set
    private var chatJob: Job? = null

    // People
    var followers by mutableStateOf<List<ApiPlayer>>(emptyList()); private set
    var following by mutableStateOf<List<ApiPlayer>>(emptyList()); private set
    var viewedPlayer by mutableStateOf<ApiPlayer?>(null); private set
    var connectionsFollowers by mutableStateOf<List<ApiPlayer>>(emptyList()); private set
    var connectionsFollowing by mutableStateOf<List<ApiPlayer>>(emptyList()); private set

    // Profile forms
    var profileLoading by mutableStateOf(false); private set
    var formBusy by mutableStateOf(false); private set
    var formError by mutableStateOf(""); private set
    var photoError by mutableStateOf(""); private set
    var renameAllowance by mutableStateOf<RenameAllowance?>(null); private set
    /** Where the photo picker came from: the profile, or Edit profile. */
    private var photoFromProfile = false

    private var started = false
    private var loopJob: Job? = null

    /** Offscreen renders (pageShot): sample people and chats; nothing is fetched. */
    internal fun showForPreview(
        board: List<ApiPlayer>,
        chats: List<Conversation>,
        global: List<ChatMessage>,
        page: Page?,
        viewed: ApiPlayer? = null,
    ) {
        started = true
        scoreBoard = board
        killBoard = board.sortedByDescending { it.kills }
        conversations = chats
        unreadDm = chats.sumOf { it.unread }
        globalMessages = global
        threadMessages = global.take(4)
        threadPlayer = viewed
        following = board.take(4)
        followers = board.drop(2).take(4)
        connectionsFollowers = board.take(5)
        connectionsFollowing = board.drop(1).take(3)
        viewedPlayer = viewed
        globalUnread = 3
        pages.clear()
        page?.let { pages.add(it) }
    }

    /** Offscreen renders (pageShot): a section, a thread, a trail window. */
    internal fun showSectionForPreview(next: Section, thread: ApiPlayer? = null, trail: String? = null) {
        section = next
        threadPlayer = thread
        modalTrail = trail
    }

    /** After sign-in: the stores get the account, the caches paint, the loops start. */
    fun start() {
        val me = Account.player ?: return
        cache.switchAccount(me.id)
        if (started) return
        started = true
        AvatarImages.init(DesktopContext)
        TrailImages.attach(DesktopContext)
        TrailsStore.repository = repository
        TrailsStore.cache = cache
        BadgeStore.repository = repository
        BadgeStore.cache = cache
        FriendPresence.sharing = me.shareActivity
        FriendPresence.arenaName = { address ->
            HomeState.arenas.firstOrNull { it.endpoint == address }?.let { "Arena %04d".format(it.id % 10_000) } ?: address
        }
        cache.leaderboard("score")?.let { scoreBoard = it.value; boardUpdatedAt = it.savedAt }
        cache.leaderboard("kills")?.let { killBoard = it.value }
        cache.saveProfile(me)
        refreshLeaderboards(silent = true, minAgeMs = 2 * 60_000L)
        refreshUnreadDm()
        scope.launch { refreshFollowing() }
        refreshGlobalUnread()
        loopJob = scope.launch {
            var tick = 0
            while (isActive) {
                delay(15_000L)
                if (!repository.hasSession) continue
                tick++
                val inMatch = Engine.screen == Engine.SCREEN_PLAYING
                if (tick % 2 == 0 && friendsOnScreen() && !inMatch) FriendPresence.refresh(repository)
                if (tick % 3 == 0 && !inMatch) refreshGlobalUnread()
                if (tick % 240 == 0 && !inMatch) refreshLeaderboards(silent = true, minAgeMs = 60 * 60_000L)
            }
        }
    }

    /** Sign-out: nothing of this account stays in memory or in the cache. */
    fun reset() {
        chatJob?.cancel()
        loopJob?.cancel()
        started = false
        pages.clear()
        section = Section.MESSAGES
        modalTrail = null
        ownConnectionsLoaded = false
        scoreBoard = emptyList(); killBoard = emptyList(); boardUpdatedAt = 0L
        conversations = emptyList(); unreadDm = 0; globalMessages = emptyList(); globalUnread = 0
        threadPlayer = null; threadMessages = emptyList(); draft = ""; chatError = ""
        followers = emptyList(); following = emptyList(); viewedPlayer = null
        connectionsFollowers = emptyList(); connectionsFollowing = emptyList()
        formError = ""; photoError = ""; renameAllowance = null
        TrailsStore.reset()
        BadgeStore.reset()
        FriendPresence.reset()
        cache.clear()
    }

    /* ------------------------------------------------------------ navigation */

    fun push(page: Page) {
        // Social's pages live in the Trails and Social tabs' main area; from
        // anywhere else (an alert on Play, a toast) they open on Social.
        if (ShellNav.tab.value != Tab.Social && ShellNav.tab.value != Tab.Trails) ShellNav.tab.value = Tab.Social
        modalTrail = null
        pages.add(page)
        onTopChanged()
    }

    /** A sidebar row: that section, any open page closed. */
    fun openSection(next: Section) {
        modalTrail = null
        pages.clear()
        if (ShellNav.tab.value != Tab.Social) ShellNav.tab.value = Tab.Social
        section = next
        chatError = ""
        when (next) {
            Section.MESSAGES -> scope.launch { refreshFollowing() }
            Section.LEADERBOARD -> refreshLeaderboards(silent = true, minAgeMs = 2 * 60_000L)
            Section.CONNECTIONS -> loadOwnConnections()
            Section.PROFILE -> refreshProfile()
            else -> Unit
        }
        onTopChanged()
    }

    /** The tab or the section changed (the Social page calls this): polls follow what is on screen. */
    fun onScreenChanged() = onTopChanged()

    fun back() {
        if (pages.isEmpty()) return
        pages.removeAt(pages.lastIndex)
        onTopChanged()
    }

    fun closeAll() {
        pages.clear()
        modalTrail = null
        onTopChanged()
    }

    fun closeTrail() {
        modalTrail = null
    }

    /** The chat pane on screen: Messages (and its open thread), Global, or none. */
    private fun chatOnScreen(): Section? = when {
        top is Page.Global -> Section.GLOBAL
        top is Page.Messages || top is Page.Thread -> Section.MESSAGES
        top == null && ShellNav.tab.value == Tab.Social && (section == Section.MESSAGES || section == Section.GLOBAL) -> section
        else -> null
    }

    private fun onTopChanged() {
        if (chatOnScreen() != null) pollChat() else {
            if (chatJob?.isActive == true) {
                chatJob?.cancel()
                draft = ""
                refreshUnreadDm()
            }
        }
        if (friendsOnScreen()) scope.launch { FriendPresence.refresh(repository) }
    }

    private fun friendsOnScreen(): Boolean = when (top) {
        // The Social tab's sidebar and the Trails tab's rail both show friends.
        null -> ShellNav.tab.value == Tab.Social || ShellNav.tab.value == Tab.Trails
        is Page.Messages, is Page.Thread, is Page.Connections, is Page.Player -> true
        else -> false
    }

    /* ---------------------------------------------------------------- boards */

    fun openLeaderboard() {
        boardSort = 0
        openSection(Section.LEADERBOARD)
    }

    /** Android's refreshLeaderboards: both boards together; a failure keeps the shown rows. */
    fun refreshLeaderboards(silent: Boolean = false, minAgeMs: Long = 0L) {
        if (silent && minAgeMs > 0L) {
            if (System.currentTimeMillis() - boardUpdatedAt < minAgeMs) return
            if (repository.shouldStayQuiet) return
        }
        if (!repository.hasSession) return
        val gen = ++boardGen
        scope.launch {
            if (!silent) boardLoading = true
            val score = async { runCatching { repository.leaderboard("score") } }
            val kills = async { runCatching { repository.leaderboard("kills") } }
            val scoreResult = score.await()
            val killResult = kills.await()
            if (gen != boardGen) return@launch
            scoreResult.onSuccess { scoreBoard = it; cache.saveLeaderboard("score", it) }
            killResult.onSuccess { killBoard = it; cache.saveLeaderboard("kills", it) }
            if (scoreResult.isSuccess && killResult.isSuccess) boardUpdatedAt = System.currentTimeMillis()
            boardLoading = false
            // Faces for the top of each board; the rest load as their rows scroll in.
            AvatarImages.prefetch((scoreBoard.take(30) + killBoard.take(30)).map { it.avatarUrl })
        }
    }

    /* ----------------------------------------------------------------- chats */

    fun openMessages() {
        draft = ""
        openSection(Section.MESSAGES)
    }

    fun openGlobal() {
        draft = ""
        openSection(Section.GLOBAL)
    }

    private fun pollChat() {
        chatJob?.cancel()
        chatJob = scope.launch {
            while (isActive) {
                val pane = chatOnScreen() ?: break
                runCatching { repository.conversations() }.onSuccess {
                    conversations = it
                    unreadDm = it.sumOf { row -> row.unread }
                }
                val thread = threadPlayer
                when {
                    pane == Section.MESSAGES && thread != null ->
                        runCatching { repository.directMessages(thread.id) }.onSuccess {
                            if (threadPlayer?.id == thread.id) threadMessages = it
                        }
                    pane == Section.GLOBAL -> runCatching { repository.globalMessages() }
                        .onSuccess {
                            globalMessages = it
                            chatError = ""
                            noteGlobalSeen(it)
                        }
                        .onFailure { chatError = "Can't reach chat right now." }
                    else -> Unit
                }
                delay(if (pane == Section.MESSAGES && thread != null) 4000 else 3000)
            }
        }
    }

    fun refreshConversations() {
        scope.launch {
            runCatching { repository.conversations() }.onSuccess {
                conversations = it
                unreadDm = it.sumOf { row -> row.unread }
            }
            refreshFollowing()
        }
    }

    private suspend fun refreshFollowing() {
        val me = Account.player?.id ?: return
        runCatching { repository.connections(me, "following") }.onSuccess { following = it }
        runCatching { repository.connections(me, "followers") }.onSuccess { followers = it }
    }

    private fun refreshUnreadDm() {
        if (!repository.hasSession) return
        scope.launch {
            runCatching { repository.conversations() }.onSuccess {
                conversations = it
                unreadDm = it.sumOf { row -> row.unread }
            }
        }
    }

    fun sendGlobal() {
        val body = draft.trim().take(280)
        if (body.isEmpty() || sending) return
        draft = ""
        scope.launch {
            sending = true
            runCatching { repository.sendGlobal(body) }
                .onSuccess {
                    chatError = ""
                    runCatching { repository.globalMessages() }.onSuccess {
                        globalMessages = it
                        noteGlobalSeen(it)
                    }
                }
                .onFailure {
                    chatError = iosChatError(repository.errorCode(it))
                    if (draft.isEmpty()) draft = body
                }
            sending = false
        }
    }

    fun sendDirect() {
        val other = threadPlayer ?: return
        val body = draft.trim().take(1000)
        if (body.isEmpty() || sending) return
        draft = ""
        scope.launch {
            sending = true
            runCatching { repository.sendDirect(other.id, body) }
                .onSuccess {
                    chatError = ""
                    runCatching { repository.directMessages(other.id) }.onSuccess { threadMessages = it }
                }
                .onFailure {
                    chatError = readableChatError(repository.errorCode(it))
                    if (draft.isEmpty()) draft = body
                }
            sending = false
        }
    }

    /** A conversation: Messages' right pane (the desktop's two-pane inbox). */
    fun openThread(other: ApiPlayer) {
        if (threadPlayer?.id != other.id) threadMessages = emptyList()
        threadPlayer = other
        draft = ""
        chatError = ""
        openSection(Section.MESSAGES)
        scope.launch {
            // The follow state on a board row can be minutes old, and it decides whether the thread is open.
            runCatching { repository.player(other.id) }.onSuccess { threadPlayer = it }
        }
    }

    /** The thread pane's Back: no conversation open. */
    fun closeThread() {
        threadPlayer = null
        threadMessages = emptyList()
        draft = ""
        chatError = ""
        onTopChanged()
    }

    fun reportMessage(message: ChatMessage, reason: String) {
        scope.launch {
            runCatching { repository.reportMessage(message.id, reason) }
                .onSuccess { chatError = "Reported. An operator will look at it." }
                .onFailure { chatError = "Couldn't send that report." }
        }
    }

    /** Android's refreshGlobalUnread: never while the room is open. */
    private fun refreshGlobalUnread() {
        val me = Account.player?.id ?: return
        if (!repository.hasSession || chatOnScreen() == Section.GLOBAL) return
        scope.launch {
            runCatching { repository.globalMessages() }.onSuccess { messages ->
                val seen = globalChatPrefs.getString("seen_at", null)
                if (seen == null) {
                    noteGlobalSeen(messages)
                    return@onSuccess
                }
                globalUnread = messages.count { it.createdAt > seen && it.authorId != me }
            }
        }
    }

    private fun noteGlobalSeen(messages: List<ChatMessage>) {
        val newest = messages.maxOfOrNull { it.createdAt } ?: return
        val seen = globalChatPrefs.getString("seen_at", null)
        if (seen == null || newest > seen) globalChatPrefs.edit().putString("seen_at", newest).apply()
        globalUnread = 0
    }

    /* ---------------------------------------------------------------- people */

    fun openPlayer(id: String) {
        if (id.isBlank()) return
        if (id == Account.player?.id) {
            openProfile()
            return
        }
        formError = ""
        // The last visit paints at once (no "Player" flash); the server answer replaces it.
        viewedPlayer = cache.player(id)?.value
        push(Page.Player(id))
        scope.launch {
            runCatching { repository.player(id) }
                .onSuccess {
                    if (top == Page.Player(id)) viewedPlayer = it
                    cache.savePlayer(it)
                }
                .onFailure {
                    if (viewedPlayer == null && top == Page.Player(id)) {
                        formError = "That player couldn't be loaded."
                        back()
                    }
                }
        }
    }

    /** Back onto another player's profile: that player again (the page under can be someone else). */
    fun showPlayer(id: String) {
        if (viewedPlayer?.id != id) viewedPlayer = cache.player(id)?.value
    }

    fun refreshPlayer(id: String) {
        scope.launch {
            runCatching { repository.player(id) }.onSuccess { fresh ->
                if (viewedPlayer?.id == id) viewedPlayer = fresh
                cache.savePlayer(fresh)
            }
        }
    }

    fun toggleFollow() {
        val other = viewedPlayer ?: return
        if (formBusy) return
        scope.launch {
            formBusy = true
            formError = ""
            runCatching { repository.setFollow(other.id, !other.isFollowing) }
                .onSuccess {
                    viewedPlayer = it
                    cache.savePlayer(it)
                    refreshFollowing()
                    refreshProfile(force = true)
                }
                .onFailure { formError = "That didn't go through. Try again." }
            formBusy = false
        }
    }

    fun openConnections(playerId: String, kind: String) {
        if (playerId == Account.player?.id) {
            openSection(Section.CONNECTIONS)
            return
        }
        // The end of the new-follower trail: your own followers, seen.
        if (playerId == Account.player?.id && kind == "followers") {
            Account.notifications.filter { !it.read && it.kind == "follow" }.forEach { row ->
                scope.launch { Account.setRead(row.id, true) }
            }
        }
        connectionsFollowers = emptyList()
        connectionsFollowing = emptyList()
        push(Page.Connections(playerId, kind))
        scope.launch {
            runCatching { repository.connections(playerId, "followers") }.onSuccess { connectionsFollowers = it }
            runCatching { repository.connections(playerId, "following") }.onSuccess { connectionsFollowing = it }
        }
    }

    /** A friend's "Playing in …" › Join: that arena from the lobby's Play. */
    fun joinFriendArena(address: String) {
        if (!com.wyrm.omrajput.data.ArenaDirectory.isValidEndpoint(address)) return
        closeAll()
        ShellNav.tab.value = Tab.Play
        HomeState.selected = address
        startPlay(HomeState.nickname, address)
    }

    /* --------------------------------------------------------------- profile */

    fun openProfile() {
        photoError = ""
        openSection(Section.PROFILE)
    }

    /** Connections (your own) as a section: both lists side by side. */
    private fun loadOwnConnections() {
        val me = Account.player?.id ?: return
        scope.launch {
            runCatching { repository.connections(me, "followers") }.onSuccess { followers = it }
            runCatching { repository.connections(me, "following") }.onSuccess { following = it }
            ownConnectionsLoaded = true
        }
        // The end of the new-follower trail: your own followers, seen.
        Account.notifications.filter { !it.read && it.kind == "follow" }.forEach { row ->
            scope.launch { Account.setRead(row.id, true) }
        }
    }

    /** Android's refreshProfile: the cache paints an empty screen, a fresh one skips the call. */
    fun refreshProfile(force: Boolean = false) {
        val cached = cache.profile()
        if (!force && cached != null) {
            if (Account.player == null) Account.accept(cached.value)
            if (cached.fresh) return
        }
        scope.launch {
            profileLoading = true
            runCatching { repository.me() }.onSuccess { acceptPlayer(it) }
            profileLoading = false
        }
    }

    private fun acceptPlayer(player: ApiPlayer) {
        Account.accept(player)
        FriendPresence.sharing = player.shareActivity
        cache.switchAccount(player.id)
        cache.saveProfile(player)
    }

    fun openEditProfile() {
        formError = ""
        renameAllowance = null
        push(Page.EditProfile)
        scope.launch { runCatching { repository.renameAllowance() }.onSuccess { renameAllowance = it } }
    }

    fun saveProfile(displayName: String, ingameName: String, username: String, bio: String) {
        val me = Account.player ?: return
        if (formBusy) return
        scope.launch {
            formBusy = true
            formError = ""
            runCatching {
                repository.updateProfile(
                    displayName = displayName,
                    // An empty arena name would fail the 3–20 rule; leave it untouched.
                    ingameName = ingameName.trim().takeIf { it.isNotEmpty() },
                    username = username,
                    bio = bio,
                    avatarKey = me.avatarKey,
                )
            }.onSuccess {
                acceptPlayer(it)
                formBusy = false
                back()
            }.onFailure {
                formBusy = false
                formError = readableProfileError(repository.errorCode(it))
            }
        }
    }

    /** Change photo: the desktop's file dialog stands in for the phone's photo picker. */
    fun pickPhoto(fromProfile: Boolean) {
        photoFromProfile = fromProfile
        photoError = ""
        formError = ""
        scope.launch {
            val file = DesktopFiles.chooseImage("Choose a photo") ?: return@launch
            val photo = withContext(Dispatchers.IO) { android.graphics.BitmapFactory.decodeFile(file.absolutePath) }
            if (photo == null) {
                formError = "That image couldn't be opened. Try another."
                if (photoFromProfile) photoError = formError
                return@launch
            }
            push(Page.Crop(photo))
        }
    }

    fun uploadPhoto(cropped: android.graphics.Bitmap) {
        if (formBusy) return
        scope.launch {
            formBusy = true
            formError = ""
            val bytes = withContext(Dispatchers.Default) { cropped.toUploadBytes() }
            runCatching { repository.uploadAvatar(bytes) }
                .onSuccess {
                    acceptPlayer(it)
                    formBusy = false
                    if (top is Page.Crop) back()
                }
                .onFailure {
                    formBusy = false
                    formError = readablePhotoError(repository.errorCode(it))
                }
        }
    }

    fun removePhoto() {
        if (formBusy) return
        val old = Account.player?.avatarUrl.orEmpty()
        scope.launch {
            formBusy = true
            formError = ""
            runCatching { repository.removeAvatar() }
                .onSuccess {
                    // The old address is dead now: nothing redraws a face that no longer exists.
                    AvatarImages.forget(old)
                    acceptPlayer(it)
                    formBusy = false
                }
                .onFailure {
                    formBusy = false
                    formError = readablePhotoError(repository.errorCode(it))
                    photoError = formError
                }
        }
    }

    fun deleteAccount() {
        if (formBusy) return
        scope.launch {
            formBusy = true
            formError = ""
            runCatching { repository.deleteAccount() }
                .onSuccess {
                    formBusy = false
                    Account.signOut()
                }
                .onFailure {
                    formBusy = false
                    formError = "Couldn't delete the account: " + readableProfileError(repository.errorCode(it))
                }
        }
    }

    /** Share profile: the line Android shares, on the clipboard (there is no share sheet here). */
    fun shareProfile() {
        val me = Account.player ?: return
        val name = if (me.username.isNullOrBlank()) me.displayName.ifBlank { "Wyrm" } else "@${me.username}"
        runCatching {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard
                .setContents(java.awt.datatransfer.StringSelection("Find me on Wyrm: $name"), null)
        }
        TrailsStore.toast = "Copied: Find me on Wyrm: $name"
    }

    /* ---------------------------------------------------------------- trails */

    /** A trail from the feed, a grid or an alert: its likes and replies, seen. */
    fun openTrail(id: String) {
        if (id.isBlank()) return
        Account.notifications
            .filter { !it.read && it.meta["trailId"] == id }
            .forEach { row -> scope.launch { Account.setRead(row.id, true) } }
        modalTrail = id
    }

    fun openStudio() = push(Page.Studio)

    /** The Social tab's red count: unread DMs, voice invites, new followers. */
    fun socialBadge(): Int {
        val alerts = Account.notifications.count {
            !it.read && (it.kind == "follow" || (it.kind == "voice_invite" && com.wyrm.omrajput.data.VOICE_ENABLED))
        }
        return unreadDm.toInt() + alerts
    }

    /** The Trails tab's red count: unread likes and replies on your trails. */
    fun trailsBadge(): Int = Account.notifications.count { !it.read && (it.kind == "trail_like" || it.kind == "trail_reply") }
}
