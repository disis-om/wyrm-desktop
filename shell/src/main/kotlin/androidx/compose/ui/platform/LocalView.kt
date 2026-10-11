package androidx.compose.ui.platform

import androidx.compose.runtime.staticCompositionLocalOf

/** Wyrm Desktop: Android's LocalView, for the shared screens' haptic ticks (no-ops here). */
val LocalView = staticCompositionLocalOf { android.view.View() }
