package com.wyrm.omrajput.data

import android.content.Context
import com.wyrm.omrajput.WyrmActivity
import java.io.File
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Wyrm Desktop's team vault key, in place of Android's Keystore one (same
 * object name and call, so TeamService.kt compiles here unchanged).
 *
 * A random AES-256 key, kept on disk only as a Windows DPAPI blob
 * (`nativeDesktopProtect`, CryptProtectData): only this Windows user on this
 * computer can open it, so a copy of the files elsewhere is worth nothing, as
 * on a phone. File: %APPDATA%\Wyrm\secure\<alias>.key.
 */
internal object TeamVaultKey {
    private val lock = Any()
    @Volatile private var cached: SecretKey? = null

    fun key(context: Context, alias: String): SecretKey = synchronized(lock) {
        cached?.let { return it }
        val dir = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "Wyrm/secure").apply { mkdirs() }
        val file = File(dir, "$alias.key")
        val raw: ByteArray = if (file.isFile) {
            WyrmActivity.nativeDesktopUnprotect(file.readBytes())
                ?: error("The saved team key could not be opened by this Windows user")
        } else {
            val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val sealed = WyrmActivity.nativeDesktopProtect(fresh) ?: error("Windows could not protect the team key")
            val temp = File(dir, "$alias.key.tmp")
            temp.writeBytes(sealed)
            if (!temp.renameTo(file)) {
                file.delete()
                check(temp.renameTo(file)) { "The team key could not be saved" }
            }
            fresh
        }
        require(raw.size == 32) { "The saved team key is damaged" }
        SecretKeySpec(raw, "AES").also { cached = it }
    }
}
