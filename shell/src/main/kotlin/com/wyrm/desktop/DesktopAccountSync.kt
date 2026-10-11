package com.wyrm.desktop

import com.wyrm.omrajput.WyrmActivity
import com.wyrm.omrajput.data.SettingType
import com.wyrm.omrajput.data.SettingsCodec
import com.wyrm.omrajput.data.TrailSkin
import com.wyrm.omrajput.ui.JoystickLaserStore
import com.wyrm.omrajput.ui.NearOriginalStore
import com.wyrm.omrajput.ui.TeamHudStore
import com.wyrm.omrajput.ui.WyrmLookStore
import com.wyrm.omrajput.ui.lookSpec
import com.wyrm.omrajput.ui.toSkinState
import com.wyrm.omrajput.ui.toTrailSkin
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Account-linked settings on the desktop (D6, 2026-10-12): Wyrm Android's
 * AccountSync rules (CLAUDE.md "Account-linked settings") for platform
 * **windows**. Backend: `backend/src/account-settings.mjs`.
 *
 * Two documents per account:
 *  - **windows** (this computer's own copy): the engine's settings table and
 *    keyboard keys, and the preference files listed in [FILES];
 *  - **shared** (the same on every platform). The desktop writes only the
 *    keys it really has (skin, look, arena background, in-game name, Near
 *    Original, the joystick laser, and the engine's snake-look settings) on
 *    top of the copy it last read, so the phones' own keys (play feel,
 *    orientation, performance mode) are carried through untouched.
 *
 * Saved when the window loses focus (at most every 20 s), when it closes and
 * when the player logs out; restored behind the W on log in; log out wipes
 * this computer. Nothing is uploaded before this computer holds the account's
 * copy ([ready]), so a fresh install can never overwrite the account with its
 * defaults. Restore is per key: an unknown, missing or invalid key is skipped.
 *
 * **Rule for every future change:** a new thing a player sets goes in a file
 * listed here (or it will not follow the account); a renamed key gets an
 * entry in [PREF_RENAMES] / [ENGINE_RENAMES].
 */
internal object DesktopAccountSync {
    const val PLATFORM = "windows"
    const val SCHEMA = 1

    enum class Scope { SYNC, WIPE, KEEP }

    class PrefFile(val name: String, val scope: Scope, val keys: Set<String>? = null, val rest: Scope = Scope.WIPE)

    val FILES: List<PrefFile> = listOf(
        // Synced: the player's choices on this computer.
        PrefFile("wyrm_ui_preferences", Scope.SYNC),            // theme, intensity, nickname_chosen
        PrefFile("wyrm_near_original", Scope.SYNC),             // also in the shared document
        PrefFile("wyrm_joystick_laser", Scope.SYNC),            // also in the shared document
        PrefFile("wyrm_play_feel", Scope.SYNC),                 // Display › Look ahead (shared: lookAhead)
        PrefFile("wyrm_team_hud", Scope.SYNC),                  // roster + chat window look
        PrefFile("wyrm_skin_studio", Scope.SYNC),               // saved patterns
        PrefFile("wyrm_saved_arenas", Scope.SYNC),
        PrefFile("wyrm_recent_arenas", Scope.SYNC),
        PrefFile("wyrm_notification_preferences", Scope.SYNC),
        PrefFile("wyrm_desktop_performance", Scope.SYNC),       // frame cap, vsync
        PrefFile("wyrm_desktop_updates", Scope.SYNC, keys = setOf("beta"), rest = Scope.KEEP), // Beta updates
        // Run receipts not yet uploaded: carried by the account so none is lost.
        PrefFile("wyrm_pending_runs", Scope.SYNC),
        PrefFile("wyrm_drop", Scope.SYNC, keys = setOf("auto_send")),
        PrefFile("wyrm_crash", Scope.SYNC, keys = setOf("auto_send"), rest = Scope.KEEP),
        // Wiped: the account's caches and session.
        PrefFile("wyrm_look", Scope.WIPE),                      // travels in the shared document
        PrefFile("wyrm_orientation", Scope.WIPE),               // a phone's; never used here
        PrefFile("wyrm_session", Scope.WIPE),
        PrefFile("wyrm_team_mode", Scope.WIPE),                 // team keys never leave this computer
        PrefFile("wyrm_social_cache", Scope.WIPE),
        PrefFile("wyrm_global_chat", Scope.WIPE),
        PrefFile("wyrm_support_seen", Scope.WIPE),
        PrefFile("wyrm_account_sync", Scope.WIPE),              // an owed restore, per account
        // About this computer: kept.
        PrefFile("wyrm_desktop_window", Scope.KEEP),            // full screen
    )

    /** Old preference key -> new, per file, for documents saved by older builds. */
    private val PREF_RENAMES: Map<String, Map<String, String>> = emptyMap()
    /** Old engine setting id -> new id. */
    private val ENGINE_RENAMES: Map<String, String> = emptyMap()

    /** True once this computer holds the account's own settings. */
    @Volatile var ready = false
        private set
    private var lastSave = 0L
    /** The shared document as the server last gave it: the base every save writes on top of. */
    private var sharedBase: JSONObject? = null
    private val syncPrefs get() = DesktopContext.getSharedPreferences("wyrm_account_sync", 0)

    class Report(val applied: Int, val skipped: Int)

    /* ------------------------------------------------------------ engine */

    private fun engineSettings() = runCatching { SettingsCodec.settings(WyrmActivity.nativeSettingsSnapshot()) }.getOrDefault(emptyList())
    private fun engineHotkeys() = runCatching { SettingsCodec.hotkeys(WyrmActivity.nativeHotkeysSnapshot()) }.getOrDefault(emptyList())

    /** The engine has described its settings (its title screen is up). */
    private suspend fun waitForEngine(timeoutMs: Long = 12_000L): Boolean {
        val until = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < until) {
            if (Engine.started && engineSettings().isNotEmpty()) return true
            delay(200)
        }
        return false
    }

    private fun writeSetting(id: String, values: FloatArray) {
        if (!Engine.started || values.isEmpty()) return
        WyrmActivity.nativeSetSetting(id, values.getOrElse(0) { 0f }, values.getOrElse(1) { 0f },
            values.getOrElse(2) { 0f }, values.getOrElse(3) { 0f }, values.size)
    }

    /* ---------------------------------------------------------- snapshot */

    /** This computer's copy, ready for `PUT /v1/me/settings/windows`. */
    fun platformDocument(): JSONObject {
        val doc = JSONObject().put("schema", SCHEMA).put("platform", PLATFORM)
        if (Engine.started) {
            val settings = engineSettings()
            if (settings.isNotEmpty()) {
                val values = JSONObject()
                settings.forEach { values.put(it.id, it.raw) }
                doc.put("engine", values)
            }
            val keys = engineHotkeys()
            if (keys.isNotEmpty()) {
                doc.put("hotkeys", JSONArray().apply {
                    keys.forEach {
                        put(JSONObject().put("action", it.action).put("name", it.name).put("key", it.key)
                            .put("mode", it.mode).put("visible", it.visible)
                            .put("x", it.x.toDouble()).put("y", it.y.toDouble()))
                    }
                })
            }
        }
        val prefs = JSONObject()
        FILES.filter { it.scope == Scope.SYNC }.forEach { file ->
            val out = JSONObject()
            DesktopContext.getSharedPreferences(file.name, 0).all.forEach { (key, value) ->
                if (file.keys != null && key !in file.keys) return@forEach
                // Every desktop preference is text (DesktopContext); kept as Android's typed pair.
                if (value != null) out.put(key, JSONArray().put("s").put(value.toString()))
            }
            if (out.length() > 0) prefs.put(file.name, out)
        }
        doc.put("prefs", prefs)
        return doc
    }

    /** A typed value from any platform's document, as the text the desktop stores. */
    private fun text(typed: JSONArray): String? = runCatching {
        when (typed.getString(0)) {
            "b" -> typed.getBoolean(1).toString()
            "i" -> typed.getInt(1).toString()
            "l" -> typed.getLong(1).toString()
            "f" -> typed.getDouble(1).toFloat().takeIf { it.isFinite() }?.toString()
            "s" -> typed.getString(1)
            "S" -> typed.getJSONArray(1).let { rows -> (0 until rows.length()).joinToString("\u001F") { rows.getString(it) } }
            else -> null
        }
    }.getOrNull()

    /** The account's shared document with this computer's keys on top. */
    fun sharedDocument(): JSONObject {
        val doc = JSONObject((sharedBase ?: JSONObject()).toString()).put("schema", 1)
        doc.put("skin", Engine.skin.toTrailSkin(WyrmLookStore.spec()).toJson())
        doc.put("arenaBackground", Engine.arenaBackground)
        val nickname = HomeState.nickname.ifEmpty { Engine.savedNickname }
        doc.put("nickname", nickname)
        val ui = DesktopContext.getSharedPreferences("wyrm_ui_preferences", 0)
        doc.put("nicknameChosen", ui.getBoolean("nickname_chosen", nickname.isNotEmpty()))
        doc.put("nearOriginal", NearOriginalStore.on)
        doc.put("joystickLaser", JSONObject().put("on", JoystickLaserStore.on).put("length", JoystickLaserStore.length.toDouble()))
        // Look ahead is set here too (Settings › Display); arrow motion and zoom style are the phones'.
        doc.put("lookAhead", com.wyrm.omrajput.ui.PlayFeelStore.lookAhead)
        // The snake look and Auto restart: only what the engine has reported.
        val reported = engineSettings().associateBy { it.id }
        reported["general.auto_respawn"]?.let { doc.put("autoRestart", it.number >= 0.5f) }
        reported["normal.render_mode"]?.let { doc.put("renderModeNormal", it.number.toInt().coerceIn(0, 3)) }
        reported["assist.render_mode"]?.let { doc.put("renderModeAssist", it.number.toInt().coerceIn(0, 3)) }
        reported["normal.spine"]?.let { doc.put("spineNormal", it.number >= 0.5f) }
        reported["assist.spine"]?.let { doc.put("spineAssist", it.number >= 0.5f) }
        reported["assist.hide_cosmetics"]?.let { doc.put("assistHideCosmetics", it.number >= 0.5f) }
        reported["normal.spine_width"]?.let { doc.put("spineWidthNormal", it.number.coerceIn(0f, 1f).toDouble()) }
        reported["assist.spine_width"]?.let { doc.put("spineWidthAssist", it.number.coerceIn(0f, 1f).toDouble()) }
        reported["normal.snake_shadow"]?.let { doc.put("snakeShadowNormal", it.number >= 0.5f) }
        reported["assist.snake_shadow"]?.let { doc.put("snakeShadowAssist", it.number >= 0.5f) }
        return doc
    }

    /* ----------------------------------------------------------- restore */

    /** This computer's saved copy, one key at a time: preference files first, then the engine. */
    suspend fun applyPlatform(doc: JSONObject): Report {
        var applied = 0
        var skipped = 0
        val prefs = doc.optJSONObject("prefs") ?: JSONObject()
        val known = FILES.associateBy { it.name }
        prefs.keys().forEach { name ->
            val file = known[name]
            val rows = prefs.optJSONObject(name)
            if (file == null || file.scope != Scope.SYNC || rows == null) { skipped += rows?.length() ?: 1; return@forEach }
            val renames = PREF_RENAMES[name].orEmpty()
            val editor = DesktopContext.getSharedPreferences(name, 0).edit()
            rows.keys().forEach { stored ->
                val key = renames[stored] ?: stored
                val value = rows.optJSONArray(stored)?.let(::text)
                if (value == null || (file.keys != null && key !in file.keys)) skipped++ else { editor.putString(key, value); applied++ }
            }
            editor.commit()
        }
        if (Engine.started) {
            val current = engineSettings().associateBy { it.id }
            val saved = doc.optJSONObject("engine") ?: JSONObject()
            val changes = mutableListOf<Pair<String, FloatArray>>()
            saved.keys().forEach { stored ->
                val id = ENGINE_RENAMES[stored] ?: stored
                val setting = current[id]
                val values = saved.optString(stored, "").split(",").mapNotNull { it.trim().toFloatOrNull()?.takeIf(Float::isFinite) }
                if (setting == null || values.isEmpty()) { skipped++; return@forEach }
                val wanted = when (setting.type) {
                    SettingType.COLOR3, SettingType.COLOR4 -> values.map { it.coerceIn(0f, 1f) }
                    else -> listOf(values[0].coerceIn(minOf(setting.minimum, setting.maximum), maxOf(setting.minimum, setting.maximum)))
                }
                val now = setting.raw.split(",").mapNotNull { it.trim().toFloatOrNull() }
                val same = now.size >= wanted.size && wanted.indices.all { kotlin.math.abs(now[it] - wanted[it]) < 0.0006f }
                if (!same) changes += id to wanted.toFloatArray()
                applied++
            }
            // The engine's mailbox takes 128 changes a frame: small batches.
            changes.chunked(40).forEachIndexed { index, batch ->
                if (index > 0) delay(140)
                batch.forEach { (id, values) -> writeSetting(id, values) }
            }
            val buttons = engineHotkeys()
            val byName = buttons.associateBy { it.name }
            val byAction = buttons.associateBy { it.action }
            val rows = doc.optJSONArray("hotkeys") ?: JSONArray()
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val target = byName[row.optString("name")] ?: byAction[row.optInt("action", -1)]
                if (target == null) { skipped++; continue }
                val x = row.optDouble("x", Double.NaN).toFloat()
                val y = row.optDouble("y", Double.NaN).toFloat()
                if (!x.isFinite() || !y.isFinite()) { skipped++; continue }
                WyrmActivity.nativeSetHotkey(target.action, row.optInt("key", target.key),
                    if (target.fixedMode) target.mode else row.optInt("mode", target.mode),
                    row.optBoolean("visible", target.visible), x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
                applied++
            }
        }
        return Report(applied, skipped)
    }

    /** The shared document: each key on its own; a missing one keeps this computer's value. */
    fun applyShared(doc: JSONObject) {
        TrailSkin.from(doc.optJSONObject("skin"))?.let { skin ->
            val state = skin.toSkinState()
            // A preset keeps the player's own pattern underneath, as picking one does.
            if (state.code.isNotEmpty()) Engine.skinCode(state.code, state.coloursFor(state.code.length))
            if (!state.custom) Engine.skinPreset(state.preset)
            Engine.skinAccessory(state.accessory)
            WyrmLookStore.wear(skin.lookSpec())
        }
        val background = doc.optInt("arenaBackground", -1)
        if (background >= 0) Engine.skinBackground(background)
        if (doc.has("nickname")) {
            val name = doc.optString("nickname").take(24)
            HomeState.nickname = name
            if (Engine.started) WyrmActivity.nativeSetNickname(name)
            DesktopContext.getSharedPreferences("wyrm_ui_preferences", 0).edit()
                .putBoolean("nickname_chosen", doc.optBoolean("nicknameChosen", name.isNotEmpty())).commit()
        }
        if (doc.has("nearOriginal")) {
            DesktopContext.getSharedPreferences("wyrm_near_original", 0).edit()
                .putBoolean("on", doc.optBoolean("nearOriginal", false)).commit()
        }
        doc.optJSONObject("joystickLaser")?.let { laser ->
            val length = laser.optDouble("length", Double.NaN)
            DesktopContext.getSharedPreferences("wyrm_joystick_laser", 0).edit().apply {
                if (laser.has("on")) putBoolean("on", laser.optBoolean("on", true))
                if (length.isFinite()) putFloat("length", length.toFloat().coerceIn(JoystickLaserStore.RANGE))
            }.commit()
        }
        if (doc.has("lookAhead")) {
            DesktopContext.getSharedPreferences("wyrm_play_feel", 0).edit()
                .putBoolean("look_ahead", doc.optBoolean("lookAhead", false)).commit()
        }
        fun bool(key: String, id: String) {
            if (doc.has(key)) writeSetting(id, floatArrayOf(if (doc.optBoolean(key, false)) 1f else 0f))
        }
        bool("autoRestart", "general.auto_respawn")
        if (doc.has("renderModeNormal")) writeSetting("normal.render_mode", floatArrayOf(doc.optInt("renderModeNormal", 0).coerceIn(0, 3).toFloat()))
        if (doc.has("renderModeAssist")) writeSetting("assist.render_mode", floatArrayOf(doc.optInt("renderModeAssist", 0).coerceIn(0, 3).toFloat()))
        bool("spineNormal", "normal.spine")
        bool("spineAssist", "assist.spine")
        bool("assistHideCosmetics", "assist.hide_cosmetics")
        doc.optDouble("spineWidthNormal", Double.NaN).takeIf { it.isFinite() }?.let {
            writeSetting("normal.spine_width", floatArrayOf(it.toFloat().coerceIn(0f, 1f)))
        }
        doc.optDouble("spineWidthAssist", Double.NaN).takeIf { it.isFinite() }?.let {
            writeSetting("assist.spine_width", floatArrayOf(it.toFloat().coerceIn(0f, 1f)))
        }
        bool("snakeShadowNormal", "normal.snake_shadow")
        bool("snakeShadowAssist", "assist.snake_shadow")
    }

    /** Stores that read their files once: read them again after a restore or a wipe. */
    private fun reloadStores() {
        EngineSettings.loadTheme()
        EngineSettings.publishArenaTheme()
        EngineSettings.loadPerformance()
        EngineSettings.publishFramePolicy()
        WyrmLookStore.reload(DesktopContext)
        NearOriginalStore.reload(DesktopContext)
        JoystickLaserStore.reload(DesktopContext)
        TeamHudStore.reload(DesktopContext)
        com.wyrm.omrajput.ui.PlayFeelStore.reload(DesktopContext)
        com.wyrm.omrajput.data.DropWatch.reloadPrefs()
        com.wyrm.omrajput.data.CrashWatch.reloadPrefs()
        EngineSettings.refresh()
        EngineSettings.refreshKeys()
    }

    /* -------------------------------------------------------- the flow */

    /** Log in: the account's copy goes in behind the W. False when Wyrm could not be reached. */
    suspend fun restore(): Boolean {
        val owner = Account.player?.id ?: return false
        ready = false
        syncPrefs.edit().putString("restore_owed", owner).commit()
        return tryRestore()
    }

    /** One attempt: fetch, apply key by key, reload the stores. */
    private suspend fun tryRestore(): Boolean {
        val (platform, shared) = runCatching { Account.repository.accountSettings(PLATFORM) }.getOrElse { return false }
        waitForEngine()
        sharedBase = shared
        if (platform != null) runCatching { applyPlatform(platform) }
        if (shared != null) runCatching { applyShared(shared) }
        reloadStores()
        syncPrefs.edit().remove("restore_owed").commit()
        ready = true
        return true
    }

    /**
     * Launch with a saved session: finish an owed restore (retrying every 30 s
     * while offline), or start keeping the account's copy current. The first
     * save uploads what this computer already had, so nobody loses a setting.
     */
    suspend fun resume() {
        val me = Account.player?.id ?: return
        val owed = syncPrefs.getString("restore_owed", null)
        if (owed != null && owed == me) {
            while (Account.hasSession && Account.player?.id == me && !ready) {
                if (tryRestore()) break
                delay(30_000L)
            }
        } else {
            // The account's shared copy is the base this computer writes on top of.
            sharedBase = runCatching { Account.repository.accountSettings(PLATFORM).second }.getOrNull()
            ready = true
            delay(6_000L)
            save()
        }
    }

    /**
     * Saves this computer's settings to the account. Refuses when this computer
     * does not hold the account's copy yet, or the engine has not described its
     * settings: a partial copy would replace a whole one.
     */
    suspend fun save(force: Boolean = false): Result<Unit> {
        if (!Account.hasSession || !ready) return Result.failure(IllegalStateException("NOT_READY"))
        if (!force && System.currentTimeMillis() - lastSave < 20_000L) return Result.success(Unit)
        // The engine publishes the worn skin when asked; give it a moment.
        if (Engine.started) WyrmActivity.nativeSkinSync(false)
        delay(250)
        val platform = platformDocument()
        if (!platform.has("engine")) return Result.failure(IllegalStateException("ENGINE_NOT_READY"))
        val shared = if (Engine.skinKnown) sharedDocument() else null
        return runCatching { Account.repository.saveAccountSettings(PLATFORM, platform, shared) }
            .onSuccess {
                lastSave = System.currentTimeMillis()
                if (shared != null) sharedBase = shared
            }
    }

    /**
     * Log out: every preference file not about this computer, the engine back
     * to its defaults (skin, keys, layout, name), and the account's caches.
     */
    fun wipe() {
        ready = false
        sharedBase = null
        FILES.forEach { file ->
            val store = DesktopContext.getSharedPreferences(file.name, 0)
            when {
                file.scope == Scope.KEEP -> Unit
                file.keys == null -> store.edit().clear().commit()
                file.rest == Scope.KEEP -> store.edit().apply { file.keys.forEach { remove(it) } }.commit()
                else -> store.edit().clear().commit()
            }
        }
        if (Engine.started) WyrmActivity.nativeSettingsAction(1)
        // Cached faces, photos and flags of the account.
        runCatching { DesktopContext.cacheDir.listFiles()?.forEach { if (it.isDirectory) it.listFiles()?.forEach(File::deleteRecursively) else it.delete() } }
        HomeState.nickname = ""
        reloadStores()
    }
}
