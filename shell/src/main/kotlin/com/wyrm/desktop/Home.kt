package com.wyrm.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wyrm.omrajput.WyrmActivity
import com.wyrm.omrajput.data.Arena
import com.wyrm.omrajput.data.ArenaDirectory
import com.wyrm.omrajput.data.ServerNotification
import com.wyrm.omrajput.data.SettingsCodec
import com.wyrm.omrajput.ui.IosArenaCard
import com.wyrm.omrajput.ui.IosEmptyPanel
import com.wyrm.omrajput.ui.IosFoodWell
import com.wyrm.omrajput.ui.IosGlyph
import com.wyrm.omrajput.ui.IosIcon
import com.wyrm.omrajput.ui.IosListRow
import com.wyrm.omrajput.ui.IosLoadoutIcon
import com.wyrm.omrajput.ui.IosLoadoutRow
import com.wyrm.omrajput.ui.IosMetric
import com.wyrm.omrajput.ui.IosPaperCard
import com.wyrm.omrajput.ui.IosScreenHeader
import com.wyrm.omrajput.ui.IosSectionLabel
import com.wyrm.omrajput.ui.NearOriginalStore
import com.wyrm.omrajput.ui.Wyrm
import com.wyrm.omrajput.ui.WyrmCapsule
import com.wyrm.omrajput.ui.WyrmCountBadge
import com.wyrm.omrajput.ui.iosSpring
import com.wyrm.omrajput.ui.wyrmRounded
import kotlinx.coroutines.launch

/**
 * Play = Wyrm Android's Home (HomeScreen.kt, wired as WyrmOverlay.PlayHome),
 * laid out for a desktop: the header across the top, then two columns —
 * the arena card, metrics and the last run on the left; Loadout and Rooms &
 * team on the right (max 1180 dp). The arena picker and Alerts open over the
 * page (`HomeOverlays`, drawn by the shell above the tab bar's backdrop).
 */
object HomeState {
    /** The arena Enter plays on: the engine's saved one until the player picks. */
    var selected by mutableStateOf("")
    var nickname by mutableStateOf("")
    /** The live directory, loaded on Home (no pings: those run only in the picker). */
    var arenas by mutableStateOf<List<Arena>>(emptyList())
    var directoryLoaded by mutableStateOf(false)
    var pickerOpen by mutableStateOf(false)
    /** The Ready Room is up (Home's arena card leads there; matches start from it). */
    var lobbyOpen by mutableStateOf(false)
    var alertsOpen by mutableStateOf(false)
}

/** Android's iosArenaTitle: "Arena 0123", "Custom arena" or "Pick a server". */
private fun arenaTitle(arena: Arena?, endpoint: String): String = when {
    arena != null && arena.id >= 0 -> "Arena %04d".format(arena.id % 10_000)
    ArenaDirectory.isValidEndpoint(endpoint) -> "Custom arena"
    else -> "Pick a server"
}

/** The engine's settings table, or nothing before the engine runs (offscreen checks). */
private fun engineSettings() = runCatching {
    if (Engine.started) SettingsCodec.settings(WyrmActivity.nativeSettingsSnapshot()) else emptyList()
}.getOrDefault(emptyList())

private fun grouped(value: Long): String = "%,d".format(value)

@Composable
internal fun PlayPage() {
    LaunchedEffect(Engine.savedNickname) { if (HomeState.nickname.isEmpty()) HomeState.nickname = Engine.savedNickname }
    LaunchedEffect(Engine.savedArena) { if (HomeState.selected.isEmpty()) HomeState.selected = Engine.savedArena }
    LaunchedEffect(Unit) {
        ArenaDirectory.endArenaPlay()
        if (!HomeState.directoryLoaded) {
            runCatching { ArenaDirectory.load() }.onSuccess {
                HomeState.arenas = it
                HomeState.directoryLoaded = true
            }
        }
        // The arena card's flag (Android loads the countries on Home too).
        runCatching { ArenaDirectory.loadCountries(HomeState.arenas.map { it.address }) }
    }
    val settings = remember(Engine.started, Engine.screen) { engineSettings() }
    val food = settings.firstOrNull { it.id == "normal.food_type" }
    val foodLabel = food?.options?.getOrNull(food.index) ?: "Original"

    val arena = HomeState.arenas.firstOrNull { it.endpoint.equals(HomeState.selected, ignoreCase = true) }
    val ready = arena != null || ArenaDirectory.isValidEndpoint(HomeState.selected)
    val player = Account.player

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 1180.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 28.dp, end = 28.dp, top = TopTabsClearance, bottom = 32.dp),
        ) {
            HomeHeader(
                nickname = HomeState.nickname,
                displayName = player?.displayName.orEmpty().ifBlank { HomeState.nickname },
                username = player?.username.orEmpty(),
                unread = Account.unread,
                onNicknameChange = {
                    HomeState.nickname = it
                    // Android's nickname_chosen: a name the player typed (even blank) is theirs.
                    DesktopContext.getSharedPreferences("wyrm_ui_preferences", 0).edit().putBoolean("nickname_chosen", true).apply()
                },
                onNicknameDone = { if (Engine.started) WyrmActivity.nativeSetNickname(HomeState.nickname) },
                onAlerts = { HomeState.alertsOpen = true },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f)) {
                    IosPaperCard {
                        IosArenaCard(
                            title = arenaTitle(arena, HomeState.selected),
                            live = arena != null,
                            players = arena?.players ?: 0,
                            endpoint = arena?.endpoint ?: HomeState.selected.takeIf { ArenaDirectory.isValidEndpoint(it) }.orEmpty(),
                            ready = ready,
                            onEnter = { if (ready) HomeState.lobbyOpen = true else HomeState.pickerOpen = true },
                            onPickServer = { HomeState.pickerOpen = true },
                            titleAccessory = {
                                if (arena != null && arena.id >= 0) CountryBadge(ArenaDirectory.countryOf(arena.address), Modifier.padding(start = 8.dp))
                            },
                        )
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Wyrm.Rule))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IosMetric("BEST SCORE", grouped(player?.highestScore ?: 0))
                            Box(Modifier.width(1.dp).height(51.dp).background(Wyrm.Rule))
                            IosMetric("TOTAL KILLS", grouped(player?.kills ?: 0))
                        }
                    }
                    Engine.lastRun?.let { (score, kills, seconds) ->
                        IosSectionLabel("Last run")
                        IosPaperCard {
                            val total = seconds.toLong()
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IosMetric("SCORE", grouped(score.toLong()))
                                Box(Modifier.width(1.dp).height(51.dp).background(Wyrm.Rule))
                                IosMetric("KILLS", grouped(kills.toLong()))
                                Box(Modifier.width(1.dp).height(51.dp).background(Wyrm.Rule))
                                IosMetric("TIME", "${total / 60}:${(total % 60).toString().padStart(2, '0')}")
                            }
                        }
                    }
                    Engine.notice?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(it, fontFamily = Wyrm.Body, fontSize = 14.sp, color = Wyrm.Badge, modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
                Column(Modifier.weight(1f)) {
                    IosSectionLabel("Loadout")
                    IosPaperCard {
                        IosLoadoutRow("Food", foodLabel, first = true, leading = { IosFoodWell() }) { ShellNav.openSettings(SettingsPage.FOOD) }
                        IosLoadoutRow("Mode", "", leading = { IosLoadoutIcon(IosGlyph.SCOPE) }) { ShellNav.openSettings(SettingsPage.MODES) }
                        NearOriginalDesktopRow(NearOriginalStore.on) { NearOriginalStore.applyOn(it) }
                    }
                    IosSectionLabel("Rooms & team")
                    IosPaperCard {
                        if (com.wyrm.omrajput.data.VOICE_ENABLED) IosListRow(
                            title = "Voice rooms",
                            detail = "Own and community rooms",
                            value = "0 live",
                            glyph = IosGlyph.MIC,
                            tint = Wyrm.Live,
                        ) { Social.openSection(Social.Section.VOICE) }
                        IosListRow(
                            title = "Team mode",
                            detail = when {
                                !DesktopTeam.running -> "Connect your NTL team"
                                DesktopTeam.state.connected -> "${DesktopTeam.state.members.size} members · team chat"
                                else -> "Joining your team"
                            },
                            value = if (DesktopTeam.running && DesktopTeam.state.connected) "Live" else "Open",
                            glyph = IosGlyph.PERSON_2,
                            tint = Wyrm.Live,
                        ) { ShellNav.teamOpen.value = true }
                    }
                }
            }
        }
    }
}

internal fun startPlay(nickname: String, endpoint: String) {
    ArenaDirectory.closePicker()
    ArenaDirectory.beginArenaPlay()
    Engine.play(nickname, endpoint)
}

/** Android Home's header: in-game name (click to rename), name, @handle, avatar; plus Alerts. */
@Composable
private fun HomeHeader(
    nickname: String,
    displayName: String,
    username: String,
    unread: Int,
    onNicknameChange: (String) -> Unit,
    onNicknameDone: () -> Unit,
    onAlerts: () -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    fun finish() {
        if (!editing) return
        editing = false
        onNicknameDone()
    }
    LaunchedEffect(editing) { if (editing) focus.requestFocus() }
    val name = displayName.ifBlank { "Wyrm" }
    val handle = username.trim().removePrefix("@")
    val initial = name.filter { it.isLetter() }.take(2).uppercase().ifEmpty { "W" }
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 15.dp, bottom = 15.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(
            Modifier
                .weight(1f)
                .clip(wyrmRounded(10.dp))
                .clickable(enabled = !editing) { editing = true }
                .padding(end = 12.dp),
        ) {
            Text("Wyrm", fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 10.5.sp, letterSpacing = 1.sp, color = Wyrm.Quiet)
            if (editing) {
                BasicTextField(
                    value = nickname,
                    onValueChange = { typed -> onNicknameChange(typed.filterNot { it.isISOControl() }.take(24)) },
                    singleLine = true,
                    textStyle = TextStyle(fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 29.sp, letterSpacing = (-0.5).sp, color = Wyrm.Ink),
                    cursorBrush = SolidColor(Wyrm.Link),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { finish() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .onFocusChanged { state ->
                            if (state.isFocused) focused = true else if (focused) { focused = false; finish() }
                        },
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (nickname.isBlank()) {
                                Text("Wyrm Player", fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 29.sp,
                                    letterSpacing = (-0.5).sp, color = Wyrm.Faint)
                            }
                            inner()
                        }
                    },
                )
            } else {
                Text(
                    nickname.ifBlank { "Wyrm Player" },
                    fontFamily = Wyrm.Body,
                    fontWeight = FontWeight.Bold,
                    fontSize = 29.sp,
                    letterSpacing = (-0.5).sp,
                    color = if (nickname.isBlank()) Wyrm.Faint else Wyrm.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text("Click to rename in-game name", fontFamily = Wyrm.Body, fontSize = 9.5.sp, color = Wyrm.Quiet.copy(alpha = 0.55f))
        }
        AlertsButton(unread, onAlerts)
        Spacer(Modifier.width(16.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(name, fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Wyrm.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (handle.isNotBlank()) {
                Text("@$handle", fontFamily = Wyrm.Body, fontSize = 10.5.sp, color = Wyrm.Quiet, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(9.dp))
        // The avatar's initials (photographs come with the profile screen).
        Box(
            Modifier.size(36.dp).clip(wyrmRounded(10.8.dp)).background(Wyrm.Ink),
            contentAlignment = Alignment.Center,
        ) {
            Text(initial, fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Wyrm.OnInk)
        }
    }
}

/** The bell with the unread count (Android's Alerts tab badge). */
@Composable
private fun AlertsButton(unread: Int, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Box {
        Box(
            Modifier
                .size(40.dp)
                .clip(WyrmCapsule)
                .background(if (hovered) Wyrm.Well else Wyrm.Card)
                .border(1.dp, Wyrm.Rule, WyrmCapsule)
                .hoverable(hover)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            IosIcon(IosGlyph.BELL_BADGE, Wyrm.Ink, size = 19.dp, semibold = true)
        }
        if (unread > 0) WyrmCountBadge(unread, Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp))
    }
}

/**
 * What opens over Home: the arena picker (a modal glass sheet) and Alerts
 * (a glass panel from the right edge). Drawn by the shell outside the page's
 * recorded layer, so the glass can refract the page under it.
 */
@Composable
internal fun BoxScope.HomeOverlays() {
    Scrim(HomeState.pickerOpen) { HomeState.pickerOpen = false }
    AnimatedVisibility(
        visible = HomeState.pickerOpen,
        enter = fadeIn(iosSpring(0.4f, 0.9f)) + scaleIn(iosSpring(0.45f, 0.82f), initialScale = 0.94f),
        exit = fadeOut(iosSpring(0.3f, 1f)) + scaleOut(iosSpring(0.3f, 1f), targetScale = 0.96f),
        modifier = Modifier.align(Alignment.Center),
    ) {
        ArenaPicker(onClose = { HomeState.pickerOpen = false })
    }
    Scrim(HomeState.alertsOpen) { HomeState.alertsOpen = false }
    AnimatedVisibility(
        visible = HomeState.alertsOpen,
        enter = slideInHorizontally(iosSpring(0.45f, 0.86f)) { it } + fadeIn(iosSpring(0.4f, 0.9f)),
        exit = slideOutHorizontally(iosSpring(0.35f, 1f)) { it } + fadeOut(iosSpring(0.3f, 1f)),
        modifier = Modifier.align(Alignment.CenterEnd),
    ) {
        AlertsPanel(onClose = { HomeState.alertsOpen = false })
    }
}

@Composable
private fun Scrim(visible: Boolean, onDismiss: () -> Unit) {
    AnimatedVisibility(visible, enter = fadeIn(iosSpring(0.4f, 1f)), exit = fadeOut(iosSpring(0.3f, 1f))) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.22f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
    }
}

/** A floating sheet over the page (paperPanel; it was glass); Esc closes it. */
@Composable
internal fun GlassSheet(
    modifier: Modifier,
    shape: androidx.compose.ui.graphics.Shape,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(
        modifier
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) { onClose(); true } else false
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .paperPanel(shape, elevation = 24.dp),
    ) { Box(Modifier.clip(shape)) { content() } }
}

/**
 * Home › Near Original on a computer (OM, 2026-10-11: it stays): slither's
 * own minimap, leaderboard and HUD. Android's row speaks of the joystick and
 * arrow, which a computer does not have, so the words are the desktop's.
 */
@Composable
private fun NearOriginalDesktopRow(on: Boolean, onToggle: (Boolean) -> Unit) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Wyrm.RowRule))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IosLoadoutIcon(IosGlyph.ARROW_CLOCKWISE)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Near Original", fontFamily = Wyrm.Body, fontSize = 15.5.sp, color = Wyrm.Ink, maxLines = 1)
                Text(
                    "Slither's own minimap, leaderboard and HUD. The mouse and your keys still steer.",
                    fontFamily = Wyrm.Body, fontSize = 12.sp, lineHeight = 15.sp, color = Wyrm.Quiet,
                )
            }
            Spacer(Modifier.width(10.dp))
            com.wyrm.omrajput.ui.PaperSwitch(on = on, onToggle = onToggle)
        }
    }
}
