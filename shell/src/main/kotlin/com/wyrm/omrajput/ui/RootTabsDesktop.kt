package com.wyrm.omrajput.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Dp

/*
 * Wyrm Desktop: the phone's bottom tab bar lives in HomeScreen.kt, which the
 * desktop does not use (its tabs are TopTabs.kt). Shared screens that can show
 * it are always given showRootTabs = false here; these keep them compiling.
 */
enum class RootTab { NOTIFICATIONS, SOCIAL, PLAY, SKIN, SETTINGS }

@Composable
fun RootTabs(
    selected: RootTab,
    insetBottom: Dp,
    unreadNotifications: Int,
    onNotifications: (Rect) -> Unit,
    onPlay: (Rect) -> Unit,
    onSocial: (Rect) -> Unit,
    onSkin: (Rect) -> Unit,
    onSettings: (Rect) -> Unit,
) = Unit
