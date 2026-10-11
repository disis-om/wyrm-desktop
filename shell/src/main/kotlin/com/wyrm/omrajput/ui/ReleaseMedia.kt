package com.wyrm.omrajput.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/*
 * Wyrm Desktop's stand-ins for the release-media pieces of Android's
 * WhatsNewSheet.kt that WyrmMarkdown.kt uses (same names and rules): a
 * trusted picture in a notification is drawn inline, a trusted video is a
 * card that opens in the browser (the desktop has no in-app player).
 */

enum class ReleaseMediaKind { IMAGE, VIDEO }

data class ReleaseMedia(
    val url: String,
    val kind: ReleaseMediaKind,
    val description: String,
)

private val TRUSTED_MEDIA_HOSTS = setOf(
    "github.com",
    "raw.githubusercontent.com",
    "user-images.githubusercontent.com",
    "user-attachments.githubusercontent.com",
    "objects.githubusercontent.com",
    "release-assets.githubusercontent.com",
    "github-releases.githubusercontent.com",
)

internal fun trustedReleaseMediaUrl(address: String): Boolean = runCatching {
    val url = URL(address)
    val host = url.host.lowercase()
    url.protocol.equals("https", true) && url.userInfo == null && url.port == -1 &&
        host in TRUSTED_MEDIA_HOSTS
}.getOrDefault(false)

internal fun videoReleaseUrl(address: String): Boolean {
    val path = runCatching { URL(address).path.lowercase() }.getOrDefault("")
    return path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".mov") ||
        runCatching { URL(address).host.equals("github.com", true) && path.startsWith("/user-attachments/") }
            .getOrDefault(false)
}

private fun openInBrowser(url: String) {
    runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
}

@Composable
fun ReleaseMediaCard(media: ReleaseMedia, onOpen: () -> Unit) {
    val shape = wyrmRounded(14.dp)
    when (media.kind) {
        ReleaseMediaKind.IMAGE -> {
            var image by remember(media.url) { mutableStateOf<ImageBitmap?>(null) }
            LaunchedEffect(media.url) {
                image = withContext(Dispatchers.IO) {
                    runCatching {
                        val connection = (URL(media.url).openConnection() as HttpURLConnection).apply {
                            connectTimeout = 8_000
                            readTimeout = 12_000
                        }
                        try {
                            connection.inputStream.use { org.jetbrains.skia.Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap() }
                        } finally {
                            connection.disconnect()
                        }
                    }.getOrNull()
                }
            }
            Box(
                Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(shape).background(Wyrm.Well).border(1.dp, Wyrm.Rule, shape)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { openInBrowser(media.url) },
                contentAlignment = Alignment.Center,
            ) {
                image?.let { Image(it, contentDescription = media.description, contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth()) }
                    ?: Text("Loading picture…", fontFamily = Wyrm.Body, fontSize = 12.sp, color = Wyrm.Quiet)
            }
        }
        ReleaseMediaKind.VIDEO -> Row(
            Modifier.fillMaxWidth().clip(shape).background(Wyrm.Well).border(1.dp, Wyrm.Rule, shape)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { openInBrowser(media.url) }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IosIcon(IosGlyph.PLAY_CIRCLE, Wyrm.Ink, size = 16.dp)
            Spacer(Modifier.width(10.dp))
            Text(media.description.ifBlank { "Video" }, fontFamily = Wyrm.Body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Wyrm.Ink, modifier = Modifier.weight(1f))
            Text("Watch ›", fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = Wyrm.Link)
        }
    }
}
