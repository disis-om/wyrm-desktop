package com.wyrm.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wyrm.omrajput.BuildConfig
import com.wyrm.omrajput.ui.Wyrm
import com.wyrm.omrajput.ui.wyrmRounded
import kotlinx.coroutines.delay

/**
 * Settings › About Wyrm on a computer (D3): Wyrm Android's About (AboutScreen /
 * AboutInfo: the story in four chapters, the ecosystem, the community, a
 * coffee, the contact), with a computer's actions: links open in the browser,
 * the UPI ID is copied, the QR is the same picture the phones show.
 */
private object DesktopAbout {
    const val UPI_ID = "ommanav@fam"
    const val EMAIL = "ommanav.mail@gmail.com"
    const val WEBSITE = "https://www.omrajput.in"
    const val DISCORD_INVITE = "https://discord.gg/efqjEmQdeq"

    val ecosystem = listOf(
        "Wyrm Android" to "Where it all started",
        "Wyrm iOS" to "The same Wyrm on iPhone",
        "Wyrm Desktop" to "For the big screen",
        "Wyrm Windows" to "Native on Windows",
        "Wyrm Linux" to "Native on Linux",
        "NTL VANCED" to "Browser extension, powered by Wyrm",
        "Slither for Android" to "The original app, modded into Wyrm",
        "Slither for iOS" to "The original app, modded into Wyrm",
    )

    /** The phones' UPI QR (drawable-nodpi/about_upi_qr.png), shipped as a resource. */
    val qr: ImageBitmap? by lazy {
        runCatching {
            DesktopAbout::class.java.getResourceAsStream("/assets/about_upi_qr.png")?.use {
                org.jetbrains.skia.Image.makeFromEncoded(it.readBytes()).toComposeImageBitmap()
            }
        }.getOrNull()
    }

    fun open(uri: String) {
        runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(uri)) }
    }

    fun copy(text: String) {
        runCatching {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard
                .setContents(java.awt.datatransfer.StringSelection(text), null)
        }
    }
}

@Composable
internal fun AboutPage() {
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toast) { if (toast != null) { delay(2200); toast = null } }
    Column(Modifier.widthIn(max = 760.dp).padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 24.dp)) {
        Text("THE STORY OF", fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.4.sp, color = Wyrm.Quiet)
        Text("Wyrm", fontFamily = Wyrm.Display, fontSize = 58.sp, color = Wyrm.Ink)
        Text("One developer. One snake game.\nA whole ecosystem.", fontFamily = Wyrm.Body, fontSize = 17.sp, lineHeight = 24.sp, color = Wyrm.Mute)
        Spacer(Modifier.height(26.dp))
        Chapter("I", "It began as a mod",
            "Wyrm started as a simple slither.io mod for Android. A few tweaks for my own games, nothing more.")
        Chapter("II", "Then it grew a name",
            "I kept building, one feature after another, until the mod was no longer a mod. It became Wyrm: my own brand, a small one, inside the slither community.")
        Chapter("III", "Then it became an ecosystem",
            "Wyrm now runs on phones and computers, lives inside the browser, and even the original slither apps on Android and iOS were modded into it.")
        DesktopAbout.ecosystem.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().padding(start = 52.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { (name, detail) ->
                    Column(
                        Modifier.weight(1f).clip(wyrmRounded(14.dp)).background(Wyrm.Card).border(1.dp, Wyrm.Rule, wyrmRounded(14.dp)).padding(14.dp),
                    ) {
                        Text(name, fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Wyrm.Ink)
                        Text(detail, fontFamily = Wyrm.Body, fontSize = 11.5.sp, lineHeight = 15.sp, color = Wyrm.Quiet)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(16.dp))
        Chapter("IV", "Built by one person",
            "I'm OM Rajput, and Wyrm is a one-person project. The backend, the apps, the engine work, the design and every release: I build and run all of it myself.")
        Row(Modifier.padding(start = 52.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Backend", "Apps", "Engine", "Design", "Releases", "Support").forEach { Chip(it) }
        }
        Text("From the first line of the server to the last pixel of this page.",
            fontFamily = Wyrm.Body, fontSize = 12.5.sp, color = Wyrm.Quiet, modifier = Modifier.padding(start = 52.dp))

        Kicker("THE COMMUNITY")
        Text("Play with the people who play Wyrm", fontFamily = Wyrm.Display, fontSize = 28.sp, color = Wyrm.Ink)
        Text("Updates first, ideas heard, games together.", fontFamily = Wyrm.Body, fontSize = 14.sp, color = Wyrm.Mute)
        Spacer(Modifier.height(12.dp))
        Action("Join the Wyrm Discord", filled = true) { DesktopAbout.open(DesktopAbout.DISCORD_INVITE) }

        Kicker("BUY ME A COFFEE")
        Text("Keep the servers warm", fontFamily = Wyrm.Display, fontSize = 28.sp, color = Wyrm.Ink)
        Text("Wyrm is free. If it made your games better, a coffee keeps it going. Scan with any UPI app.",
            fontFamily = Wyrm.Body, fontSize = 14.sp, lineHeight = 20.sp, color = Wyrm.Mute)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Box(Modifier.size(180.dp).clip(wyrmRounded(18.dp)).background(androidx.compose.ui.graphics.Color.White).border(1.dp, Wyrm.Rule, wyrmRounded(18.dp)).padding(12.dp)) {
                DesktopAbout.qr?.let { Image(it, contentDescription = "UPI QR code for ${DesktopAbout.UPI_ID}", modifier = Modifier.size(156.dp)) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("UPI ID", fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.2.sp, color = Wyrm.Quiet)
                Text(DesktopAbout.UPI_ID, fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = Wyrm.Ink)
                Action("Copy UPI ID", filled = false) {
                    DesktopAbout.copy(DesktopAbout.UPI_ID)
                    toast = "UPI ID copied"
                }
            }
        }

        Kicker("SAY HELLO")
        Text("Write to me", fontFamily = Wyrm.Display, fontSize = 28.sp, color = Wyrm.Ink)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Contact("EMAIL", DesktopAbout.EMAIL) { DesktopAbout.open("mailto:${DesktopAbout.EMAIL}") }
            Contact("WEBSITE", "omrajput.in") { DesktopAbout.open(DesktopAbout.WEBSITE) }
        }
        Spacer(Modifier.height(28.dp))
        Text("Made with care by OM Rajput", fontFamily = Wyrm.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, color = Wyrm.Mute)
        Text("Wyrm ${BuildConfig.VERSION_NAME}", fontFamily = Wyrm.Body, fontSize = 11.sp, color = Wyrm.Quiet)
        toast?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, fontFamily = Wyrm.Body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Wyrm.Live)
        }
    }
}

@Composable
private fun Chapter(numeral: String, title: String, text: String) {
    Row(Modifier.padding(bottom = 18.dp)) {
        Box(Modifier.size(36.dp).clip(wyrmRounded(999.dp)).background(Wyrm.Ink), contentAlignment = Alignment.Center) {
            Text(numeral, fontFamily = Wyrm.Display, fontSize = 15.sp, color = Wyrm.OnInk)
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text("CHAPTER $numeral", fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.2.sp, color = Wyrm.Quiet)
            Text(title, fontFamily = Wyrm.Display, fontSize = 27.sp, lineHeight = 32.sp, color = Wyrm.Ink)
            Text(text, fontFamily = Wyrm.Body, fontSize = 15.sp, lineHeight = 23.sp, color = Wyrm.Mute)
        }
    }
}

@Composable
private fun Kicker(text: String) {
    Spacer(Modifier.height(30.dp))
    Text(text, fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 10.5.sp, letterSpacing = 1.4.sp, color = Wyrm.Quiet)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Chip(text: String) {
    Text(text, fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Wyrm.Ink,
        modifier = Modifier.clip(wyrmRounded(999.dp)).background(Wyrm.Well).padding(horizontal = 10.dp, vertical = 5.dp))
}

@Composable
private fun Action(label: String, filled: Boolean, onClick: () -> Unit) {
    val shape = wyrmRounded(999.dp)
    Box(
        Modifier
            .height(42.dp)
            .clip(shape)
            .background(if (filled) Wyrm.Ink else Wyrm.Well)
            .then(if (filled) Modifier else Modifier.border(1.dp, Wyrm.Rule, shape))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = if (filled) Wyrm.OnInk else Wyrm.Ink)
    }
}

@Composable
private fun Contact(title: String, value: String, onClick: () -> Unit) {
    Column(
        Modifier
            .widthIn(min = 220.dp)
            .clip(wyrmRounded(14.dp))
            .background(Wyrm.Card)
            .border(1.dp, Wyrm.Rule, wyrmRounded(14.dp))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(14.dp),
    ) {
        Text(title, fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.2.sp, color = Wyrm.Quiet)
        Text(value, fontFamily = Wyrm.Body, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = Wyrm.Ink, maxLines = 1)
    }
}
