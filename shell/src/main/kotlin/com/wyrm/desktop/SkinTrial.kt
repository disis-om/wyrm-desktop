package com.wyrm.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.wyrm.omrajput.data.Trail
import com.wyrm.omrajput.ui.SkinCatalog
import com.wyrm.omrajput.ui.SkinState
import com.wyrm.omrajput.ui.WyrmLookSpec
import com.wyrm.omrajput.ui.WyrmLookStore
import com.wyrm.omrajput.ui.lookSpec
import com.wyrm.omrajput.ui.toSkinState

/**
 * "Try this skin" (Android's SkinTrial, 2026-09-30) on the desktop: a trail
 * whose poster shared their skin opens the Skin tab with that skin on the
 * stage and a banner — Wear puts it on (skin, accessory, Wyrm look, tag),
 * Back to mine drops it. Nothing is saved until Wear; leaving the Skin tab is
 * "Back to mine".
 */
internal object SkinTrial {
    data class Trial(val author: String, val skin: SkinState, val look: WyrmLookSpec, val tagId: Int)

    var trial by mutableStateOf<Trial?>(null)
        private set

    fun open(trail: Trail) {
        val skin = trail.skin ?: return
        trial = Trial(
            author = trail.author.name,
            skin = skin.toSkinState(),
            look = skin.lookSpec(),
            tagId = SkinCatalog.tags.firstOrNull { it.ntlId == skin.tag }?.id ?: -1,
        )
        Social.closeTrail()
        Social.closeAll()
        ShellNav.tab.value = Tab.Skin
    }

    /** Wear: the calls the Skin tab makes (a preset keeps your own pattern underneath), then saved. */
    fun wear() {
        val current = trial ?: return
        val skin = current.skin
        Engine.skinPreset(skin.preset)
        if (skin.custom && skin.code.isNotEmpty()) Engine.skinCode(skin.code, skin.coloursFor(skin.code.length))
        Engine.skinAccessory(skin.accessory)
        WyrmLookStore.wear(current.look)
        // The poster's tag too; a trail without one leaves yours on.
        if (current.tagId >= 0) {
            EngineSettings.refresh()
            EngineSettings.write("tags.index", current.tagId.toFloat())
        }
        trial = null
    }

    fun backToMine() {
        trial = null
    }
}
