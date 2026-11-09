package com.wyrm.desktop

import com.wyrm.omrajput.WyrmActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wyrm.omrajput.data.TeamMember
import com.wyrm.omrajput.ui.IosListRow
import com.wyrm.omrajput.ui.IosPaperCard
import com.wyrm.omrajput.ui.IosSectionLabel
import com.wyrm.omrajput.ui.SettingsActionRow
import com.wyrm.omrajput.ui.SettingsBoolRow
import com.wyrm.omrajput.ui.SettingsCaption
import com.wyrm.omrajput.ui.Wyrm
import com.wyrm.omrajput.ui.wyrmRounded

/**
 * Home › Team mode on a computer (OM, 2026-10-19). With no team: one card in
 * the middle asks for the team's name, Team ID and Auth key. Connected: the
 * roster on the left with everything the service says about each teammate,
 * the team chat on the right, and a gear at the top right with what the
 * phones keep in their menu (add a team, switch, disconnect, remove).
 */
@Composable
internal fun TeamPage(onBack: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val running = DesktopTeam.running
    val teams = DesktopTeam.teams
    val state = DesktopTeam.state
    Box(
        Modifier
            .fillMaxSize()
            .background(Wyrm.Paper)
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    if (DesktopTeam.adding && teams.isNotEmpty()) DesktopTeam.adding = false else onBack()
                    true
                } else false
            }
            .focusable(),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 24.dp, top = 22.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill("‹  Play", filled = false, onClick = onBack)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (running) teams.getOrNull(DesktopTeam.active)?.name?.ifBlank { "Team mode" } ?: "Team mode" else "Team mode",
                        fontFamily = Wyrm.Display, fontSize = 30.sp, color = Wyrm.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        when {
                            DesktopTeam.paused -> "Team services are switched off in this build."
                            !running && teams.isNotEmpty() -> "Disconnected. Your saved teams stay on this computer."
                            !running -> "Connect your team to see who is where and talk to them."
                            state.connected -> "${state.members.size} ${if (state.members.size == 1) "teammate" else "teammates"} online · Esc goes back"
                            state.status.isNotBlank() && state.status != "Connected" -> state.status
                            else -> "Joining your team…"
                        },
                        fontFamily = Wyrm.Body, fontSize = 13.sp, color = Wyrm.Mute,
                    )
                }
                if (running) StatusChip(state.connected, state.status)
                if (teams.isNotEmpty() && !DesktopTeam.paused) {
                    Spacer(Modifier.width(10.dp))
                    TeamGear()
                }
            }
            when {
                DesktopTeam.paused -> CentredNote("Team mode is paused", "Nothing is sent to or received from the team service in this build.")
                teams.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ConnectCard(canCancel = false) }
                !running -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Reconnect() }
                else -> Row(Modifier.fillMaxSize().padding(start = 4.dp, end = 12.dp, bottom = 18.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) { RosterColumn() }
                    Column(Modifier.weight(1.15f).fillMaxHeight()) { ChatColumn() }
                }
            }
        }
        // Add a team over the page, in the middle (from the gear).
        if (DesktopTeam.adding && teams.isNotEmpty()) {
            Box(
                Modifier.fillMaxSize().background(Wyrm.Ink.copy(alpha = 0.32f))
                    .clickable(remember { MutableInteractionSource() }, indication = null) { DesktopTeam.adding = false },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.clickable(remember { MutableInteractionSource() }, indication = null) {}) { ConnectCard(canCancel = true) }
            }
        }
    }
}

@Composable
private fun StatusChip(connected: Boolean, status: String) {
    val live = connected
    val label = when {
        live -> "Live"
        status.isNotBlank() && status != "Connected" -> "Offline"
        else -> "Joining"
    }
    Row(
        Modifier.clip(CircleShape).background(Wyrm.Well).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (live) Wyrm.Live else if (label == "Offline") Wyrm.Badge else Wyrm.Quiet))
        Text(label, fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Wyrm.Ink)
    }
}

/** The gear: Android's team menu (add, switch, disconnect / connect, remove). */
@Composable
private fun TeamGear() {
    var open by remember { mutableStateOf(false) }
    Box {
        val hover = remember { MutableInteractionSource() }
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(Wyrm.Well)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(hover, indication = null) { open = !open },
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(
                androidx.compose.ui.res.painterResource(com.composables.icons.lucide.R.drawable.lucide_ic_settings),
                contentDescription = "Team settings", tint = Wyrm.Ink, modifier = Modifier.size(19.dp),
            )
        }
        if (open) {
            androidx.compose.ui.window.Popup(
                alignment = Alignment.TopEnd,
                offset = androidx.compose.ui.unit.IntOffset(0, 120),
                onDismissRequest = { open = false },
                properties = androidx.compose.ui.window.PopupProperties(focusable = true),
            ) {
                Column(
                    Modifier.width(280.dp).clip(wyrmRounded(18.dp)).background(Wyrm.Card)
                        .border(1.dp, Wyrm.Rule, wyrmRounded(18.dp)).padding(vertical = 6.dp),
                ) {
                    val teams = DesktopTeam.teams
                    MenuLabel("Your teams")
                    teams.forEachIndexed { index, team ->
                        MenuItem(team.name.ifBlank { "Team ${index + 1}" }, checked = DesktopTeam.running && index == DesktopTeam.active) {
                            open = false
                            if (!DesktopTeam.enabled) DesktopTeam.applyEnabled(true)
                            DesktopTeam.selectTeam(index)
                        }
                    }
                    MenuRule()
                    MenuItem("Add a team") { open = false; DesktopTeam.adding = true }
                    if (DesktopTeam.enabled) MenuItem("Disconnect") { open = false; DesktopTeam.applyEnabled(false) }
                    else MenuItem("Connect") { open = false; DesktopTeam.applyEnabled(true) }
                    if (DesktopTeam.running) {
                        MenuRule()
                        MenuItem("Remove this team", danger = true) { open = false; DesktopTeam.removeActiveTeam() }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuLabel(text: String) {
    Text(text.uppercase(), fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.8.sp,
        color = Wyrm.Quiet, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp))
}

@Composable
private fun MenuRule() {
    Box(Modifier.fillMaxWidth().padding(vertical = 5.dp).height(1.dp).background(Wyrm.RowRule))
}

@Composable
private fun MenuItem(label: String, checked: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp).clip(wyrmRounded(10.dp))
            .background(if (hovered) Wyrm.Well else androidx.compose.ui.graphics.Color.Transparent)
            .hoverable(hover)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(hover, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontFamily = Wyrm.Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
            color = if (danger) Wyrm.Badge else Wyrm.Ink, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (checked) Text("✓", fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Wyrm.Live)
    }
}

@Composable
private fun CentredNote(title: String, body: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 460.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Wyrm.Ink)
            Text(body, fontFamily = Wyrm.Body, fontSize = 14.sp, color = Wyrm.Mute, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

/** Saved teams, Team mode off: connect again (or pick which one). */
@Composable
private fun Reconnect() {
    Column(
        Modifier.width(440.dp).clip(wyrmRounded(24.dp)).background(Wyrm.Card).border(1.dp, Wyrm.Rule, wyrmRounded(24.dp)).padding(26.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Team mode is off", fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Wyrm.Ink)
        Text("Pick a team to connect.", fontFamily = Wyrm.Body, fontSize = 13.5.sp, color = Wyrm.Mute)
        DesktopTeam.teams.forEachIndexed { index, team ->
            MenuItem(team.name.ifBlank { "Team ${index + 1}" }, checked = index == DesktopTeam.active) {
                DesktopTeam.applyEnabled(true)
                DesktopTeam.selectTeam(index)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Connect", filled = true) { DesktopTeam.applyEnabled(true) }
            Pill("Add a team", filled = false) { DesktopTeam.adding = true }
        }
    }
}

/** The team's name, Team ID and Auth key, in one card. */
@Composable
private fun ConnectCard(canCancel: Boolean) {
    var name by remember { mutableStateOf("") }
    var teamId by remember { mutableStateOf("") }
    var authKey by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    val ready = teamId.trim().length >= 16 && authKey.trim().length >= 16
    Column(
        Modifier.width(460.dp).clip(wyrmRounded(24.dp)).background(Wyrm.Card).border(1.dp, Wyrm.Rule, wyrmRounded(24.dp)).padding(26.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(if (canCancel) "Add a team" else "No team added yet", fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = Wyrm.Ink)
        Text(
            "Enter your team's name, Team ID and Auth key. They stay on this computer only, sealed to your Windows account.",
            fontFamily = Wyrm.Body, fontSize = 13.sp, color = Wyrm.Mute,
        )
        PaperField(name, { name = it.take(48) }, "Team name (only you see it)")
        PaperField(teamId, { teamId = it.take(2048) }, "Team ID")
        PaperField(authKey, { authKey = it.take(2048) }, "Auth key", secret = true)
        (problem ?: DesktopTeam.state.status.takeIf { it.isNotBlank() && !DesktopTeam.state.connected && DesktopTeam.teams.isEmpty() })?.let {
            Text(it, fontFamily = Wyrm.Body, fontSize = 12.5.sp, color = Wyrm.Badge)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Connect", filled = true, enabled = ready) {
                problem = DesktopTeam.addTeam(name.trim(), teamId.trim(), authKey.trim())
                if (problem == null) { name = ""; teamId = ""; authKey = ""; DesktopTeam.adding = false }
            }
            if (canCancel) Pill("Cancel", filled = false) { DesktopTeam.adding = false }
        }
        Text("Team ID and Auth key are at least 16 characters each.", fontFamily = Wyrm.Body, fontSize = 11.5.sp, color = Wyrm.Quiet)
    }
}

@Composable
private fun RosterColumn() {
    val state = DesktopTeam.state
    IosSectionLabel("Roster")
    if (state.members.isEmpty()) {
        IosPaperCard {
            IosListRow(
                title = "Nobody online yet",
                detail = "Teammates appear here, and on your minimap, as they play.",
                showsChevron = false,
            )
        }
        return
    }
    IosPaperCard {
        state.members.forEachIndexed { index, member ->
            if (index > 0) Box(Modifier.fillMaxWidth().padding(start = 35.dp).height(1.dp).background(Wyrm.RowRule))
            RosterRow(member, sameArena = member.arena.equals(Engine.savedArena, ignoreCase = true)) {
                if (member.playing) Engine.play(Engine.savedNickname, member.arena)
            }
        }
    }
    SettingsCaption("A green dot is in your arena. Click a teammate who is playing to join their arena.")
}

/** Android's RosterRow: name, key owner and arena; FPS, ping and leaderboard place. */
@Composable
private fun RosterRow(member: TeamMember, sameArena: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 62.dp)
            .then(if (member.playing) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (sameArena) Wyrm.Live else Wyrm.Quiet.copy(alpha = 0.3f)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(member.name, fontFamily = Wyrm.Body, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, color = Wyrm.Ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    member.owner.takeIf { it.isNotBlank() }?.let { "Key $it" },
                    if (member.playing) "Arena ${member.arena}" else "In the menu",
                    member.version.takeIf { it.isNotBlank() }?.let { "v$it" },
                    "bot".takeIf { member.bot },
                ).joinToString(" · "),
                fontFamily = Wyrm.Body, fontSize = 11.sp, color = Wyrm.Quiet, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (member.playing && member.score > 0) Stat("%,d".format(member.score))
            member.fps?.let { Stat("$it fps") }
            member.ping?.let { Stat("$it ms") }
            if (member.rank > 0) Stat("#${member.rank}", strong = true)
        }
    }
}

@Composable
private fun Stat(text: String, strong: Boolean = false) {
    Text(
        text,
        fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 10.sp,
        style = TextStyle(fontFeatureSettings = "tnum"),
        color = if (strong) Wyrm.OnInk else Wyrm.Ink,
        modifier = Modifier.clip(CircleShape).background(if (strong) Wyrm.Ink else Wyrm.Well).padding(horizontal = 7.dp, vertical = 4.dp),
    )
}

/** Team chat: the transcript, newest at the bottom, and the box under it. */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ChatColumn() {
    val messages = DesktopTeam.messages + listOfNotNull(DesktopTeam.pendingMessage)
    val me = Engine.savedNickname.trim()
    val list = rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) list.animateScrollToItem(messages.lastIndex) }
    IosSectionLabel("Team chat")
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .weight(1f)
            .clip(wyrmRounded(17.dp))
            .background(Wyrm.Card.copy(alpha = 0.92f))
            .border(1.dp, Wyrm.Rule, wyrmRounded(17.dp)),
    ) {
        if (messages.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No Team messages yet", fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Wyrm.Ink)
                Text("Messages from your connected NTL Team appear here.", fontFamily = Wyrm.Body, fontSize = 12.5.sp, color = Wyrm.Mute)
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
                itemsIndexed(messages, key = { index, m -> "$index-${m.from.hashCode()}-${m.text.hashCode()}" }) { _, message ->
                    val mine = message.from.equals(me, ignoreCase = true)
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                        if (!mine) Text(message.from, fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Wyrm.Quiet)
                        Text(
                            message.text,
                            fontFamily = Wyrm.Body, fontSize = 14.sp,
                            color = if (mine) Wyrm.OnInk else Wyrm.Ink,
                            modifier = Modifier.widthIn(max = 420.dp).clip(wyrmRounded(14.dp))
                                .background(if (mine) Wyrm.Ink else Wyrm.Well).padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Wyrm.RowRule))
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                PaperField(DesktopTeam.draft, { DesktopTeam.draft = it.replace("\n", "").take(280) }, "Message the team", onEnter = { DesktopTeam.send() }, enabled = DesktopTeam.running)
            }
            Spacer(Modifier.width(8.dp))
            Pill(if (DesktopTeam.sending) "Sending…" else "Send", filled = true, enabled = DesktopTeam.running && DesktopTeam.draft.isNotBlank() && !DesktopTeam.sending) { DesktopTeam.send() }
        }
    }
    SettingsCaption("A message goes out with the next team update, within four seconds.")
    if (DesktopTeam.sendError.isNotBlank()) SettingsCaption(DesktopTeam.sendError)
}

/**
 * The arena's team message box (D2): opened by Enter in an online match with a
 * team, or by the chat window's message box. Enter sends, Esc closes. While it
 * is open the keyboard is the box's, and the snake keeps following the mouse.
 */
@Composable
internal fun ArenaComposer(modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // The box writes to the tab the chat window shows: GLOBAL goes to Wyrm's
    // global chat, TEAM to the team (no team: global).
    val global = DesktopTeam.composerTab == 1
    val busy = if (global) DesktopArenaFeed.sending else DesktopTeam.sending
    val submit = {
        if (global) {
            DesktopArenaFeed.send(DesktopTeam.draft) { ok ->
                if (ok) { DesktopTeam.draft = ""; DesktopTeam.closeComposer() }
            }
        } else if (DesktopTeam.send()) DesktopTeam.closeComposer()
    }
    Column(modifier.widthIn(max = 620.dp)) {
    val error = if (global) DesktopArenaFeed.error else DesktopTeam.sendError
    if (error.isNotBlank()) Text(error, fontFamily = Wyrm.Body, color = Wyrm.Badge, modifier = Modifier.padding(12.dp))
    Row(
        Modifier
            .widthIn(max = 620.dp)
            .fillMaxWidth()
            .paperPanel(wyrmRounded(22.dp), fill = 0.95f)
            .padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (global) "GLOBAL" else "TEAM", fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 10.5.sp, color = Wyrm.Live)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            PaperField(
                value = DesktopTeam.draft,
                onChange = { DesktopTeam.draft = it.replace("\n", "").take(280) },
                hint = if (global) "Message everyone · Enter sends · Esc closes" else "Message the team · Enter sends · Esc closes",
                modifier = Modifier.focusRequester(focus),
                onEnter = { submit() },
                onEscape = { DesktopTeam.closeComposer() },
                onTab = { DesktopTeam.switchComposerTab() },
                framed = false,
            )
        }
        Spacer(Modifier.width(8.dp))
        Pill(if (busy) "Sending…" else "Send", filled = true, enabled = DesktopTeam.draft.isNotBlank() && !busy) { submit() }
    }
    }
}

/**
 * The chat box over a direct match (2026-11-05): the game is a native window
 * (DirectGameSurface), which Compose cannot draw over, so the box is its own
 * small borderless, see-through window owned by Wyrm's, at the same place
 * (bottom centre, 40 dp up, at most 620 dp wide). Esc or Send closes it.
 */
@Composable
internal fun ArenaComposerWindow() {
    val frame = DesktopWindow.frame ?: return
    val bounds = frame.bounds
    val width = minOf(620, bounds.width - 48).coerceAtLeast(240)
    val height = 120
    val state = androidx.compose.ui.window.rememberDialogState(
        position = androidx.compose.ui.window.WindowPosition(
            (bounds.x + (bounds.width - width) / 2).dp,
            (bounds.y + bounds.height - 40 - height).dp,
        ),
        size = androidx.compose.ui.unit.DpSize(width.dp, height.dp),
    )
    androidx.compose.ui.window.DialogWindow(
        onCloseRequest = { DesktopTeam.closeComposer() },
        state = state,
        title = "Wyrm chat",
        undecorated = true,
        transparent = true,
        resizable = false,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            ArenaComposer(Modifier.fillMaxWidth())
        }
    }
}

/** A one-line field in the app's paper style. */
@Composable
private fun PaperField(
    value: String,
    onChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    enabled: Boolean = true,
    framed: Boolean = true,
    onEnter: (() -> Unit)? = null,
    onEscape: (() -> Unit)? = null,
    onTab: (() -> Unit)? = null,
) {
    val shape = wyrmRounded(12.dp)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        singleLine = true,
        textStyle = TextStyle(fontFamily = Wyrm.Body, fontSize = 14.5.sp, color = Wyrm.Ink),
        cursorBrush = SolidColor(Wyrm.Ink),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    event.key == Key.Enter && onEnter != null -> { onEnter(); true }
                    event.key == Key.Escape && onEscape != null -> { onEscape(); true }
                    event.key == Key.Tab && onTab != null -> { onTab(); true }
                    else -> false
                }
            },
        decorationBox = { field ->
            Box(
                (if (framed) Modifier.clip(shape).background(Wyrm.Well).border(1.dp, Wyrm.Rule, shape) else Modifier)
                    .padding(horizontal = if (framed) 12.dp else 0.dp, vertical = 10.dp),
            ) {
                if (value.isEmpty()) Text(hint, fontFamily = Wyrm.Body, fontSize = 14.sp, color = Wyrm.Quiet, maxLines = 1, overflow = TextOverflow.Ellipsis)
                field()
            }
        },
    )
}

@Composable
private fun Pill(label: String, filled: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = wyrmRounded(999.dp)
    Box(
        Modifier
            .height(38.dp)
            .clip(shape)
            .background(if (filled) Wyrm.Ink.copy(alpha = if (enabled) 1f else 0.35f) else Wyrm.Well)
            .then(if (filled) Modifier else Modifier.border(1.dp, Wyrm.Rule, shape))
            .then(if (enabled) Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = if (filled) Wyrm.OnInk else Wyrm.Ink)
    }
}
