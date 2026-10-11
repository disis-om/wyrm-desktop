package com.wyrm.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wyrm.omrajput.data.Arena
import com.wyrm.omrajput.data.ArenaDirectory
import com.wyrm.omrajput.data.RecentArenas
import com.wyrm.omrajput.data.SavedArenas
import com.wyrm.omrajput.ui.IosGlyph
import com.wyrm.omrajput.ui.IosIcon
import com.wyrm.omrajput.ui.NearOriginalStore
import com.wyrm.omrajput.ui.Wyrm
import com.wyrm.omrajput.ui.WyrmCapsule
import com.wyrm.omrajput.ui.iosSpring
import com.wyrm.omrajput.ui.wyrmRounded

/** Wyrm iOS's four-digit arena code (Android's Arena.iosCode). */
private fun Arena.code(): String = "%04d".format(id % 10_000)

private fun Arena.title(): String = if (id < 0) "Custom arena" else "Arena ${code()}"

/** A saved or typed address as an arena row; a missing port is Slither's 444 (Android's customArena). */
private fun customArena(raw: String): Arena? {
    val trimmed = raw.trim()
    val withPort = if (trimmed.contains(':')) trimmed else "$trimmed:444"
    if (!ArenaDirectory.isValidEndpoint(withPort)) return null
    val address = withPort.substringBeforeLast(':')
    val port = withPort.substringAfterLast(':').toIntOrNull() ?: return null
    return Arena(address = address, port = port, players = 0, id = -1, cluster = 0)
}

/**
 * The arena picker (D4, OM 2026-10-11: "android and ios jaisa"): Wyrm
 * Android's IosArenaPicker in a sheet. LIVE DIRECTORY over "Pick a server", a +
 * for a custom address, Close; search by arena code or IP; BEST FOR YOU (the
 * lowest measured ping), RECENTLY JOINED, then the directory by latency (ten,
 * the rest behind See all), and SAVED ARENAS in a fold. A row is the arena
 * code with its country flag, its address and its latency, coloured from the
 * fastest (green) to the slowest (red). Probes run only while it is open.
 */
@Composable
internal fun ArenaPicker(onClose: () -> Unit) {
    val pings = remember { mutableStateMapOf<String, Int>() }
    val customPings = remember { mutableStateMapOf<String, Int>() }
    var loading by remember { mutableStateOf(!HomeState.directoryLoaded) }
    var error by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var showAll by remember { mutableStateOf(false) }
    var showSaved by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var customAddress by remember { mutableStateOf("") }
    var addressError by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(SavedArenas.load(DesktopContext)) }
    val recent = remember { RecentArenas.load(DesktopContext) }

    // Probes only while this popup is open (CLAUDE.md: the arena IP penalty).
    DisposableEffect(Unit) {
        ArenaDirectory.openPicker()
        onDispose { ArenaDirectory.closePicker() }
    }
    LaunchedEffect(Unit) {
        runCatching { ArenaDirectory.load() }
            .onSuccess { list ->
                HomeState.arenas = list
                HomeState.directoryLoaded = true
                loading = false
                runCatching { ArenaDirectory.loadCountries(list.map { it.address }) }
                ArenaDirectory.pingAll(list) { endpoint, ms -> pings[endpoint] = ms }
            }
            .onFailure {
                loading = false
                error = "The arena list could not be reached."
            }
    }
    // Custom addresses are not in the directory sweep: measure the ones on
    // screen, once each, as Android and iOS measure a saved arena.
    LaunchedEffect(saved, HomeState.arenas) {
        (saved + HomeState.selected).distinct().mapNotNull(::customArena)
            .filter { custom -> HomeState.arenas.none { it.endpoint == custom.endpoint } && custom.endpoint !in customPings }
            .forEach { custom -> customPings[custom.endpoint] = runCatching { ArenaDirectory.ping(custom) }.getOrDefault(-1) }
    }

    val live = HomeState.arenas
    fun matches(arena: Arena) = search.isEmpty() ||
        arena.endpoint.contains(search.trim()) || arena.title().contains(search.trim(), ignoreCase = true)
    fun latency(arena: Arena): Int? = if (arena.id < 0) customPings[arena.endpoint] else pings[arena.endpoint]

    val filtered = live.filter(::matches).sortedWith(
        compareBy<Arena> { a -> pings[a.endpoint]?.takeIf { it > 0 } ?: Int.MAX_VALUE }.thenBy { it.code() },
    )
    val recentRows = recent.mapNotNull { endpoint ->
        live.firstOrNull { it.endpoint == endpoint } ?: if (endpoint in saved) customArena(endpoint) else null
    }.filter(::matches).sortedWith(compareBy { a -> latency(a)?.takeIf { it > 0 } ?: Int.MAX_VALUE })
    val best = if (search.isEmpty()) {
        live.filter { (pings[it.endpoint] ?: 0) > 0 }.minByOrNull { pings[it.endpoint] ?: Int.MAX_VALUE }
    } else null
    val ranked = filtered.filter { it.endpoint !in recent }
    val positive = pings.values.filter { it > 0 }
    val low = positive.minOrNull()
    val high = positive.maxOrNull()

    fun latencyText(arena: Arena): String {
        val value = latency(arena) ?: return "—"
        return if (value > 0) "${value}ms" else "Unavailable"
    }
    fun latencyColour(arena: Arena): Color {
        val value = latency(arena) ?: return Wyrm.Quiet
        if (value <= 0) return Color(0.75f, 0.25f, 0.22f)
        val lo = low ?: value
        val hi = high ?: value
        val ratio = if (hi == lo) 0f else ((value - lo).toFloat() / (hi - lo)).coerceIn(0f, 1f)
        return Color(0.18f + 0.68f * ratio, 0.68f - 0.45f * ratio, 0.27f - 0.08f * ratio)
    }
    fun pick(arena: Arena) {
        HomeState.selected = arena.endpoint
        NearOriginalStore.applyServer(arena.id.coerceAtLeast(0))
        onClose()
    }
    fun saveCustom() {
        val arena = customArena(customAddress)
        if (arena == null) {
            addressError = true
            return
        }
        addressError = false
        saved = SavedArenas.save(DesktopContext, arena.endpoint)
        showSaved = true
        showAdd = false
        customAddress = ""
    }

    GlassSheet(Modifier.width(680.dp).fillMaxHeight(0.84f), wyrmRounded(26.dp), onClose) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 18.dp, top = 20.dp, bottom = 14.dp), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("LIVE DIRECTORY", fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.sp, color = Wyrm.Live)
                    Text("Pick a server", fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 27.sp, color = Wyrm.Ink)
                }
                Text(
                    when {
                        loading -> "Loading…"
                        error != null -> error!!
                        else -> "${live.size} live"
                    },
                    fontFamily = Wyrm.Body, fontSize = 12.sp, color = Wyrm.Quiet,
                    modifier = Modifier.padding(end = 12.dp, bottom = 8.dp),
                )
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (showAdd) Wyrm.Ink else Wyrm.Card)
                        .border(1.dp, Wyrm.Rule, CircleShape)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable { showAdd = !showAdd },
                    contentAlignment = Alignment.Center,
                ) { IosIcon(IosGlyph.PLUS, if (showAdd) Wyrm.OnInk else Wyrm.Ink, size = 19.dp, semibold = true) }
                Spacer(Modifier.width(10.dp))
                Text(
                    "Close",
                    fontFamily = Wyrm.Body, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Wyrm.Link,
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onClose).padding(vertical = 8.dp, horizontal = 4.dp),
                )
            }
            // Search.
            Row(
                Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(wyrmRounded(13.dp))
                    .background(Wyrm.Card.copy(alpha = 0.82f))
                    .border(1.dp, Wyrm.Rule, wyrmRounded(13.dp))
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IosIcon(IosGlyph.MAGNIFIER, Wyrm.Ink, size = 16.dp, semibold = true)
                Spacer(Modifier.width(8.dp))
                PickerField(search, { search = it }, "Arena code or IP", Modifier.weight(1f), autofocus = true)
            }
            // Best for you and Recently joined arrive with the pings, above the
            // rows already shown; a list keeps its first visible row, so they
            // landed out of sight. Untouched, it goes back to the top for them.
            val list = androidx.compose.foundation.lazy.rememberLazyListState()
            LaunchedEffect(best?.endpoint, recentRows.size) {
                if (list.firstVisibleItemIndex <= 3 && list.firstVisibleItemScrollOffset < 200) list.scrollToItem(0)
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                state = list,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                if (showAdd) {
                    item(key = "add") {
                        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Row(
                                Modifier.fillMaxWidth().clip(wyrmRounded(14.dp)).background(Wyrm.Card).border(1.dp, Wyrm.Rule, wyrmRounded(14.dp)).padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                PickerField(customAddress, { customAddress = it; addressError = false }, "IPv4 address:port", Modifier.weight(1f), autofocus = true, onEnter = ::saveCustom)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Save", fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Wyrm.Link,
                                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand).clickable { saveCustom() },
                                )
                            }
                            if (addressError) {
                                Text("Enter a valid IPv4 address and port (1–65535).", fontFamily = Wyrm.Body, fontSize = 11.sp, color = Color(0xFFFF3B30))
                            }
                        }
                    }
                }
                best?.let { top ->
                    item(key = "best-label") { SectionLabel("BEST FOR YOU") }
                    item(key = "best-" + top.endpoint) {
                        ArenaRow(top, HomeState.selected, latencyText(top), latencyColour(top), tag = "Lowest ping") { pick(top) }
                    }
                }
                if (recentRows.isNotEmpty()) {
                    item(key = "recent-label") { SectionLabel("RECENTLY JOINED") }
                    items(recentRows, key = { "recent-" + it.endpoint }) { arena ->
                        ArenaRow(arena, HomeState.selected, latencyText(arena), latencyColour(arena)) { pick(arena) }
                    }
                }
                item(key = "arenas-label") { SectionLabel(if (search.isEmpty()) "ARENAS" else "SEARCH RESULTS") }
                val shown = if (showAll || search.isNotEmpty()) ranked else ranked.take(10)
                items(shown, key = { it.endpoint }) { arena ->
                    ArenaRow(arena, HomeState.selected, latencyText(arena), latencyColour(arena)) { pick(arena) }
                }
                if (search.isEmpty() && ranked.size > 10) {
                    item(key = "see-all") {
                        Text(
                            if (showAll) "Show less" else "See all",
                            fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Wyrm.Ink, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Hand).clickable { showAll = !showAll }.padding(14.dp),
                        )
                    }
                }
                if (saved.isNotEmpty()) {
                    item(key = "saved") {
                        val chevron by animateFloatAsState(if (showSaved) 90f else 0f, iosSpring(0.35f, 0.86f), label = "saved-chevron")
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(wyrmRounded(15.dp))
                                .background(Wyrm.Card.copy(alpha = 0.9f))
                                .border(1.dp, Wyrm.Rule, wyrmRounded(15.dp))
                                .padding(14.dp)
                                .animateContentSize(iosSpring(0.4f, 0.86f)),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Hand)
                                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { showSaved = !showSaved },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(Modifier.weight(1f)) { SectionLabel("SAVED ARENAS · ${saved.size}") }
                                IosIcon(IosGlyph.CHEVRON_RIGHT, Wyrm.Ink, size = 14.dp, semibold = true, modifier = Modifier.rotate(chevron))
                            }
                            AnimatedVisibility(showSaved, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                                Column(Modifier.padding(top = 9.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                    saved.mapNotNull(::customArena).filter(::matches).forEach { arena ->
                                        ArenaRow(
                                            arena, HomeState.selected, latencyText(arena), latencyColour(arena),
                                            onRemove = { saved = SavedArenas.remove(DesktopContext, arena.endpoint) },
                                        ) { pick(arena) }
                                    }
                                }
                            }
                        }
                    }
                }
                if (filtered.isEmpty() && recentRows.isEmpty() && saved.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            if (loading) "Loading the live directory…" else "No active arenas right now. Try again in a moment or add a custom IP.",
                            fontFamily = Wyrm.Body, fontSize = 12.sp, color = Wyrm.Quiet, modifier = Modifier.padding(20.dp),
                        )
                    }
                }
            }
        }
    }
}

/** A row: the arena code with its flag (and a tag), the address, the latency. */
@Composable
private fun ArenaRow(
    arena: Arena,
    selection: String,
    latency: String,
    latencyColour: Color,
    tag: String? = null,
    onRemove: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val chosen = selection == arena.endpoint
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val shape = wyrmRounded(15.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (hovered && !chosen) Wyrm.Card else Wyrm.Card.copy(alpha = 0.9f))
            .border(if (chosen) 2.dp else 1.dp, if (chosen) Wyrm.Ink else Wyrm.Rule, shape)
            .hoverable(hover)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(arena.title(), fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Wyrm.Ink)
                if (arena.id >= 0) CountryBadge(ArenaDirectory.countryOf(arena.address), Modifier.padding(start = 8.dp))
                if (tag != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        tag, fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 10.sp, color = Wyrm.Live,
                        modifier = Modifier.clip(WyrmCapsule).background(Wyrm.Live.copy(alpha = 0.12f)).padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            }
            Text(arena.endpoint, fontFamily = Wyrm.Body, fontSize = 10.5.sp, color = Wyrm.Quiet)
        }
        Text(latency, fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = latencyColour)
        if (onRemove != null) {
            Spacer(Modifier.width(12.dp))
            Text(
                "Remove", fontFamily = Wyrm.Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Wyrm.Badge,
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onRemove).padding(4.dp),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text, fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 0.8.sp, color = Wyrm.Quiet,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 3.dp),
    )
}

@Composable
private fun PickerField(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    autofocus: Boolean = false,
    onEnter: (() -> Unit)? = null,
) {
    val focus = remember { FocusRequester() }
    if (autofocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val style = TextStyle(fontFamily = Wyrm.Body, fontSize = 13.sp, color = Wyrm.Ink)
    BasicTextField(
        value = value,
        onValueChange = { onValue(it.replace("\n", "").take(64)) },
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(Wyrm.Link),
        modifier = modifier
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                if (onEnter != null && event.type == KeyEventType.KeyDown && event.key == Key.Enter) { onEnter(); true } else false
            },
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, style = style.copy(color = Wyrm.Quiet.copy(alpha = 0.7f)))
                inner()
            }
        },
    )
}
