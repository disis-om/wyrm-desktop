package androidx.compose.ui.platform

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import com.wyrm.desktop.DesktopContext

/**
 * Wyrm Desktop: Android's LocalContext, for the shared Android Compose files
 * that read preferences through it. Always the desktop context.
 */
val LocalContext = staticCompositionLocalOf<Context> { DesktopContext }
