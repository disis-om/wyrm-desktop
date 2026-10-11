package com.wyrm.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wyrm.omrajput.data.ArenaDirectory
import com.wyrm.omrajput.ui.Wyrm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Country flags for the arena picker (Android's ArenaCountryBadge + its
 * AvatarImages cache, for a computer): flagcdn's 160 px PNG, kept in memory
 * and on disk (%APPDATA%\Wyrm\cache\flags), so a flag is fetched once.
 */
internal object FlagImages {
    private val memory = ConcurrentHashMap<String, ImageBitmap>()
    private val failed = ConcurrentHashMap<String, Long>()
    private val dir: File by lazy {
        File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "Wyrm/cache/flags").apply { mkdirs() }
    }

    fun peek(country: String): ImageBitmap? = memory[country.lowercase()]

    suspend fun load(country: String): ImageBitmap? {
        val code = country.lowercase()
        if (code.length != 2 || !code.all { it in 'a'..'z' }) return null
        memory[code]?.let { return it }
        // A refused or missing picture is not asked for again for ten minutes.
        failed[code]?.let { if (System.currentTimeMillis() - it < 600_000) return null }
        return withContext(Dispatchers.IO) {
            runCatching {
                val file = File(dir, "$code.png")
                val bytes = if (file.isFile && file.length() > 0) file.readBytes() else {
                    val connection = (URL(ArenaDirectory.flagUrl(code)).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 6_000
                        readTimeout = 8_000
                        setRequestProperty("User-Agent", "Wyrm Desktop")
                    }
                    try {
                        check(connection.responseCode == 200) { "flag ${connection.responseCode}" }
                        connection.inputStream.use { it.readBytes() }.also { file.writeBytes(it) }
                    } finally {
                        connection.disconnect()
                    }
                }
                org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap().also { memory[code] = it }
            }.onFailure { failed[code] = System.currentTimeMillis() }.getOrNull()
        }
    }
}

/**
 * Android's ArenaCountryBadge: the flag with rounded corners and a hairline,
 * then the country code ("IN"). Nothing while the country is unknown; a quiet
 * plate until the picture lands.
 */
@Composable
internal fun CountryBadge(country: String, modifier: Modifier = Modifier) {
    if (country.length != 2) return
    var image by remember(country) { mutableStateOf(FlagImages.peek(country)) }
    LaunchedEffect(country) { if (image == null) image = FlagImages.load(country) }
    val shape = RoundedCornerShape(3.dp)
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(width = 20.dp, height = 14.dp)
                .clip(shape)
                .background(Wyrm.Track)
                .border(0.5.dp, Wyrm.Ink.copy(alpha = 0.14f), shape),
        ) {
            image?.let { Image(it, contentDescription = country, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Spacer(Modifier.width(5.dp))
        Text(country, fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 10.5.sp, letterSpacing = 0.6.sp, color = Wyrm.Quiet)
    }
}
