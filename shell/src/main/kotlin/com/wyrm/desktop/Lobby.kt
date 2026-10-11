package com.wyrm.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wyrm.omrajput.WyrmActivity
import com.wyrm.omrajput.data.ArenaDirectory
import com.wyrm.omrajput.ui.Wyrm
import com.wyrm.omrajput.ui.WyrmCapsule
import com.wyrm.omrajput.ui.WyrmMark
import com.wyrm.omrajput.ui.iosSpring
import com.wyrm.omrajput.ui.pressScale
import com.wyrm.omrajput.ui.wyrmRounded
import com.composables.icons.lucide.R as LucideR

/*
 * The Ready Room (Android's LobbyScreen) for a desktop: one centred column on
 * paper, with the curving W large and faint behind it. Home's arena card leads
 * here; nothing starts a match from Home. The selected arena (click to change:
 * the same picker popup), the name you play as, the last run, and the three
 * ways on: Home, Play with AI, PLAY. Enter plays, Esc goes home. Everything
 * arrives out of a blur, as the sign-in does.
 */

@Composable
fun DesktopLobby() {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(620, easing = FastOutSlowInEasing)) }
    var entering by remember { mutableStateOf(false) }
    // A refused or ignored entry comes back as a notice: the button is free again.
    LaunchedEffect(Engine.notice) { if (Engine.notice != null) entering = false }

    val arena = HomeState.arenas.firstOrNull { it.endpoint.equals(HomeState.selected, ignoreCase = true) }
    val ready = arena != null || ArenaDirectory.isValidEndpoint(HomeState.selected)
    fun play() {
        if (!ready || entering) return
        entering = true
        if (Engine.started) WyrmActivity.nativeSetNickname(HomeState.nickname)
        startPlay(HomeState.nickname, HomeState.selected)
    }
    fun playAi() {
        if (entering) return
        entering = true
        Engine.playWithAi(HomeState.nickname)
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || HomeState.pickerOpen) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Enter, Key.NumPadEnter -> { play(); true }
                    Key.Escape -> { HomeState.lobbyOpen = false; true }
                    else -> false
                }
            },
    ) {
        Box(Modifier.fillMaxSize().background(Wyrm.Paper), contentAlignment = Alignment.Center) {
            WyrmMark(modifier = Modifier.graphicsLayer { alpha = 0.045f }, size = 520.dp, ink = Wyrm.Ink, unfilled = Color.Transparent)
            Column(
                Modifier
                    .width(600.dp)
                    .graphicsLayer {
                        val p = appear.value
                        alpha = p
                        translationY = (1f - p) * 24.dp.toPx()
                        val r = 14.dp.toPx() * (1f - p)
                        renderEffect = if (r > 0.5f) BlurEffect(r, r, TileMode.Decal) else null
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("READY ROOM", fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 11.5.sp, letterSpacing = 1.6.sp, color = Wyrm.Quiet)
                Text("Enter the arena", fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 36.sp, letterSpacing = (-0.8).sp,
                    color = Wyrm.Ink, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(28.dp))
                ArenaCard(
                    address = HomeState.selected,
                    code = arena?.id?.takeIf { it >= 0 },
                    players = arena?.players,
                    onChange = { HomeState.pickerOpen = true },
                )
                Spacer(Modifier.height(14.dp))
                NameCard(HomeState.nickname, onName = {
                    HomeState.nickname = it.filterNot { c -> c.isISOControl() }.take(24)
                    DesktopContext.getSharedPreferences("wyrm_ui_preferences", 0).edit().putBoolean("nickname_chosen", true).apply()
                }, onDone = ::play)
                Engine.lastRun?.let { (score, kills, seconds) ->
                    Spacer(Modifier.height(14.dp))
                    LastRunStrip(score, kills, seconds)
                }
                Engine.notice?.let {
                    Text(it, fontFamily = Wyrm.Body, fontSize = 13.5.sp, color = Wyrm.Badge, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 14.dp))
                }
                Spacer(Modifier.height(30.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    LobbyButton("Home", LucideR.drawable.lucide_ic_house, width = 128.dp, enabled = !entering) { HomeState.lobbyOpen = false }
                    LobbyButton("Play with AI", LucideR.drawable.lucide_ic_bot, width = 170.dp, enabled = !entering, onClick = ::playAi)
                    PlayButton(entering = entering, enabled = ready && !entering, onClick = ::play)
                }
            }
        }
        // The arena picker opens here too, over the room.
        HomeOverlays()
    }
}

@Composable
private fun ArenaCard(address: String, code: Int?, players: Int?, onChange: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val shape = wyrmRounded(20.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .scale(pressScale(pressed))
            .clip(shape)
            .background(Wyrm.Card)
            .border(1.dp, if (hovered) Wyrm.Ink.copy(alpha = 0.25f) else Wyrm.Rule, shape)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onChange)
            .padding(horizontal = 26.dp, vertical = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("SELECTED ARENA", fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.3.sp,
                color = Wyrm.Quiet, modifier = Modifier.weight(1f))
            Text("Change", fontFamily = Wyrm.Body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Wyrm.Link)
        }
        Spacer(Modifier.height(8.dp))
        AnimatedContent(targetState = address, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) }, label = "arena") { shown ->
            Text(
                shown.ifBlank { "Pick an arena" },
                fontFamily = Wyrm.Display,
                fontSize = 36.sp,
                color = if (shown.isBlank()) Wyrm.Quiet else Wyrm.Ink,
                maxLines = 1,
            )
        }
        if (code != null || players != null) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                code?.let { Chip("Server code", it.toString()) }
                players?.let { Chip("Playing", it.toString()) }
            }
        }
    }
}

@Composable
private fun Chip(label: String, value: String) {
    Row(
        Modifier.clip(WyrmCapsule).background(Wyrm.Well).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label.uppercase(), fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.sp, color = Wyrm.Quiet)
        Text(value, fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Wyrm.Ink)
    }
}

@Composable
private fun NameCard(nickname: String, onName: (String) -> Unit, onDone: () -> Unit) {
    val shape = wyrmRounded(20.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Wyrm.Card)
            .border(1.dp, Wyrm.Rule, shape)
            .padding(horizontal = 26.dp, vertical = 18.dp),
    ) {
        Text("PLAYING AS", fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.3.sp, color = Wyrm.Quiet)
        Spacer(Modifier.height(6.dp))
        BasicTextField(
            value = nickname,
            onValueChange = onName,
            singleLine = true,
            textStyle = TextStyle(fontFamily = Wyrm.Display, fontSize = 32.sp, color = Wyrm.Ink),
            cursorBrush = SolidColor(Wyrm.Link),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onDone() }),
            decorationBox = { inner ->
                Box {
                    if (nickname.isEmpty()) Text("Wyrm Player", fontFamily = Wyrm.Display, fontSize = 32.sp, color = Wyrm.Faint)
                    inner()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun LastRunStrip(score: Int, kills: Int, seconds: Double) {
    val total = seconds.toLong()
    Row(
        Modifier.fillMaxWidth().clip(wyrmRounded(16.dp)).background(Wyrm.Well).padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("LAST RUN", fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 10.5.sp, letterSpacing = 1.2.sp,
            color = Wyrm.Quiet, modifier = Modifier.weight(1f))
        Stat("Score", "%,d".format(score))
        Spacer(Modifier.width(22.dp))
        Stat("Kills", kills.toString())
        Spacer(Modifier.width(22.dp))
        Stat("Time", "${total / 60}:${(total % 60).toString().padStart(2, '0')}")
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontFamily = Wyrm.Body, fontSize = 12.5.sp, color = Wyrm.Mute)
        Text(value, fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Wyrm.Ink)
    }
}

@Composable
private fun LobbyButton(label: String, icon: Int, width: Dp, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .width(width)
            .height(54.dp)
            .scale(pressScale(pressed, enabled))
            .clip(WyrmCapsule)
            .background(if (hovered && enabled) Wyrm.Well else Wyrm.Card)
            .border(1.dp, Wyrm.Rule, WyrmCapsule)
            .hoverable(interaction)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, tint = if (enabled) Wyrm.Ink else Wyrm.Quiet, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 14.5.sp, color = if (enabled) Wyrm.Ink else Wyrm.Quiet)
    }
}

@Composable
private fun PlayButton(entering: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .width(220.dp)
            .height(58.dp)
            .scale(pressScale(pressed, enabled))
            .clip(WyrmCapsule)
            .background(
                when {
                    !enabled && !entering -> Wyrm.Ink.copy(alpha = 0.35f)
                    hovered -> Wyrm.Ink.copy(alpha = 0.88f)
                    else -> Wyrm.Ink
                },
            )
            .hoverable(interaction)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(targetState = entering, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(140)) }, label = "play") { busy ->
            Text(
                if (busy) "Entering…" else "PLAY",
                fontFamily = Wyrm.Body,
                fontWeight = FontWeight.Bold,
                fontSize = if (busy) 15.sp else 16.sp,
                letterSpacing = if (busy) 0.3.sp else 2.4.sp,
                color = Wyrm.OnInk,
            )
        }
    }
}
