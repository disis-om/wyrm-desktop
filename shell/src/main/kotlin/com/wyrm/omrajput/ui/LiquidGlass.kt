package com.wyrm.omrajput.ui

import androidx.compose.animation.core.EaseOut
import androidx.compose.foundation.clickable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Liquid Glass, for Compose.
 *
 * Wyrm iOS gets its switches, sliders, segmented pills and tab bar from the
 * system, and on iOS 26 the system draws them in Liquid Glass: a thumb that
 * lifts into a clear lens while a finger holds it, bends whatever is under
 * it, and lands again with a small overshoot. Android has no such thing, so
 * this file builds it — the lens is a real refraction of what sits behind
 * (Kyant's backdrop, a RuntimeShader on Android 13 and up), not a white
 * blob with a gradient on it.
 *
 * Sizes and springs are the iOS ones: SwiftUI's spring(response:damping:)
 * converts to stiffness (2π / response)² at the same damping ratio, so a
 * number read off the Swift source means the same motion here.
 */

/*
 * WYRM DESKTOP COPY (OM, 2026-10-11: "liquid glass hata de, sab waisa hi bina
 * glass ke, smooth"). Same names, parameters, springs, drags and haptics as
 * Wyrm Android's LiquidGlass.kt, without the backdrop library: every surface
 * is what Android itself draws where glass is unavailable (Wyrm.TabBar under
 * the frost); thumbs are solid and lift a little on press. Not synced from
 * Android (shell/build.gradle.kts): change both by hand.
 */

/**
 * How much paper the tab bar lays over what it refracts, and how far it
 * blurs it. The phones keep 0.4 and 8 dp; Wyrm Desktop's bar floats over
 * any page (the Skin stage, a dark floor) and asks for more so its labels
 * always read.
 */
val LocalTabBarFrost = staticCompositionLocalOf { 0.4f }
val LocalTabBarBlur = staticCompositionLocalOf { 8.dp }

/** Height the floating tab bar takes out of a root page's scroll content. */
val LocalRootTabClearance = staticCompositionLocalOf { 0.dp }

/** iOS draws every rounded corner with continuous curvature. */
val WyrmCapsule: Shape = Capsule()
fun wyrmRounded(radius: Dp): Shape = RoundedRectangle(radius)

/** SwiftUI `.spring(response:dampingFraction:)`. */
internal fun <T> iosSpring(response: Float, damping: Float, threshold: T? = null): SpringSpec<T> {
    val omega = 2f * PI.toFloat() / response
    // Wyrm Desktop (OM, 2026-10-11: no bounce): never under-damped, so nothing
    // overshoots and wobbles back; the same response, so the same speed.
    return spring(dampingRatio = maxOf(damping, 1f), stiffness = omega * omega, visibilityThreshold = threshold)
}

/** SwiftUI `.interpolatingSpring(stiffness:damping:)` at unit mass. */
internal fun <T> iosInterpolatingSpring(stiffness: Float, damping: Float): SpringSpec<T> =
    // Wyrm Desktop: critically damped at least (no bounce), as iosSpring.
    spring(dampingRatio = maxOf(damping / (2f * kotlin.math.sqrt(stiffness)), 1f), stiffness = stiffness)

/** The system fills iOS paints unselected controls with. */
internal object IosFill {
    val switchOff: Color get() = if (Wyrm.currentPalette.dark) Color(0x52787880) else Color(0x29787880)
    val segmentTrack: Color get() = if (Wyrm.currentPalette.dark) Color(0x3D767680) else Color(0x1F767680)
    // Dark themes: a light thumb carrying dark ink (Wyrm.PillInk), as on iOS.
    val segmentThumb: Color get() = if (Wyrm.currentPalette.dark) Wyrm.PillThumb else Color.White
    val sliderTrack: Color get() = if (Wyrm.currentPalette.dark) Color(0x52787880) else Color(0x33787880)
}


/* ------------------------------------------------------------------ switch */

/**
 * The iOS 26 switch: 63 × 28 track, a 37 × 24 capsule thumb. Press and it
 * swells into a clear lens over the track; drag it or let go and it springs
 * across and lands. Tinted with the theme's live colour, as Wyrm iOS tints
 * its system toggles.
 */
@Composable
fun LiquidSwitch(
    on: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Wyrm.Live,
    surface: Color = Wyrm.Card,
) {
    val trackW = 63.dp
    val trackH = 28.dp
    val thumbW = 37.dp
    val thumbH = 24.dp
    val inset = 2.dp
    val density = LocalDensity.current
    val travelPx = with(density) { (trackW - thumbW - inset * 2).toPx() }
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val fraction = remember { Animatable(if (on) 1f else 0f) }
    val lift = remember { Animatable(0f) }
    var holding by remember { mutableStateOf(false) }
    val current by rememberUpdatedState(on)
    val toggle by rememberUpdatedState(onToggle)

    LaunchedEffect(on) {
        if (!holding) fraction.animateTo(if (on) 1f else 0f, iosSpring(0.34f, 0.68f))
    }

    val offTrack = IosFill.switchOff
    Box(
        modifier = modifier
            .size(trackW, trackH)
            .semantics {
                role = Role.Switch
                stateDescription = if (on) "On" else "Off"
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown()
                    holding = true
                    scope.launch { lift.animateTo(1f, iosSpring(0.3f, 0.48f)) }
                    val start = fraction.value
                    var dx = 0f
                    var dragged = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        dx += change.positionChange().x
                        if (!dragged && abs(dx) > slop) dragged = true
                        if (dragged) {
                            change.consume()
                            val next = (start + dx / travelPx).coerceIn(0f, 1f)
                            if ((next > 0.5f) != (fraction.value > 0.5f)) {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            }
                            scope.launch { fraction.snapTo(next) }
                        }
                    }
                    val target = if (dragged) fraction.value > 0.5f else !current
                    haptics.performHapticFeedback(if (target) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                    scope.launch { fraction.animateTo(if (target) 1f else 0f, iosSpring(0.34f, 0.62f)) }
                    scope.launch {
                        delay(if (dragged) 30 else 160)
                        lift.animateTo(0f, iosInterpolatingSpring(240f, 12f))
                        holding = false
                    }
                    if (target != current) toggle(target)
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        // The track as seen, exactly its own size, so nothing beside it is painted over.
        Box(
            Modifier
                .align(Alignment.Center)
                .requiredSize(trackW, trackH)
                .drawBehind {
                    val colour = lerp(offTrack, tint, fraction.value)
                    drawRoundRect(
                        color = if (enabled) colour else colour.copy(alpha = colour.alpha * 0.5f),
                        cornerRadius = CornerRadius(size.height / 2f),
                    )
                },
        )
        SolidThumb(
            lift = { lift.value },
            scaleX = 1.42f,
            scaleY = 1.5f,
            restColor = Color.White,
            modifier = Modifier
                .offset { androidx.compose.ui.unit.IntOffset((inset.toPx() + travelPx * fraction.value).roundToInt(), 0) }
                .size(thumbW, thumbH),
        )
    }
}

/**
 * The thumb, solid and white. It does not swell on press (OM, 2026-10-11: no
 * bounce); only its shadow deepens a touch while held. [scaleX] / [scaleY]
 * are Android's lens swell, kept so the call sites stay Android's.
 */
@Composable
private fun SolidThumb(
    lift: () -> Float,
    scaleX: Float,
    scaleY: Float,
    restColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .drawBehind {
                val l = lift()
                val r = CornerRadius(size.height / 2f)
                val drop = lerp(2.5f, 4f, l).dp.toPx()
                drawRoundRect(Color.Black.copy(alpha = lerp(0.10f, 0.14f, l)), Offset(0f, drop * 0.5f), size, r)
                drawRoundRect(Color.Black.copy(alpha = 0.05f), Offset(0f, drop), size, r)
                drawRoundRect(restColor, cornerRadius = r)
            },
    )
}

/* ------------------------------------------------------------------ slider */

/**
 * The iOS 26 slider: a 6 dp track filled in [active] up to a 38 × 24 capsule
 * thumb. Drag the thumb and it lifts into a lens and follows the finger
 * relative to where it was caught. Only the thumb moves it (OM, 2026-10-05):
 * a tap on the track, or a scroll passing over it, changes nothing. [steps] follows Material's meaning — the stops between ends.
 */
@Composable
fun LiquidSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    active: Color = Wyrm.Ink,
    surface: Color = Wyrm.Card,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val thumbW = 38.dp
    val thumbH = 24.dp
    val trackH = 6.dp
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val lift = remember { Animatable(0f) }
    var dragFraction by remember { mutableFloatStateOf(-1f) }
    val change by rememberUpdatedState(onValueChange)
    val finished by rememberUpdatedState(onValueChangeFinished)
    // Holding a slider that changes a control raises the adjust preview.
    val adjusting by rememberUpdatedState(LocalAdjustSubject.current)
    val shownFraction = if (dragFraction >= 0f) dragFraction else ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val animatedFraction = remember { Animatable(shownFraction) }
    LaunchedEffect(shownFraction) {
        if (dragFraction >= 0f) animatedFraction.snapTo(shownFraction)
        else animatedFraction.animateTo(shownFraction, iosSpring(0.3f, 0.8f))
    }

    fun snap(f: Float): Float {
        if (steps <= 0) return f.coerceIn(0f, 1f)
        val segments = steps + 1
        return ((f * segments).roundToInt().toFloat() / segments).coerceIn(0f, 1f)
    }

    val trackFill = IosFill.sliderTrack
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(34.dp)
            .semantics { role = Role.Button }
            .pointerInput(enabled, steps, valueRange) {
                if (!enabled) return@pointerInput
                val thumbPx = thumbW.toPx()
                val run = (size.width - thumbPx).coerceAtLeast(1f)
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val current = animatedFraction.value
                    val thumbCentre = thumbPx / 2f + run * current
                    val onThumb = abs(down.position.x - thumbCentre) <= thumbPx / 2f + 14.dp.toPx()
                    // OM, 2026-10-05: only the thumb moves a slider. A touch anywhere
                    // else on the track (a scroll passing over it) changes nothing,
                    // and a mostly vertical swipe stays the page's.
                    if (!onThumb) return@awaitEachGesture
                    val start = current
                    var lastStop = snap(start)
                    var claimed = false
                    var held: AdjustSubject? = null
                    var dx = 0f
                    var dy = 0f
                    try { while (true) {
                        val event = awaitPointerEvent()
                        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!pointer.pressed) break
                        val moved = pointer.positionChange()
                        dx += moved.x
                        dy += moved.y
                        if (!claimed) {
                            if (abs(dy) > slop && abs(dy) > abs(dx)) break
                            if (abs(dx) <= slop / 3f) continue
                            claimed = true
                            dragFraction = start
                            held = adjusting
                            held?.let { AdjustPreview.editing(it, true) }
                            scope.launch { lift.animateTo(1f, iosSpring(0.3f, 0.5f)) }
                        }
                        pointer.consume()
                        val raw = (start + dx / run).coerceIn(0f, 1f)
                        val stop = snap(raw)
                        dragFraction = if (steps > 0) stop else raw
                        if (stop != lastStop) {
                            if (steps > 0 || stop == 0f || stop == 1f) {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                            }
                            lastStop = stop
                        }
                        change(valueRange.start + stop * span)
                    } } finally { held?.let { AdjustPreview.editing(it, false) } }
                    if (claimed) {
                        dragFraction = -1f
                        finished?.invoke()
                        scope.launch { lift.animateTo(0f, iosInterpolatingSpring(260f, 13f)) }
                    }
                }
            },
    ) {
        val run = maxWidth - thumbW
        // The track as seen: only the slider's own 34 dp, so it never paints over
        // the label above it (the padded copy below used to cut labels in half).
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind {
                    val th = trackH.toPx()
                    val top = (size.height - th) / 2f
                    val radius = CornerRadius(th / 2f)
                    drawRoundRect(trackFill, Offset(0f, top), Size(size.width, th), radius)
                    val thumbPx = thumbW.toPx()
                    val filled = thumbPx / 2f + (size.width - thumbPx) * animatedFraction.value
                    drawRoundRect(
                        if (enabled) active else active.copy(alpha = 0.35f),
                        Offset(0f, top),
                        Size(filled.coerceAtLeast(th), th),
                        radius,
                    )
                },
        )
        SolidThumb(
            lift = { lift.value },
            scaleX = 1.5f,
            scaleY = 1.55f,
            restColor = Color.White,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset { androidx.compose.ui.unit.IntOffset((run.toPx() * animatedFraction.value).roundToInt(), 0) }
                .size(thumbW, thumbH),
        )
    }
}

/* --------------------------------------------------------------- segmented */

/**
 * The iOS 26 segmented control: a capsule well with a capsule thumb. Hold
 * the thumb and it lifts into a lens you can drag across the segments; tap a
 * segment and it lifts, travels and lands. Type is Manrope, medium at rest
 * and bold when chosen, as Wyrm iOS sets it through UIAppearance.
 */
@Composable
fun LiquidSegmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    height: Dp = 34.dp,
    fontSize: TextUnit = 13.sp,
    /** Quiet text after each label, e.g. a count. */
    counts: List<String>? = null,
    /** A live position (0..n-1) to follow, e.g. a pager's scroll. */
    follow: Float? = null,
) {
    if (options.isEmpty()) return
    val safe = selected.coerceIn(options.indices)
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val lift = remember { Animatable(0f) }
    val position = remember { Animatable(safe.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    val pick by rememberUpdatedState(onSelect)
    val current by rememberUpdatedState(safe)
    LaunchedEffect(safe) {
        if (!dragging && follow == null) position.animateTo(safe.toFloat(), iosSpring(0.36f, 0.72f))
    }
    LaunchedEffect(follow) {
        if (follow != null && !dragging) position.snapTo(follow.coerceIn(0f, options.size - 1f))
    }
    val inset = 2.dp
    BoxWithConstraints(
        modifier = modifier
            .height(height)
            .pointerInput(options.size) {
                val insetPx = inset.toPx()
                val segment = (size.width - insetPx * 2) / options.size
                fun indexAt(x: Float) = ((x - insetPx) / segment).toInt().coerceIn(0, options.size - 1)
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val startIndex = indexAt(down.position.x)
                    val onThumb = startIndex == current
                    dragging = true
                    scope.launch { lift.animateTo(1f, iosSpring(0.3f, 0.5f)) }
                    var lastPreview = startIndex
                    var moved = false
                    val downX = down.position.x
                    while (true) {
                        val event = awaitPointerEvent()
                        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!pointer.pressed) break
                        val x = pointer.position.x
                        if (abs(x - downX) > viewConfiguration.touchSlop) moved = true
                        if (onThumb && moved) {
                            pointer.consume()
                            val continuous = ((x - insetPx) / segment - 0.5f).coerceIn(0f, options.size - 1f)
                            scope.launch { position.animateTo(continuous, iosSpring(0.22f, 0.62f)) }
                            val preview = indexAt(x)
                            if (preview != lastPreview) {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                lastPreview = preview
                            }
                        }
                    }
                    val target = if (onThumb && moved) lastPreview else startIndex
                    if (target != current) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    scope.launch { position.animateTo(target.toFloat(), iosSpring(0.34f, 0.7f)) }
                    scope.launch {
                        delay(if (moved) 40 else 200)
                        lift.animateTo(0f, iosInterpolatingSpring(240f, 11f))
                        dragging = false
                    }
                    if (target != current) pick(target)
                }
            },
    ) {
        val segmentWidth = (maxWidth - inset * 2) / options.size
        val track = IosFill.segmentTrack
        val thumb = IosFill.segmentThumb
        Box(
            Modifier
                .matchParentSize()
                .clip(WyrmCapsule)
                .background(Wyrm.Card)
                .background(track),
        ) {
            // The thumb, a raised capsule beneath the words; it slides, no swell.
            Box(
                Modifier
                    .offset { androidx.compose.ui.unit.IntOffset(((inset + segmentWidth * position.value).toPx()).roundToInt(), inset.roundToPx()) }
                    .size(segmentWidth, height - inset * 2)
                    .drawBehind {
                        val r = CornerRadius(size.height / 2f)
                        drawRoundRect(Color.Black.copy(alpha = 0.06f), Offset(0f, 1.dp.toPx()), size, r)
                        drawRoundRect(thumb, cornerRadius = r)
                    },
            )
            Row(Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = inset)) {
                options.forEachIndexed { index, label ->
                    val chosen = index == safe
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = label,
                                fontFamily = Wyrm.Body,
                                fontWeight = if (chosen) FontWeight.Bold else FontWeight.Medium,
                                fontSize = fontSize,
                                color = if (chosen) Wyrm.PillInk else Wyrm.Mute,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            counts?.getOrNull(index)?.let { count ->
                                Text(
                                    text = "  $count",
                                    fontFamily = Wyrm.Body,
                                    fontWeight = if (chosen) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = fontSize,
                                    color = Wyrm.Quiet,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/* ---------------------------------------------------------------- tab bar */

/**
 * The iOS 26 tab bar, built the way the backdrop library's own reference
 * `LiquidBottomTabs` builds it (Kyant0/AndroidLiquidGlass 1.0.6, Apache 2.0).
 *
 * Three layers, all drawn from the first frame so nothing pops in on a press:
 * the bar (refracting the page), an ink-tinted copy of the tabs recorded as a
 * backdrop, and the selection pill. At rest the pill is a quiet tinted thumb;
 * a press or a drag raises it into a lens over the tinted copy, which is how
 * the icon under the finger turns ink and swells through the glass. The pill
 * follows the finger with damped physics, squashes with speed, the bar swells
 * and shifts a little with the drag, and a touch light follows the finger.
 */
@Composable
fun LiquidTabBar(
    tabs: List<LiquidTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    collapsed: Boolean = false,
    onExpand: () -> Unit = {},
) {
    // iOS 26 `.tabBarMinimizeBehavior(.onScrollDown)`: the bar folds towards
    // the leading edge into a glass circle carrying the chosen tab, and a tap
    // on that circle (or scrolling back up) unfolds it.
    val fold = remember { Animatable(if (collapsed) 1f else 0f) }
    LaunchedEffect(collapsed) { fold.animateTo(if (collapsed) 1f else 0f, iosSpring(0.42f, 0.78f)) }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // Resting open, the real bar with its pill and drag. Anything else is one
        // piece of glass whose width and height run from the bar to a 56 dp
        // circle: the same glass the whole way, so it never swaps or blinks.
        if (fold.value <= 0.001f) {
            ExpandedLiquidTabBar(tabs = tabs, selected = selected, onSelect = onSelect)
        } else {
            FoldingTabGlass(
                tabs = tabs,
                selected = selected,
                fold = fold.value,
                fullWidth = maxWidth - 36.dp,
                onExpand = onExpand,
            )
        }
    }
}

/**
 * The bar mid-fold and folded. The tabs stay laid out at full width inside the
 * shrinking glass, so they slide out under its trailing edge and fade instead
 * of squeezing; the chosen tab fades up in the circle as it closes.
 */
@Composable
private fun FoldingTabGlass(
    tabs: List<LiquidTab>,
    selected: Int,
    fold: Float,
    fullWidth: Dp,
    onExpand: () -> Unit,
) {
    val container = Wyrm.Paper.copy(alpha = LocalTabBarFrost.current)
    // A light, see-through pill over the chosen tab; its ink shows through (OM).
    val restingThumb = Wyrm.TabThumb
    val width = androidx.compose.ui.unit.lerp(fullWidth, 56.dp, fold)
    val height = androidx.compose.ui.unit.lerp(64.dp, 56.dp, fold)
    val barAlpha = (1f - fold / 0.5f).coerceIn(0f, 1f)
    val iconAlpha = ((fold - 0.55f) / 0.45f).coerceIn(0f, 1f)
    Box(
        Modifier
            .padding(start = 18.dp)
            .width(width)
            .height(height)
            .barSurface(container)
            .clickable(enabled = fold >= 0.98f, interactionSource = null, indication = null, role = Role.Button) { onExpand() },
        contentAlignment = Alignment.CenterStart,
    ) {
        if (barAlpha > 0f) {
            val tabWidth = (fullWidth - 8.dp) / tabs.size.coerceAtLeast(1)
            Box(
                Modifier
                    .requiredWidth(fullWidth)
                    .height(64.dp)
                    .graphicsLayer { alpha = barAlpha }
                    .padding(4.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    Modifier
                        .offset(x = tabWidth * selected)
                        .width(tabWidth)
                        .height(56.dp)
                        .clip(WyrmCapsule)
                        .background(restingThumb),
                )
                Row(Modifier.fillMaxWidth().fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                    tabs.forEachIndexed { index, tab ->
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            tab.content(index == selected)
                        }
                    }
                }
            }
        }
        if (iconAlpha > 0f) {
            Box(
                Modifier
                    .size(56.dp)
                    .graphicsLayer {
                        alpha = iconAlpha
                        val s = lerp(0.7f, 1f, iconAlpha)
                        scaleX = s
                        scaleY = s
                    }
                    .padding(4.dp)
                    .clip(WyrmCapsule)
                    .background(restingThumb),
                contentAlignment = Alignment.Center,
            ) { tabs.getOrNull(selected)?.content?.invoke(true) }
        }
    }
}

@Composable
private fun ExpandedLiquidTabBar(
    tabs: List<LiquidTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Plain and smooth (OM, 2026-10-11: "bounce hoke jate h, plainly transfer
    // hone chaiye smoothly"): the pill glides to the chosen tab on an ease-out
    // curve and stops there. No press swell, no squash, no overshoot, no drag
    // (a computer clicks its tabs).
    val count = tabs.size.coerceAtLeast(1)
    val pick by rememberUpdatedState(onSelect)
    val container = Wyrm.Paper.copy(alpha = LocalTabBarFrost.current)
    // A light, see-through pill over the chosen tab; its ink shows through (OM).
    val restingThumb = Wyrm.TabThumb
    val position = remember { Animatable(selected.toFloat()) }
    LaunchedEffect(selected) {
        position.animateTo(selected.toFloat(), tween(280, easing = FastOutSlowInEasing))
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        val density = LocalDensity.current
        val tabWidth = with(density) { (constraints.maxWidth.toFloat() - 8.dp.toPx()) / count }

        Row(
            Modifier
                .barSurface(container)
                .height(64.dp)
                .fillMaxWidth()
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(WyrmCapsule)
                        .clickable(interactionSource = null, indication = null, role = Role.Tab) {
                            if (index != selected) pick(index)
                        },
                    contentAlignment = Alignment.Center,
                ) { tab.content(index == selected) }
            }
        }

        // The selection pill, gliding (translation only: drawn in its layer,
        // so the move never recomposes the bar).
        Box(
            Modifier
                .padding(horizontal = 4.dp)
                .graphicsLayer { translationX = position.value * tabWidth }
                .clip(WyrmCapsule)
                .drawBehind { drawRect(restingThumb) }
                .height(56.dp)
                .fillMaxWidth(1f / count),
        )
    }
}

/**
 * The tab bar's surface: Android's own fallback where there is no glass
 * (Wyrm.TabBar under the paper frost), a hairline and a soft shadow.
 */
private fun Modifier.barSurface(frost: Color): Modifier = this
    .shadow(10.dp, WyrmCapsule, clip = false, ambientColor = Color.Black.copy(alpha = 0.07f), spotColor = Color.Black.copy(alpha = 0.07f))
    .clip(WyrmCapsule)
    .drawBehind {
        drawRect(Wyrm.TabBar)
        drawRect(frost)
    }
    .border(1.dp, Wyrm.Rule.copy(alpha = 0.6f), WyrmCapsule)

/** One tab: its own drawing, told whether it is the chosen one. */
class LiquidTab(val content: @Composable (selected: Boolean) -> Unit)

/** The page behind the bars. The desktop draws no glass, so it is never read for refraction. */
val LocalPageBackdrop = staticCompositionLocalOf<com.kyant.backdrop.Backdrop> { com.kyant.backdrop.backdrops.emptyBackdrop() }

/**
 * Android's `liquidGlass` for the shared screens: on the desktop (no glass,
 * OM 2026-10-03) the surface is Android's own [fallback] colour in [shape],
 * with [tint] over it. Blur, lens and shadow are not drawn.
 */
@Suppress("UNUSED_PARAMETER")
fun Modifier.liquidGlass(
    backdrop: com.kyant.backdrop.Backdrop,
    shape: Shape = WyrmCapsule,
    tint: Color = Color.Transparent,
    blur: Dp = 1.5.dp,
    refraction: Dp = 16.dp,
    depth: Dp = 26.dp,
    shadow: com.kyant.backdrop.shadow.Shadow? = com.kyant.backdrop.shadow.Shadow(14.dp, DpOffset(0.dp, 6.dp), Color.Black.copy(alpha = 0.07f)),
    fallback: Color = Wyrm.TabBar,
): Modifier = this
    .then(if (shadow != null) Modifier.shadow(shadow.radius / 2, shape, clip = false, ambientColor = shadow.color, spotColor = shadow.color) else Modifier)
    .clip(shape)
    .drawBehind {
        drawRect(fallback)
        if (tint.alpha > 0f) drawRect(tint.copy(alpha = tint.alpha * 0.35f))
    }
