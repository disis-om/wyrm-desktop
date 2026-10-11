package com.wyrm.omrajput.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wyrm.desktop.DesktopLog
import com.wyrm.omrajput.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale

/*
 * Wyrm Desktop's halves of Android's SupportCenter.kt (D6, 2026-10-12): the
 * device facts and the crash watch, with Android's names and API, so the
 * shared SupportStore, DropWatch and SupportScreens work unchanged.
 *
 * - A crash is kept on this computer and nothing leaves on its own: the next
 *   launch says Wyrm closed unexpectedly and asks; "Always send" skips the
 *   question from then on (the same `wyrm_crash` file and keys as Android).
 * - Kotlin/Java crashes: the uncaught-exception handler writes them down.
 * - Native crashes (the engine, a driver): the JVM's own report
 *   (`-XX:ErrorFile=%LOCALAPPDATA%\Wyrm\crash\hs_err_pid%p.log`, build.gradle.kts),
 *   found at the next launch. Older reports left in the engine's working
 *   folder are looked at too.
 * - Reports carry the app and Windows version, the GPU line the engine
 *   logged, the screen, and the last lines of Wyrm's own logs (app + engine),
 *   with tokens, passwords, keys and emails redacted (SupportRedact).
 */

/* ------------------------------------------------------------ device facts */

object SupportContext {
    fun current(context: Context, screen: String): Map<String, String> = mapOf(
        "platform" to "windows",
        "appVersion" to BuildConfig.VERSION_NAME,
        "build" to BuildConfig.VERSION_CODE.toString(),
        "osVersion" to "${System.getProperty("os.name", "Windows")} ${System.getProperty("os.version", "")}".trim(),
        "device" to deviceLine().take(120),
        "screen" to screen.take(80),
        "locale" to Locale.getDefault().toLanguageTag(),
    )

    fun minimal(): Map<String, String> = mapOf(
        "platform" to "windows",
        "appVersion" to BuildConfig.VERSION_NAME,
        "build" to BuildConfig.VERSION_CODE.toString(),
    )

    /** The GPU the engine picked (its own startup line), else the CPU architecture. */
    private fun deviceLine(): String {
        val gpu = DesktopLog.tail("engine.log", 200_000).lineSequence()
            .firstOrNull { it.contains("Wyrm startup: GPU ") }
            ?.substringAfter("Wyrm startup: GPU ")?.substringBefore(',')?.trim()
        val cores = Runtime.getRuntime().availableProcessors()
        return listOfNotNull(gpu, "${System.getProperty("os.arch")} · $cores threads").joinToString(" · ")
    }

    /**
     * The newest lines of Wyrm's own logs: the engine's first (what a drop or a
     * crash is about), then the shell's, inside [limit] (the backend keeps the
     * last 120 KB). [previousLaunch] reads the launch before this one, which
     * is the one a crash report is about.
     */
    suspend fun recentLog(maxLines: Int = 600, limit: Int = 100_000, previousLaunch: Boolean = false): String =
        withContext(Dispatchers.IO) {
            runCatching {
                val engineName = if (previousLaunch) "engine.previous.log" else "engine.log"
                val appName = if (previousLaunch) "app.previous.log" else "app.log"
                val engineHead = "--- Wyrm engine log ---\n"
                val appHead = "\n--- Wyrm app log ---\n"
                val engine = SupportRedact.clean(DesktopLog.tail(engineName, limit * 3 / 5)).lineSequence()
                    .toList().takeLast(maxLines * 3).joinToString("\n")
                val room = (limit - engineHead.length - engine.length - appHead.length).coerceAtLeast(0)
                val app = SupportRedact.clean(DesktopLog.tail(appName, room)).takeLast(room)
                (engineHead + engine + appHead + app).take(limit)
            }.getOrDefault("")
        }
}

/* ------------------------------------------------------------- crash watch */

object CrashWatch {
    private const val PREFS = "wyrm_crash"
    private const val KEY_LAST = "last"
    private const val KEY_SEEN = "native_seen"
    private const val KEY_AUTO = "auto_send"

    /** The crash the launch prompt is asking about. */
    var prompt by mutableStateOf<CrashRecord?>(null)
        private set
    /** The newest crash, kept so it can still be sent from Help & feedback. */
    var last by mutableStateOf<CrashRecord?>(null)
        private set
    var toast by mutableStateOf("")
    var autoSend by mutableStateOf(false)
        private set
    /** Where the player is, for the report. */
    @Volatile var screen: String = "Launch"

    val crashFolder: File = File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "Wyrm/crash")
    private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun javaFile(context: Context) = File(context.filesDir, "wyrm-crash/java.txt")

    /** After a log in or log out rewrote the file (account sync). */
    fun reloadPrefs() {
        appContext?.let { autoSend = prefs(it).getBoolean(KEY_AUTO, false) }
    }

    /** Once, first thing in main: read what the last run left, then arm. */
    fun install(context: Context) {
        if (appContext != null) return
        appContext = context
        crashFolder.mkdirs()
        val prefs = prefs(context)
        autoSend = prefs.getBoolean(KEY_AUTO, false)
        last = prefs.getString(KEY_LAST, null)?.let { runCatching { CrashRecord.from(JSONObject(it)) }.getOrNull() }
        runCatching { collectLeftovers(context) }

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val file = javaFile(context)
                file.parentFile?.mkdirs()
                file.writeText(
                    "${error.javaClass.name}: ${error.message.orEmpty()}\n" +
                        "thread: ${thread.name}\nbuild: ${BuildConfig.VERSION_CODE}\n" +
                        android.util.Log.getStackTraceString(error),
                )
            }
            if (previous != null) previous.uncaughtException(thread, error)
            else error.printStackTrace()
        }
    }

    fun applyAutoSend(on: Boolean) {
        autoSend = on
        appContext?.let { prefs(it).edit().putBoolean(KEY_AUTO, on).apply() }
    }

    /** JVM crash reports this launch can see: the crash folder, and the engine's working folder. */
    private fun nativeReports(): List<File> {
        val folders = listOfNotNull(
            crashFolder,
            System.getProperty("wyrm.android.root")?.let(::File),
            File(System.getProperty("user.dir")),
        )
        return folders.flatMap { folder ->
            folder.listFiles { file -> file.isFile && file.name.startsWith("hs_err_pid") && file.name.endsWith(".log") }.orEmpty().toList()
        }.distinctBy { it.absolutePath }
    }

    private fun collectLeftovers(context: Context) {
        val prefs = prefs(context)
        var record: CrashRecord? = null
        val file = javaFile(context)
        if (file.exists()) {
            val text = runCatching { file.readText() }.getOrDefault("")
            file.delete()
            if (text.isNotBlank()) {
                val build = Regex("build: (\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: BuildConfig.VERSION_CODE
                record = CrashRecord(cause = "java", title = text.lineSequence().first().take(160), stack = text,
                    appVersion = BuildConfig.VERSION_NAME, build = build,
                    at = file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis())
            }
        }
        val reports = nativeReports()
        val newest = reports.maxOfOrNull { it.lastModified() } ?: 0L
        val seen = prefs.getLong(KEY_SEEN, -1L)
        // The first launch with the watch only records where history starts:
        // an old crash from before this build is not asked about.
        if (seen >= 0 && record == null) {
            reports.filter { it.lastModified() > seen }.maxByOrNull { it.lastModified() }?.let { record = recordFrom(it) }
        }
        prefs.edit().putLong(KEY_SEEN, maxOf(newest, seen, 0L)).apply()
        // Reports already read are moved out of the way (they are large).
        reports.filter { it.lastModified() <= maxOf(newest, seen) && it.parentFile != crashFolder }.forEach { old ->
            runCatching { old.renameTo(File(crashFolder, old.name)) }
        }
        crashFolder.listFiles()?.sortedByDescending { it.lastModified() }?.drop(5)?.forEach { it.delete() }
        record?.let {
            keep(it)
            prompt = it
        }
    }

    /** A JVM crash report: its summary, the native and Java frames, the loaded Wyrm modules. */
    private fun recordFrom(file: File): CrashRecord {
        val text = runCatching { file.readText() }.getOrDefault("")
        val frame = Regex("# Problematic frame:\\s*\\n#\\s*(.+)").find(text)?.groupValues?.get(1)?.trim().orEmpty()
        val summary = text.lineSequence().takeWhile { !it.startsWith("---------------  T H R E A D") }.joinToString("\n")
        val stackPart = Regex("(Native frames:[\\s\\S]*?)(?:\\n\\n|$)").find(text)?.groupValues?.get(1).orEmpty()
        val javaPart = Regex("(Java frames:[\\s\\S]*?)(?:\\n\\n|$)").find(text)?.groupValues?.get(1).orEmpty()
        val stack = buildString {
            append("reason: native\n")
            append(summary.take(6_000)).append("\n\n")
            append(stackPart.take(20_000)).append("\n\n")
            append(javaPart.take(20_000))
        }
        return CrashRecord(
            cause = "native",
            title = "Native crash${if (frame.isNotBlank()) ": $frame" else ""}".take(160),
            stack = stack.take(56_000),
            appVersion = BuildConfig.VERSION_NAME,
            build = BuildConfig.VERSION_CODE,
            at = file.lastModified(),
        )
    }

    private fun keep(record: CrashRecord) {
        last = record
        appContext?.let { prefs(it).edit().putString(KEY_LAST, record.toJson().toString()).apply() }
    }

    /** With "Always send" on, the report goes and the prompt never shows. */
    fun launchCheck(repository: WyrmRepository) {
        val record = prompt ?: return
        if (!autoSend) return
        prompt = null
        send(repository, record, "") { ok -> if (ok) toast = "Crash report sent. Thank you." }
    }

    fun dismissPrompt() { prompt = null }

    fun send(repository: WyrmRepository, record: CrashRecord, note: String, done: (Boolean) -> Unit) {
        val context = appContext ?: return done(false)
        scope.launch {
            val ok = try {
                val facts = SupportContext.current(context, screen) + mapOf(
                    "crashCause" to record.cause,
                    "crashedBuild" to record.build.toString(),
                    "crashedAt" to java.time.Instant.ofEpochMilli(record.at).toString(),
                )
                // The crash ended the launch before this one: its logs are the previous ones.
                val logs = SupportContext.recentLog(previousLaunch = true)
                repository.submitSupport("crash", note.trim(), facts, stack = record.stack.ifBlank { record.title }, logs = logs)
                keep(record.copy(sent = true))
                if (prompt?.id == record.id) prompt = null
                true
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                android.util.Log.w("Wyrm", "crash report not sent: ${failure.message}")
                false
            }
            done(ok)
        }
    }
}
