package com.wyrm.desktop

import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wyrm.omrajput.ui.Wyrm

/**
 * Wyrm Desktop's floating panel (sheets, popups, editor bars), standing in
 * for the Liquid Glass it used to be (OM, 2026-10-11: no glass, same look):
 * the page's paper, nearly opaque so text over a busy arena reads, a hairline
 * and a soft drop shadow. One draw, nothing sampled from behind, so it costs
 * the same over a still page as over a running match.
 */
internal fun Modifier.paperPanel(shape: Shape, fill: Float = 0.97f, elevation: Dp = 18.dp): Modifier = this
    .shadow(elevation, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.22f))
    .clip(shape)
    .drawBehind { drawRect(Wyrm.Paper.copy(alpha = fill)) }
    .border(1.dp, Wyrm.Rule, shape)
