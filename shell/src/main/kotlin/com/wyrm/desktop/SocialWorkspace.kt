package com.wyrm.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wyrm.omrajput.data.ApiPlayer
import com.wyrm.omrajput.data.Conversation
import com.wyrm.omrajput.data.FriendPresence
import com.wyrm.omrajput.ui.FriendActivityLine
import com.wyrm.omrajput.ui.IosConnectionsScreen
import com.wyrm.omrajput.ui.IosGlobalChatScreen
import com.wyrm.omrajput.ui.IosGlyph
import com.wyrm.omrajput.ui.IosIcon
import com.wyrm.omrajput.ui.IosLeaderboardScreen
import com.wyrm.omrajput.ui.IosProfileScreen
import com.wyrm.omrajput.ui.IosThreadScreen
import com.wyrm.omrajput.ui.LocalPageChromeHidden
import com.wyrm.omrajput.ui.TrailsFeedScreen
import com.wyrm.omrajput.ui.Wyrm
import com.wyrm.omrajput.ui.WyrmAvatar
import com.wyrm.omrajput.ui.WyrmCapsule
import com.wyrm.omrajput.ui.WyrmCountBadge
import com.wyrm.omrajput.ui.WyrmMark
import com.wyrm.omrajput.ui.pressScale
import com.wyrm.omrajput.ui.shortSpan
import com.wyrm.omrajput.ui.wyrmRounded

/*
 * Social and Trails laid out for a computer (OM, 2026-10-12: "it looked like a
 * phone in the middle of the screen"). The pattern big-screen social apps use
 * (Instagram on the web since 2022, Apple's multi-column guidance):
 *
 *  - Social: a fixed left sidebar is the navigation (Android's Social hub
 *    rows, with their red counts), and the section fills the rest. Messages
 *    is a two-pane inbox: conversations on the left, the open thread on the
 *    right (the thread never closes to switch).
 *  - Trails: one readable feed column (470 dp, Instagram's) and a right rail
 *    for what is secondary: you, friends playing now, the top of the board.
 *    A trail opens in its own window over the page (TrailWindow.kt).
 *  - A player, Edit profile, a crop, the studio: pages that open in the same
 *    main area with Back, sliding in; picking a section closes them.
 *
 * The lists, chats and profiles inside are Wyrm Android's own screens (their
 * phone header turned off where the desktop draws its own).
 */

private val SidebarWidth = 268.dp
private val RailWidth = 300.dp
private val FeedWidth = 470.dp

/* ---------------------------------------------------------------- Social */

/** Social tab: sidebar | section (or an open page). */
@Composable
internal fun SocialTabPage() {
    LaunchedEffect(Unit) { Social.start() }
    LaunchedEffect(Social.section, ShellNav.tab.value) { Social.onScreenChanged() }
    Row(Modifier.fillMaxSize().padding(top = TopTabsClearance - 6.dp)) {
        SocialSidebar(Modifier.width(SidebarWidth).fillMaxHeight())
        Box(Modifier.padding(vertical = 14.dp).width(1.dp).fillMaxHeight().background(Wyrm.Rule))
        Box(Modifier.weight(1f).fillMaxHeight()) { MainArea { SectionContent(Social.section) } }
    }
}

/**
 * The main area: the section, or the page opened over it. Pages slide in from
 * the right and back out to it; a new section rises in softly.
 */
@Composable
private fun MainArea(section: @Composable () -> Unit) {
    val top = Social.top
    val key: Any = top?.let { Social.pages.size to it } ?: Social.section
    AnimatedContent(
        targetState = key,
        transitionSpec = {
            val pageIn = targetState is Pair<*, *>
            val pageOut = initialState is Pair<*, *>
            val deeper = pageIn && (!pageOut || (targetState as Pair<*, *>).first as Int > (initialState as Pair<*, *>).first as Int)
            when {
                pageIn || pageOut -> {
                    val dir = if (deeper) 1 else -1
                    (fadeIn(tween(220)) + slideInHorizontally(tween(280)) { dir * it / 9 }) togetherWith
                        (fadeOut(tween(150)) + slideOutHorizontally(tween(280)) { -dir * it / 14 })
                }
                else -> (fadeIn(tween(240)) + slideInVertically(tween(280)) { it / 24 }) togetherWith fadeOut(tween(140))
            }
        },
        label = "social-main",
    ) { shown ->
        if (shown is Pair<*, *>) {
            val page = shown.second as Social.Page
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = 920.dp).fillMaxSize()) { SocialPage(page) }
            }
        } else {
            section()
        }
    }
}

/** The left sidebar: the sections with their counts, then who you played with. */
@Composable
private fun SocialSidebar(modifier: Modifier) {
    val unreadKind = { kind: String -> Account.notifications.count { !it.read && it.kind == kind } }
    val me = Account.player
    Column(modifier.padding(start = 18.dp, end = 12.dp)) {
        Text("Social", fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = (-0.5).sp,
            color = Wyrm.Ink, modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 12.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SidebarRow(Social.Section.MESSAGES, IosGlyph.MESSAGE, Social.unreadDm.toInt(),
                if (Social.conversations.isEmpty()) "Direct with friends" else "${Social.conversations.size} conversations")
            SidebarRow(Social.Section.GLOBAL, IosGlyph.BUBBLES, Social.globalUnread, "Everyone on Wyrm")
            SidebarRow(Social.Section.LEADERBOARD, IosGlyph.TROPHY, 0,
                Social.scoreBoard.indexOfFirst { it.id == me?.id }.takeIf { it >= 0 }?.let { "You are #${it + 1}" } ?: "Score and kills")
            SidebarRow(Social.Section.CONNECTIONS, IosGlyph.PERSON_2, unreadKind("follow"),
                "${me?.followerCount ?: 0} followers · ${me?.followingCount ?: 0} following")
            SidebarRow(Social.Section.PROFILE, IosGlyph.PERSON_CIRCLE, 0, me?.handle.orEmpty().ifEmpty { "You" })
            // Voice is hidden on every app (OM, 2026-10-12; VOICE_ENABLED).
            if (com.wyrm.omrajput.data.VOICE_ENABLED) SidebarRow(Social.Section.VOICE, IosGlyph.MIC, unreadKind("voice_invite"), "Rooms and calls")
            val recents = remember(Social.conversations, Social.following) {
                (Social.conversations.map { it.player } + Social.following).distinctBy { it.id }
                    .filter { it.id != Account.player?.id }.take(6)
            }
            if (recents.isNotEmpty()) {
                SidebarLabel("Recently played with")
                recents.forEach { PersonRow(it, compact = true) { Social.openPlayer(it.id) } }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SidebarLabel(text: String) {
    Text(text.uppercase(), fontFamily = Wyrm.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.9.sp,
        color = Wyrm.Quiet, modifier = Modifier.padding(start = 12.dp, top = 22.dp, bottom = 6.dp))
}

@Composable
private fun SidebarRow(section: Social.Section, glyph: IosGlyph, badge: Int, detail: String) {
    val chosen = Social.top == null && Social.section == section
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val fill by animateColorAsState(
        when {
            chosen -> Wyrm.Card
            hovered -> Wyrm.Ink.copy(alpha = 0.04f)
            else -> Wyrm.Paper.copy(alpha = 0f)
        }, tween(160), label = "side-fill",
    )
    val edge by animateColorAsState(if (chosen) Wyrm.Rule else Wyrm.Rule.copy(alpha = 0f), tween(160), label = "side-edge")
    Row(
        Modifier
            .padding(vertical = 2.dp)
            .fillMaxWidth()
            .scale(pressScale(pressed))
            .clip(wyrmRounded(14.dp))
            .background(fill)
            .border(1.dp, edge, wyrmRounded(14.dp))
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null) { Social.openSection(section) }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(32.dp).clip(wyrmRounded(10.dp)).background(if (chosen) Wyrm.Ink else Wyrm.Well), contentAlignment = Alignment.Center) {
            IosIcon(glyph, if (chosen) Wyrm.OnInk else Wyrm.Ink, size = 17.dp, semibold = chosen)
        }
        Column(Modifier.weight(1f)) {
            Text(section.title, fontFamily = Wyrm.Body, fontWeight = if (chosen) FontWeight.Bold else FontWeight.SemiBold,
                fontSize = 14.5.sp, color = Wyrm.Ink, maxLines = 1)
            if (detail.isNotEmpty()) Text(detail, fontFamily = Wyrm.Body, fontSize = 11.5.sp, color = Wyrm.Quiet, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        WyrmCountBadge(badge)
    }
}

/** A person in a list: face (with the online dot), name, what they are doing. */
@Composable
private fun PersonRow(player: ApiPlayer, compact: Boolean, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val activity = FriendPresence.of(player.id)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(wyrmRounded(12.dp))
            .background(Wyrm.Ink.copy(alpha = if (hovered) 0.045f else 0f))
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = if (compact) 6.dp else 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        WyrmAvatar(player.avatarUrl, player.avatarKey, player.displayName.take(1), if (compact) 30.dp else 40.dp,
            online = activity?.online == true)
        Column(Modifier.weight(1f)) {
            Text(player.displayName, fontFamily = Wyrm.Body, fontWeight = FontWeight.SemiBold, fontSize = if (compact) 13.5.sp else 14.5.sp,
                color = Wyrm.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (activity != null) {
                FriendActivityLine(activity, fontSize = 11.sp, onJoin = Social::joinFriendArena)
            } else if (player.handle.isNotEmpty()) {
                Text(player.handle, fontFamily = Wyrm.Body, fontSize = 11.5.sp, color = Wyrm.Quiet, maxLines = 1)
            }
        }
        trailing?.invoke()
    }
}

/** A section's own title, where the phone had its "Back · Title" bar. */
@Composable
private fun SectionHeader(kicker: String, title: String, detail: String = "") {
    Column(Modifier.fillMaxWidth().padding(start = 4.dp, top = 12.dp, bottom = 10.dp)) {
        Text(kicker.uppercase(), fontFamily = Wyrm.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 1.sp, color = Wyrm.Quiet)
        Text(title, fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = (-0.4).sp, color = Wyrm.Ink)
        if (detail.isNotEmpty()) Text(detail, fontFamily = Wyrm.Body, fontSize = 13.5.sp, color = Wyrm.Mute, modifier = Modifier.padding(top = 2.dp))
    }
}

/** A section in a centred column of [width], under its own header. */
@Composable
private fun SectionColumn(width: Dp, kicker: String, title: String, detail: String = "", content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = width).fillMaxSize().padding(horizontal = 24.dp)) {
            SectionHeader(kicker, title, detail)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                CompositionLocalProvider(LocalPageChromeHidden provides true) { content() }
            }
        }
    }
}

@Composable
private fun SectionContent(section: Social.Section) {
    val me = Account.player
    when (section) {
        Social.Section.MESSAGES -> MessagesSection()
        Social.Section.GLOBAL -> SectionColumn(820.dp, "Social", "Global chat", "Everyone on Wyrm, live. Be kind.") {
            IosGlobalChatScreen(
                messages = Social.globalMessages,
                meId = me?.id.orEmpty(),
                draft = Social.draft,
                sending = Social.sending,
                error = Social.chatError,
                insetTop = 0.dp,
                insetBottom = 16.dp,
                onDraftChange = { Social.draft = it },
                onSend = Social::sendGlobal,
                onBack = {},
                onOpenAuthor = Social::openPlayer,
                onReport = { message, reason -> Social.reportMessage(message, reason) },
            )
        }
        Social.Section.LEADERBOARD -> SectionColumn(860.dp, "Arena", "Leaderboard", "The best runs and the most kills on Wyrm.") {
            IosLeaderboardScreen(
                score = Social.scoreBoard,
                kills = Social.killBoard,
                sort = Social.boardSort,
                refreshing = Social.boardLoading,
                insetTop = 0.dp,
                insetBottom = 16.dp,
                onSort = { Social.boardSort = it },
                onRefresh = { Social.refreshLeaderboards() },
                onBack = {},
                onOpenPlayer = Social::openPlayer,
            )
        }
        Social.Section.CONNECTIONS -> SectionColumn(820.dp, "People", "Connections", "Who follows you and who you follow.") {
            IosConnectionsScreen(
                followers = Social.followers,
                following = Social.following,
                initialPage = 0,
                insetTop = 0.dp,
                insetBottom = 16.dp,
                onBack = {},
                onOpenPlayer = Social::openPlayer,
                activityOf = { FriendPresence.of(it) },
                onJoin = Social::joinFriendArena,
            )
        }
        Social.Section.PROFILE -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = 935.dp).fillMaxSize().padding(horizontal = 16.dp)) {
                CompositionLocalProvider(LocalPageChromeHidden provides true) {
                    IosProfileScreen(
                        own = true,
                        playerId = me?.id.orEmpty(),
                        displayName = me?.displayName?.ifBlank { "Wyrm" } ?: "Wyrm",
                        handle = me?.handle.orEmpty(),
                        bio = me?.bio.orEmpty(),
                        avatarUrl = me?.avatarUrl.orEmpty(),
                        avatarKey = me?.avatarKey ?: "mono-ink",
                        score = me?.highestScore ?: 0,
                        kills = me?.kills ?: 0,
                        followers = me?.followerCount ?: 0,
                        following = me?.followingCount ?: 0,
                        isFollowing = false,
                        followsYou = false,
                        canMessage = false,
                        refreshing = Social.profileLoading,
                        insetTop = 8.dp,
                        insetBottom = 16.dp,
                        onBack = {},
                        onEdit = Social::openEditProfile,
                        onRefresh = { Social.refreshProfile(force = true) },
                        onFollowers = { Social.openSection(Social.Section.CONNECTIONS) },
                        onFollowing = { Social.openSection(Social.Section.CONNECTIONS) },
                        onToggleFollow = {},
                        onMessage = {},
                        onOpenTrail = Social::openTrail,
                        onNewTrail = { ShellNav.tab.value = Tab.Trails; Social.openStudio() },
                        onShare = Social::shareProfile,
                        onChangePhoto = { Social.pickPhoto(fromProfile = true) },
                        onRemovePhoto = Social::removePhoto,
                        photoBusy = Social.formBusy,
                        photoError = Social.photoError,
                    )
                }
            }
        }
        Social.Section.VOICE -> EmptyPane(
            title = "Voice rooms",
            body = "Talking with your team is coming to Wyrm for computers. For now, join a room from Wyrm on your phone.",
        )
    }
}

/** An empty pane: Wyrm's mark, a title, a line. */
@Composable
private fun EmptyPane(title: String, body: String) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        WyrmMark(size = 56.dp, ink = Wyrm.Ink.copy(alpha = 0.16f))
        Spacer(Modifier.height(16.dp))
        Text(title, fontFamily = Wyrm.Display, fontSize = 30.sp, color = Wyrm.Ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, fontFamily = Wyrm.Body, fontSize = 14.sp, color = Wyrm.Quiet, textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 420.dp))
    }
}

/* -------------------------------------------------------------- Messages */

/** The two-pane inbox: conversations on the left, the open thread on the right. */
@Composable
private fun MessagesSection() {
    val me = Account.player
    Row(Modifier.fillMaxSize()) {
        ConversationRail(Modifier.width(340.dp).fillMaxHeight())
        Box(Modifier.padding(vertical = 14.dp).width(1.dp).fillMaxHeight().background(Wyrm.Rule))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val peer = Social.threadPlayer
            AnimatedContent(
                targetState = peer?.id,
                transitionSpec = { (fadeIn(tween(200)) + slideInHorizontally(tween(240)) { it / 16 }) togetherWith fadeOut(tween(120)) },
                label = "thread",
            ) { id ->
                val shown = Social.threadPlayer?.takeIf { it.id == id }
                if (shown == null) {
                    EmptyPane("Your messages", "Pick a conversation on the left, or start one with a friend. Friends who follow each other can message.")
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        Box(Modifier.widthIn(max = 860.dp).fillMaxSize()) {
                            IosThreadScreen(
                                title = shown.displayName,
                                messages = Social.threadMessages,
                                meId = me?.id.orEmpty(),
                                draft = Social.draft,
                                sending = Social.sending,
                                error = Social.chatError,
                                insetTop = 0.dp,
                                insetBottom = 16.dp,
                                onDraftChange = { Social.draft = it },
                                onSend = Social::sendDirect,
                                onBack = Social::closeThread,
                                peer = shown,
                                onPeer = { Social.openPlayer(shown.id) },
                                activity = FriendPresence.of(shown.id),
                                onJoin = Social::joinFriendArena,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationRail(modifier: Modifier) {
    val candidates = remember(Social.followers, Social.following, Social.conversations) {
        val talking = Social.conversations.map { it.player.id }.toSet()
        (Social.followers + Social.following).filter { it.canMessage && it.id !in talking }.distinctBy { it.id }
    }
    Column(modifier.padding(horizontal = 12.dp)) {
        SectionHeader("Social", "Messages")
        LazyColumn(Modifier.weight(1f)) {
            if (Social.conversations.isEmpty() && candidates.isEmpty()) {
                item {
                    Text("No conversations yet. When you and a friend follow each other, you can message here.",
                        fontFamily = Wyrm.Body, fontSize = 13.sp, color = Wyrm.Quiet, modifier = Modifier.padding(8.dp))
                }
            }
            items(Social.conversations, key = { "c-" + it.player.id }) { row -> ConversationRow(row) }
            if (candidates.isNotEmpty()) {
                item(key = "label") { SidebarLabel("People you can message") }
                items(candidates, key = { "p-" + it.id }) { person ->
                    PersonRow(person, compact = false, trailing = {
                        IosIcon(IosGlyph.MESSAGE, Wyrm.Link, size = 16.dp)
                    }) { Social.openThread(person) }
                }
            }
            item { Spacer(Modifier.height(18.dp)) }
        }
    }
}

@Composable
private fun ConversationRow(row: Conversation) {
    val chosen = Social.threadPlayer?.id == row.player.id
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val fill by animateColorAsState(
        if (chosen) Wyrm.Card else Wyrm.Ink.copy(alpha = if (hovered) 0.045f else 0f), tween(150), label = "conv-fill",
    )
    val activity = FriendPresence.of(row.player.id)
    val ago = remember(row.lastAt) {
        runCatching { shortSpan(System.currentTimeMillis() - java.time.Instant.parse(row.lastAt).toEpochMilli()) }.getOrDefault("")
    }
    Row(
        Modifier
            .padding(vertical = 2.dp)
            .fillMaxWidth()
            .clip(wyrmRounded(14.dp))
            .background(fill)
            .border(1.dp, if (chosen) Wyrm.Rule else Wyrm.Rule.copy(alpha = 0f), wyrmRounded(14.dp))
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null) { Social.openThread(row.player) }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        WyrmAvatar(row.player.avatarUrl, row.player.avatarKey, row.player.displayName.take(1), 44.dp, online = activity?.online == true)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.player.displayName, fontFamily = Wyrm.Body, fontWeight = if (row.unread > 0) FontWeight.Bold else FontWeight.SemiBold,
                    fontSize = 14.5.sp, color = Wyrm.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (ago.isNotEmpty()) Text(ago, fontFamily = Wyrm.Body, fontSize = 11.sp, color = Wyrm.Quiet)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.lastMessage, fontFamily = Wyrm.Body, fontWeight = if (row.unread > 0) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 12.5.sp, color = if (row.unread > 0) Wyrm.Ink else Wyrm.Quiet, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                WyrmCountBadge(row.unread.toInt(), Modifier.padding(start = 6.dp))
            }
        }
    }
}

/* ---------------------------------------------------------------- Trails */

/** Trails tab: the feed column and, on a wide window, the right rail. Pages open in the same space. */
@Composable
internal fun TrailsTabPage() {
    LaunchedEffect(Unit) { Social.start() }
    LaunchedEffect(ShellNav.tab.value) { Social.onScreenChanged() }
    Box(Modifier.fillMaxSize().padding(top = TopTabsClearance - 6.dp)) {
        MainArea {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val rail = maxWidth >= FeedWidth + RailWidth + 120.dp
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center) {
                    Box(Modifier.width(FeedWidth).fillMaxHeight()) {
                        TrailsFeedScreen(
                            insetTop = 0.dp,
                            insetBottom = 18.dp,
                            onNew = Social::openStudio,
                            onOpen = Social::openTrail,
                            onAuthor = Social::openPlayer,
                            onTrySkin = SkinTrial::open,
                        )
                    }
                    if (rail) {
                        Spacer(Modifier.width(56.dp))
                        TrailsRail(Modifier.width(RailWidth).fillMaxHeight())
                    }
                }
            }
        }
    }
}

/** What is secondary to the feed: you, friends on Wyrm, the top of the board. */
@Composable
private fun TrailsRail(modifier: Modifier) {
    val me = Account.player
    Column(modifier.verticalScroll(rememberScrollState()).padding(top = 18.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (me != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.clip(wyrmRounded(14.dp)).clickable { Social.openProfile() }.padding(6.dp)) {
                WyrmAvatar(me.avatarUrl, me.avatarKey, me.displayName.take(1), 52.dp)
                Column(Modifier.weight(1f)) {
                    Text(me.displayName, fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Wyrm.Ink, maxLines = 1)
                    Text(me.handle, fontFamily = Wyrm.Body, fontSize = 12.5.sp, color = Wyrm.Quiet, maxLines = 1)
                }
            }
            RailButton("New trail", IosGlyph.PLUS, Social::openStudio)
        }
        val friends = remember(Social.following, Social.followers, FriendPresence.friends.toMap()) {
            (Social.following + Social.followers).distinctBy { it.id }
                .filter { FriendPresence.of(it.id)?.online == true }
                .sortedByDescending { if (FriendPresence.of(it.id)?.state == "playing") 1 else 0 }
                .take(6)
        }
        RailCard("Friends on Wyrm") {
            if (friends.isEmpty()) {
                Text("No friends online right now.", fontFamily = Wyrm.Body, fontSize = 12.5.sp, color = Wyrm.Quiet,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
            } else {
                friends.forEach { PersonRow(it, compact = true) { Social.openPlayer(it.id) } }
            }
        }
        RailCard("Top players", action = "See all", onAction = Social::openLeaderboard) {
            Social.scoreBoard.take(5).forEachIndexed { index, player ->
                val interaction = remember { MutableInteractionSource() }
                val hovered by interaction.collectIsHoveredAsState()
                Row(
                    Modifier.fillMaxWidth().clip(wyrmRounded(10.dp)).background(Wyrm.Ink.copy(alpha = if (hovered) 0.045f else 0f))
                        .hoverable(interaction).clickable(interactionSource = interaction, indication = null) { Social.openPlayer(player.id) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("${index + 1}", fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 12.5.sp, color = Wyrm.Quiet,
                        modifier = Modifier.width(16.dp))
                    WyrmAvatar(player.avatarUrl, player.avatarKey, player.displayName.take(1), 28.dp)
                    Text(player.displayName, fontFamily = Wyrm.Body, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = Wyrm.Ink,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("%,d".format(player.highestScore), fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 12.5.sp, color = Wyrm.Ink)
                }
            }
        }
        Text("Wyrm · Trails", fontFamily = Wyrm.Body, fontSize = 11.5.sp, color = Wyrm.Quiet, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun RailCard(title: String, action: String = "", onAction: () -> Unit = {}, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title.uppercase(), fontFamily = Wyrm.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.9.sp,
                color = Wyrm.Quiet, modifier = Modifier.weight(1f))
            if (action.isNotEmpty()) {
                Text(action, fontFamily = Wyrm.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Wyrm.Link,
                    modifier = Modifier.clip(WyrmCapsule).clickable(onClick = onAction).padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
        Column(Modifier.fillMaxWidth().clip(wyrmRounded(18.dp)).background(Wyrm.Card).border(1.dp, Wyrm.Rule, wyrmRounded(18.dp)).padding(6.dp)) {
            content()
        }
    }
}

@Composable
private fun RailButton(label: String, glyph: IosGlyph, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().height(42.dp).scale(pressScale(pressed)).clip(WyrmCapsule)
            .background(if (hovered) Wyrm.Ink.copy(alpha = 0.86f) else Wyrm.Ink)
            .hoverable(interaction).clickable(interactionSource = interaction, indication = null, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IosIcon(glyph, Wyrm.OnInk, size = 15.dp, semibold = true)
        Spacer(Modifier.width(8.dp))
        Text(label, fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Wyrm.OnInk)
    }
}

/** Kept for Shell.kt: Social's pages now live inside the tabs; only the trail window floats. */
@Composable
internal fun SocialPagesHost() = TrailWindowHost()
