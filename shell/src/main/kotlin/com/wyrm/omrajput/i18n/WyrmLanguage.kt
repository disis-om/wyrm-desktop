package com.wyrm.omrajput.i18n

/** Desktop retains its existing English UI; phone language preferences are not
 * a Windows preference. Shared Text keeps Material's complete overloads. */
internal object WyrmLanguage {
    fun tr(text: String): String = text
}
