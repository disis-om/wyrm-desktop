package com.wyrm.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.wyrm.omrajput.data.Trail
import com.wyrm.omrajput.data.TrailComment
import com.wyrm.omrajput.ui.IosChatComposer
import com.wyrm.omrajput.ui.IosGlyph
import com.wyrm.omrajput.ui.IosIcon
import com.wyrm.omrajput.ui.IosSpinner
import com.wyrm.omrajput.ui.TrailBead
import com.wyrm.omrajput.ui.TrailImages
import com.wyrm.omrajput.ui.TrailsStore
import com.wyrm.omrajput.ui.Wyrm
import com.wyrm.omrajput.ui.WyrmAvatar
import com.wyrm.omrajput.ui.clipTime
import com.wyrm.omrajput.ui.trailTime
import com.wyrm.omrajput.ui.wyrmRounded

/*
 * A trail in its own window over the page (OM, 2026-10-12): the way a post
 * opens on Instagram's web. The picture on the left, as large as the window
 * allows and never cropped; on the right the poster, the caption, the bead,
 * the replies and the reply box. Esc, the ✕ or a click outside closes it.
 * Everything it does goes through Android's TrailsStore (likes, replies,
 * deleting your reply), so the feed card behind it changes with it.
 */

@Composable
internal fun TrailWindowHost() {
    val id = Social.modalTrail
    var shown by remember { mutableStateOf<String?>(null) }
    if (id != null) shown = id
    AnimatedVisibility(
        visible = id != null,
        enter = fadeIn(tween(180)),
        exit = fadeOut(tween(160)),
    ) {
        val trailId = shown ?: return@AnimatedVisibility
        val focus = remember { FocusRequester() }
        LaunchedEffect(trailId) {
            if (TrailsStore.trail(trailId) == null) TrailsStore.reload(trailId)
            TrailsStore.loadComments(trailId)
            runCatching { focus.requestFocus() }
        }
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.58f))
                .clickable(remember { MutableInteractionSource() }, null) { Social.closeTrail() }
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) { Social.closeTrail(); true } else false
                },
            contentAlignment = Alignment.Center,
        ) {
            val w = (maxWidth * 0.9f).coerceAtMost(1180.dp)
            val h = (maxHeight * 0.88f).coerceAtMost(860.dp)
            AnimatedVisibility(
                visible = true,
                enter = scaleIn(tween(220), initialScale = 0.96f) + fadeIn(tween(200)),
                exit = scaleOut(tween(160), targetScale = 0.97f),
            ) {
                Row(
                    Modifier
                        .size(w, h)
                        .clip(wyrmRounded(22.dp))
                        .background(Wyrm.Paper)
                        // A click on the window itself stays in it.
                        .clickable(remember { MutableInteractionSource() }, null) {},
                ) {
                    val trail = TrailsStore.trail(trailId)
                    Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFF111111)), contentAlignment = Alignment.Center) {
                        if (trail == null) IosSpinner(size = 22.dp, colour = Color.White) else TrailMedia(trail)
                    }
                    TrailSide(trailId, trail, Modifier.width(400.dp).fillMaxHeight())
                }
            }
            // Close, in the window's corner.
            Box(
                Modifier.align(Alignment.TopEnd).padding(18.dp).size(38.dp).clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.16f))
                    .clickable { Social.closeTrail() },
                contentAlignment = Alignment.Center,
            ) { IosIcon(IosGlyph.XMARK, Color.White, size = 16.dp, semibold = true) }
        }
    }
}

/** The picture (or the poster of a clip), fitted; a text trail as large type on paper. */
@Composable
private fun TrailMedia(trail: Trail) {
    val photo = trail.photo
    if (photo == null) {
        Box(Modifier.fillMaxSize().background(Wyrm.Card).padding(48.dp), contentAlignment = Alignment.Center) {
            Text(trail.caption, fontFamily = Wyrm.Display, fontSize = 34.sp, lineHeight = 42.sp, color = Wyrm.Ink, textAlign = TextAlign.Center)
        }
        return
    }
    var image by remember(photo.url) { mutableStateOf<ImageBitmap?>(TrailImages.peek(photo.url)) }
    LaunchedEffect(photo.url) {
        if (image == null) trail.thumbUrl?.let { thumb -> TrailImages.load(thumb)?.let { if (image == null) image = it } }
        TrailImages.load(photo.url)?.let { image = it }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        image?.let { Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            ?: IosSpinner(size = 22.dp, colour = Color.White)
        trail.video?.let { clip ->
            Row(
                Modifier.align(Alignment.TopStart).padding(14.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("▶  " + clipTime(clip.durationMs), fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Color.White)
            }
        }
    }
}

/** The right side: who, what they said, the bead, the replies, the reply box. */
@Composable
private fun TrailSide(trailId: String, trail: Trail?, modifier: Modifier) {
    val me = Account.player?.id.orEmpty()
    val replies = TrailsStore.comments[trailId].orEmpty()
    var draft by remember(trailId) { mutableStateOf("") }
    var sending by remember(trailId) { mutableStateOf(false) }
    Column(modifier) {
        if (trail != null) {
            Row(
                Modifier.fillMaxWidth().clickable { Social.closeTrail(); Social.openPlayer(trail.author.playerId) }
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                WyrmAvatar(trail.author.avatarUrl, trail.author.avatarKey, trail.author.initials, 38.dp)
                Column(Modifier.weight(1f)) {
                    Text(trail.author.name, fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 14.5.sp, color = Wyrm.Ink, maxLines = 1)
                    Text(listOf(trail.author.handle, trailTime(trail.createdAt)).filter { it.isNotBlank() }.joinToString(" · "),
                        fontFamily = Wyrm.Body, fontSize = 12.sp, color = Wyrm.Quiet, maxLines = 1)
                }
            }
        }
        // The poster shared their skin: try it on the Skin tab (Android's "Try this skin").
        if (trail?.skin != null) {
            Row(
                Modifier.padding(start = 18.dp, bottom = 12.dp).clip(CircleShape).background(Wyrm.Ink)
                    .clickable { SkinTrial.open(trail) }.padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Try this skin", fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 12.5.sp, color = Wyrm.OnInk)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Wyrm.Rule))
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            if (trail != null && trail.photo != null && trail.caption.isNotBlank()) {
                item(key = "caption") {
                    Text(trail.caption, fontFamily = Wyrm.Body, fontSize = 14.5.sp, lineHeight = 20.sp, color = Wyrm.Ink,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp))
                }
            }
            item(key = "label") {
                Text("REPLIES", fontFamily = Wyrm.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.9.sp,
                    color = Wyrm.Quiet, modifier = Modifier.padding(start = 18.dp, top = 12.dp, bottom = 6.dp))
            }
            if (replies.isEmpty()) {
                item(key = "none") {
                    Text("No replies yet. Be the first to say something.", fontFamily = Wyrm.Body, fontSize = 13.sp, color = Wyrm.Quiet,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp))
                }
            }
            items(replies, key = { it.id }) { reply ->
                ReplyRow(reply, canDelete = reply.mine || trail?.mine == true, isAuthor = reply.author.playerId == trail?.author?.playerId) {
                    TrailsStore.deleteReply(trailId, reply.id)
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Wyrm.Rule))
        if (trail != null) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TrailBead(trail.liked, trail.likeCount) { TrailsStore.toggleLike(trail.id) }
                Text(if (trail.commentCount == 1) "1 reply" else "${trail.commentCount} replies",
                    fontFamily = Wyrm.Body, fontSize = 12.5.sp, color = Wyrm.Quiet)
            }
        }
        IosChatComposer(
            text = draft,
            onText = { draft = it.take(300) },
            placeholder = "Reply to this trail",
            limit = 300,
            sending = sending,
            bottomInset = 4.dp,
            backdrop = rememberLayerBackdrop(),
        ) {
            val body = draft.trim()
            if (body.isNotEmpty() && !sending && me.isNotEmpty()) {
                sending = true
                TrailsStore.reply(trailId, body) { ok -> if (ok) draft = ""; sending = false }
            }
        }
    }
}

@Composable
private fun ReplyRow(reply: TrailComment, canDelete: Boolean, isAuthor: Boolean, onDelete: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().hoverable(interaction).padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.clickable { Social.closeTrail(); Social.openPlayer(reply.author.playerId) }) {
            WyrmAvatar(reply.author.avatarUrl, reply.author.avatarKey, reply.author.initials, 30.dp)
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(reply.author.name, fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Wyrm.Ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (isAuthor) {
                    Text("Author", fontFamily = Wyrm.Body, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, color = Wyrm.Quiet,
                        modifier = Modifier.clip(CircleShape).border(1.dp, Wyrm.Rule, CircleShape).padding(horizontal = 6.dp, vertical = 1.dp))
                }
                Text(trailTime(reply.createdAt), fontFamily = Wyrm.Body, fontSize = 11.sp, color = Wyrm.Quiet)
            }
            Text(reply.body, fontFamily = Wyrm.Body, fontSize = 13.5.sp, lineHeight = 18.sp, color = Wyrm.Ink)
        }
        if (canDelete && hovered) {
            Text("Delete", fontFamily = Wyrm.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Wyrm.Badge,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onDelete).padding(horizontal = 6.dp, vertical = 2.dp))
        }
    }
}
