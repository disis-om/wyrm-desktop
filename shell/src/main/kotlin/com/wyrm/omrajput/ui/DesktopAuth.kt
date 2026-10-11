package com.wyrm.omrajput.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Wyrm Desktop's sign-in (OM, 2026-10-02): the opening is the desktop's own,
 * then the flow is Wyrm Android's and iOS's cinematic sign-in exactly (the
 * same stages, checks, wording and pieces from CinematicAuthScreen.kt).
 *
 * Opening: a blank page; the W materialises out of a blur in the middle, rises
 * a little as "The arena and people in it." materialises under it, then both
 * settle higher while "Create Wyrm account" and "Log in" materialise below.
 * Every arrival and departure is the blur fade the phones use.
 */

/** How far the opening has played: blank, the W, the line, the buttons. */
private enum class Opening { BLANK, MARK, LINE, ACTIONS }

@Composable
fun DesktopAuth(
    checkUsername: suspend (String) -> UsernameAvailability,
    signUp: suspend (username: String, password: String) -> String?,
    logIn: suspend (username: String, password: String) -> String?,
    bootstrap: suspend () -> Unit,
    onComplete: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var stage by remember { mutableStateOf(AuthStage.LANDING) }
    var opening by remember { mutableStateOf(Opening.BLANK) }
    var showPrivacy by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var availability by remember { mutableStateOf(UsernameAvailability.IDLE) }
    var confirmationState by remember { mutableStateOf(ConfirmationState.IDLE) }
    var logoDissolved by remember { mutableStateOf(false) }
    var focusTarget by remember { mutableStateOf<AuthField?>(null) }
    var focusPulse by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        delay(260)
        opening = Opening.MARK
        delay(900)
        opening = Opening.LINE
        delay(1050)
        opening = Opening.ACTIONS
    }

    val usernameValid = usernamePattern.matches(username)
    val passwordValid = password.length in 8..200

    fun enter(next: AuthStage, focus: AuthField?) {
        errorMessage = ""
        focusManager.clearFocus()
        stage = next
        focusTarget = focus
        focusPulse++
    }

    // The phones' checks, to the millisecond.
    LaunchedEffect(username, stage) {
        if (stage != AuthStage.CREATE_USERNAME || !usernamePattern.matches(username)) {
            availability = UsernameAvailability.IDLE
            return@LaunchedEffect
        }
        availability = UsernameAvailability.CHECKING
        delay(420)
        availability = checkUsername(username)
    }
    LaunchedEffect(password, confirmation, stage) {
        if (stage != AuthStage.CREATE_CONFIRMATION || password.isEmpty() || confirmation.length < password.length) {
            confirmationState = ConfirmationState.IDLE
            return@LaunchedEffect
        }
        confirmationState = ConfirmationState.CHECKING
        delay(280)
        confirmationState = if (confirmation == password) ConfirmationState.MATCHED else ConfirmationState.MISMATCHED
    }

    val actionEnabled = when (stage) {
        AuthStage.CREATE_USERNAME -> usernameValid && availability == UsernameAvailability.AVAILABLE
        AuthStage.LOGIN_USERNAME -> username.isNotEmpty()
        AuthStage.CREATE_PASSWORD -> passwordValid
        AuthStage.LOGIN_PASSWORD -> password.isNotEmpty() && password.length <= 200
        AuthStage.CREATE_CONFIRMATION -> passwordValid && confirmationState == ConfirmationState.MATCHED
        else -> false
    }
    val showsAction = when (stage) {
        AuthStage.CREATE_USERNAME -> usernameValid
        AuthStage.LOGIN_USERNAME -> username.isNotEmpty()
        AuthStage.CREATE_PASSWORD, AuthStage.LOGIN_PASSWORD -> password.isNotEmpty()
        AuthStage.CREATE_CONFIRMATION -> confirmationState == ConfirmationState.MATCHED
        else -> false
    }
    val showsBack = stage in setOf(
        AuthStage.CREATE_USERNAME, AuthStage.CREATE_PASSWORD, AuthStage.CREATE_CONFIRMATION,
        AuthStage.LOGIN_USERNAME, AuthStage.LOGIN_PASSWORD,
    )
    val actionTitle = when (stage) {
        AuthStage.CREATE_USERNAME, AuthStage.LOGIN_USERNAME -> "Continue"
        AuthStage.CREATE_PASSWORD -> "Next"
        AuthStage.LOGIN_PASSWORD -> "Log in"
        AuthStage.CREATE_CONFIRMATION -> "Create account"
        else -> "Continue"
    }

    var authJob by remember { mutableStateOf<Job?>(null) }
    fun authenticate(create: Boolean) {
        if (authJob?.isActive == true) return
        focusManager.clearFocus()
        errorMessage = ""
        stage = if (create) AuthStage.CREATING else AuthStage.SIGNING_IN
        authJob = scope.launch {
            val failure = if (create) signUp(username, password) else logIn(username, password)
            if (failure != null) {
                val usernameFailure = create && failure.contains("username", ignoreCase = true)
                enter(
                    if (usernameFailure) AuthStage.CREATE_USERNAME else if (create) AuthStage.CREATE_CONFIRMATION else AuthStage.LOGIN_PASSWORD,
                    if (usernameFailure) AuthField.USERNAME else if (create) AuthField.CONFIRMATION else AuthField.PASSWORD,
                )
                errorMessage = failure
                return@launch
            }
            // The W stays until the account's surfaces are fetched, so Home
            // never draws an empty or previous account.
            bootstrap()
            stage = AuthStage.SUCCESS
            delay(700)
            logoDissolved = true
            delay(520)
            onComplete()
        }
    }

    fun advance() {
        if (!actionEnabled) return
        when (stage) {
            AuthStage.CREATE_USERNAME -> enter(AuthStage.CREATE_PASSWORD, AuthField.PASSWORD)
            AuthStage.LOGIN_USERNAME -> enter(AuthStage.LOGIN_PASSWORD, AuthField.PASSWORD)
            AuthStage.CREATE_PASSWORD -> enter(AuthStage.CREATE_CONFIRMATION, AuthField.CONFIRMATION)
            AuthStage.LOGIN_PASSWORD -> authenticate(create = false)
            AuthStage.CREATE_CONFIRMATION -> authenticate(create = true)
            else -> Unit
        }
    }

    fun goBack() {
        when (stage) {
            AuthStage.CREATE_USERNAME, AuthStage.LOGIN_USERNAME -> enter(AuthStage.LANDING, null)
            AuthStage.CREATE_PASSWORD -> enter(AuthStage.CREATE_USERNAME, AuthField.USERNAME)
            AuthStage.CREATE_CONFIRMATION -> enter(AuthStage.CREATE_PASSWORD, AuthField.PASSWORD)
            AuthStage.LOGIN_PASSWORD -> enter(AuthStage.LOGIN_USERNAME, AuthField.USERNAME)
            else -> Unit
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Wyrm.Paper)
            // A keyboard and a mouse: Enter goes on, Esc goes back.
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || showPrivacy) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Enter, Key.NumPadEnter -> { advance(); true }
                    Key.Escape -> { if (showsBack) goBack(); showsBack }
                    else -> false
                }
            },
    ) {
        val height = maxHeight
        val width = maxWidth

        // The opening's resting places: the middle, a little up, then settled.
        val landingMarkY = when (opening) {
            Opening.BLANK, Opening.MARK -> height * 0.5f
            Opening.LINE -> height * 0.5f - 38.dp
            Opening.ACTIONS -> height * 0.34f
        }

        // The stages under the W.
        AnimatedContent(
            targetState = stage,
            transitionSpec = {
                (fadeIn(iosSpring<Float>(0.58f, 0.86f)) + scaleIn(iosSpring<Float>(0.58f, 0.86f), initialScale = 0.985f)) togetherWith
                    (fadeOut(iosSpring<Float>(0.58f, 0.86f)) + scaleOut(iosSpring<Float>(0.58f, 0.86f), targetScale = 0.985f))
            },
            contentKey = { it },
            modifier = Modifier.fillMaxSize(),
            label = "auth-stage",
        ) { shown ->
            Box(Modifier.fillMaxSize().then(blurFade())) {
                when (shown) {
                    AuthStage.LANDING -> OpeningCopy(
                        opening = opening,
                        lineY = landingMarkY + 82.dp,
                        onCreate = { enter(AuthStage.CREATE_USERNAME, AuthField.USERNAME) },
                        onLogin = { enter(AuthStage.LOGIN_USERNAME, AuthField.USERNAME) },
                        onPrivacy = { showPrivacy = true },
                    )
                    AuthStage.CREATE_USERNAME, AuthStage.LOGIN_USERNAME, AuthStage.CREATE_PASSWORD,
                    AuthStage.LOGIN_PASSWORD, AuthStage.CREATE_CONFIRMATION -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                        Box(Modifier.widthIn(max = 460.dp).fillMaxSize()) {
                            CredentialStage(
                                stage = shown,
                                keyboardHeight = 0.dp,
                                username = username,
                                onUsername = { value ->
                                    username = value.filter { it.isLetterOrDigit() || it == '_' }.take(20)
                                    errorMessage = ""
                                },
                                password = password,
                                onPassword = { password = it },
                                confirmation = confirmation,
                                onConfirmation = { confirmation = it },
                                availability = availability,
                                usernameValid = usernameValid,
                                passwordValid = passwordValid,
                                confirmationState = confirmationState,
                                errorMessage = errorMessage,
                                actionEnabled = actionEnabled,
                                onSubmit = ::advance,
                                focusTarget = focusTarget,
                                focusPulse = focusPulse,
                            )
                        }
                    }
                    AuthStage.CREATING, AuthStage.SIGNING_IN -> WorkingStatus(
                        title = if (shown == AuthStage.CREATING) "Creating your account…" else "Entering Wyrm…",
                        modifier = Modifier.align(Alignment.TopCenter).offset(y = height * 0.45f + 92.dp - 9.dp),
                    )
                    AuthStage.SUCCESS -> Unit
                }
            }
        }

        // The W: one mark that travels between every stage.
        val logoSize by animateDpAsState(
            targetValue = when (stage) {
                AuthStage.LANDING -> 116.dp
                AuthStage.CREATING, AuthStage.SIGNING_IN -> 118.dp
                AuthStage.SUCCESS -> 132.dp
                else -> 74.dp
            },
            animationSpec = iosSpring<Dp>(0.58f, 0.86f),
            label = "logo-size",
        )
        val logoY by animateDpAsState(
            targetValue = when (stage) {
                AuthStage.LANDING -> landingMarkY
                AuthStage.CREATING, AuthStage.SIGNING_IN, AuthStage.SUCCESS -> height * 0.45f
                else -> 96.dp
            },
            animationSpec = iosSpring<Dp>(0.72f, 0.9f),
            label = "logo-y",
        )
        // The first arrival: out of a blur, a touch small, into place.
        val materialise = remember { Animatable(0f) }
        LaunchedEffect(opening) {
            if (opening != Opening.BLANK && materialise.value < 1f) materialise.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
        }
        val logoScale by animateFloatAsState(
            targetValue = if (stage == AuthStage.SUCCESS) 1.08f else 1f,
            animationSpec = iosSpring<Float>(0.58f, 0.86f),
            label = "logo-scale",
        )
        val dissolve by animateFloatAsState(
            targetValue = if (logoDissolved) 1f else 0f,
            animationSpec = tween(520, easing = FastOutSlowInEasing),
            label = "logo-dissolve",
        )
        WyrmBrandMark(
            size = logoSize,
            modifier = Modifier.offset { IntOffset(((width - logoSize) / 2).roundToPx(), (logoY - logoSize / 2).roundToPx()) },
            layer = {
                val m = materialise.value
                val s = logoScale * (0.9f + 0.1f * m)
                scaleX = s
                scaleY = s
                alpha = m * (1f - dissolve)
                val radius = (18.dp.toPx() * (1f - m)) + 18.dp.toPx() * dissolve
                renderEffect = if (radius > 0.5f) BlurEffect(radius, radius, TileMode.Decal) else null
            },
        )

        // Back.
        AnimatedVisibility(
            visible = showsBack,
            enter = fadeIn(iosSpring<Float>(0.58f, 0.86f)) + scaleIn(iosSpring<Float>(0.58f, 0.86f), initialScale = 0.985f),
            exit = fadeOut(iosSpring<Float>(0.58f, 0.86f)) + scaleOut(iosSpring<Float>(0.58f, 0.86f), targetScale = 0.985f),
            modifier = Modifier.offset(x = 28.dp, y = 24.dp),
        ) {
            Box(
                Modifier
                    .then(blurFade())
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(Wyrm.Card.copy(alpha = 0.88f))
                    .border(1.dp, Wyrm.Rule, CircleShape)
                    .clickable(onClick = ::goBack),
                contentAlignment = Alignment.Center,
            ) { IosIcon(IosGlyph.CHEVRON_LEFT, Wyrm.Ink, size = 18.dp, weight = 2.4f) }
        }

        // The stage's action, under the field (Enter does the same).
        AnimatedVisibility(
            visible = showsAction,
            enter = fadeIn(iosSpring<Float>(0.58f, 0.86f)) + scaleIn(iosSpring<Float>(0.58f, 0.86f), initialScale = 0.985f),
            exit = fadeOut(iosSpring<Float>(0.58f, 0.86f)) + scaleOut(iosSpring<Float>(0.58f, 0.86f), targetScale = 0.985f),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp),
        ) {
            KeyboardAction(title = actionTitle, enabled = actionEnabled, modifier = Modifier.then(blurFade()), onClick = ::advance)
        }

        // Privacy Policy: Android's page, over the sign-in.
        AnimatedVisibility(
            visible = showPrivacy,
            enter = fadeIn(iosSpring<Float>(0.5f, 0.9f)) + scaleIn(iosSpring<Float>(0.5f, 0.9f), initialScale = 0.98f),
            exit = fadeOut(iosSpring<Float>(0.4f, 1f)) + scaleOut(iosSpring<Float>(0.4f, 1f), targetScale = 0.98f),
        ) {
            Box(Modifier.fillMaxSize().background(Wyrm.Paper).then(blurFade()), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = 760.dp).fillMaxSize()) {
                    PrivacyScreen(insetTop = 12.dp, insetBottom = 12.dp, backLabel = "Back", onBack = { showPrivacy = false })
                }
            }
        }
    }
}

/** The opening's words and buttons, each materialising in turn. */
@Composable
private fun OpeningCopy(
    opening: Opening,
    lineY: Dp,
    onCreate: () -> Unit,
    onLogin: () -> Unit,
    onPrivacy: () -> Unit,
) {
    val lineTop by animateDpAsState(lineY, iosSpring(0.72f, 0.9f), label = "line-y")
    Column(
        Modifier.fillMaxWidth().offset(y = lineTop),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AnimatedVisibility(
            visible = opening >= Opening.LINE,
            enter = fadeIn(tween(700)) + scaleIn(iosSpring(0.7f, 0.9f), initialScale = 0.97f),
        ) {
            Text(
                "The arena and people in it.",
                fontFamily = Wyrm.Body,
                fontWeight = FontWeight.Bold,
                fontSize = 30.sp,
                letterSpacing = (-0.6).sp,
                color = Wyrm.Ink,
                modifier = Modifier.then(blurFade()),
            )
        }
        Spacer(Modifier.height(42.dp))
        AnimatedVisibility(
            visible = opening >= Opening.ACTIONS,
            enter = fadeIn(tween(650)) + scaleIn(iosSpring(0.7f, 0.9f), initialScale = 0.97f),
        ) {
            Column(Modifier.width(340.dp).then(blurFade()), horizontalAlignment = Alignment.CenterHorizontally) {
                WelcomeCapsule("Create Wyrm account", filled = true, onClick = onCreate)
                Spacer(Modifier.height(10.dp))
                WelcomeCapsule("Log in", filled = false, onClick = onLogin)
                Text(
                    text = buildAnnotatedString {
                        append("By continuing, you agree to Wyrm's ")
                        pushStyle(SpanStyle(color = Wyrm.Link, textDecoration = TextDecoration.Underline))
                        append("Privacy Policy")
                        pop()
                    },
                    fontFamily = Wyrm.Body,
                    fontSize = 12.sp,
                    color = Wyrm.Quiet,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onPrivacy),
                )
            }
        }
    }
}
