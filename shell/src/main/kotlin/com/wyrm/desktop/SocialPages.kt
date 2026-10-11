package com.wyrm.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wyrm.omrajput.data.FriendPresence
import com.wyrm.omrajput.ui.IosConnectionsScreen
import com.wyrm.omrajput.ui.IosEditProfileScreen
import com.wyrm.omrajput.ui.IosGlobalChatScreen
import com.wyrm.omrajput.ui.IosLeaderboardScreen
import com.wyrm.omrajput.ui.IosMessagesScreen
import com.wyrm.omrajput.ui.IosPageChrome
import com.wyrm.omrajput.ui.IosProfileScreen
import com.wyrm.omrajput.ui.IosSpinner
import com.wyrm.omrajput.ui.IosThreadScreen
import com.wyrm.omrajput.ui.LiquidSegmented
import com.wyrm.omrajput.ui.PhotoCropScreen
import com.wyrm.omrajput.ui.SocialRecent
import com.wyrm.omrajput.ui.SocialScreen
import com.wyrm.omrajput.ui.TrailDetailScreen
import com.wyrm.omrajput.ui.TrailsFeedScreen
import com.wyrm.omrajput.ui.TrailsStore
import com.wyrm.omrajput.ui.Wyrm
import com.wyrm.omrajput.ui.WyrmCapsule
import com.wyrm.omrajput.ui.wyrmRounded

/*
 * The pages Social opens (a player, editing your profile, a photo crop,
 * someone's connections, the trail studio), drawn by SocialWorkspace.kt in
 * the Social or Trails tab's main area. The screens are Wyrm Android's.
 */

internal val PageInsetTop = 6.dp
internal val PageInsetBottom = 18.dp

@Composable
internal fun SocialPage(page: Social.Page) {
    val me = Account.player
    when (page) {
        Social.Page.Leaderboard -> IosLeaderboardScreen(
            score = Social.scoreBoard,
            kills = Social.killBoard,
            sort = Social.boardSort,
            refreshing = Social.boardLoading,
            insetTop = PageInsetTop,
            insetBottom = PageInsetBottom,
            onSort = { Social.boardSort = it },
            onRefresh = { Social.refreshLeaderboards() },
            onBack = Social::back,
            onOpenPlayer = Social::openPlayer,
        )

        Social.Page.Messages -> IosMessagesScreen(
            conversations = Social.conversations,
            candidates = (Social.followers + Social.following).filter { it.canMessage }.distinctBy { it.id },
            refreshing = false,
            insetTop = PageInsetTop,
            insetBottom = PageInsetBottom,
            onRefresh = Social::refreshConversations,
            onBack = Social::back,
            onOpenThread = Social::openThread,
            activityOf = { FriendPresence.of(it) },
            onJoin = Social::joinFriendArena,
        )

        Social.Page.Global -> IosGlobalChatScreen(
            messages = Social.globalMessages,
            meId = me?.id.orEmpty(),
            draft = Social.draft,
            sending = Social.sending,
            error = Social.chatError,
            insetTop = PageInsetTop,
            insetBottom = PageInsetBottom,
            onDraftChange = { Social.draft = it },
            onSend = Social::sendGlobal,
            onBack = Social::back,
            onOpenAuthor = Social::openPlayer,
            onReport = { message, reason -> Social.reportMessage(message, reason) },
        )

        is Social.Page.Thread -> {
            val peer = Social.threadPlayer?.takeIf { it.id == page.player.id } ?: page.player
            IosThreadScreen(
                title = peer.displayName,
                messages = Social.threadMessages,
                meId = me?.id.orEmpty(),
                draft = Social.draft,
                sending = Social.sending,
                error = Social.chatError,
                insetTop = PageInsetTop,
                insetBottom = PageInsetBottom,
                onDraftChange = { Social.draft = it },
                onSend = Social::sendDirect,
                onBack = Social::back,
                peer = peer,
                onPeer = { Social.openPlayer(peer.id) },
                activity = FriendPresence.of(peer.id),
                onJoin = Social::joinFriendArena,
            )
        }

        is Social.Page.Player -> {
            LaunchedEffect(page.id) { Social.showPlayer(page.id) }
            val other = Social.viewedPlayer?.takeIf { it.id == page.id }
            IosProfileScreen(
                own = false,
                playerId = page.id,
                displayName = other?.displayName ?: "Player",
                handle = other?.handle.orEmpty(),
                bio = other?.bio.orEmpty(),
                avatarUrl = other?.avatarUrl.orEmpty(),
                avatarKey = other?.avatarKey ?: "mono-ink",
                score = other?.highestScore ?: 0,
                kills = other?.kills ?: 0,
                followers = other?.followerCount ?: 0,
                following = other?.followingCount ?: 0,
                isFollowing = other?.isFollowing == true,
                followsYou = other?.followsYou == true,
                canMessage = other?.canMessage == true,
                refreshing = false,
                insetTop = PageInsetTop,
                insetBottom = PageInsetBottom,
                onBack = Social::back,
                onEdit = {},
                onRefresh = { Social.refreshPlayer(page.id) },
                onFollowers = { Social.openConnections(page.id, "followers") },
                onFollowing = { Social.openConnections(page.id, "following") },
                onToggleFollow = Social::toggleFollow,
                onMessage = { other?.takeIf { it.canMessage }?.let { Social.openThread(it) } },
                onOpenTrail = Social::openTrail,
                onNewTrail = {},
                onShare = {},
                onChangePhoto = {},
                onRemovePhoto = {},
                ready = other != null,
                followBusy = Social.formBusy,
            )
        }

        Social.Page.Profile -> IosProfileScreen(
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
            insetTop = PageInsetTop,
            insetBottom = PageInsetBottom,
            onBack = Social::back,
            onEdit = Social::openEditProfile,
            onRefresh = { Social.refreshProfile(force = true) },
            onFollowers = { me?.let { Social.openConnections(it.id, "followers") } },
            onFollowing = { me?.let { Social.openConnections(it.id, "following") } },
            onToggleFollow = {},
            onMessage = {},
            onOpenTrail = Social::openTrail,
            onNewTrail = Social::openStudio,
            onShare = Social::shareProfile,
            onChangePhoto = { Social.pickPhoto(fromProfile = true) },
            onRemovePhoto = Social::removePhoto,
            photoBusy = Social.formBusy,
            photoError = Social.photoError,
        )

        Social.Page.EditProfile -> IosEditProfileScreen(
            displayName = me?.displayName.orEmpty(),
            ingameName = me?.ingameName.orEmpty(),
            username = me?.username.orEmpty(),
            bio = me?.bio.orEmpty(),
            avatarUrl = me?.avatarUrl.orEmpty(),
            avatarKey = me?.avatarKey ?: "mono-ink",
            renames = Social.renameAllowance?.let { "Renames left this month: display name ${it.displayName} · username ${it.username}" }.orEmpty(),
            busy = Social.formBusy,
            error = Social.formError,
            insetTop = PageInsetTop,
            insetBottom = PageInsetBottom,
            onBack = Social::back,
            onSave = { name, arena, user, about -> Social.saveProfile(name, arena, user, about) },
            onPickPhoto = { Social.pickPhoto(fromProfile = false) },
            onRemovePhoto = Social::removePhoto,
            onDeleteAccount = Social::deleteAccount,
        )

        is Social.Page.Crop -> PhotoCropScreen(
            photo = page.photo,
            busy = Social.formBusy,
            error = Social.formError,
            insetTop = PageInsetTop,
            insetBottom = PageInsetBottom,
            onCancel = Social::back,
            onConfirm = Social::uploadPhoto,
        )

        is Social.Page.Connections -> IosConnectionsScreen(
            followers = Social.connectionsFollowers,
            following = Social.connectionsFollowing,
            initialPage = if (page.kind == "following") 1 else 0,
            insetTop = PageInsetTop,
            insetBottom = PageInsetBottom,
            onBack = Social::back,
            onOpenPlayer = Social::openPlayer,
            activityOf = { FriendPresence.of(it) },
            onJoin = Social::joinFriendArena,
        )

        is Social.Page.Trail -> TrailDetailScreen(
            trailId = page.id,
            meId = me?.id.orEmpty(),
            insetTop = PageInsetTop,
            insetBottom = PageInsetBottom,
            onBack = Social::back,
            onAuthor = Social::openPlayer,
            onTrySkin = SkinTrial::open,
        )

        Social.Page.Studio -> DesktopTrailStudio(onClose = Social::back)

        Social.Page.VoiceSoon -> IosPageChrome(title = "Voice rooms", insetTop = PageInsetTop, onBack = Social::back) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Voice rooms", fontFamily = Wyrm.Display, fontSize = 34.sp, color = Wyrm.Ink)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Talking with your team is coming to Wyrm for computers. For now, join a room from Wyrm on your phone.",
                    fontFamily = Wyrm.Body, fontSize = 14.sp, color = Wyrm.Quiet, textAlign = TextAlign.Center,
                )
            }
        }
    }
}
