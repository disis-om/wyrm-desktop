package androidx.core.text

/**
 * Desktop stand-in for androidx.core's HtmlCompat, for the shared
 * WyrmMarkdown.kt (its `shadeMarkdown`, the phone's notification shade text).
 * Delegates to the desktop android.text.Html shim.
 */
object HtmlCompat {
    const val FROM_HTML_MODE_LEGACY = 0
    const val FROM_HTML_MODE_COMPACT = 63

    @JvmStatic
    fun fromHtml(source: String, flags: Int): CharSequence = android.text.Html.fromHtml(source, flags)
}
