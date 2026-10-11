package com.wyrm.omrajput.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Android's `ArenaCountryBadge` (IosArenaPicker.kt) for the shared screens
 * (a friend's "Playing in Arena 4817 · IN" line): the desktop's own flag
 * badge from its arena picker (FlagImages.kt), the same look.
 */
@Composable
fun ArenaCountryBadge(country: String, modifier: Modifier = Modifier) {
    com.wyrm.desktop.CountryBadge(country, modifier)
}
