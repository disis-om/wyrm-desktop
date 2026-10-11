package com.wyrm.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wyrm.omrajput.BuildConfig
import com.wyrm.omrajput.ui.IosSpinner
import com.wyrm.omrajput.ui.Wyrm
import com.wyrm.omrajput.ui.WyrmCapsule
import com.wyrm.omrajput.ui.WyrmBrandMark
import com.wyrm.omrajput.ui.wyrmRounded
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Wyrm Desktop's updater (D8, OM 2026-10-12), Wyrm Android's rules:
 *
 *  - The manifest (`update/latest.json`, and `beta.json` when Beta updates is
 *    on) lives in the public repo disis-om/wyrm-desktop and is believed only
 *    when its `.sig` verifies (SHA256withECDSA) against the public key in the
 *    app — the same key that signs Android's manifests.
 *  - It must name `com.wyrm.desktop`, a newer `versionCode`, and a setup on
 *    this repo's releases (https, github.com). The download must match the
 *    manifest's size and SHA-256 before it is run.
 *  - Update now: the account's settings are saved, the new WyrmSetup.exe runs
 *    with `--update` (it waits for this Wyrm to close, swaps the folder and
 *    opens the new Wyrm), and this Wyrm exits.
 *  - Checked 8 s after launch and every 6 hours, and from Settings › Updates.
 *    Only an installed Wyrm installs updates; `gradlew run` only reports them.
 */
internal object DesktopUpdater {
    private const val REPO = "disis-om/wyrm-desktop"
    private const val BASE = "https://raw.githubusercontent.com/$REPO/main/update/"
    private const val PACKAGE = "com.wyrm.desktop"
    private const val MAX_MANIFEST = 128 * 1024
    private val prefs get() = DesktopContext.getSharedPreferences("wyrm_desktop_updates", 0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    data class Manifest(
        val versionCode: Int,
        val versionName: String,
        val setupUrl: String,
        val setupSize: Long,
        val sha256: String,
        val headline: String,
        val notes: List<String>,
        val mandatory: Boolean,
        val beta: Boolean,
    )

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Downloading(val progress: Float) : State
        data object Starting : State
        data class Failed(val message: String) : State
    }

    var offer by mutableStateOf<Manifest?>(null)
        private set
    var state by mutableStateOf<State>(State.Idle)
        private set
    /** The card is up (an offer the player has not put off). */
    var showCard by mutableStateOf(false)
    var beta by mutableStateOf(true)
        private set
    var lastChecked by mutableStateOf(0L)
        private set

    /** Closes Wyrm as its window's close does (Main.kt sets it). */
    var quit: () -> Unit = {}

    /** This Wyrm can update itself (it runs from an install, not `gradlew run`). */
    val installed: Boolean get() = System.getProperty("jpackage.app-path") != null

    fun start() {
        beta = prefs.getBoolean("beta", true)
        scope.launch {
            delay(8_000L)
            while (true) {
                check(userAsked = false)
                delay(6 * 60 * 60_000L)
            }
        }
    }

    fun applyBeta(on: Boolean) {
        beta = on
        prefs.edit().putBoolean("beta", on).apply()
        scope.launch { check(userAsked = true) }
    }

    /** Checks both channels as Android does and keeps the newest valid offer. */
    suspend fun check(userAsked: Boolean) {
        if (state is State.Downloading || state is State.Starting) return
        // A log in may have restored the account's switch since the last check.
        beta = prefs.getBoolean("beta", true)
        state = State.Checking
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val stable = runCatching { fetch("latest.json", beta = false) }.getOrNull()
                val betaOne = if (beta) runCatching { fetch("beta.json", beta = true) }.getOrNull() else null
                listOfNotNull(stable, betaOne).maxByOrNull { it.versionCode }
            }
        }
        lastChecked = System.currentTimeMillis()
        val newest = result.getOrNull()
        state = when {
            result.isFailure && userAsked -> State.Failed("Wyrm could not check for updates. Check your connection.")
            newest != null && newest.versionCode > BuildConfig.VERSION_CODE -> {
                val fresh = offer?.versionCode != newest.versionCode
                offer = newest
                // A new version shows its card once on its own; Settings › Updates always has it.
                if (fresh && prefs.getInt("dismissed", 0) != newest.versionCode) showCard = true
                if (userAsked) showCard = true
                State.Idle
            }
            else -> { offer = null; State.UpToDate }
        }
    }

    /** pageShot only: a made-up offer on screen, nothing fetched. */
    internal fun showForPreview(manifest: Manifest) {
        offer = manifest
        state = State.Idle
        showCard = true
    }

    fun later() {
        offer?.let { prefs.edit().putInt("dismissed", it.versionCode).apply() }
        showCard = false
    }

    /** Download, check, save the account's settings, run the new setup, exit. */
    fun installNow(exit: () -> Unit) {
        val manifest = offer ?: return
        if (!installed) {
            state = State.Failed("Updates install into an installed Wyrm. This copy runs from the source tree.")
            return
        }
        if (state is State.Downloading || state is State.Starting) return
        scope.launch {
            state = State.Downloading(0f)
            val file = withContext(Dispatchers.IO) {
                runCatching { download(manifest) { p -> scope.launch { state = State.Downloading(p) } } }
            }
            val setup = file.getOrElse {
                state = State.Failed(it.message ?: "The update could not be downloaded. Try again.")
                return@launch
            }
            state = State.Starting
            withTimeoutOrNull(4_000L) { DesktopAccountSync.save(force = true) }
            val started = runCatching { ProcessBuilder(setup.absolutePath, "--update").start() }.isSuccess
            if (!started) {
                state = State.Failed("The update could not be started. Try again.")
                return@launch
            }
            delay(400)
            exit()
        }
    }

    /* --------------------------------------------------------------- network */

    private fun fetch(name: String, beta: Boolean): Manifest {
        val stamp = "?t=" + System.currentTimeMillis() / 1000
        val body = get(BASE + name + stamp, MAX_MANIFEST)
        val sig = get(BASE + name + ".sig" + stamp, 4096)
        val signature = Base64.getDecoder().decode(String(sig, Charsets.US_ASCII).trim())
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(publicKey())
        verifier.update(body)
        if (!verifier.verify(signature)) throw SecurityException("The update manifest is not signed by Wyrm")
        val json = JSONObject(String(body, Charsets.UTF_8))
        val manifest = Manifest(
            versionCode = json.getInt("versionCode"),
            versionName = json.getString("versionName"),
            setupUrl = json.getString("setupUrl"),
            setupSize = json.getLong("setupSize"),
            sha256 = json.getString("sha256").lowercase(),
            headline = json.optString("headline", "A new version of Wyrm"),
            notes = json.optJSONArray("releaseNotes")?.let { rows -> (0 until rows.length()).map { rows.getString(it) } }.orEmpty(),
            mandatory = json.optBoolean("mandatory", false),
            beta = beta,
        )
        val url = URL(manifest.setupUrl)
        require(json.optString("packageName") == PACKAGE) { "Not a Wyrm Desktop manifest" }
        require(url.protocol == "https" && url.host == "github.com" && url.path.startsWith("/$REPO/releases/download/")) { "Unexpected setup address" }
        require(manifest.setupSize in 1..(2L shl 30) && manifest.sha256.matches(Regex("[0-9a-f]{64}"))) { "Bad manifest" }
        return manifest
    }

    private fun publicKey(): java.security.PublicKey {
        val pem = DesktopUpdater::class.java.getResourceAsStream("/assets/wyrm_update_manifest_public.pem")!!.use { it.readBytes() }
        val der = Base64.getDecoder().decode(
            String(pem, Charsets.US_ASCII).replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "").replace(Regex("\\s"), ""),
        )
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
    }

    private fun get(address: String, limit: Int): ByteArray {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 30_000
            setRequestProperty("Cache-Control", "no-cache")
        }
        try {
            if (connection.responseCode !in 200..299) throw java.io.IOException("HTTP ${connection.responseCode}")
            return connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > limit) throw java.io.IOException("Too large")
                }
                out.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun download(manifest: Manifest, progress: (Float) -> Unit): File {
        val target = File(System.getProperty("java.io.tmpdir"), "WyrmSetup-${manifest.versionName}.exe")
        val connection = (URL(manifest.setupUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        try {
            if (connection.responseCode !in 200..299) throw java.io.IOException("The download failed (HTTP ${connection.responseCode}).")
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(1 shl 16)
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        copied += n
                        if (copied > manifest.setupSize) throw java.io.IOException("The download is larger than it should be.")
                        if (copied - lastReport > 256 * 1024) {
                            lastReport = copied
                            progress(copied.toFloat() / manifest.setupSize)
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (copied != manifest.setupSize || sha != manifest.sha256) {
            target.delete()
            throw SecurityException("The download did not match the update. Try again.")
        }
        progress(1f)
        return target
    }
}

/* -------------------------------------------------------------------- UI */

/** The update card over the app: what is new, Later / Update now, then the download. */
@Composable
internal fun UpdateCardHost(exit: () -> Unit) {
    val offer = DesktopUpdater.offer
    AnimatedVisibility(
        visible = DesktopUpdater.showCard && offer != null,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(160)),
    ) {
        val shown = offer ?: return@AnimatedVisibility
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.42f))
                .clickable(remember { MutableInteractionSource() }, null) {},
            contentAlignment = Alignment.Center,
        ) {
            AnimatedVisibility(visible = true, enter = scaleIn(tween(240), initialScale = 0.95f), exit = scaleOut()) {
                UpdateCard(shown, exit)
            }
        }
    }
}

@Composable
private fun UpdateCard(offer: DesktopUpdater.Manifest, exit: () -> Unit) {
    val state = DesktopUpdater.state
    Column(
        Modifier.width(440.dp).clip(wyrmRounded(24.dp)).background(Wyrm.Paper).border(1.dp, Wyrm.Rule, wyrmRounded(24.dp)).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        WyrmBrandMark(size = 64.dp)
        Spacer(Modifier.height(14.dp))
        Text(offer.headline, fontFamily = Wyrm.Body, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp, color = Wyrm.Ink)
        Text("Wyrm ${offer.versionName}${if (offer.beta) " (beta)" else ""} · ${"%.0f".format(offer.setupSize / 1_048_576.0)} MB",
            fontFamily = Wyrm.Body, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = Wyrm.Quiet, modifier = Modifier.padding(top = 4.dp))
        if (offer.notes.isNotEmpty()) {
            Column(Modifier.padding(top = 16.dp).fillMaxWidth().heightIn(max = 200.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                offer.notes.forEach { note ->
                    Row {
                        Box(Modifier.padding(top = 7.dp, end = 10.dp).size(6.dp).clip(WyrmCapsule).background(Wyrm.Live))
                        Text(note, fontFamily = Wyrm.Body, fontWeight = FontWeight.Medium, fontSize = 13.5.sp, color = Wyrm.Ink)
                    }
                }
            }
        }
        Spacer(Modifier.height(22.dp))
        when (state) {
            is DesktopUpdater.State.Downloading -> Column(Modifier.fillMaxWidth()) {
                val shown by animateFloatAsState(state.progress, tween(240), label = "update-beads")
                BeadProgress(shown, Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text("Downloading the update", fontFamily = Wyrm.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp,
                        color = Wyrm.Mute, modifier = Modifier.weight(1f))
                    Text("${(shown * 100).toInt()}%", fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 12.5.sp, color = Wyrm.Ink)
                }
            }
            DesktopUpdater.State.Starting -> Row(verticalAlignment = Alignment.CenterVertically) {
                IosSpinner(size = 16.dp)
                Spacer(Modifier.width(8.dp))
                Text("Restarting Wyrm…", fontFamily = Wyrm.Body, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = Wyrm.Mute)
            }
            else -> {
                if (state is DesktopUpdater.State.Failed) {
                    Text(state.message, fontFamily = Wyrm.Body, fontSize = 12.5.sp, color = Wyrm.Badge, modifier = Modifier.padding(bottom = 10.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!offer.mandatory) CardButton("Later", primary = false, onClick = DesktopUpdater::later)
                    CardButton("Update now", primary = true) { DesktopUpdater.installNow(exit) }
                }
            }
        }
    }
}

@Composable
private fun CardButton(label: String, primary: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.width(170.dp).height(44.dp).clip(WyrmCapsule).background(if (primary) Wyrm.Ink else Wyrm.Card)
            .border(1.dp, if (primary) Wyrm.Ink else Wyrm.Rule, WyrmCapsule).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = if (primary) Wyrm.OnInk else Wyrm.Ink)
    }
}

@Suppress("unused")
private val keepFill = Modifier.fillMaxWidth()
