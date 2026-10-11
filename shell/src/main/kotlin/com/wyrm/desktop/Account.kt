package com.wyrm.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wyrm.omrajput.BuildConfig
import com.wyrm.omrajput.data.ApiPlayer
import com.wyrm.omrajput.data.FriendPresence
import com.wyrm.omrajput.data.ServerNotification
import com.wyrm.omrajput.data.SupportStore
import com.wyrm.omrajput.data.WyrmRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The player's Wyrm account on this computer: Wyrm Android's own backend
 * client (`WyrmRepository`, token kept in %APPDATA%\Wyrm\prefs like Android's
 * `wyrm_session`), the signed-in player, the alerts feed, and the account's
 * life on this computer (D6): settings restored behind the W on log in,
 * saved and wiped on log out (DesktopAccountSync), the live inbox and
 * presence (DesktopLive), Help & feedback's store.
 */
object Account {
    val repository = WyrmRepository(DesktopContext, BuildConfig.WYRM_API_URL)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var player by mutableStateOf<ApiPlayer?>(null)
        private set
    /** The alerts feed; voice invitations are left out while voice is hidden (OM, 2026-10-12). */
    private var notificationRows by mutableStateOf<List<ServerNotification>>(emptyList())
    var notifications: List<ServerNotification>
        get() = notificationRows
        private set(value) {
            notificationRows = if (com.wyrm.omrajput.data.VOICE_ENABLED) value else value.filterNot { it.kind.startsWith("voice_") }
        }

    val hasSession: Boolean get() = repository.hasSession

    /** The W's words while a log out saves and clears (Android's sessionTransitionTitle). */
    var transitionTitle by mutableStateOf<String?>(null)
        private set
    /** Settings › Log out asked: the sheet is up. */
    var logOutAsking by mutableStateOf(false)
        private set
    /** Why the settings could not be saved during log out; the three choices show. */
    var logOutFailure by mutableStateOf<String?>(null)
        private set

    /** Signed in just now (not a relaunch): the account's settings come in behind the W. */
    private var freshSignIn = false

    /** Offscreen renders (pageShot): sample alerts, as the server would send them. */
    internal fun showNotificationsForPreview(rows: List<ServerNotification>) {
        notifications = rows
        preview = true
    }

    /** An offscreen render keeps its samples: no refresh from the server. */
    private var preview = false
    val unread: Int get() = notifications.count { !it.read }

    /** The player record changed (profile save, photo, follow counts). */
    fun accept(player: ApiPlayer) {
        this.player = player
        FriendPresence.sharing = player.shareActivity
    }

    /** The sign-in or sign-up page answered: a new session on this computer. */
    fun acceptSignIn(player: ApiPlayer) {
        freshSignIn = true
        accept(player)
    }

    /** At launch with a saved token: is it still good? False means sign in again. */
    suspend fun resume(): Boolean {
        val me = runCatching { repository.me() }
        me.onSuccess { accept(it); return true }
        val error = me.exceptionOrNull()!!
        if (repository.isUnauthorized(error)) {
            repository.clearSession()
            return false
        }
        // Offline: keep the session; Home fills in when Wyrm can be reached.
        return true
    }

    /**
     * What Home needs before it shows (Android's bootstrapSession, the desktop
     * part), while the W is on screen. A fresh sign-in also brings the
     * account's settings in now; a relaunch keeps this computer's and saves
     * them a few seconds later (or finishes a restore still owed).
     */
    suspend fun bootstrap() = coroutineScope {
        val me = async { runCatching { repository.me() }.getOrNull() }
        val alerts = async { runCatching { repository.notifications() }.getOrNull() }
        me.await()?.let { accept(it) }
        alerts.await()?.let { notifications = it }
        if (freshSignIn) {
            freshSignIn = false
            // Offline: owed, finished by resume() (it retries every 30 s).
            if (!DesktopAccountSync.restore()) scope.launch { DesktopAccountSync.resume() }
        } else {
            scope.launch { DesktopAccountSync.resume() }
        }
        startSession()
    }

    /** Everything that lives as long as the session. */
    private fun startSession() {
        SupportStore.attach(DesktopContext, repository, Social.cache)
        Social.start()
        DesktopLive.start()
        SupportStore.refresh()
    }

    /* ------------------------------------------------------------ log out */

    fun askLogOut() {
        if (transitionTitle != null) return
        logOutAsking = true
    }

    fun cancelLogOut() {
        logOutAsking = false
        logOutFailure = null
    }

    /**
     * Log out (Android's logOut): save the settings to the account (unless
     * [skipSave]), then clear this computer and go back to the sign-in.
     */
    fun logOut(skipSave: Boolean = false) {
        logOutAsking = false
        logOutFailure = null
        if (transitionTitle != null) return
        transitionTitle = "Saving your settings…"
        scope.launch {
            if (!skipSave && DesktopAccountSync.ready) {
                val saved = DesktopAccountSync.save(force = true)
                if (saved.isFailure && saved.exceptionOrNull()?.message != "ENGINE_NOT_READY") {
                    transitionTitle = null
                    logOutFailure = "Wyrm could not reach your account. If you log out anyway, " +
                        "the changes you made since the last save are cleared from this computer."
                    return@launch
                }
            }
            transitionTitle = "Logging you out…"
            DesktopLive.stop()
            runCatching { repository.signOut() }
            Social.reset()
            SupportStore.reset()
            DesktopAccountSync.wipe()
            // As on Android and iOS: the account's surfaces clear behind the W, then the sign-in.
            delay(920)
            player = null
            notifications = emptyList()
            ShellNav.tab.value = Tab.Play
            AppGate.state.value = Gate.SIGN_IN
            transitionTitle = null
        }
    }

    /** Account deleted on the server: this computer forgets it at once (no save). */
    suspend fun signOut() {
        logOut(skipSave = true)
    }

    /** Settings › Privacy › Show my activity to friends; undone if the server says no. */
    fun applyActivitySharing(share: Boolean) {
        val before = FriendPresence.sharing
        FriendPresence.sharing = share
        scope.launch {
            runCatching { repository.setActivitySharing(share) }
                .onSuccess {
                    FriendPresence.sharing = it
                    FriendPresence.refresh(repository, force = true)
                }
                .onFailure { FriendPresence.sharing = before }
        }
    }

    /* ------------------------------------------------------------- alerts */

    suspend fun refreshNotifications() {
        if (preview) return
        runCatching { repository.notifications() }.getOrNull()?.let { notifications = it }
    }

    suspend fun markAllRead() {
        runCatching { repository.markNotificationsRead() }
        notifications = notifications.map { it.copy(read = true) }
    }

    /** The card's ⋯ menu (Android's onSetNotificationRead): shown at once, then the server. */
    suspend fun setRead(id: String, read: Boolean) {
        notifications = notifications.map { if (it.id == id) it.copy(read = read) else it }
        runCatching { repository.setNotificationRead(id, read) }
    }

    /** The card's ⋯ menu › Delete (Android's onDeleteNotification). */
    suspend fun delete(id: String) {
        notifications = notifications.filterNot { it.id == id }
        runCatching { repository.deleteNotification(id) }
    }

    /** Android's friendlyAuthError, word for word. */
    fun friendlyAuthError(error: Throwable): String {
        val code = error.message.orEmpty()
        return when {
            error is java.io.IOException -> "Could not reach Wyrm. Check your connection and try again."
            code == "USERNAME_TAKEN" -> "That username is already taken."
            code == "INVALID_LOGIN" -> "Username or password is incorrect."
            code == "NAME_CHANGE_LIMIT" || code == "USERNAME_CHANGE_LIMIT" -> "The monthly name-change limit has been reached."
            code == "IGN_TAKEN" -> "That arena name is already in use."
            code == "INVALID_PROFILE" || code == "INVALID_BODY" -> "Please check the fields and try again."
            code.isBlank() -> "Could not reach Wyrm. Check your connection and try again."
            else -> "Wyrm could not complete that request ($code)."
        }
    }
}
