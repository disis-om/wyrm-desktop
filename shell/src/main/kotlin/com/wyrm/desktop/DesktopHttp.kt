package com.wyrm.desktop

import java.net.HttpURLConnection
import java.net.URL

internal object DesktopHttp {
    fun open(url: URL, method: String): HttpURLConnection =
        if (method == "PATCH") DesktopPatchConnection(url) else url.openConnection() as HttpURLConnection
}
