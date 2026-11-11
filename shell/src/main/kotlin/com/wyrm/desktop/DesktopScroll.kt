package com.wyrm.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.unit.Density
import java.awt.event.MouseWheelEvent
import kotlin.math.abs
import kotlin.math.sign

/**
 * Scrolling at Windows' own speed (OM, 2026-11-11: "scroll bhot fast hora h,
 * system se sync ho"). Compose's Windows rule moves a list by a twentieth of
 * its height per wheel step whatever the list, and a touchpad sends many
 * steps, so long lists flew. Here one wheel step moves Windows' "lines to
 * scroll" setting (Mouse settings; AWT's scrollAmount, 3 by default) times a
 * 20 dp line, and a touchpad's fractional steps move their share of that.
 * "One screen at a time" keeps Compose's page step. Compose's smooth
 * scrolling animation is kept.
 *
 * Compose has no public hook for this, so its own ScrollConfig is wrapped
 * through reflection; if that ever fails the app simply keeps Compose's.
 */
internal object DesktopScroll {
    private const val LINE_DP = 20f

    @Suppress("UNCHECKED_CAST")
    private val local: ProvidableCompositionLocal<Any?>? = runCatching {
        Class.forName("androidx.compose.foundation.gestures.DesktopScrollable_desktopKt")
            .getMethod("getLocalScrollConfig").invoke(null) as ProvidableCompositionLocal<Any?>
    }.getOrNull()

    private val config: Any? = runCatching {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return@runCatching null
        val iface = Class.forName("androidx.compose.foundation.gestures.ScrollConfig")
        val base = Class.forName("androidx.compose.foundation.gestures.WindowsWinUIConfig").getField("INSTANCE").get(null)
        java.lang.reflect.Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, method, args ->
            val result = method.invoke(base, *(args ?: emptyArray()))
            if (method.name.startsWith("calculateMouseWheelScroll") && args != null && args.size >= 3 && result is Long) {
                runCatching { rescale(args[0] as Density, args[1] as PointerEvent, result) }.getOrDefault(result)
            } else result
        }
    }.getOrNull()

    private fun rescale(density: Density, event: PointerEvent, composeOffset: Long): Long {
        val wheel = event.awtEventOrNull as? MouseWheelEvent ?: return composeOffset
        if (wheel.scrollType == MouseWheelEvent.WHEEL_BLOCK_SCROLL) return composeOffset
        val lines = wheel.scrollAmount.coerceIn(1, 30)
        val step = abs(wheel.preciseWheelRotation).toFloat() * lines * LINE_DP * density.density
        val x = Float.fromBits((composeOffset ushr 32).toInt())
        val y = Float.fromBits((composeOffset and 0xFFFFFFFFL).toInt())
        val nx = if (x != 0f) sign(x) * step else 0f
        val ny = if (y != 0f) sign(y) * step else 0f
        return (nx.toRawBits().toLong() shl 32) or (ny.toRawBits().toLong() and 0xFFFFFFFFL)
    }

    @Composable
    fun Provide(content: @Composable () -> Unit) {
        val l = local
        val c = config
        if (l != null && c != null) CompositionLocalProvider(l provides c, content = content) else content()
    }
}
