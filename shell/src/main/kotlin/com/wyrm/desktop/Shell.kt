package com.wyrm.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wyrm.omrajput.data.Arena
import com.wyrm.omrajput.data.ArenaDirectory
import com.wyrm.omrajput.ui.Wyrm
import com.wyrm.omrajput.ui.iosSpring
import com.wyrm.omrajput.ui.WyrmMark
import com.wyrm.omrajput.ui.DesktopAuth
import com.wyrm.omrajput.ui.UsernameAvailability
import com.wyrm.omrajput.ui.WyrmSessionTransition
import kotlinx.coroutines.delay

/** The whole app: the welcome, the shell, and the game when a match is on. */
@Composable
fun WyrmApp() {
    // Signed in? A saved token is checked behind the W; without one, the sign-in.
    // Held in AppGate so the window can be small while signing in (Main.kt).
    var gate by AppGate.state
    LaunchedEffect(Unit) {
        if (gate == Gate.RESUMING) {
            gate = if (Account.resume()) {
                Account.bootstrap()
                Gate.IN
            } else {
                Gate.SIGN_IN
            }
        }
    }
    // Signed in: Social's caches paint and its quiet refreshes start (tab badges, boards).
    LaunchedEffect(gate, Account.player?.id) { if (gate == Gate.IN) Social.start() }
    val inMatch = Engine.screen == Engine.SCREEN_PLAYING
    Box(Modifier.fillMaxSize().background(Wyrm.Paper)) {
        when {
            Engine.failure != null -> Failure(Engine.failure!!)
            else -> AnimatedContent(
                targetState = when {
                    Account.transitionTitle != null -> 8
                    gate == Gate.RESUMING -> 4
                    gate == Gate.SIGN_IN -> 3
                    Engine.backgroundEditor != null -> 2
                    Engine.hudEditor != null -> 5
                    inMatch -> 1
                    ShellNav.teamOpen.value -> 7
                    HomeState.lobbyOpen -> 6
                    else -> 0
                },
                // Pages (the lobby, Team mode, sign-in) rise in softly; the match only fades.
                transitionSpec = {
                    if (targetState == 1 || initialState == 1) fadeIn(tween(320)) togetherWith fadeOut(tween(220))
                    else (fadeIn(tween(300)) + scaleIn(tween(320), initialScale = 0.985f)) togetherWith
                        (fadeOut(tween(200)) + scaleOut(tween(240), targetScale = 1.01f))
                },
                label = "match",
            ) { stage ->
                when (stage) {
                    4 -> WyrmSessionTransition("Syncing your Wyrm…")
                    8 -> WyrmSessionTransition(Account.transitionTitle ?: "Logging you out…")
                    3 -> DesktopAuth(
                        checkUsername = { name ->
                            runCatching { Account.repository.usernameAvailable(name) }.fold(
                                onSuccess = { if (it) UsernameAvailability.AVAILABLE else UsernameAvailability.TAKEN },
                                onFailure = { UsernameAvailability.UNAVAILABLE },
                            )
                        },
                        signUp = { username, password ->
                            // As Android: the username is the honest default display name.
                            runCatching { Account.repository.guestSignUp(username, username, password) }.fold(
                                onSuccess = { Account.acceptSignIn(it); null },
                                onFailure = Account::friendlyAuthError,
                            )
                        },
                        logIn = { username, password ->
                            runCatching { Account.repository.logIn(username, password) }.fold(
                                onSuccess = { Account.acceptSignIn(it); null },
                                onFailure = Account::friendlyAuthError,
                            )
                        },
                        bootstrap = { Account.bootstrap() },
                        onComplete = { gate = Gate.IN },
                    )
                    2 -> BackgroundSizeEditor()
                    5 -> HudEditor()
                    1 -> Match()
                    6 -> DesktopLobby()
                    7 -> TeamPage(onBack = { ShellNav.teamOpen.value = false })
                    else -> Shell()
                }
            }
        }
        // Account (D6), over everything: Android's log-out sheet and its save-failed
        // choices, and the crash / arena-drop cards (never over a match).
        val me = Account.player
        com.wyrm.omrajput.ui.LogOutSheet(
            visible = Account.logOutAsking,
            name = me?.displayName.orEmpty(),
            handle = me?.handle.orEmpty(),
            avatarUrl = me?.avatarUrl.orEmpty(),
            avatarKey = me?.avatarKey ?: "mono-ink",
            insetBottom = 24.dp,
            onLogOut = { Account.logOut() },
            onCancel = Account::cancelLogOut,
        )
        Account.logOutFailure?.let { message ->
            com.wyrm.omrajput.ui.LogOutSaveFailed(
                message = message,
                onRetry = { Account.logOut() },
                onLogOutAnyway = { Account.logOut(skipSave = true) },
                onStay = Account::cancelLogOut,
            )
        }
        if (!inMatch && gate != Gate.RESUMING && Account.transitionTitle == null) {
            com.wyrm.omrajput.ui.CrashPromptHost(Account.repository, insetBottom = 24.dp, insetTop = 24.dp)
            if (gate == Gate.IN) com.wyrm.omrajput.ui.DropPromptHost(Account.repository, insetBottom = 24.dp, insetTop = 24.dp)
        }
    }
    // Presence follows the lobby opening and closing too.
    LaunchedEffect(HomeState.lobbyOpen) { DesktopLive.pushPresence() }
    // Where the player is, for a crash report.
    LaunchedEffect(ShellNav.tab.value, Social.top, inMatch) {
        com.wyrm.omrajput.data.CrashWatch.screen = when {
            inMatch -> if (Engine.inAiArena) "Practice" else "Arena"
            Social.top != null -> Social.top!!::class.simpleName.orEmpty()
            else -> ShellNav.tab.value.label
        }
    }
}

/** Which tab is open, kept for the run; Home's rows can open a Settings page. */
internal object ShellNav {
    val tab = mutableStateOf(Tab.Play)

    /** Home › Team mode is open (D2). */
    val teamOpen = mutableStateOf(false)

    /** Help & feedback in Settings, on [page] (a support alert opens Your reports). */
    fun openHelp(page: HelpPage) {
        Social.closeAll()
        HelpNav.page = page
        openSettings(SettingsPage.HELP)
    }

    fun openSettings(page: SettingsPage) {
        SettingsPageShot.open = page
        tab.value = Tab.Settings
    }
}

/** Where the app stands with the account: checking, signing in, or in. */
internal enum class Gate { RESUMING, SIGN_IN, IN }

/** The gate, readable by the window: sign-in uses a small fixed window. */
internal object AppGate {
    val state = mutableStateOf(if (Account.hasSession) Gate.RESUMING else Gate.SIGN_IN)
    val signingIn: Boolean get() = state.value == Gate.SIGN_IN
}

@Composable
private fun Failure(message: String) {
    Column(Modifier.fillMaxSize().padding(48.dp), verticalArrangement = Arrangement.Center) {
        Text("Wyrm could not start", fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 24.sp, color = Wyrm.Ink)
        Spacer(Modifier.height(8.dp))
        Text(message, fontFamily = Wyrm.Body, fontSize = 15.sp, color = Wyrm.Mute)
    }
}

/** A match: the engine's frames and the team box above them. */
@Composable
private fun Match() {
    // Leaving the match closes the team box (and gives the keys back).
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { DesktopTeam.closeComposer() } }
    Box(Modifier.fillMaxSize()) {
        GameSurface()
        if (DesktopTeam.composerOpen) {
            ArenaComposer(Modifier.align(Alignment.BottomCenter).padding(start = 24.dp, end = 24.dp, bottom = 40.dp))
        }
        Text(
            if (DesktopTeam.canChatInArena) "Esc  leave   ·   Enter  team chat   ·   F11  full screen" else "Esc  leave   ·   F11  full screen",
            fontFamily = Wyrm.Body,
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.45f),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
        )
        // No death card: the engine never raises one (android_home.c
        // `raise_death_card` has no caller); after a death it returns to the
        // lobby, as on Android and iOS (OM, 2026-10-11).
    }
}


/**
 * The shell: the page fills the window; the tab bar floats over its top
 * centre, so switching tabs never moves the content. Pages slide sideways on iOS springs, in tab order.
 */
@Composable
private fun Shell() {
    var tab by ShellNav.tab
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val tabsWidth = topTabsWidth(maxWidth)
        Box(Modifier.fillMaxSize().background(Wyrm.Paper)) {
            run {
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        val forward = targetState.ordinal > initialState.ordinal
                        (fadeIn(iosSpring(0.42f, 0.9f)) +
                            slideInHorizontally(iosSpring(0.5f, 0.86f)) { (if (forward) 1 else -1) * it / 10 } +
                            scaleIn(iosSpring(0.5f, 0.86f), initialScale = 0.99f)) togetherWith
                            (fadeOut(iosSpring(0.3f, 1f)) +
                                slideOutHorizontally(iosSpring(0.5f, 0.86f)) { (if (forward) -1 else 1) * it / 14 } +
                                scaleOut(iosSpring(0.5f, 0.86f), targetScale = 0.99f))
                    },
                    label = "tab",
                ) { current ->
                    when (current) {
                        Tab.Play -> PlayPage()
                        Tab.Trails -> TrailsTabPage()
                        Tab.Social -> SocialTabPage()
                        Tab.Skin -> SkinPage()
                        Tab.Settings -> SettingsPage()
                    }
                }
            }
        }
        run {
            TopTabs(
                selected = tab,
                onSelect = { tab = it },
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp).width(tabsWidth),
            )
            // Home's arena picker and Alerts open over the page and the tab bar.
            if (tab == Tab.Play) HomeOverlays()
            // Social's pages (profile, chats, a trail…) open over everything, as Android's panel does.
            SocialPagesHost()
        }
    }
}

@Composable
internal fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Wyrm.Card)
            .border(1.dp, Wyrm.Rule, RoundedCornerShape(18.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) { content() }
}

@Composable
internal fun Label(text: String) {
    Text(text.uppercase(), fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.5.sp, color = Wyrm.Quiet)
}

@Composable
internal fun PaperButton(label: String, primary: Boolean, enabled: Boolean = true, wide: Boolean = false, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val width: Dp = if (wide) 220.dp else 150.dp
    Box(
        Modifier
            .size(width = width, height = 52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(
                when {
                    !enabled -> Wyrm.Track
                    primary -> if (hovered) Wyrm.Ink.copy(alpha = 0.88f) else Wyrm.Ink
                    else -> if (hovered) Wyrm.Well else Wyrm.Card
                },
            )
            .border(1.dp, if (primary) Color.Transparent else Wyrm.Rule, RoundedCornerShape(26.dp))
            .hoverable(hover)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontFamily = Wyrm.Body,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            letterSpacing = if (primary) 2.sp else 0.5.sp,
            color = when {
                !enabled -> Wyrm.Quiet
                primary -> Wyrm.OnInk
                else -> Wyrm.Ink
            },
        )
    }
}
