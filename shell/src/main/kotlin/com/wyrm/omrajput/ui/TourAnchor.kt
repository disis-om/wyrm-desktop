package com.wyrm.omrajput.ui

import androidx.compose.ui.Modifier

/**
 * Android's app tour (WyrmTour.kt) marks controls with `tourAnchor`. The
 * desktop has no tour yet, so the shared screens get a mark that does nothing.
 */
internal fun Modifier.tourAnchor(@Suppress("UNUSED_PARAMETER") id: String): Modifier = this
