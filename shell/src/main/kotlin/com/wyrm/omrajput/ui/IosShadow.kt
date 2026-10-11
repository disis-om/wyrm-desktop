package com.wyrm.omrajput.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import org.jetbrains.skia.FilterBlurMode
import org.jetbrains.skia.MaskFilter
import org.jetbrains.skia.Paint
import org.jetbrains.skia.RRect

/** Wyrm Desktop: Android's SwiftUI-style shadow (ui/IosShadow.kt), drawn with Skia. */
fun Modifier.iosShadow(color: Color, radius: Dp, y: Dp, corner: Dp): Modifier = drawBehind {
    if (color.alpha <= 0f) return@drawBehind
    drawIntoCanvas { canvas ->
        val paint = Paint().apply {
            isAntiAlias = true
            this.color = color.toArgb()
            // Android's BlurMaskFilter radius is the blur's reach; Skia wants sigma.
            maskFilter = MaskFilter.makeBlur(FilterBlurMode.NORMAL, (radius.toPx() / 2f).coerceAtLeast(0.5f))
        }
        val r = corner.toPx()
        canvas.nativeCanvas.drawRRect(RRect.makeLTRB(0f, y.toPx(), size.width, size.height + y.toPx(), r), paint)
    }
}
