package com.wyrm.desktop

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import kotlin.math.roundToInt
import com.wyrm.omrajput.ui.isWyrmTag
import com.wyrm.omrajput.ui.TagPickerOrder
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wyrm.omrajput.ui.AdjustBackgroundButton
import com.wyrm.omrajput.ui.AirSkin
import com.wyrm.omrajput.ui.AirWheelPanel
import com.wyrm.omrajput.ui.BackgroundTile
import com.wyrm.omrajput.ui.GradientTrack
import com.wyrm.omrajput.ui.ImageTile
import com.wyrm.omrajput.ui.IosSectionLabel
import com.wyrm.omrajput.ui.LiquidSegmented
import com.wyrm.omrajput.ui.PaperSegmented
import com.wyrm.omrajput.ui.SelectionTile
import com.wyrm.omrajput.ui.SkinCatalog
import com.wyrm.omrajput.ui.SkinTextures
import com.wyrm.omrajput.ui.TileGrid
import com.wyrm.omrajput.ui.Wyrm
import com.wyrm.omrajput.ui.WyrmBeads
import com.wyrm.omrajput.ui.WyrmCapsule
import com.wyrm.omrajput.ui.WyrmLook
import com.wyrm.omrajput.ui.WyrmLookSpec
import com.wyrm.omrajput.ui.WyrmLookStore
import com.wyrm.omrajput.ui.iosSpring
import com.wyrm.omrajput.ui.rememberSkinTextures
import com.wyrm.omrajput.ui.wyrmRounded
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch

/*
 * Skin, for a desktop window (OM, 2026-10-02): the stage across the top
 * centre (the worn snake slithering, `SlitherStage`), the sections in a Liquid
 * Glass segmented control under it, and the chosen section below, laid out
 * wide. Everything a pointer rests on is tried on the stage at once (presets,
 * beads, accessories, Wyrm looks, floors); a click wears it. Same pieces,
 * catalogues and engine glue as Wyrm Android's Skin tab (IosSkinScreen).
 */

internal enum class Section(val title: String) {
    PRESETS("Default skins"), PATTERN("Pattern"), ACCESSORIES("Accessory"),
    WYRM_ACCESSORIES("Wyrm accessories"), BACKGROUND("Arena background"), TAGS("Tag"),
}

/* Tags are on again with NTL (OM, 2026-10-04 on the phones; the desktop
   2026-10-11): the arena drops were the oversized skin block, not tags. */

/** Where the Skin page was last, kept while the app runs. */
internal var lastSection = Section.PRESETS

/**
 * What the pointer is resting on, shown on the stage until it leaves. Leaving
 * waits a moment, so gliding from one tile to the next never flashes the worn
 * skin in between.
 */
private class TryOn(private val scope: CoroutineScope) {
    var preset by mutableStateOf<Int?>(null)
    var accessory by mutableStateOf<Int?>(null)
    var look by mutableStateOf<WyrmLookSpec?>(null)
    var background by mutableStateOf<Int?>(null)
    var bead by mutableStateOf<Pair<Int, Int>?>(null)
    private var leaving: Job? = null

    fun show(apply: TryOn.() -> Unit) {
        leaving?.cancel()
        clear()
        apply()
    }

    fun leave() {
        leaving?.cancel()
        leaving = scope.launch {
            delay(120)
            clear()
        }
    }

    fun clear() {
        preset = null
        accessory = null
        look = null
        background = null
        bead = null
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.tryOn(tryOn: TryOn, apply: TryOn.() -> Unit): Modifier = this
    .onPointerEvent(PointerEventType.Enter) { tryOn.show(apply) }
    .onPointerEvent(PointerEventType.Exit) { tryOn.leave() }

@Composable
fun SkinPage() {
    val textures by rememberSkinTextures()
    LaunchedEffect(Unit) { Engine.skinOpened() }
    val state = Engine.skin
    val background = Engine.arenaBackground
    val look = WyrmLookStore.spec()
    val prefs = remember { DesktopContext.getSharedPreferences("wyrm_skin_studio", android.content.Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val tryOn = remember { TryOn(scope) }

    var section by remember { mutableStateOf(lastSection) }
    // What is worn, mirrored locally so a click shows at once; the engine's echo re-syncs it.
    var preset by remember { mutableIntStateOf(state.preset) }
    var customEnabled by remember { mutableStateOf(state.custom) }
    var pattern by remember { mutableStateOf(state.code.mapNotNull { SkinCatalog.group(it) }) }
    var patternColors by remember { mutableStateOf(List(state.code.length) { state.colourAt(it) }) }
    var accessory by remember { mutableIntStateOf(state.accessory) }
    var backgroundId by remember { mutableIntStateOf(background) }
    LaunchedEffect(state) {
        preset = state.preset
        customEnabled = state.custom && state.code.isNotEmpty()
        val groups = state.code.mapNotNull { SkinCatalog.group(it) }
        pattern = groups
        patternColors = List(groups.size) { state.colourAt(it) }
        accessory = state.accessory
    }
    LaunchedEffect(background) { backgroundId = background }

    val customGroups = pattern.filter { it in SkinCatalog.validGroups }.take(256)
    val customColors = List(customGroups.size) { patternColors.getOrElse(it) { 0 } }
    val activeSource = if (customEnabled && customGroups.isNotEmpty()) customGroups
        else SkinCatalog.presets.getOrNull(preset) ?: listOf(7)
    val activeGroups = List(256) { activeSource[it % activeSource.size] }
    val activeColors = if (customEnabled && customGroups.isNotEmpty()) List(256) { customColors[it % customColors.size] } else List(256) { 0 }
    // While the pattern is being built, the stage shows only what is built.
    var editingPattern by remember { mutableStateOf(false) }
    var wheelRgb by remember { mutableIntStateOf(prefs.getInt("air-rgb", 0x808080) and 0xFFFFFF) }

    fun savePattern(groups: List<Int>, colors: List<Int>) {
        editingPattern = true
        pattern = groups
        patternColors = colors.take(groups.size)
        customEnabled = groups.isNotEmpty()
        Engine.skinCode(SkinCatalog.code(groups), IntArray(groups.size) { colors.getOrElse(it) { 0 } })
    }

    fun open(target: Section) {
        if (target != Section.PATTERN) editingPattern = false
        tryOn.clear()
        section = target
        lastSection = target
    }

    // "Try this skin": a trail's shared skin on the stage until Wear or Back to mine.
    val trial = SkinTrial.trial
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { SkinTrial.backToMine() } }
    // The stage: what is worn, or what the pointer is trying on.
    val wornAccessory = tryOn.accessory ?: trial?.skin?.accessory ?: accessory
    val wornLook = tryOn.look ?: trial?.look ?: look
    val stageSkin = if (trial != null && tryOn.preset == null && tryOn.bead == null) run {
        val s = trial.skin
        val groups = s.code.mapNotNull { SkinCatalog.group(it) }
        if (s.custom && groups.isNotEmpty()) {
            StageSkin(List(256) { groups[it % groups.size] }, List(256) { s.colourAt(it % groups.size) }, s.preset, true, wornAccessory, wornLook)
        } else {
            val preset = SkinCatalog.presets.getOrNull(s.preset) ?: listOf(7)
            StageSkin(List(256) { preset[it % preset.size] }, List(256) { 0 }, s.preset, false, wornAccessory, wornLook)
        }
    } else run {
        val hoveredPreset = tryOn.preset
        val hoveredBead = tryOn.bead
        when {
            hoveredPreset != null -> {
                val groups = SkinCatalog.presets[hoveredPreset]
                StageSkin(List(256) { groups[it % groups.size] }, List(256) { 0 }, hoveredPreset, false, wornAccessory, wornLook)
            }
            hoveredBead != null && customGroups.size < 256 -> {
                val groups = customGroups + hoveredBead.first
                val colours = customColors + hoveredBead.second
                StageSkin(List(256) { groups.getOrElse(it) { -1 } }, List(256) { colours.getOrElse(it) { 0 } }, preset, true, wornAccessory, wornLook)
            }
            editingPattern -> StageSkin(
                List(256) { customGroups.getOrElse(it) { -1 } }, List(256) { customColors.getOrElse(it) { 0 } },
                preset, customEnabled, wornAccessory, wornLook,
            )
            else -> StageSkin(activeGroups, activeColors, preset, customEnabled, wornAccessory, wornLook)
        }
    }
    val floorId = tryOn.background ?: backgroundId
    val floorAlpha by animateFloatAsState(if (section == Section.BACKGROUND) 1f else 0f, iosSpring(0.5f, 1f), label = "floor")
    // Accessories sit on the face: the stage's camera moves in on it.
    val faceZoom by animateFloatAsState(
        if (section == Section.ACCESSORIES || section == Section.WYRM_ACCESSORIES) 1f else 0f,
        iosSpring(0.62f, 0.9f),
        label = "face-zoom",
    )

    // Arriving smoothly (OM, 2026-10-11): the first visit draws the skin
    // atlases for the first time (the GPU upload of 3136 x 4032 pictures),
    // which used to stall the tab slide. The page slides in light, with a
    // spinner where the skin will be; the skin mounts once the slide is over
    // and fades in. Later visits find the pictures uploaded and skip this.
    var ready by remember { mutableStateOf(SkinPageWarm.done && textures != null) }
    val appear = remember { androidx.compose.animation.core.Animatable(if (ready) 1f else 0f) }
    LaunchedEffect(textures) {
        if (!ready && textures != null) {
            delay(420)
            androidx.compose.runtime.withFrameNanos { }
            ready = true
            SkinPageWarm.done = true
        }
    }
    LaunchedEffect(ready) {
        if (ready) appear.animateTo(1f, androidx.compose.animation.core.tween(280, easing = androidx.compose.animation.core.FastOutSlowInEasing))
    }
    if (!ready) {
        Box(Modifier.fillMaxSize().padding(top = TopTabsClearance), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                com.wyrm.omrajput.ui.IosSpinner(size = 26.dp)
                Text("Loading your skin…", fontFamily = Wyrm.Body, fontSize = 13.sp, color = Wyrm.Quiet)
            }
        }
        return
    }

    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer { alpha = appear.value }) {
        val stageHeight = (maxHeight * 0.38f).coerceIn(250.dp, 340.dp)
        Column(Modifier.fillMaxSize()) {
            // Full width under the floating tab bar: the glass refracts the snake.
            SlitherStage(
                textures = textures,
                skin = stageSkin,
                floor = textures?.backgrounds?.get(floorId),
                floorId = floorId,
                floorAlpha = floorAlpha,
                top = TopTabsClearance - 8.dp,
                faceZoom = faceZoom,
                modifier = Modifier.fillMaxWidth().height(stageHeight),
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f))
                LiquidSegmented(
                    options = Section.entries.map { it.title },
                    selected = section.ordinal,
                    onSelect = { open(Section.entries[it]) },
                    modifier = Modifier.widthIn(max = 940.dp).fillMaxWidth(0.82f),
                    height = 40.dp,
                    fontSize = 13.sp,
                )
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    if (section == Section.BACKGROUND) AdjustBackgroundButton({ Engine.openBackgroundEditor() })
                }
            }
            // Android's trial banner: whose skin this is, Wear, Back to mine.
            androidx.compose.animation.AnimatedVisibility(trial != null) {
                var shown by remember { mutableStateOf(trial) }
                if (trial != null) shown = trial
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(Modifier.widthIn(max = 720.dp)) {
                        shown?.let { com.wyrm.omrajput.ui.SkinTrialBanner(it.author, SkinTrial::wear, SkinTrial::backToMine) }
                    }
                }
            }
            AnimatedContent(
                targetState = section,
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    (fadeIn(iosSpring(0.4f, 0.9f)) + slideInHorizontally(iosSpring(0.48f, 0.86f)) { (if (forward) 1 else -1) * it / 9 }) togetherWith
                        (fadeOut(iosSpring(0.28f, 1f)) + slideOutHorizontally(iosSpring(0.48f, 0.86f)) { (if (forward) -1 else 1) * it / 12 })
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                label = "skin-section",
            ) { shown ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = 1180.dp).fillMaxSize()) {
                        when (shown) {
                            Section.PRESETS -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                                Box(Modifier.widthIn(max = 840.dp)) {
                                    PresetsSection(preset, customEnabled, tryOn) { index ->
                                        preset = index
                                        customEnabled = false
                                        editingPattern = false
                                        Engine.skinPreset(index)
                                    }
                                }
                            }
                            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
                                when (shown) {
                                    Section.PATTERN -> {
                                        PatternBar(
                                            customGroups = customGroups,
                                            onType = { typed ->
                                                val groups = typed.lowercase().take(256).mapNotNull { SkinCatalog.group(it) }
                                                val common = groups.zip(customGroups).takeWhile { it.first == it.second }.size
                                                savePattern(groups, List(groups.size) { if (it < common) customColors[it] else 0 })
                                            },
                                            onUndo = {
                                                val groups = customGroups.dropLast(1)
                                                savePattern(groups, customColors.take(groups.size))
                                            },
                                            onClear = { savePattern(emptyList(), emptyList()) },
                                        )
                                        PatternPickers(
                                            beads = {
                                                BeadPalette(textures, tryOn) { group ->
                                                    if (customGroups.size < 256) savePattern(customGroups + group, customColors + 0)
                                                }
                                            },
                                            wheel = {
                                                AirWheelPanel(textures, prefs, onColour = { wheelRgb = it }) { kind, rgb ->
                                                    if (customGroups.size < 256) {
                                                        savePattern(customGroups + AirSkin.nearestGroup(rgb), customColors + (AirSkin.marker(kind) or rgb))
                                                    }
                                                }
                                            },
                                            wyrmBeads = {
                                                WyrmBeadPalette(textures, wheelRgb, tryOn) { argb ->
                                                    if (customGroups.size < 256) {
                                                        savePattern(customGroups + AirSkin.nearestGroup(argb and 0xFFFFFF), customColors + argb)
                                                    }
                                                }
                                            },
                                        )
                                    }
                                    Section.ACCESSORIES -> {
                                        Spacer(Modifier.height(14.dp))
                                        TileGrid(minimum = 104.dp, count = SkinCatalog.accessories.size + 1) { index ->
                                            if (index == 0) {
                                                Box(Modifier.fillMaxSize().tryOn(tryOn) { this.accessory = -1 }) {
                                                    SelectionTile(accessory < 0, "None") { accessory = -1; Engine.skinAccessory(-1) }
                                                }
                                            } else {
                                                val item = SkinCatalog.accessories[index - 1]
                                                Box(Modifier.fillMaxSize().hoverLift().tryOn(tryOn) { this.accessory = item.id }) {
                                                    ImageTile(accessory == item.id, textures?.accessoryThumbnails?.get(item.id), 8.dp) {
                                                        accessory = item.id
                                                        Engine.skinAccessory(item.id)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    Section.WYRM_ACCESSORIES -> WyrmAccessoriesSection(textures, look, tryOn)
                                    Section.BACKGROUND -> {
                                        Spacer(Modifier.height(14.dp))
                                        TileGrid(minimum = 200.dp, count = SkinCatalog.backgroundOrder.size, aspect = null) { index ->
                                            val item = SkinCatalog.backgrounds[SkinCatalog.backgroundOrder[index]]
                                            Box(Modifier.hoverLift().tryOn(tryOn) { this.background = item.id }) {
                                                BackgroundTile(item, textures?.backgrounds?.get(item.id), backgroundId == item.id) {
                                                    backgroundId = item.id
                                                    Engine.skinBackground(item.id)
                                                }
                                            }
                                        }
                                    }
                                    Section.TAGS -> TagsSection(textures)
                                    else -> Unit
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A tile rises a touch under the pointer. */
@Composable
private fun Modifier.hoverLift(): Modifier {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val lift by animateFloatAsState(if (hovered) 1f else 0f, iosSpring(0.32f, 0.72f), label = "lift")
    return this.hoverable(hover).scale(1f + 0.035f * lift)
}

/**
 * The 66 default skins in one lazy list of pictures (shipped with the app,
 * `PresetPreviews`); two rows past each edge are kept ready. Resting on a
 * row tries it on the stage.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetsSection(preset: Int, custom: Boolean, tryOn: TryOn, onPick: (Int) -> Unit) {
    val rowAndGap = 58.dp + 6.dp
    val list = rememberLazyListState(cacheWindow = LazyLayoutCacheWindow(ahead = rowAndGap * 2, behind = rowAndGap * 2))
    LazyColumn(
        state = list,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(SkinCatalog.presets, key = { index, _ -> index }) { index, _ ->
            PresetRow(index, !custom && preset == index, Modifier.tryOn(tryOn) { this.preset = index }) { onPick(index) }
        }
    }
}

@Composable
private fun PresetRow(index: Int, worn: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val lift by animateFloatAsState(if (hovered) 1f else 0f, iosSpring(0.3f, 0.8f), label = "preset-hover")
    val shape = wyrmRounded(16.dp)
    Row(
        modifier
            .fillMaxWidth()
            .height(58.dp)
            .scale(1f + 0.008f * lift)
            .clip(shape)
            .background(Wyrm.Card.copy(alpha = 0.5f + 0.42f * lift))
            .border(if (worn) 2.dp else 1.dp, if (worn) Wyrm.Ink else Wyrm.Rule, shape)
            .hoverable(hover)
            .clickable(interactionSource = hover, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "%02d".format(index + 1),
            fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 11.sp,
            color = if (worn) Wyrm.Live else Wyrm.Quiet,
            modifier = Modifier.widthIn(min = 22.dp),
        )
        val picture = PresetPreviews.images[index]
        Box(Modifier.weight(1f).height(36.dp), contentAlignment = Alignment.CenterStart) {
            if (picture != null) {
                Image(
                    bitmap = picture,
                    contentDescription = "Skin ${index + 1}",
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.Center,
                    filterQuality = FilterQuality.High,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxWidth().height(22.dp).clip(WyrmCapsule).background(Wyrm.Well))
            }
        }
    }
}

/** The code field with its count, undo and clear: Android's pattern bar, one line. */
@Composable
private fun PatternBar(customGroups: List<Int>, onType: (String) -> Unit, onUndo: () -> Unit, onClear: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val codeStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp, color = Wyrm.Ink)
        BasicTextField(
            value = SkinCatalog.code(customGroups),
            onValueChange = onType,
            singleLine = true,
            textStyle = codeStyle,
            cursorBrush = SolidColor(Wyrm.Link),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            modifier = Modifier
                .weight(1f)
                .clip(wyrmRounded(12.dp))
                .background(Wyrm.Card.copy(alpha = 0.9f))
                .border(1.dp, Wyrm.Rule, wyrmRounded(12.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            decorationBox = { inner ->
                Box {
                    if (customGroups.isEmpty()) Text("Type or paste a skin code", style = codeStyle.copy(color = Wyrm.Quiet.copy(alpha = 0.6f)))
                    inner()
                }
            },
        )
        Text("${customGroups.size} / 256 beads", fontFamily = Wyrm.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Wyrm.Quiet)
        TextAction("UNDO", Wyrm.Ink, onUndo)
        TextAction("CLEAR", Color(0xFFFF3B30), onClear)
    }
}

@Composable
private fun TextAction(label: String, colour: Color, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Text(
        label,
        fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 10.5.sp, letterSpacing = 0.6.sp,
        color = colour.copy(alpha = if (hovered) 0.7f else 1f),
        modifier = Modifier
            .clip(wyrmRounded(8.dp))
            .hoverable(hover)
            .clickable(interactionSource = hover, indication = null, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
    )
}

/**
 * Colours, the AIR colour wheel and Wyrm's beads: side by side when the window
 * is wide, the wheel under the colours when it is not.
 */
@Composable
private fun PatternPickers(beads: @Composable () -> Unit, wheel: @Composable () -> Unit, wyrmBeads: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 1080.dp) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1f)) { IosSectionLabel("Build a Wyrm"); beads() }
                Column(Modifier.weight(1.1f)) { IosSectionLabel("Colour wheel"); wheel() }
                Column(Modifier.weight(1f)) { IosSectionLabel("Wyrm beads"); wyrmBeads() }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1f)) {
                    IosSectionLabel("Build a Wyrm"); beads()
                    IosSectionLabel("Colour wheel"); wheel()
                }
                Column(Modifier.weight(1f)) { IosSectionLabel("Wyrm beads"); wyrmBeads() }
            }
        }
    }
}

/** The colour groups as round beads; resting on one adds it to the stage. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BeadPalette(textures: SkinTextures?, tryOn: TryOn, onPick: (Int) -> Unit) {
    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (group in SkinCatalog.validGroups) {
            BeadButton(textures?.beads?.get(group), null, Modifier.tryOn(tryOn) { bead = group to 0 }) { onPick(group) }
        }
    }
}

/** Wyrm's own beads; patterned ones take the colour wheel's colour. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WyrmBeadPalette(textures: SkinTextures?, wheelRgb: Int, tryOn: TryOn, onPick: (Int) -> Unit) {
    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (kind in 0 until WyrmBeads.COUNT) {
            val argb = WyrmBeads.argb(kind, wheelRgb)
            val tint = if (WyrmBeads.tinted[kind]) Color(0xFF000000.toInt() or wheelRgb) else null
            BeadButton(textures?.wyrmBeads?.get(kind), tint, Modifier.tryOn(tryOn) { bead = AirSkin.nearestGroup(argb and 0xFFFFFF) to argb }) { onPick(argb) }
        }
    }
}

@Composable
private fun BeadButton(image: ImageBitmap?, tint: Color?, modifier: Modifier, onClick: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val lift by animateFloatAsState(if (hovered) 1f else 0f, iosSpring(0.28f, 0.6f), label = "bead")
    Box(
        modifier
            .size(46.dp)
            .scale(1f + 0.14f * lift)
            .clip(CircleShape)
            .background(Wyrm.Card.copy(alpha = 0.72f + 0.2f * lift))
            .border(1.dp, Wyrm.Ink.copy(alpha = 0.18f * lift), CircleShape)
            .hoverable(hover)
            .clickable(interactionSource = hover, indication = null, onClick = onClick)
            .padding(5.dp),
    ) {
        if (image != null) Canvas(Modifier.fillMaxSize()) { drawFittedImage(image, tint) }
    }
}

private fun DrawScope.drawFittedImage(image: ImageBitmap, tint: Color?) {
    val scale = minOf(size.width / image.width, size.height / image.height)
    val w = image.width * scale
    val h = image.height * scale
    withTransform({
        translate((size.width - w) / 2f, (size.height - h) / 2f)
        scale(scale, scale, pivot = Offset.Zero)
    }) {
        drawImage(image, colorFilter = tint?.let { ColorFilter.tint(it, BlendMode.Modulate) })
    }
}

/** Hair, ears and glasses: Android's tabs, hair colour track and picture tiles. */
@Composable
private fun WyrmAccessoriesSection(textures: SkinTextures?, look: WyrmLookSpec, tryOn: TryOn) {
    var tab by remember { mutableIntStateOf(0) }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        PaperSegmented(options = listOf("Hair", "Ears", "Glasses"), selected = tab, onSelect = { tab = it }, modifier = Modifier.width(340.dp))
        if (tab == 0) {
            Column(Modifier.weight(1f).widthIn(max = 480.dp)) {
                Text("Hair colour", fontFamily = Wyrm.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Wyrm.Quiet)
                Spacer(Modifier.height(6.dp))
                GradientTrack(
                    fraction = look.hairTone,
                    brush = Brush.horizontalGradient(*WyrmLook.hairStops.map { (at, rgb) -> at to WyrmLook.rgbColor(rgb) }.toTypedArray()),
                    onPick = { tone -> WyrmLookStore.pickHairTone(tone) },
                )
            }
        }
    }
    when (tab) {
        0 -> TileGrid(minimum = 104.dp, count = WyrmLook.hairNames.size + 1) { index ->
            val style = index - 1
            Box(Modifier.fillMaxSize().then(if (index > 0) Modifier.hoverLift() else Modifier).tryOn(tryOn) { this.look = look.copy(hair = style) }) {
                if (index == 0) {
                    SelectionTile(look.hair < 0, "None") { WyrmLookStore.pickHair(-1) }
                } else {
                    ImageTile(look.hair == style, textures?.lookThumbnails?.get(style), 10.dp, tint = WyrmLook.rgbColor(look.hairRgb)) {
                        WyrmLookStore.pickHair(style)
                    }
                }
            }
        }
        else -> {
            val ears = tab == 1
            val names = if (ears) WyrmLook.earNames else WyrmLook.glassesNames
            val current = if (ears) look.ears else look.glasses
            fun pick(style: Int) = if (ears) WyrmLookStore.pickEars(style) else WyrmLookStore.pickGlasses(style)
            TileGrid(minimum = 104.dp, count = names.size + 1) { index ->
                val style = index - 1
                val tried = if (ears) look.copy(ears = style) else look.copy(glasses = style)
                Box(Modifier.fillMaxSize().then(if (index > 0) Modifier.hoverLift() else Modifier).tryOn(tryOn) { this.look = tried }) {
                    if (index == 0) {
                        SelectionTile(current < 0, "None") { pick(-1) }
                    } else {
                        val cell = (if (ears) 16 else 28) + style
                        ImageTile(current == style, textures?.lookThumbnails?.get(cell), 10.dp) { pick(style) }
                    }
                }
            }
        }
    }
}

/**
 * Skin › Tag (Android's SkinSection.TAGS): the rope's chain, swing and size,
 * then every tag, Wyrm's own first (TagPickerOrder), NTL's with their number.
 * The tag goes to the arena in the skin block's corner (NTL tag corner).
 */
@Composable
private fun TagsSection(textures: SkinTextures?) {
    var tag by remember { mutableIntStateOf(EngineSettings.number("tags.index", -1f).roundToInt()) }
    LaunchedEffect(EngineSettings.settings) { tag = EngineSettings.number("tags.index", -1f).roundToInt() }
    Spacer(Modifier.height(14.dp))
    Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        TagSlider("Chain", "tags.chain", 1f..3f, Modifier.weight(1f))
        TagSlider("Swing", "tags.swing", 1f..2f, Modifier.weight(1f))
        TagSlider("Size", "tags.scale", 0.4f..2f, Modifier.weight(1f))
    }
    Spacer(Modifier.height(18.dp))
    TileGrid(minimum = 88.dp, count = TagPickerOrder.size + 1) { index ->
        if (index == 0) {
            SelectionTile(tag < 0, "None") { tag = -1; EngineSettings.write("tags.index", -1f) }
        } else {
            val item = SkinCatalog.tags[TagPickerOrder[index - 1]]
            Box(Modifier.fillMaxSize().hoverLift()) {
                ImageTile(tag == item.id, textures?.tagThumbnails?.get(item.id), 7.dp,
                    badge = if (isWyrmTag(item.ntlId)) null else "${item.ntlId}") {
                    tag = item.id
                    EngineSettings.write("tags.index", item.id.toFloat())
                }
            }
        }
    }
}

@Composable
private fun TagSlider(label: String, id: String, range: ClosedFloatingPointRange<Float>, modifier: Modifier) {
    var value by remember(id) { mutableStateOf(EngineSettings.number(id, range.start)) }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontFamily = Wyrm.Body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Wyrm.Ink, modifier = Modifier.weight(1f))
            Text("%.2f".format(value), fontFamily = Wyrm.Body, fontSize = 12.sp, color = Wyrm.Mute)
        }
        com.wyrm.omrajput.ui.LiquidSlider(
            value = value,
            onValueChange = { value = it; EngineSettings.write(id, it) },
            valueRange = range,
        )
    }
}

/** Whether this run has drawn the Skin page once (its pictures are on the GPU). */
private object SkinPageWarm {
    var done = false
}
