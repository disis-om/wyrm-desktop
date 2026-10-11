package com.wyrm.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
 * Home › Team mode on a computer (D2, OM 2026-10-11): Wyrm Android's Team
 * mode (TeamScreen / TeamIos: status, saved teams, connect form, live roster,
 * team chat) laid out for a window: the team on the left, the roster and the
 * chat side by side on the right. Same words and rules as the phones.
 */
@Composable
internal fun TeamPage(onBack: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(
        Modifier
            .fillMaxSize()
            .background(Wyrm.Paper)
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) { onBack(); true } else false
            }
            .focusable(),
    ) {
        // Header: back, title, the line under it.
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 28.dp, top = 22.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill("‹  Play", filled = false, onClick = onBack)
            Spacer(Modifier.width(16.dp))
            Column {
                Text("Team mode", fontFamily = Wyrm.Display, fontSize = 30.sp, color = Wyrm.Ink)
                Text("Your NTL team: who is where, and the team chat. Esc goes back.", fontFamily = Wyrm.Body, fontSize = 13.sp, color = Wyrm.Mute)
            }
        }
        Row(Modifier.fillMaxSize().padding(bottom = 18.dp)) {
            Column(Modifier.width(400.dp).fillMaxHeight().verticalScroll(rememberScrollState())) { TeamColumn() }
            Row(Modifier.weight(1f).fillMaxHeight().padding(end = 12.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) { RosterColumn() }
                Column(Modifier.weight(1.1f).fillMaxHeight()) { ChatColumn() }
            }
        }
    }
}

@Composable
private fun TeamColumn() {
    val running = DesktopTeam.running
    val state = DesktopTeam.state
    val teams = DesktopTeam.teams
    if (DesktopTeam.paused) {
        IosSectionLabel("Paused")
        IosPaperCard {
            IosListRow(
                title = "Team mode is paused",
                detail = "NTL services are switched off in this build: nothing is sent to or received from NTL. Your saved teams stay on this computer.",
                showsChevron = false,
            )
        }
        return
    }
    IosSectionLabel("Team mode")
    IosPaperCard {
        IosListRow(
            title = if (running) teams.getOrNull(DesktopTeam.active)?.name?.ifBlank { "Team" } ?: "Team" else "No team connected",
            detail = when {
                !running -> if (teams.isNotEmpty()) "Pick a saved team below." else "Use the Auth and Team ID from your NTL Team."
                state.connected -> "${state.members.size} members · NTL compatible"
                state.status.isNotBlank() && state.status != "Connected" -> state.status
                else -> "Joining your team"
            },
            value = when {
                !running -> ""
                state.connected -> "Live"
                state.status.isNotBlank() && state.status != "Connected" -> "Offline"
                else -> "Joining"
            },
            showsChevron = false,
        )
    }
    if (teams.isNotEmpty()) {
        Box(Modifier.padding(top = 10.dp)) {
            IosPaperCard {
                SettingsBoolRow(
                    title = "Team mode",
                    detail = "Teammates on your minimap and the team chat in the arena",
                    on = DesktopTeam.enabled,
                    first = true,
                    onToggle = DesktopTeam::applyEnabled,
                )
            }
        }
        IosSectionLabel("Saved teams")
        IosPaperCard {
            teams.forEachIndexed { index, team ->
                IosListRow(
                    title = team.name.ifBlank { "Team ${index + 1}" },
                    value = if (running && index == DesktopTeam.active) "✓" else "",
                    showsChevron = false,
                ) { DesktopTeam.selectTeam(index) }
            }
        }
        Box(Modifier.padding(top = 10.dp)) {
            IosPaperCard {
                SettingsActionRow("Add another team", first = true) { DesktopTeam.adding = true }
                if (running) SettingsActionRow("Remove this team", first = false, danger = true) { DesktopTeam.removeActiveTeam() }
            }
        }
    }
    if (teams.isEmpty() || DesktopTeam.adding) ConnectForm(canCancel = teams.isNotEmpty())
    Spacer(Modifier.height(24.dp))
}

/** Android's TeamConnectPage, as a card: name, Team ID, Auth key, Connect. */
@Composable
private fun ConnectForm(canCancel: Boolean) {
    var name by remember { mutableStateOf("") }
    var teamId by remember { mutableStateOf("") }
    var authKey by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf<String?>(null) }
    val ready = teamId.trim().length >= 16 && authKey.trim().length >= 16
    IosSectionLabel("Add a team")
    IosPaperCard {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Paste what the team service gave you", fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Wyrm.Ink)
            Text(
                "Team mode runs on the NTL service. The ID and key are kept on this computer only, sealed to your Windows account — they are never sent to Wyrm.",
                fontFamily = Wyrm.Body, fontSize = 12.5.sp, color = Wyrm.Mute,
            )
            PaperField(name, { name = it.take(48) }, "Name it — only you see this")
            PaperField(teamId, { teamId = it.take(2048) }, "Team ID · at least 16 characters")
            PaperField(authKey, { authKey = it.take(2048) }, "Auth key · at least 16 characters", secret = true)
            (problem ?: DesktopTeam.state.status.takeIf { it.isNotBlank() && !DesktopTeam.state.connected && DesktopTeam.teams.isEmpty() })?.let {
                Text(it, fontFamily = Wyrm.Body, fontSize = 12.5.sp, color = Wyrm.Badge)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canCancel) Pill("Cancel", filled = false) { DesktopTeam.adding = false }
                Pill("Connect", filled = true, enabled = ready) {
                    problem = DesktopTeam.addTeam(name.trim(), teamId.trim(), authKey.trim())
                    if (problem == null) { name = ""; teamId = ""; authKey = "" }
                }
            }
        }
    }
    SettingsCaption("No team yet? A team ID comes from whoever runs your squad's NTL server.")
}

@Composable
private fun RosterColumn() {
    val state = DesktopTeam.state
    IosSectionLabel("Live roster")
    if (!DesktopTeam.running || state.members.isEmpty()) {
        IosPaperCard {
            IosListRow(
                title = if (DesktopTeam.running) "Nobody online yet" else "No team connected",
                detail = if (DesktopTeam.running) "Teammates appear here, and on your minimap, as they play." else "Add a team to see who is where.",
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
                    if (member.playing) member.arena else "In menu",
                ).joinToString(" · "),
                fontFamily = Wyrm.Body, fontSize = 11.sp, color = Wyrm.Quiet, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            member.fps?.let { Stat("$it fps") }
            member.ping?.let { Stat("$it ms") }
            if (member.rank > 0) Stat("LB #${member.rank}", strong = true)
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
    val messages = DesktopTeam.messages
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
    Row(
        modifier
            .widthIn(max = 620.dp)
            .fillMaxWidth()
            .paperPanel(wyrmRounded(22.dp), fill = 0.95f)
            .padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("TEAM", fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 10.5.sp, color = Wyrm.Live)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            PaperField(
                value = DesktopTeam.draft,
                onChange = { DesktopTeam.draft = it.replace("\n", "").take(280) },
                hint = "Message the team · Enter sends · Esc closes",
                modifier = Modifier.focusRequester(focus),
                onEnter = {
                    DesktopTeam.send()
                    DesktopTeam.closeComposer()
                },
                onEscape = { DesktopTeam.closeComposer() },
                framed = false,
            )
        }
        Spacer(Modifier.width(8.dp))
        Pill("Send", filled = true, enabled = DesktopTeam.draft.isNotBlank()) {
            DesktopTeam.send()
            DesktopTeam.closeComposer()
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
