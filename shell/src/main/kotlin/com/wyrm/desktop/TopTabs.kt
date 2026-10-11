package com.wyrm.desktop

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Box
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.unit.Dp
import com.wyrm.omrajput.ui.iosSpring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wyrm.omrajput.ui.IosGlyph
import com.wyrm.omrajput.ui.IosIcon
import com.wyrm.omrajput.ui.LiquidTab
import com.wyrm.omrajput.ui.LiquidTabBar
import com.wyrm.omrajput.ui.Wyrm

/** The root pages, in Wyrm Android's and iOS's tab order (Play in the middle) and with their glyphs. */
enum class Tab(val label: String, val glyph: IosGlyph) {
    Trails("Trails", IosGlyph.TRAILS),
    Social("Social", IosGlyph.PERSON_2),
    Play("Play", IosGlyph.PLAY_CIRCLE),
    Skin("Skin", IosGlyph.HEXAGON_GRID),
    Settings("Settings", IosGlyph.SLIDERS),
}

/** Height the floating tab bar takes from the top of every page. */
val TopTabsClearance = 92.dp

/**
 * The bar's width for a window this wide (OM: it looked too big in a small
 * window): 48 % of the window, between 480 and 640 dp (five tabs since Trails,
 * D5), eased on an iOS spring so a resize glides instead of jumping.
 */
@Composable
fun topTabsWidth(windowWidth: Dp): Dp {
    val target = (windowWidth * 0.48f).coerceIn(480.dp, 640.dp)
    val width by animateDpAsState(target, iosSpring(0.45f, 0.86f), label = "tabs-width")
    return width
}

/**
 * The tabs, floating at the top centre: Wyrm Android's own Liquid Glass tab
 * bar (`LiquidTabBar`: the page refracted through the bar, a pill that turns
 * into a lens while it is held or dragged), the same component the phones
 * use. It floats over the page, so opening a tab never moves the content.
 */
@Composable
fun TopTabs(selected: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val tabs = Tab.entries.map { tab -> LiquidTab { chosen -> TabLabel(tab, chosen) } }
    // The bar floats over every page, the Skin stage and its dark floors included:
    // more paper and blur than the phones' (0.4, 8 dp) so the labels always read.
    androidx.compose.runtime.CompositionLocalProvider(
        com.wyrm.omrajput.ui.LocalTabBarFrost provides 0.8f,
        com.wyrm.omrajput.ui.LocalTabBarBlur provides 18.dp,
    ) {
        LiquidTabBar(
            tabs = tabs,
            selected = selected.ordinal,
            onSelect = { onSelect(Tab.entries[it]) },
            modifier = modifier,
        )
    }
}

@Composable
private fun TabLabel(tab: Tab, chosen: Boolean) {
    val colour by animateColorAsState(if (chosen) Wyrm.Ink else Wyrm.Mute, tween(160), label = "tab-ink")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Android's badge trail: unread likes/replies on Trails; DMs, invites and followers on Social.
        val count = when (tab) {
            Tab.Trails -> Social.trailsBadge()
            Tab.Social -> Social.socialBadge()
            else -> 0
        }
        Box {
            IosIcon(tab.glyph, colour, size = if (tab == Tab.Play) 22.dp else 20.dp, semibold = chosen)
            com.wyrm.omrajput.ui.WyrmCountBadge(
                count,
                Modifier.align(Alignment.TopEnd).offset(x = 11.dp, y = (-9).dp).graphicsLayer { scaleX = 0.78f; scaleY = 0.78f },
            )
        }
        Text(
            tab.label,
            fontFamily = Wyrm.Body,
            fontWeight = if (chosen) FontWeight.Bold else FontWeight.SemiBold,
            fontSize = 14.sp,
            color = colour,
            maxLines = 1,
        )
    }
}
