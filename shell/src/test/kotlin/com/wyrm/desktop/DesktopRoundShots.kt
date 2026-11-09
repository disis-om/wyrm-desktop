package com.wyrm.desktop

import java.io.File

object DesktopRoundShots {
    @JvmStatic fun main(args: Array<String>) {
        val out = File("build/shots/round-2026-10-19").apply { mkdirs() }
        val render = Class.forName("com.wyrm.desktop.PageShotKt").getMethod("main", Array<String>::class.java)
        checkNotNull(kotlinx.coroutines.runBlocking { com.wyrm.omrajput.ui.SkinTextures.load() }) { "Skin textures did not load" }
        val sections = if (args.isNotEmpty()) args.toList() else listOf("DIALOG_DELETE", "DIALOG_LOGOUT", "SETTINGS_CONTROLS", "SETTINGS_EDIT", "SOCIAL_BOARD", "TRAILS", "PATTERN")
        for (section in sections) {
            render.invoke(null, arrayOf(File(out, "$section.png").absolutePath, section))
        }
        kotlin.system.exitProcess(0)
    }
}
