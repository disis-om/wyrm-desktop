package com.wyrm.omrajput

import java.io.File

/**
 * Wyrm Desktop: the part of Wyrm Android's R the shared files use. Ids are
 * desktop-only; [fontFile] turns one back into the Android tree's file.
 */
@Suppress("ClassName")
object R {
    object font {
        const val manrope = 1
        const val bodoni_moda = 2
    }

    private val androidRes = File(System.getProperty("wyrm.android.root") ?: "../Wyrm Android", "android/app/src/main/res")

    fun fontFile(id: Int): File = when (id) {
        font.manrope -> File(androidRes, "font/manrope.ttf")
        font.bodoni_moda -> File(androidRes, "font/bodoni_moda.ttf")
        else -> error("Unknown font id $id")
    }
}
