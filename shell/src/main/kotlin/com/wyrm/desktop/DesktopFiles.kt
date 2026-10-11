package com.wyrm.desktop

import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Picking a file from the computer: the system's own Open dialog (the phone's
 * photo picker on the desktop). Runs on the window's thread; the dialog is
 * modal to the Wyrm window.
 */
internal object DesktopFiles {
    private val imageTypes = setOf("png", "jpg", "jpeg")

    fun chooseImage(title: String): File? {
        val owner = Frame.getFrames().firstOrNull { it.isVisible }
        val dialog = FileDialog(owner, title, FileDialog.LOAD).apply {
            file = "*.png;*.jpg;*.jpeg"
            setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in imageTypes }
            isMultipleMode = false
        }
        dialog.isVisible = true
        val chosen = dialog.files.firstOrNull() ?: return null
        return chosen.takeIf { it.isFile && it.extension.lowercase() in imageTypes }
    }
}
