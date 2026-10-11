package kotlinx.coroutines.android

import androidx.compose.runtime.withFrameNanos

/** Wyrm Desktop: kotlinx-coroutines-android's awaitFrame, on Compose's frame clock. */
suspend fun awaitFrame(): Long = withFrameNanos { it }
