package com.wyrm.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wyrm.omrajput.ui.DEFAULT_BG_SCALE
import com.wyrm.omrajput.ui.LiquidSlider
import com.wyrm.omrajput.ui.Wyrm
import com.wyrm.omrajput.ui.WyrmCapsule
import com.wyrm.omrajput.ui.bgScaleAt
import com.wyrm.omrajput.ui.bgScaleLabel
import com.wyrm.omrajput.ui.bgSliderOf
import com.wyrm.omrajput.ui.iosSpring
import com.wyrm.omrajput.ui.pressScale
import com.wyrm.omrajput.ui.wyrmRounded

/**
 * Skin › Arena background › Adjust size, as Android's background-size editor:
 * the engine's own AI arena with only its map and board (bare editor), the
 * size written live to both modes, Cancel puts the old size back. The panel is
 * Liquid Glass over the running arena, so the floor shows through it.
 */
@Composable
fun BackgroundSizeEditor() {
    val editor = Engine.backgroundEditor ?: return
    val rise = remember { Animatable(0f) }
    LaunchedEffect(Unit) { rise.animateTo(1f, iosSpring(0.5f, 0.82f)) }
    Box(Modifier.fillMaxSize()) {
        GameSurface(Modifier.fillMaxSize(), gameKeys = false)
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp)
                .width(540.dp)
                .graphicsLayer {
                    translationY = (1f - rise.value) * 60.dp.toPx()
                    alpha = rise.value.coerceIn(0f, 1f)
                }
                .paperPanel(wyrmRounded(26.dp), fill = 0.94f)
                .padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ARENA BACKGROUND SIZE", fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 10.5.sp,
                    letterSpacing = 1.2.sp, color = Wyrm.Quiet, modifier = Modifier.weight(1f))
                Text(bgScaleLabel(editor.scale), fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Wyrm.Ink)
            }
            Spacer(Modifier.height(10.dp))
            LiquidSlider(
                value = bgSliderOf(editor.scale),
                onValueChange = { Engine.setBackgroundScale(bgScaleAt(it)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Smaller looks further away", fontFamily = Wyrm.Body, fontSize = 11.5.sp, color = Wyrm.Quiet, modifier = Modifier.weight(1f))
                EditorAction("Cancel", filled = false) { Engine.closeBackgroundEditor(save = false) }
                EditorAction("Reset", filled = false) { Engine.setBackgroundScale(DEFAULT_BG_SCALE) }
                EditorAction("Save", filled = true) { Engine.closeBackgroundEditor(save = true) }
            }
        }
    }
}

@Composable
private fun EditorAction(label: String, filled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier
            .height(36.dp)
            .scale(pressScale(pressed))
            .clip(WyrmCapsule)
            .background(
                when {
                    filled -> if (hovered) Wyrm.Ink.copy(alpha = 0.86f) else Wyrm.Ink
                    hovered -> Wyrm.Ink.copy(alpha = 0.07f)
                    else -> Color.Transparent
                },
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontFamily = Wyrm.Body, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = if (filled) Wyrm.OnInk else Wyrm.Ink)
    }
}
