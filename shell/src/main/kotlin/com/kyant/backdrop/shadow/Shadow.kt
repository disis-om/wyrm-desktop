package com.kyant.backdrop.shadow

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/** Wyrm Desktop: the glass shadow's settings (the desktop paints none). */
data class Shadow(
    val radius: Dp = 24.dp,
    val offset: DpOffset = DpOffset(0.dp, 4.dp),
    val color: Color = Color.Black.copy(alpha = 0.1f),
)
