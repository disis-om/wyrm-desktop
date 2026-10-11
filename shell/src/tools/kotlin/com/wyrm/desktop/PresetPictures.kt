package com.wyrm.desktop

import com.wyrm.omrajput.ui.SkinCatalog
import com.wyrm.omrajput.ui.SkinTextures
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Build step (Gradle `generatePresetPictures`): draws the 66 default skins
 * from the real atlases into PNGs that ship inside the app.
 */
fun main(args: Array<String>) {
    val out = File(args[0], "presets").apply { deleteRecursively(); mkdirs() }
    val textures = runBlocking { SkinTextures.load() } ?: error("Skin atlases could not be read")
    for (index in SkinCatalog.presets.indices) {
        File(out, PresetPreviews.resourceName(index).substringAfter('/')).writeBytes(PresetPreviews.png(PresetPreviews.draw(textures, index)))
    }
    println("Preset pictures: ${SkinCatalog.presets.size} in ${out.absolutePath}")
}
