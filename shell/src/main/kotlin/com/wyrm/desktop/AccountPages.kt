package com.wyrm.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wyrm.omrajput.data.NotificationPreferences
import com.wyrm.omrajput.data.SupportKind
import com.wyrm.omrajput.data.SupportStore
import com.wyrm.omrajput.ui.HelpCenterScreen
import com.wyrm.omrajput.ui.LocalSettingsEmbedded
import com.wyrm.omrajput.ui.SettingsBoolRow
import com.wyrm.omrajput.ui.SettingsCaption
import com.wyrm.omrajput.ui.SettingsCard
import com.wyrm.omrajput.ui.SettingsSectionLabel
import com.wyrm.omrajput.ui.SupportComposeScreen
import com.wyrm.omrajput.ui.SupportReportsScreen
import com.wyrm.omrajput.ui.notificationGroups

/*
 * Settings › Notifications and Settings › Help & feedback on the desktop (D6).
 * The rows, words and stores are Wyrm Android's (NotificationPreferences,
 * notificationGroups, HelpCenterScreen, SupportComposeScreen,
 * SupportReportsScreen); the master switch speaks of Windows.
 */

/** Settings › Notifications: Windows' own switch first, then each kind, as Android's page. */
@Composable
internal fun NotificationsPage() {
    var kinds by androidx.compose.runtime.remember { mutableStateOf(NotificationPreferences.enabledKinds(DesktopContext)) }
    val master = NotificationPreferences.systemEnabled(DesktopContext)
    SettingsSectionLabel("Windows", top = 14.dp)
    SettingsCard {
        SettingsBoolRow(
            title = "Windows notifications",
            detail = if (master) {
                "Wyrm can show alerts while its window is in the background. Click to open Windows' notification settings."
            } else {
                "This computer has no notification area for Wyrm."
            },
            on = master,
            first = true,
            onToggle = { NotificationPreferences.openSystemSettings(DesktopContext) },
        )
    }
    notificationGroups.forEach { group ->
        SettingsSectionLabel(group.title)
        SettingsCard {
            group.rows.forEachIndexed { index, choice ->
                SettingsBoolRow(
                    title = choice.title,
                    detail = choice.detail,
                    on = master && choice.kind in kinds,
                    first = index == 0,
                    onToggle = { on ->
                        NotificationPreferences.setKindEnabled(DesktopContext, choice.kind, on)
                        kinds = NotificationPreferences.enabledKinds(DesktopContext)
                    },
                )
            }
        }
    }
    SettingsCaption("Nothing here shows while you are in an arena, or while Wyrm's window is in front: the badges say it there.")
}

/** Which Help & feedback page is open. */
internal enum class HelpPage { MAIN, COMPOSE, REPORTS }

internal object HelpNav {
    var page by mutableStateOf(HelpPage.MAIN)
    var kind by mutableStateOf(SupportKind.BUG)
}

/**
 * Help & feedback: Android's Help center inside Settings; writing a report and
 * Your reports open over it with their own header (Back, Send), sliding in.
 */
@Composable
internal fun HelpPages() {
    LaunchedEffect(Unit) {
        SupportStore.screenBefore = "Settings"
        SupportStore.refresh()
    }
    AnimatedContent(
        targetState = HelpNav.page,
        transitionSpec = {
            val deeper = targetState.ordinal > initialState.ordinal
            (fadeIn(tween(220)) + slideInHorizontally(tween(260)) { (if (deeper) 1 else -1) * it / 10 }) togetherWith
                (fadeOut(tween(160)) + slideOutHorizontally(tween(260)) { (if (deeper) -1 else 1) * it / 14 })
        },
        label = "help-page",
    ) { page ->
        Box(Modifier.fillMaxSize()) {
            when (page) {
                HelpPage.MAIN -> HelpCenterScreen(
                    insetTop = 0.dp,
                    insetBottom = 0.dp,
                    repository = Account.repository,
                    onBack = {},
                    onCompose = { kind -> HelpNav.kind = kind; HelpNav.page = HelpPage.COMPOSE },
                    onReports = { HelpNav.page = HelpPage.REPORTS },
                    showTour = false,
                )
                HelpPage.COMPOSE -> CompositionLocalProvider(LocalSettingsEmbedded provides false) {
                    SupportComposeScreen(
                        initialKind = HelpNav.kind,
                        signedIn = Account.hasSession,
                        handle = Account.player?.handle.orEmpty(),
                        insetTop = 0.dp,
                        insetBottom = 0.dp,
                        onBack = { HelpNav.page = HelpPage.MAIN },
                        onReports = { HelpNav.page = HelpPage.REPORTS },
                    )
                }
                HelpPage.REPORTS -> CompositionLocalProvider(LocalSettingsEmbedded provides false) {
                    SupportReportsScreen(
                        insetTop = 0.dp,
                        insetBottom = 0.dp,
                        onBack = { HelpNav.page = HelpPage.MAIN },
                        onNew = { HelpNav.kind = SupportKind.BUG; HelpNav.page = HelpPage.COMPOSE },
                    )
                }
            }
        }
    }
}
