package com.wyrm.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wyrm.omrajput.WyrmActivity
import java.awt.GraphicsEnvironment
import java.lang.management.ManagementFactory

/**
 * Settings › Developer › Diagnostics overlay on Wyrm Desktop (Android 8.6.3's,
 * OM 2026-11-10). The engine draws the strip and times its own frames
 * (app/src/game/wyrm_diag.c, compiled into wyrm.dll); this pushes this
 * computer's facts once a second: Wyrm's CPU use, cores, memory, threads and
 * the window's refresh rate. Clocks, heat and battery are a phone's and stay
 * "not known" (-1000). Nothing leaves this computer.
 *
 * Saved as `overlay` in `wyrm_developer`, Android's key, in the windows
 * platform document (DesktopAccountSync).
 */
object DesktopDiagnostics {
    const val PREFS = "wyrm_developer"
    private const val KEY = "overlay"

    var on by mutableStateOf(false)
        private set

    private var thread: Thread? = null

    fun load() {
        on = DesktopContext.getSharedPreferences(PREFS, 0).getBoolean(KEY, false)
        apply()
        DesktopDeveloper.load()
    }

    fun set(value: Boolean) {
        on = value
        DesktopContext.getSharedPreferences(PREFS, 0).edit().putBoolean(KEY, value).apply()
        apply()
    }

    private fun apply() {
        if (!Engine.started) return
        runCatching { WyrmActivity.nativeSetDiagnostics(on) }
        if (on) start() else stop()
    }

    @Synchronized
    private fun start() {
        if (thread != null) return
        thread = Thread({
            try {
                while (!Thread.currentThread().isInterrupted) {
                    runCatching { push() }
                    Thread.sleep(1000)
                }
            } catch (_: InterruptedException) {
            }
        }, "wyrm-diagnostics").apply { isDaemon = true; start() }
    }

    @Synchronized
    private fun stop() {
        thread?.interrupt()
        thread = null
    }

    private fun push() {
        val v = FloatArray(16) { -1000f }
        val os = ManagementFactory.getOperatingSystemMXBean()
        val cores = Runtime.getRuntime().availableProcessors()
        v[1] = cores.toFloat()
        (os as? com.sun.management.OperatingSystemMXBean)?.let { bean ->
            val load = bean.processCpuLoad
            if (load >= 0) v[0] = (load * 100).toFloat()
            v[9] = (bean.freeMemorySize / (1024 * 1024)).toFloat()
            v[10] = (bean.totalMemorySize / (1024 * 1024)).toFloat()
        }
        val runtime = Runtime.getRuntime()
        v[11] = ((runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)).toFloat()
        v[12] = ManagementFactory.getThreadMXBean().threadCount.toFloat()
        runCatching {
            val hz = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.displayMode.refreshRate
            if (hz > 0) v[14] = hz.toFloat()
        }
        val chip = System.getenv("PROCESSOR_IDENTIFIER")?.substringBefore(',')?.trim().orEmpty()
        val text = listOf("Windows PC", chip, "${System.getProperty("os.name")} ${System.getProperty("os.version")}")
            .filter { it.isNotBlank() }.joinToString(" · ")
        WyrmActivity.nativePushDiagnostics(v, text)
    }
}

/**
 * Settings › Developer on Wyrm Desktop (OM, 2026-11-12): Developer Mode (`on`,
 * Android's key) and, only while it is on, Swapchain images (`swap_images`):
 * how many images the game window's swapchain asks Windows for. 0 = Vlither's
 * own count (minImageCount + 1, three on Windows), else 2, 3 or 4, so OM can
 * try each one himself. Developer Mode off always uses Vlither's. Both in
 * `wyrm_developer`, the windows platform document (DesktopAccountSync).
 */
object DesktopDeveloper {
    private const val KEY_ON = "on"
    private const val KEY_SWAP = "swap_images"

    var on by mutableStateOf(false)
        private set
    var swapImages by androidx.compose.runtime.mutableIntStateOf(0)
        private set

    private val prefs get() = DesktopContext.getSharedPreferences(DesktopDiagnostics.PREFS, 0)

    fun load() {
        on = prefs.getBoolean(KEY_ON, false)
        swapImages = prefs.getInt(KEY_SWAP, 0).takeIf { it in 2..4 } ?: 0
        apply()
    }

    fun applyOn(value: Boolean) {
        on = value
        prefs.edit().putBoolean(KEY_ON, value).apply()
        apply()
    }

    fun applySwapImages(value: Int) {
        swapImages = value.takeIf { it in 2..4 } ?: 0
        prefs.edit().putInt(KEY_SWAP, swapImages).apply()
        apply()
    }

    private fun apply() {
        if (!Engine.started) return
        runCatching { WyrmActivity.nativeDesktopSetSwapImages(if (on) swapImages else 0) }
    }
}
