package androidx.compose.ui.platform

import android.content.res.Configuration
import androidx.compose.runtime.compositionLocalOf

/**
 * Wyrm Desktop: Android's LocalConfiguration for the shared files that size
 * previews by the screen. Main.kt provides the window's size in dp.
 */
val LocalConfiguration = compositionLocalOf { Configuration(1280, 800) }
