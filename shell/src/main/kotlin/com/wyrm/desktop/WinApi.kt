package com.wyrm.desktop

import java.io.File

/**
 * wyrmwin.dll (native/desktop_win.c): rounded window corners, Wyrm's own
 * taskbar identity, shortcuts. Loaded before any window; every call is safe
 * when the dll is missing (Windows 10, an offscreen check): it just does nothing.
 */
internal object WinApi {
    /** The taskbar's id for Wyrm: the window, pinned shortcuts and toasts group under it. */
    const val APP_ID = "OmRajput.Wyrm"

    var loaded = false
        private set

    fun load(): Boolean {
        if (loaded) return true
        val bin = System.getProperty("wyrm.native.bin") ?: return false
        loaded = runCatching { System.load(File(bin, "wyrmwin.dll").absolutePath) }.isSuccess
        return loaded
    }

    /** Windows 11's rounded corners (~8 px, the size Windows gives its own windows). */
    fun roundCorners(hwnd: Long) {
        if (!loaded || hwnd == 0L) return
        val ok = runCatching { nativeCorners(hwnd, 2) }.getOrDefault(false)
        println("Wyrm window: rounded corners ${if (ok) "on" else "not available (Windows 10?)"}")
    }

    fun claimTaskbarIdentity() {
        if (loaded) runCatching { nativeAppId(APP_ID) }
    }

    fun shortcut(lnk: File, target: File, arguments: String, workDir: File, icon: File, description: String): Boolean =
        loaded && runCatching {
            nativeShortcut(lnk.absolutePath, target.absolutePath, arguments, workDir.absolutePath, icon.absolutePath, APP_ID, description)
        }.getOrDefault(false)

    @JvmStatic private external fun nativeCorners(hwnd: Long, preference: Int): Boolean
    @JvmStatic private external fun nativeAppId(id: String): Boolean
    @JvmStatic private external fun nativeShortcut(
        path: String, target: String, arguments: String, workDir: String, icon: String, appId: String, description: String,
    ): Boolean
}
