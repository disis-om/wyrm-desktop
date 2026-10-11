package androidx.compose.ui.text.font

import com.wyrm.omrajput.R

/**
 * Wyrm Desktop: Android's Font(resId, …) for the shared files. Loads the same
 * variable .ttf from the Android tree, with the same variation axes.
 */
fun Font(
    resId: Int,
    weight: FontWeight = FontWeight.Normal,
    style: FontStyle = FontStyle.Normal,
    variationSettings: FontVariation.Settings = FontVariation.Settings(weight, style),
): Font = androidx.compose.ui.text.platform.Font(R.fontFile(resId), weight, style, variationSettings)
