package com.wyrm.desktop

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.wyrm.omrajput.ui.IosSpinner
import com.wyrm.omrajput.ui.SkinTextures
import com.wyrm.omrajput.ui.Wyrm
import com.wyrm.omrajput.ui.WyrmLookSpec
import com.wyrm.omrajput.ui.drawSkinAlong
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** What the stage wears: the snake as the preview builds it, and the floor under it. */
internal data class StageSkin(
    val groups: List<Int>,
    val colors: List<Int>,
    val preset: Int,
    val custom: Boolean,
    val accessory: Int,
    val look: WyrmLookSpec,
)

private const val BEADS = 256
private const val WAVES = 1.25f
private const val SPEED = 1.8f // radians a second

/** How far the body swings: half of a full-height wave (OM: half the swing). */
private const val SWING = 0.5f

/**
 * The Skin page's stage: the worn snake, all 256 beads with its eyes,
 * accessory and Wyrm look, slithering in place along a travelling wave (the
 * head steady, the ripple running back to the tail). A change of skin
 * crossfades between the two snakes on the same wave, so nothing jumps.
 * [floor] is drawn under the snake (the Arena background section).
 */
@Composable
internal fun SlitherStage(
    textures: SkinTextures?,
    skin: StageSkin,
    floor: ImageBitmap?,
    floorId: Int?,
    floorAlpha: Float,
    top: Dp,
    /** 0 = the whole snake, 1 = the camera on its face (accessories, Wyrm looks). */
    faceZoom: Float = 0f,
    modifier: Modifier = Modifier,
) {
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = androidx.compose.runtime.withFrameNanos { it }
        while (true) androidx.compose.runtime.withFrameNanos { time.floatValue = (it - start) / 1_000_000_000f }
    }
    BoxWithConstraints(modifier.clipToBounds()) {
        val density = LocalDensity.current
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val topPx = with(density) { top.toPx() }
        val pad = with(density) { 28.dp.toPx() }
        val wave = remember(w, h, topPx) { fitWave(pad, topPx, w - pad, h - with(density) { 14.dp.toPx() }) }
        Canvas(Modifier.fillMaxSize()) { drawFloor(floor, floorId, floorAlpha) }
        if (textures == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { IosSpinner(size = 20.dp, colour = Wyrm.Ink) }
        } else {
            Crossfade(targetState = skin, animationSpec = tween(220), label = "stage-skin") { shown ->
                Canvas(Modifier.fillMaxSize()) {
                    if (faceZoom <= 0.001f) {
                        drawSlither(textures, shown, wave, time)
                    } else {
                        // The head stays put on the wave, so the camera can sit on it:
                        // scale about the head, then carry it to the stage's centre.
                        val head = Offset(wave.x1, wave.cy)
                        val zoom = 1f + (minOf(4.2f, size.height * 0.4f / wave.bead) - 1f) * faceZoom
                        val toCentre = Offset(size.width / 2f - head.x, (size.height + topPx) / 2f - head.y) * faceZoom
                        withTransform({
                            translate(toCentre.x, toCentre.y)
                            scale(zoom, zoom, pivot = head)
                        }) { drawSlither(textures, shown, wave, time) }
                    }
                }
            }
        }
    }
}

/** The floor, faded into the paper at the edges so the stage has no box. */
private fun DrawScope.drawFloor(floor: ImageBitmap?, floorId: Int?, alpha: Float) {
    if (alpha <= 0.001f || floorId == null) return
    when {
        floorId == 22 -> drawRect(Color.Black, alpha = alpha)
        floor != null -> {
            // Cover the stage, as an arena floor would.
            val scale = max(size.width / floor.width, size.height / floor.height)
            val dw = floor.width * scale
            val dh = floor.height * scale
            drawImage(
                floor,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(floor.width, floor.height),
                dstOffset = IntOffset(((size.width - dw) / 2).roundToInt(), ((size.height - dh) / 2).roundToInt()),
                dstSize = IntSize(dw.roundToInt(), dh.roundToInt()),
                alpha = alpha,
            )
        }
        else -> return
    }
    // Paper creeping in from the bottom, so the floor ends softly above the controls.
    drawRect(Brush.verticalGradient(0.62f to Color.Transparent, 1f to Wyrm.Paper.copy(alpha = alpha)))
}

/** A sine wave across the stage sized so the whole snake fits on it. */
private class Wave(val bead: Float, val amp: Float, val k: Float, val x0: Float, val x1: Float, val cy: Float) {
    val run get() = x1 - x0
    /** Calm near the head, full swing from a fifth of the way back. */
    fun envelope(u: Float): Float {
        val t = (u / (run * 0.2f)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
    fun y(u: Float, phase: Float) = cy + amp * envelope(u) * sin(k * u - phase)
}

private fun fitWave(left: Float, top: Float, right: Float, bottom: Float): Wave {
    fun build(s: Float): Wave {
        val x0 = left + s * 0.6f
        val x1 = right - s * 1.6f
        val amp = ((bottom - top) - s * 2.4f).coerceAtLeast(0f) / 2f * SWING
        val k = (2f * PI.toFloat() * WAVES) / (x1 - x0).coerceAtLeast(1f)
        return Wave(s, amp, k, x0, x1, (top + bottom) / 2f)
    }
    fun length(w: Wave): Float {
        var total = 0f
        var px = 0f
        var py = w.y(0f, 0f)
        val n = 320
        for (i in 1..n) {
            val u = w.run * i / n
            val y = w.y(u, 0f)
            total += hypot(u - px, y - py)
            px = u
            py = y
        }
        return total
    }
    var lo = 4f
    var hi = 80f
    repeat(26) {
        val mid = (lo + hi) / 2f
        val need = mid * (1f + (BEADS - 1) / 6f) * 1.03f
        if (length(build(mid)) >= need) lo = mid else hi = mid
    }
    return build(lo)
}

private fun DrawScope.drawSlither(t: SkinTextures, skin: StageSkin, wave: Wave, time: State<Float>) {
    val phase = time.value * SPEED
    val step = wave.bead / 6f
    // Arc-length table from the head (u = 0) back to the tail.
    val n = 420
    val us = FloatArray(n + 1)
    val ys = FloatArray(n + 1)
    val arc = FloatArray(n + 1)
    for (i in 0..n) {
        val u = wave.run * i / n
        us[i] = u
        ys[i] = wave.y(u, phase)
        if (i > 0) arc[i] = arc[i - 1] + hypot(us[i] - us[i - 1], ys[i] - ys[i - 1])
    }
    val points = arrayOfNulls<Offset>(BEADS)
    val headings = FloatArray(BEADS)
    var j = 0
    for (back in 0 until BEADS) {
        val d = back * step
        while (j < n && arc[j + 1] < d) j++
        val point: Offset
        val angle: Float
        if (j >= n) {
            // Past the end of the wave: carry straight on to the left.
            val extra = d - arc[n]
            point = Offset(wave.x1 - us[n] - extra, ys[n])
            angle = 0f
        } else {
            val span = (arc[j + 1] - arc[j]).coerceAtLeast(1e-4f)
            val f = (d - arc[j]) / span
            val u = us[j] + (us[j + 1] - us[j]) * f
            val y = ys[j] + (ys[j + 1] - ys[j]) * f
            point = Offset(wave.x1 - u, y)
            // Travelling towards the head: from the farther sample to the nearer.
            angle = Math.toDegrees(atan2((ys[j] - ys[j + 1]).toDouble(), (us[j + 1] - us[j]).toDouble())).toFloat()
        }
        val segment = BEADS - 1 - back
        points[segment] = point
        headings[segment] = angle
    }
    val px = 1.dp.toPx()
    drawSkinAlong(
        t, skin.groups, skin.colors, skin.preset, skin.custom, skin.accessory, skin.look,
        wave.bead, px, BEADS,
        place = { points[it] ?: Offset.Zero },
        heading = { headings[it] },
    ) { img, l, top, w, h, tint, alpha ->
        // Float placement (no rounding), so a moving bead never jitters.
        if (w > 0f && h > 0f && img.width > 0 && img.height > 0) {
            withTransform({
                translate(l, top)
                scale(w / img.width, h / img.height, pivot = Offset.Zero)
            }) {
                drawImage(img, alpha = alpha, colorFilter = tint?.let { ColorFilter.tint(it, BlendMode.Modulate) })
            }
        }
    }
}
