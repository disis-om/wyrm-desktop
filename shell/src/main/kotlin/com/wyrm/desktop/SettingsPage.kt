package com.wyrm.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
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
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wyrm.omrajput.BuildConfig
import com.wyrm.omrajput.ui.LocalSettingsEmbedded
import com.wyrm.omrajput.ui.PaperSegmented
import com.wyrm.omrajput.ui.PrivacyScreen
import com.wyrm.omrajput.ui.SettingsAccessibilityScreen
import com.wyrm.omrajput.ui.SettingsActionRow
import com.wyrm.omrajput.ui.SettingsAssistScreen
import com.wyrm.omrajput.ui.SettingsBoolRow
import com.wyrm.omrajput.ui.SettingsBotScreen
import com.wyrm.omrajput.ui.SettingsCaption
import com.wyrm.omrajput.ui.SettingsCard
import com.wyrm.omrajput.ui.SettingsDisplayScreen
import com.wyrm.omrajput.ui.SettingsFocus
import com.wyrm.omrajput.ui.SettingsFoodScreen
import com.wyrm.omrajput.ui.SettingsSearchField
import com.wyrm.omrajput.ui.SettingsSectionLabel
import com.wyrm.omrajput.ui.SettingsValueRow
import com.wyrm.omrajput.ui.Wyrm
import com.wyrm.omrajput.ui.iosSpring
import com.wyrm.omrajput.ui.pressScale
import com.wyrm.omrajput.ui.wyrmRounded
import kotlinx.coroutines.launch
import com.composables.icons.lucide.R as LucideR

/*
 * Settings for a desktop window (OM, 2026-10-02): every page is Wyrm Android's
 * own (the same rows, wording and engine settings, written through the same
 * natives), set in a two-pane workspace: the sections on the left with
 * search, the open page on the right under its own title. The chosen section
 * carries an ink bead that slides to it on a spring; pages rise in from the
 * side they sit on. Phone-only pages (touch layouts, on-screen buttons,
 * joystick) are not here: on a computer the mouse and keys steer.
 */

internal enum class SettingsPage(val group: String, val title: String, val sub: String, val icon: Int) {
    DISPLAY("Arena", "Display", "Scores, names, minimap, text sizes", LucideR.drawable.lucide_ic_monitor),
    CONTROLS("Arena", "Controls", "Mouse and your keys", LucideR.drawable.lucide_ic_mouse),
    MODES("Playing help", "Modes", "Normal, Assist, helper lines and arena colours", LucideR.drawable.lucide_ic_crosshair),
    BOT("Playing help", "Bot", "When it circles, how wide it swings", LucideR.drawable.lucide_ic_bot),
    FOOD("Food", "Food style", "Original, rings and geometric shapes", LucideR.drawable.lucide_ic_cookie),
    PERFORMANCE("Performance", "Performance", "Frame rate, vsync, full screen", LucideR.drawable.lucide_ic_gauge),
    PROFILE("Account", "Profile", "Name, username, scores", LucideR.drawable.lucide_ic_user_round),
    NOTIFICATIONS("Account", "Notifications", "Which alerts reach you as Windows notifications", LucideR.drawable.lucide_ic_bell),
    PRIVACY("Account", "Privacy", "Your activity, and what is stored", LucideR.drawable.lucide_ic_shield),
    THEMES("Accessibility", "Themes", "Paper, dark and colour appearances", LucideR.drawable.lucide_ic_palette),
    VERSION("This computer", "Version", "Wyrm Desktop, its settings format and updates", LucideR.drawable.lucide_ic_info),
    HELP("Help", "Help & feedback", "Report a problem, ideas, your reports", LucideR.drawable.lucide_ic_circle_question_mark),
    ABOUT("About", "About Wyrm", "The story, the maker, and how to support", LucideR.drawable.lucide_ic_heart),
}

/** The open page, kept for the run. */
private var lastPage by mutableStateOf(SettingsPage.DISPLAY)

/** Offscreen renders pick the page to show. */
internal object SettingsPageShot {
    var open: SettingsPage
        get() = lastPage
        set(value) { lastPage = value }
}

@Composable
fun SettingsPage() {
    LaunchedEffect(Unit) { EngineSettings.refresh() }
    val page = lastPage
    fun open(next: SettingsPage) {
        if (next == SettingsPage.HELP && lastPage != SettingsPage.HELP) HelpNav.page = HelpPage.MAIN
        lastPage = next
    }

    Row(Modifier.fillMaxSize().padding(top = TopTabsClearance - 8.dp)) {
        SettingsNavigation(page, ::open, Modifier.width(330.dp).fillMaxHeight())
        Box(Modifier.padding(vertical = 18.dp).width(1.dp).fillMaxHeight().background(Wyrm.Rule))
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val down = targetState.ordinal > initialState.ordinal
                (fadeIn(iosSpring(0.4f, 0.9f)) + slideInVertically(iosSpring(0.5f, 0.86f)) { (if (down) 1 else -1) * it / 14 }) togetherWith
                    (fadeOut(iosSpring(0.28f, 1f)) + slideOutVertically(iosSpring(0.5f, 0.86f)) { (if (down) -1 else 1) * it / 18 })
            },
            modifier = Modifier.weight(1f).fillMaxHeight(),
            label = "settings-page",
        ) { shown ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 780.dp).fillMaxSize().padding(horizontal = 28.dp)) {
                    PageHeader(shown)
                    CompositionLocalProvider(
                        LocalSettingsEmbedded provides true,
                        com.wyrm.omrajput.ui.LocalTouchControls provides false,
                    ) {
                        Box(Modifier.weight(1f).fillMaxWidth()) { PageBody(shown) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PageHeader(page: SettingsPage) {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp, start = 16.dp)) {
        Text(page.group.uppercase(), fontFamily = Wyrm.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp,
            letterSpacing = 1.sp, color = Wyrm.Quiet)
        Text(page.title, fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 28.sp,
            letterSpacing = (-0.5).sp, color = Wyrm.Ink, modifier = Modifier.padding(top = 2.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Text(page.sub, fontFamily = Wyrm.Body, fontSize = 14.sp, color = Wyrm.Mute, modifier = Modifier.weight(1f))
            if (page == SettingsPage.DISPLAY) HeaderAction("Arrange arena UI", LucideR.drawable.lucide_ic_move) { Engine.openHudEditor() }
        }
    }
}

/** A page's own action, at the right of its header. */
@Composable
private fun HeaderAction(label: String, icon: Int, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    Row(
        Modifier
            .padding(start = 12.dp, end = 4.dp)
            .height(38.dp)
            .scale(pressScale(pressed))
            .clip(wyrmRounded(19.dp))
            .background(if (hovered) Wyrm.Ink.copy(alpha = 0.86f) else Wyrm.Ink)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(painterResource(icon), null, tint = Wyrm.OnInk, modifier = Modifier.size(15.dp))
        Text(label, fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 13.5.sp, color = Wyrm.OnInk)
    }
}

/** Each page: Android's own screen where there is one, embedded without its phone header. */
@Composable
private fun PageBody(page: SettingsPage) {
    val settings = EngineSettings.settings
    when (page) {
        SettingsPage.DISPLAY -> SettingsDisplayScreen(settings, 0.dp, 0.dp, onBack = {}, onChange = EngineSettings::write)
        SettingsPage.MODES -> SettingsAssistScreen(
            settings = settings,
            insetTop = 0.dp,
            insetBottom = 0.dp,
            backLabel = "Settings",
            onBack = {},
            onChange = EngineSettings::write,
            onAdjustBackground = { Engine.openBackgroundEditor() },
        )
        SettingsPage.BOT -> SettingsBotScreen(settings, 0.dp, 0.dp, onBack = {}, onChange = EngineSettings::write)
        SettingsPage.FOOD -> SettingsFoodScreen(settings, 0.dp, 0.dp, onBack = {}, onChange = EngineSettings::write)
        SettingsPage.THEMES -> SettingsAccessibilityScreen(
            selected = EngineSettings.theme,
            intensity = EngineSettings.themeIntensity,
            insetTop = 0.dp,
            insetBottom = 0.dp,
            onBack = {},
            onTheme = EngineSettings::selectTheme,
            onIntensityChange = EngineSettings::applyThemeIntensity,
            onResetIntensity = { EngineSettings.applyThemeIntensity(0.5f) },
        )
        SettingsPage.PRIVACY -> PrivacyScreen(
            insetTop = 0.dp,
            insetBottom = 0.dp,
            backLabel = "Settings",
            onBack = {},
            signedIn = Account.hasSession,
            shareActivity = com.wyrm.omrajput.data.FriendPresence.sharing,
            onShareActivity = Account::applyActivitySharing,
        )
        SettingsPage.NOTIFICATIONS -> DesktopPage { NotificationsPage() }
        SettingsPage.HELP -> HelpPages()
        SettingsPage.CONTROLS -> DesktopPage { DesktopControlsPage() }
        SettingsPage.PERFORMANCE -> DesktopPage {
            val caps = listOf(60, 120, 144, 240, 0)
            SettingsSectionLabel("Frame rate", top = 14.dp)
            Box(Modifier.padding(horizontal = 16.dp)) {
                PaperSegmented(
                    options = caps.map { if (it == 0) "No limit" else "$it FPS" },
                    selected = caps.indexOf(EngineSettings.fpsCap).coerceAtLeast(0),
                    onSelect = { EngineSettings.setFramePolicy(cap = caps[it]) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(14.dp))
            SettingsCard {
                SettingsBoolRow(
                    title = "Vsync",
                    detail = "Frames wait for the display: no tearing, a touch more delay",
                    on = EngineSettings.vsync,
                    first = true,
                    onToggle = { EngineSettings.setFramePolicy(vsync = it) },
                )
            }
            SettingsSectionLabel("Window")
            SettingsCard {
                SettingsBoolRow(
                    title = "Full screen",
                    detail = "F11 or Alt+Enter switches it anywhere in Wyrm",
                    on = DesktopWindow.fullscreen,
                    first = true,
                    onToggle = { DesktopWindow.applyFullscreen(it) },
                )
            }
        }
        SettingsPage.PROFILE -> DesktopPage {
            val player = Account.player
            SettingsSectionLabel("Your Wyrm account", top = 14.dp)
            SettingsCard {
                SettingsValueRow("Name", player?.displayName.orEmpty(), first = true)
                SettingsValueRow("Username", player?.handle.orEmpty(), first = false)
                SettingsValueRow("Best score", grouped(player?.highestScore ?: 0), first = false)
                SettingsValueRow("Kills", grouped(player?.kills ?: 0), first = false)
            }
        }
        SettingsPage.ABOUT -> DesktopPage { AboutPage() }
        SettingsPage.VERSION -> DesktopPage {
            SettingsSectionLabel("Wyrm Desktop", top = 14.dp)
            SettingsCard {
                SettingsValueRow("Version", BuildConfig.VERSION_NAME, first = true)
                SettingsValueRow("Settings format", EngineSettings.version.let { if (it.isBlank()) "" else "v$it" }, first = false)
            }
            UpdatesSection()
        }
    }
}

/** Settings › Version › Updates (D8): the channel, a check, and the offer when there is one. */
@Composable
private fun UpdatesSection() {
    val offer = DesktopUpdater.offer
    val state = DesktopUpdater.state
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    SettingsSectionLabel("Updates", top = 22.dp)
    SettingsCard {
        SettingsBoolRow(
            "Beta updates",
            "Get new versions early, before they reach everyone.",
            on = DesktopUpdater.beta,
            first = true,
            onToggle = DesktopUpdater::applyBeta,
        )
        SettingsValueRow(
            "Status",
            when {
                state is DesktopUpdater.State.Checking -> "Checking…"
                state is DesktopUpdater.State.Downloading -> "Downloading ${(state.progress * 100).toInt()}%"
                state is DesktopUpdater.State.Starting -> "Restarting Wyrm…"
                offer != null -> "Wyrm ${offer.versionName}${if (offer.beta) " (beta)" else ""} is ready"
                state is DesktopUpdater.State.UpToDate -> "Wyrm is up to date"
                else -> "Not checked yet"
            },
            first = false,
        )
        if (offer != null) {
            SettingsActionRow("Update now", first = false) {
                DesktopUpdater.showCard = true
                DesktopUpdater.installNow(DesktopUpdater.quit)
            }
        }
        SettingsActionRow("Check now", first = false) { scope.launch { DesktopUpdater.check(userAsked = true) } }
    }
    (state as? DesktopUpdater.State.Failed)?.let { SettingsCaption(it.message) }
}

/** A desktop-only page, in the same cards and spacing as Android's. */
@Composable
private fun DesktopPage(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) { content() }
}

private fun grouped(value: Long): String = "%,d".format(value)

/* ------------------------------------------------------------ navigation */

@Composable
private fun SettingsNavigation(page: SettingsPage, onOpen: (SettingsPage) -> Unit, modifier: Modifier) {
    val query = SettingsFocus.query.trim()
    Column(modifier.padding(start = 20.dp, end = 14.dp)) {
        Text("Settings", fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 30.sp,
            letterSpacing = (-0.5).sp, color = Wyrm.Ink, modifier = Modifier.padding(start = 6.dp, top = 14.dp, bottom = 12.dp))
        Box(Modifier.padding(bottom = 6.dp)) { SettingsSearchField(query = SettingsFocus.query, onQuery = { SettingsFocus.query = it }) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (query.isNotEmpty()) {
                SearchResults(query, onOpen)
            } else {
                NavigationList(page, onOpen)
            }
            Spacer(Modifier.height(18.dp))
            DangerZone()
            Text(
                if (EngineSettings.version.isNotBlank()) "Wyrm · settings format v${EngineSettings.version}" else "Wyrm",
                fontFamily = Wyrm.Body, fontSize = 12.sp, color = Wyrm.Quiet, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 18.dp),
            )
        }
    }
}

/** The sections in Android's groups; the open one wears a bead that glides to it. */
@Composable
private fun NavigationList(page: SettingsPage, onOpen: (SettingsPage) -> Unit) {
    val density = LocalDensity.current
    val rowTops = remember { mutableStateOf(mapOf<SettingsPage, Float>()) }
    val pillY = remember { Animatable(0f) }
    var placed by remember { mutableStateOf(false) }
    val target = rowTops.value[page]
    LaunchedEffect(target) {
        if (target == null) return@LaunchedEffect
        if (!placed) { pillY.snapTo(target); placed = true } else pillY.animateTo(target, iosSpring(0.46f, 0.78f))
    }
    Box(Modifier.fillMaxWidth()) {
        if (placed) {
            Box(
                Modifier
                    .offset { IntOffset(0, pillY.value.toInt()) }
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(wyrmRounded(14.dp))
                    .background(Wyrm.Card)
                    .border(1.dp, Wyrm.Rule, wyrmRounded(14.dp)),
            ) {
                // The bead: Wyrm's mark for "you are here".
                Box(Modifier.align(Alignment.CenterStart).padding(start = 6.dp).size(width = 4.dp, height = 18.dp).clip(wyrmRounded(2.dp)).background(Wyrm.Ink))
            }
        }
        Column {
            var lastGroup = ""
            for (entry in SettingsPage.entries) {
                if (entry.group != lastGroup) {
                    lastGroup = entry.group
                    Text(entry.group.uppercase(), fontFamily = Wyrm.Body, fontWeight = FontWeight.SemiBold, fontSize = 11.sp,
                        letterSpacing = 0.9.sp, color = Wyrm.Quiet, modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 6.dp))
                }
                NavigationRow(
                    entry = entry,
                    chosen = entry == page,
                    value = navigationValue(entry),
                    modifier = Modifier.onGloballyPositioned { coordinates ->
                        val y = coordinates.positionInParent().y
                        if (rowTops.value[entry] != y) rowTops.value = rowTops.value + (entry to y)
                    },
                ) { onOpen(entry) }
            }
        }
    }
}

private fun navigationValue(entry: SettingsPage): String = when (entry) {
    SettingsPage.THEMES -> EngineSettings.theme.displayName
    SettingsPage.FOOD -> EngineSettings.settings.firstOrNull { it.id == "normal.food_type" }?.let { it.options.getOrNull(it.index) }.orEmpty()
    SettingsPage.PERFORMANCE -> if (EngineSettings.fpsCap == 0) "No limit" else "${EngineSettings.fpsCap} FPS"
    SettingsPage.PROFILE -> Account.player?.handle.orEmpty()
    SettingsPage.HELP -> com.wyrm.omrajput.data.SupportStore.unseenReplies.let { if (it > 0) "$it new" else "" }
    else -> ""
}

@Composable
private fun NavigationRow(entry: SettingsPage, chosen: Boolean, value: String, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val hover by animateFloatAsState(if (hovered && !chosen) 1f else 0f, tween(140), label = "nav-hover")
    val ink by animateColorAsState(if (chosen) Wyrm.Ink else Wyrm.Mute, tween(160), label = "nav-ink")
    Row(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .scale(pressScale(pressed))
            .clip(wyrmRounded(14.dp))
            .background(Wyrm.Ink.copy(alpha = 0.04f * hover))
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(start = 18.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(30.dp).clip(wyrmRounded(9.dp)).background(if (chosen) Wyrm.Ink else Wyrm.Well),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(entry.icon), contentDescription = null, tint = if (chosen) Wyrm.OnInk else Wyrm.Ink, modifier = Modifier.size(16.dp))
        }
        Text(
            entry.title,
            fontFamily = Wyrm.Body,
            fontWeight = if (chosen) FontWeight.Bold else FontWeight.SemiBold,
            fontSize = 15.sp,
            color = ink,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (value.isNotBlank()) Text(value, fontFamily = Wyrm.Body, fontSize = 13.sp, color = Wyrm.Quiet, maxLines = 1)
    }
}

/** What matches the search: sections by name, and engine settings by label. */
@Composable
private fun SearchResults(query: String, onOpen: (SettingsPage) -> Unit) {
    val q = query.lowercase()
    val pages = SettingsPage.entries.filter { it.title.lowercase().contains(q) || it.sub.lowercase().contains(q) }
    val rows = EngineSettings.settings.filter { it.label.isNotBlank() && it.label.lowercase().contains(q) }
        .mapNotNull { setting -> pageOf(setting.id, setting.group)?.let { setting to it } }
        .distinctBy { it.first.label to it.second }
        .take(24)
    // Every action's key lives on Controls (Boost, Eyes back, Bot…).
    val keys = DesktopKeys.groups.flatMap { it.second }.map { it.second }.filter { it.lowercase().contains(q) }
    if (pages.isEmpty() && rows.isEmpty() && keys.isEmpty()) {
        Text("No results for “$query”", fontFamily = Wyrm.Body, fontSize = 14.sp, color = Wyrm.Quiet,
            modifier = Modifier.padding(start = 12.dp, top = 18.dp))
        return
    }
    Column(Modifier.padding(top = 8.dp)) {
        for (entry in pages) NavigationRow(entry, false, "", Modifier) { SettingsFocus.query = ""; onOpen(entry) }
        for ((setting, entry) in rows) {
            SearchHit(setting.label, entry.title) {
                SettingsFocus.query = ""
                SettingsFocus.reveal(setting.id)
                onOpen(entry)
            }
        }
        for (title in keys) {
            SearchHit("$title key", SettingsPage.CONTROLS.title) {
                SettingsFocus.query = ""
                onOpen(SettingsPage.CONTROLS)
            }
        }
    }
}

/** The page an engine setting is shown on; null for the phone-only ones. */
private fun pageOf(id: String, group: String): SettingsPage? = when {
    id.contains("food") -> SettingsPage.FOOD
    group == "general.bot" -> SettingsPage.BOT
    group == "general" || group.startsWith("general.") -> if (id == "general.vsync") SettingsPage.PERFORMANCE else SettingsPage.DISPLAY
    group == "normal" || group == "assist" || group.startsWith("normal.") || group.startsWith("assist.") -> SettingsPage.MODES
    else -> null
}

@Composable
private fun SearchHit(title: String, where: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(wyrmRounded(12.dp))
            .background(Wyrm.Ink.copy(alpha = if (hovered) 0.045f else 0f))
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(title, fontFamily = Wyrm.Body, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, color = Wyrm.Ink)
        Text(where, fontFamily = Wyrm.Body, fontSize = 12.5.sp, color = Wyrm.Quiet)
    }
}

/** Reset everything and Log out: both ask twice, as Android's Settings does. */
@Composable
private fun DangerZone() {
    var confirmReset by remember { mutableStateOf(false) }

    Column(Modifier.padding(horizontal = 0.dp)) {
        SettingsCard {
            SettingsActionRow(
                title = if (confirmReset) "Click again to reset everything" else "Reset everything to defaults",
                first = true,
                danger = true,
                onClick = {
                    if (confirmReset) { confirmReset = false; EngineSettings.resetAll() } else confirmReset = true
                },
            )
        }
        if (Account.hasSession) {
            Spacer(Modifier.height(12.dp))
            SettingsCard {
                SettingsActionRow(
                    title = "Log out",
                    first = true,
                    danger = true,
                    onClick = { Account.askLogOut() },
                )
            }
        }
    }
}
