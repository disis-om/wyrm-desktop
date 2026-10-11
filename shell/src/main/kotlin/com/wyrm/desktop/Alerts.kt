package com.wyrm.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.wyrm.omrajput.data.NotificationKind
import com.wyrm.omrajput.data.WyrmNotification
import com.wyrm.omrajput.data.eventWhen
import com.wyrm.omrajput.data.hasStarted
import com.wyrm.omrajput.data.toWyrmNotification
import com.wyrm.omrajput.ui.IosEmptyPanel
import com.wyrm.omrajput.ui.IosGlyph
import com.wyrm.omrajput.ui.IosIcon
import com.wyrm.omrajput.ui.IosPaperCard
import com.wyrm.omrajput.ui.IosScreenHeader
import com.wyrm.omrajput.ui.Wyrm
import com.wyrm.omrajput.ui.WyrmCapsule
import com.wyrm.omrajput.ui.WyrmMarkdown
import com.wyrm.omrajput.ui.wyrmRounded
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Alerts (D4, OM 2026-10-11: "har notification ios ki trah, full md"): Wyrm
 * iOS's alert card, as Wyrm Android draws it (NotificationsScreen
 * `NotificationCard`): unread dot, the kind in tracked caps, the time and a ⋯
 * menu; the title; the body as full Markdown (CommonMark + GFM, Android's own
 * WyrmMarkdown); the detail rows; where a click leads. A Battledome event also
 * carries its countdown: clicking it plays that event's arena once it has
 * started, and before that shows the gate (Android's EventGateSheet). Only
 * here: the lobby's Play is never gated.
 */
@Composable
internal fun AlertsPanel(onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var gate by remember { mutableStateOf<WyrmNotification?>(null) }
    LaunchedEffect(Unit) { Account.refreshNotifications() }
    val alerts = Account.notifications.map { it.toWyrmNotification() }
    fun join(address: String) {
        if (address.isBlank()) return
        onClose()
        HomeState.selected = address
        startPlay(HomeState.nickname, address)
    }
    fun open(n: WyrmNotification) {
        if (!n.read) scope.launch { Account.setRead(n.id, true) }
        when (n.kind) {
            NotificationKind.EVENT -> {
                val address = n.serverAddress.orEmpty()
                if (address.isBlank()) return
                if (n.hasStarted()) join(address) else gate = n
            }
            // Android's openNotificationCard: the follower's profile, the trail, the board.
            NotificationKind.FOLLOW -> n.actorId?.takeIf { it.isNotBlank() }?.let { actor ->
                onClose()
                Social.start()
                Social.openPlayer(actor)
            }
            NotificationKind.TRAIL_LIKE, NotificationKind.TRAIL_REPLY -> n.trailId?.let { trail ->
                onClose()
                Social.start()
                Social.openTrail(trail)
                com.wyrm.omrajput.ui.TrailsStore.reload(trail)
            }
            NotificationKind.RANK -> {
                onClose()
                Social.start()
                Social.openLeaderboard()
            }
            else -> Unit
        }
    }
    GlassSheet(
        Modifier.width(440.dp).fillMaxHeight().padding(top = 10.dp, bottom = 10.dp, end = 10.dp),
        wyrmRounded(26.dp),
        onClose,
    ) {
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                IosScreenHeader(kicker = "Inbox", title = "Alerts") {
                    Text(
                        "Read all",
                        fontFamily = Wyrm.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Wyrm.Link,
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand).clickable { scope.launch { Account.markAllRead() } },
                    )
                }
            }
            if (alerts.isEmpty()) {
                item { IosPaperCard { IosEmptyPanel(title = "All caught up", note = "Nothing new right now.") } }
            }
            items(alerts, key = { it.id }) { n ->
                Box(Modifier.padding(horizontal = 16.dp)) {
                    AlertCard(
                        n,
                        onOpen = { open(n) },
                        onSetRead = { read -> scope.launch { Account.setRead(n.id, read) } },
                        onDelete = { scope.launch { Account.delete(n.id) } },
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
    gate?.let { event ->
        EventGate(
            event,
            onOkay = { gate = null },
            onJoinAnyway = {
                gate = null
                join(event.serverAddress.orEmpty())
            },
        )
    }
}

/** Android's NotificationCard (Wyrm iOS's alert card). */
@Composable
private fun AlertCard(n: WyrmNotification, onOpen: () -> Unit, onSetRead: (Boolean) -> Unit, onDelete: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var menu by remember { mutableStateOf(false) }
    val shape = wyrmRounded(17.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (hovered) Wyrm.Hover else Wyrm.Card.copy(alpha = 0.92f))
            .border(1.dp, Wyrm.Rule, shape)
            .hoverable(hover)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onOpen)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(WyrmCapsule).background(if (n.read) Color.Transparent else Wyrm.Live))
            Spacer(Modifier.width(8.dp))
            Text(alertLabel(n.kind), fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 9.5.sp, letterSpacing = 1.sp, color = Wyrm.Live)
            Spacer(Modifier.weight(1f))
            Text(n.timestamp.ifBlank { n.createdAt.take(10) }, fontFamily = Wyrm.Body, fontSize = 10.5.sp, color = Wyrm.Quiet)
            Box {
                Box(
                    Modifier.size(28.dp).clip(WyrmCapsule)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { menu = true },
                    contentAlignment = Alignment.Center,
                ) { IosIcon(IosGlyph.ELLIPSIS, Wyrm.Quiet, size = 18.dp) }
                if (menu) {
                    Popup(alignment = Alignment.TopEnd, onDismissRequest = { menu = false }, properties = PopupProperties(focusable = true)) {
                        Column(
                            Modifier.width(200.dp).paperPanel(wyrmRounded(14.dp), elevation = 12.dp).padding(vertical = 6.dp),
                        ) {
                            MenuRow(if (n.read) "Mark as unread" else "Mark as read", Wyrm.Ink) { menu = false; onSetRead(!n.read) }
                            MenuRow("Delete", Wyrm.Badge) { menu = false; onDelete() }
                        }
                    }
                }
            }
        }
        Text(n.title, fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Wyrm.Ink)
        if (n.body.isNotBlank()) WyrmMarkdown(source = n.body, baseSize = 12.5.sp)
        alertMeta(n).forEach { (key, value) ->
            Row {
                Text(key, fontFamily = Wyrm.Body, fontSize = 11.5.sp, color = Wyrm.Quiet)
                Spacer(Modifier.weight(1f))
                Text(value, fontFamily = Wyrm.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, color = Wyrm.Ink)
            }
        }
        if (n.kind == NotificationKind.EVENT && !n.serverAddress.isNullOrBlank()) {
            EventAction(n)
        }
    }
}

/** The event's action line: a live countdown, then "Play this event ›". */
@Composable
private fun EventAction(n: WyrmNotification) {
    var clock by remember(n.id) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(n.id) {
        while (true) {
            delay(1_000L - System.currentTimeMillis() % 1_000L)
            clock = System.currentTimeMillis()
        }
    }
    val started = n.hasStarted(java.time.Instant.ofEpochMilli(clock))
    Text(
        if (started) "Play this event ›" else "Starts in ${countdown(n.startsAt, clock)} ›",
        fontFamily = Wyrm.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp,
        color = if (started) Wyrm.Live else Wyrm.Link,
    )
}

private fun countdown(startsAt: String?, now: Long): String {
    val start = runCatching { java.time.Instant.parse(startsAt).toEpochMilli() }.getOrNull() ?: return ""
    val seconds = ((start - now) / 1000).coerceAtLeast(0)
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return when {
        h >= 24 -> "${h / 24}d ${h % 24}h"
        h > 0 -> "%dh %02dm".format(h, m)
        else -> "%d:%02d".format(m, s)
    }
}

@Composable
private fun MenuRow(label: String, colour: Color, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Text(
        label, fontFamily = Wyrm.Body, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = colour,
        modifier = Modifier.fillMaxWidth().background(if (hovered) Wyrm.Hover else Color.Transparent).hoverable(hover)
            .pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/** Android's EventGateSheet: one deliberate pause before an event that has not begun. */
@Composable
private fun EventGate(event: WyrmNotification, onOkay: () -> Unit, onJoinAnyway: () -> Unit) {
    Popup(alignment = Alignment.Center, onDismissRequest = onOkay, properties = PopupProperties(focusable = true)) {
        Column(Modifier.width(420.dp).paperPanel(wyrmRounded(24.dp), elevation = 26.dp).padding(22.dp)) {
            Text("BATTLEDOME", fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 10.5.sp, letterSpacing = 1.2.sp, color = Wyrm.Live)
            Spacer(Modifier.height(7.dp))
            Text("Hasn't started yet", fontFamily = Wyrm.Display, fontSize = 25.sp, color = Wyrm.Ink)
            Spacer(Modifier.height(9.dp))
            Text("${event.title} starts", fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Wyrm.Mute)
            eventWhen(event.startsAt).takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, fontFamily = Wyrm.Body, fontSize = 12.sp, color = Wyrm.Quiet)
            }
            Spacer(Modifier.height(20.dp))
            PaperButton("OK", primary = true, wide = true, onClick = onOkay)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { PaperButton("Cancel", primary = false, wide = true, onClick = onOkay) }
                Box(Modifier.weight(1f)) { PaperButton("Join anyway", primary = false, wide = true, onClick = onJoinAnyway) }
            }
        }
    }
}

/** The kind in tracked caps, as Wyrm iOS words it (`WyrmAlertRouting.label`). */
private fun alertLabel(kind: NotificationKind): String = when (kind) {
    NotificationKind.TRAIL_LIKE -> "BEAD ON YOUR TRAIL"
    NotificationKind.TRAIL_REPLY -> "TRAIL REPLY"
    NotificationKind.SUPPORT -> "FROM WYRM"
    NotificationKind.EVENT -> "BATTLEDOME EVENT"
    else -> kind.name.replace('_', ' ')
}

/** The detail rows iOS lists under an alert (Android's alertMeta). */
private fun alertMeta(n: WyrmNotification): List<Pair<String, String>> = buildList {
    val saysWho = n.kind == NotificationKind.TRAIL_LIKE || n.kind == NotificationKind.TRAIL_REPLY || n.kind == NotificationKind.SUPPORT
    if (!saysWho) n.actorName?.takeIf { it.isNotBlank() }?.let { add("From" to it) }
    n.eventOrganizer?.takeIf { it.isNotBlank() }?.let { add("Organizer" to it) }
    n.startsAt?.takeIf { it.isNotBlank() }?.let { add("Starts" to (eventWhen(it).ifBlank { it.replace('T', ' ').take(16) })) }
    n.serverAddress?.takeIf { it.isNotBlank() }?.let { add("Server" to it) }
    n.version?.takeIf { it.isNotBlank() }?.let { add("Version" to it) }
    n.value?.let { add("Value" to it.toString()) }
}.sortedBy { it.first }
