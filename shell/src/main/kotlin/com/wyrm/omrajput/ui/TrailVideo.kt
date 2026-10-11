package com.wyrm.omrajput.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Wyrm Desktop's stand-in for Android's TrailVideo.kt (Media3 + CameraX).
 * Video trails are off on every app (TRAIL_VIDEO_ENABLED = false, OM
 * 2026-10-05), so the desktop posts none. A video already in the feed shows
 * its poster, its length and a play mark; the desktop does not play it yet.
 */

internal const val TRAIL_VIDEO_MAX_MS = 30_000L

/** A clip on disk (never made on the desktop: video posting is off). */
internal data class TrailClip(val file: java.io.File, val durationMs: Long, val width: Int, val height: Int, val rotation: Int) {
    val aspect: Float get() = if (width > 0 && height > 0) width.toFloat() / height else 9f / 16f
}

internal object TrailVideoExport {
    @Suppress("UNUSED_PARAMETER")
    suspend fun export(
        context: android.content.Context,
        clip: TrailClip,
        startMs: Long,
        endMs: Long,
        muted: Boolean,
        look: List<androidx.media3.common.Effect>,
        overlay: (Int, Int) -> android.graphics.Bitmap?,
        progress: (Float) -> Unit,
    ): java.io.File = throw java.io.IOException("Video trails are not on the desktop yet.")

    @Suppress("UNUSED_PARAMETER")
    fun poster(context: android.content.Context, clip: TrailClip, atMs: Long, filter: Any?, overlay: (Int, Int) -> android.graphics.Bitmap?): android.graphics.Bitmap? = null
}

/** Android's single feed player. Nothing plays on the desktop; the feed's bookkeeping stays. */
internal object TrailFeedPlayer {
    var muted by mutableStateOf(true)
    var activeId by mutableStateOf<String?>(null)
    var firstFrame by mutableStateOf<String?>(null)

    fun toggleSound() { muted = !muted }
    fun pause() {}
    fun resume() {}
    fun stop() {
        activeId = null
        firstFrame = null
    }
}

internal fun clipTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/** A video trail in a card: its poster, its length, and a play mark. */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun TrailVideoView(trailId: String, video: com.wyrm.omrajput.data.TrailVideo, posterUrl: String, thumbUrl: String,
                            aspect: Float, active: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().aspectRatio(aspect).background(Color.Black), contentAlignment = Alignment.Center) {
        TrailImage(posterUrl, thumbUrl, aspect, Modifier.fillMaxSize())
        Text(clipTime(video.durationMs), fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color.White,
            modifier = Modifier.align(Alignment.TopStart).padding(10.dp).clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 8.dp, vertical = 3.dp))
        Box(Modifier.size(54.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
            Text("▶", color = Color.White, fontSize = 20.sp)
        }
    }
}
