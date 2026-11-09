package androidx.activity.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import java.awt.Dialog
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent

/** Android's page-local Back maps to Escape; native dialogs keep their Escape. */
@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val callback = rememberUpdatedState(onBack)
    DisposableEffect(enabled) {
        val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val listener = KeyEventDispatcher { event ->
            if (enabled && event.id == KeyEvent.KEY_PRESSED &&
                event.keyCode == KeyEvent.VK_ESCAPE && manager.activeWindow !is Dialog) {
                callback.value()
                true
            } else false
        }
        if (enabled) manager.addKeyEventDispatcher(listener)
        onDispose { if (enabled) manager.removeKeyEventDispatcher(listener) }
    }
}
