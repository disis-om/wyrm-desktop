package com.kyant.backdrop.backdrops

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.kyant.backdrop.Backdrop

/** Wyrm Desktop: no glass, so a layer backdrop records nothing. */
class LayerBackdrop internal constructor() : Backdrop

private val desktopLayer = LayerBackdrop()

@Composable
fun rememberLayerBackdrop(): LayerBackdrop = desktopLayer

fun emptyBackdrop(): Backdrop = desktopLayer

@Suppress("UNUSED_PARAMETER")
fun Modifier.layerBackdrop(backdrop: LayerBackdrop): Modifier = this
