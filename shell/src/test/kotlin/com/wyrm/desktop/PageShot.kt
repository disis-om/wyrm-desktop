package com.wyrm.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.wyrm.omrajput.ui.DesktopAuth
import com.wyrm.omrajput.ui.UsernameAvailability
import com.wyrm.omrajput.ui.Wyrm
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * Renders a page offscreen to PNGs, with no window, no engine and no network:
 * `gradlew pageShot -Pshot=<out.png> [-Psection=<Skin section>|AUTH|AUTH_SMALL|SYNC|HOME|HOME_PICKER|HOME_ALERTS]`.
 * AUTH saves the sign-in opening at several moments (<out>-<ms>.png).
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    val out = File(args.getOrElse(0) { "page-shot.png" })
    val what = args.getOrNull(1)
    PresetPreviews.loadBundled()
    val home = what != null && what.startsWith("HOME")
    val settingsShot = what != null && what.startsWith("SETTINGS")
    if (settingsShot) {
        // A few rows as the engine describes them (android_settings.c), for the look only.
        fun row(id: String, group: String, type: String, label: String, raw: String, min: Float, max: Float, options: String = "") =
            com.wyrm.omrajput.data.Setting(id, group, com.wyrm.omrajput.data.SettingType.of(type), label, "", raw, min, max, options.split('|').filter { it.isNotEmpty() })
        val real = File("build/probe-settings.txt").takeIf { it.isFile }?.readText()
        if (real != null) EngineSettings.showForPreview(com.wyrm.omrajput.data.SettingsCodec.settings(real), "2.8") else EngineSettings.showForPreview(listOf(
            row("general.snake_scores", "general", "bool", "Show snake scores", "1", 0f, 1f),
            row("general.show_own_name", "general", "bool", "Show own name", "1", 0f, 1f),
            row("general.minimap_size", "general", "float", "Minimap size", "300", 100f, 600f),
            row("general.ui_font", "general.type", "float", "Interface text", "1", 0.5f, 2f),
            row("general.smooth_zoom", "general", "bool", "Smooth zoom", "1", 0f, 1f),
            row("normal.food_type", "normal", "enum", "Food", "0", 0f, 5f, "Original|Rings|Squares|Triangles|Stars|Hexagons"),
        ), "2.8")
        // Settings › Controls: the engine's actions with Wyrm Desktop's default keys.
        val names = listOf("HUD", "Show names", "Big food", "Assist", "Bot", "Hotkey menu", "Restart", "Quit", "Zoom in", "Zoom out", "Boost", "Turn left", "Turn right", "Fullscreen", "Auto restart", "Eyes back")
        val keyNames = mapOf(72 to "H", 80 to "P", 70 to "F", 75 to "K", 84 to "T", 90 to "Z", 82 to "R", 81 to "Q", 78 to "N", 77 to "M", 32 to "SPACE", 65 to "A", 68 to "D", 86 to "V", 69 to "E")
        val realKeys = File("build/probe-hotkeys.txt").takeIf { it.isFile }?.readText()?.split('\u0000')
        if (realKeys != null && realKeys.size == 2) EngineSettings.showKeysForPreview(
            com.wyrm.omrajput.data.SettingsCodec.hotkeys(realKeys[0]),
            com.wyrm.omrajput.data.SettingsCodec.keyOptions(realKeys[1]),
        ) else EngineSettings.showKeysForPreview(
            DesktopKeys.defaults.map { (action, def) ->
                com.wyrm.omrajput.data.Hotkey(action, names[action], def.first, keyNames[def.first].orEmpty(), def.second, action >= 8 || action == 6 || action == 7, false, 0.5f, 0.5f)
            },
            keyNames.map { (k, n) -> com.wyrm.omrajput.data.KeyOption(k, n) },
        )
        what.removePrefix("SETTINGS").removePrefix("_").takeIf { it.isNotEmpty() }?.let { name ->
            SettingsPageShot.open = SettingsPage.valueOf(name)
        }
    }
    // SOCIAL / SOCIAL_<page> / TRAILS: sample people, chats and trails (D5); nothing is fetched.
    val social = what != null && (what.startsWith("SOCIAL") || what.startsWith("TRAILS"))
    if (social) socialPreview(what!!)
    // SETUP_*: Wyrm Setup's screens in its 760 x 480 window.
    if (what != null && what.startsWith("SETUP_")) {
        val (mode, stage, p) = when (what) {
            "SETUP_WELCOME" -> Triple(SetupMode.INSTALL, SetupStage.WELCOME, 0f)
            "SETUP_WORKING" -> Triple(SetupMode.INSTALL, SetupStage.WORKING, 0.62f)
            "SETUP_DONE" -> Triple(SetupMode.INSTALL, SetupStage.DONE, 1f)
            "SETUP_UNINSTALL" -> Triple(SetupMode.UNINSTALL, SetupStage.WELCOME, 0f)
            else -> Triple(SetupMode.INSTALL, SetupStage.FAILED, 0f)
        }
        val setupFrame = runCatching { java.awt.Frame() }.getOrNull()
        val scene = ImageComposeScene(760, 480, Density(1f)) { SetupPreview(mode, stage, p, setupFrame) }
        var t = 0L
        while (t < 2_500_000_000L) { scene.render(t); t += 16_000_000L }
        out.writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        println("saved ${out.absolutePath}")
        scene.close()
        return
    }
    // UPDATE_CARD: the update card over Paper, with a made-up offer.
    if (what == "UPDATE_CARD") {
        DesktopUpdater.showForPreview(DesktopUpdater.Manifest(
            110, "1.1.0", "https://github.com/disis-om/wyrm-desktop/releases/download/v1.1.0/WyrmSetup-1.1.0.exe",
            94_371_840L, "0".repeat(64), "A new version of Wyrm",
            listOf("Updates from inside Wyrm", "Smoother arenas", "Team chat by mouse"), mandatory = false, beta = false,
        ))
        val scene = ImageComposeScene(1000, 640, Density(1f)) {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(com.wyrm.omrajput.ui.Wyrm.Paper)) { UpdateCardHost(exit = {}) }
        }
        var t = 0L
        while (t < 1_000_000_000L) { scene.render(t); t += 16_000_000L }
        out.writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        println("saved ${out.absolutePath}")
        scene.close()
        return
    }
    // STUDIO_EXPORT: the studio's export (ImageComposeScene), saved as it would be posted.
    if (what == "STUDIO_EXPORT") {
        javax.imageio.ImageIO.write(studioExportProbe(), "png", out)
        println("saved ${out.absolutePath}")
        return
    }
    // SKIN_TRIAL: "Try this skin" from a made-up trail, on the Skin tab's Presets.
    if (what == "SKIN_TRIAL") {
        SkinTrial.open(com.wyrm.omrajput.data.Trail(
            id = "t9", kind = "text", caption = "my skin", photo = null, thumbUrl = null, likeCount = 0, commentCount = 0,
            liked = false, mine = false, createdAt = java.time.Instant.now().toString(),
            author = com.wyrm.omrajput.data.TrailAuthor("k", "Kaira", "@kaira", "", "pastel-mint"),
            skin = com.wyrm.omrajput.data.TrailSkin(true, 3, "abcdefgh", List(8) { 0 }, -1, com.wyrm.omrajput.data.TrailSkinLook(-1, 0.5f, -1, -1)),
        ))
    }
    if (what != null && what != "AUTH" && what != "AUTH_SMALL" && what != "SYNC" && what != "LOBBY" && what != "TEAM" && !home && !settingsShot && !social && what != "SKIN_TRIAL") lastSection = Section.valueOf(what)
    HomeState.pickerOpen = what == "HOME_PICKER"
    HomeState.alertsOpen = what == "HOME_ALERTS"
    if (what == "HOME_ALERTS") {
        val soon = java.time.Instant.now().plusSeconds(3 * 3600 + 125).toString()
        Account.showNotificationsForPreview(listOf(
            com.wyrm.omrajput.data.ServerNotification(
                "1", "broadcast", "Wyrm 7.1 is out",
                "## What's new\n\n**Team mode** on every app, *smoother* arenas and `Eyes Back`.\n\n- Saved arenas\n- Country flags\n  - nested item\n\n1. Open **Settings**\n2. Pick a theme\n\n> Thanks for playing!\n\n| Arena | Ping |\n|---|---|\n| 4817 | 61 ms |\n\n- [x] Done\n- [ ] Next\n\n~~old~~ new · [Website](https://www.omrajput.in)\n\n```\ncode block\n```",
                emptyMap(), "2026-10-11T10:00:00Z", false,
            ),
            com.wyrm.omrajput.data.ServerNotification(
                "2", "event", "Battledome: Sunday Brawl",
                "Bring your team. **Top 3** win a custom tag.",
                mapOf("startsAt" to soon, "organizer" to "OM", "address" to "148.113.20.151:475"),
                "2026-10-11T09:00:00Z", false,
            ),
            com.wyrm.omrajput.data.ServerNotification(
                "3", "support", "We fixed the drop you reported", "Thanks for the report. It ships in **7.1.1**.",
                emptyMap(), "2026-10-10T09:00:00Z", true,
            ),
        ))
    }
    // AUTH_SMALL: the sign-in at the sign-in window's size (Main.kt, 860 x 540).
    val small = what == "AUTH_SMALL"
    // -Pheight=<px>: a tall render, to see a whole long page at once.
    val tall = args.getOrNull(2)?.toIntOrNull()
    // The whole window now (no Windows title bar): Wyrm's strip on top, as Main.kt draws it.
    val frame = runCatching { java.awt.Frame() }.getOrNull()
    val scene = ImageComposeScene(if (small) 860 else 1280, tall ?: if (small) 540 else 800, Density(1f)) {
      Box(Modifier.fillMaxSize().background(Wyrm.Paper)) {
        Box(Modifier.fillMaxSize().padding(top = if (small || what == "SYNC") 0.dp else TitleStripHeight)) {
            when (what) {
                "SYNC" -> com.wyrm.omrajput.ui.WyrmSessionTransition("Syncing your Wyrm…")
                "TEAM" -> TeamPage(onBack = {})
                "LOBBY" -> { HomeState.selected = "148.113.20.151:443"; HomeState.nickname = "Om"; DesktopLobby() }
                "AUTH", "AUTH_SMALL" -> DesktopAuth(
                    checkUsername = { UsernameAvailability.AVAILABLE },
                    signUp = { _, _ -> null },
                    logIn = { _, _ -> null },
                    bootstrap = {},
                    onComplete = {},
                )
                else -> {
                    Box(Modifier.fillMaxSize()) {
                        when {
                            what?.startsWith("TRAILS") == true -> TrailsTabPage()
                            social -> SocialTabPage()
                            settingsShot -> SettingsPage()
                            home -> PlayPage()
                            else -> SkinPage()
                        }
                    }
                    run {
                        val shown = when {
                            what?.startsWith("TRAILS") == true -> Tab.Trails
                            social -> Tab.Social
                            settingsShot -> Tab.Settings
                            home -> Tab.Play
                            else -> Tab.Skin
                        }
                        TopTabs(shown, {}, Modifier.align(Alignment.TopCenter).padding(top = 14.dp).width(topTabsWidth(1280.dp)))
                        if (home) HomeOverlays()
                        if (social) SocialPagesHost()
                    }
                }
            }
        }
        if (frame != null) TitleStrip(frame, compact = small, onClose = {})
      }
    }
    val moments = if (what == "AUTH" || what == "AUTH_SMALL" || what == "SYNC") listOf(600L, 1500L, 2400L, 4200L) else listOf(4000L)
    var time = 0L
    for (moment in moments) {
        while (time < moment * 1_000_000L) {
            scene.render(time)
            time += 16_000_000L
            Thread.sleep(16)
        }
        val image = scene.render(time)
        val file = if (moments.size == 1) out else File(out.parentFile, out.nameWithoutExtension + "-$moment.png")
        file.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        println("saved ${file.absolutePath}")
    }
    scene.close()
}

/** Sample Social data for the offscreen renders (made-up players, no network). */
private fun socialPreview(what: String) {
    fun p(id: String, name: String, user: String, score: Long, kills: Long, follow: Boolean = false, back: Boolean = false, bio: String = "") =
        com.wyrm.omrajput.data.ApiPlayer(id, ingameName = name, username = user, displayName = name, avatarKey = listOf("pastel-mint", "pastel-sky", "pastel-peach", "pastel-lilac", "pastel-lemon")[id.hashCode().mod(5)],
            highestScore = score, kills = kills, followerCount = 120 + score % 97, followingCount = 40 + kills % 31,
            isFollowing = follow, followsYou = back, canMessage = follow && back, bio = bio)
    val me = p("me", "OM", "om", 48210, 912, bio = "Making Wyrm.")
    Account.accept(me)
    val board = listOf(
        p("a", "Kaira", "kaira", 91400, 2210, true, true), p("b", "SMT SUKKI", "sukki", 77120, 1402, true, true),
        p("c", "NeonViper", "neonviper", 65030, 1980, true, false), me, p("d", "Riya", "riya", 41200, 655, false, true),
        p("e", "Ghost", "ghost", 39880, 702), p("f", "Batman", "batman", 30010, 512), p("g", "Zed", "zed", 21000, 330),
    )
    val now = java.time.Instant.now()
    fun at(min: Long) = now.minusSeconds(min * 60).toString()
    fun msg(id: String, who: com.wyrm.omrajput.data.ApiPlayer, body: String, min: Long) =
        com.wyrm.omrajput.data.ChatMessage(id, body, at(min), who.id, who.displayName, who.username.orEmpty(), "", who.avatarKey)
    val global = listOf(
        msg("1", board[0], "anyone in 4817? team up", 14), msg("2", board[1], "coming", 13), msg("3", me, "Desktop build is looking good", 9),
        msg("4", board[2], "the arena picker flags are so clean", 6), msg("5", board[4], "gg everyone", 2),
    )
    val chats = listOf(
        com.wyrm.omrajput.data.Conversation(board[0], "see you in the arena", at(5), 2),
        com.wyrm.omrajput.data.Conversation(board[1], "that kill was insane", at(48), 0),
    )
    // Pages open over a section; sections are the sidebar's rows (D6 desktop layout).
    val page: Social.Page? = when (what) {
        "SOCIAL_PLAYER" -> Social.Page.Player("a")
        "SOCIAL_EDIT" -> Social.Page.EditProfile
        "SOCIAL_STUDIO" -> Social.Page.Studio
        else -> null
    }
    Social.showForPreview(board, chats, global, page, viewed = board[0])
    val section = when (what) {
        "SOCIAL_BOARD" -> Social.Section.LEADERBOARD
        "SOCIAL_PROFILE" -> Social.Section.PROFILE
        "SOCIAL_GLOBAL" -> Social.Section.GLOBAL
        "SOCIAL_CONNECTIONS" -> Social.Section.CONNECTIONS
        "SOCIAL_VOICE" -> Social.Section.VOICE
        else -> Social.Section.MESSAGES
    }
    Social.showSectionForPreview(section, thread = if (what == "SOCIAL" || what == "SOCIAL_THREAD") board[0] else null,
        trail = if (what == "TRAILS_WINDOW") "t1" else null)
    if (what.startsWith("TRAILS") || what == "SOCIAL_PROFILE") {
        val trails = listOf(
            com.wyrm.omrajput.data.Trail(
                id = "t1", kind = "text", caption = "First trail from Wyrm Desktop", photo = null, thumbUrl = null,
                likeCount = 12, commentCount = 3, liked = true, mine = false, createdAt = at(30),
                author = com.wyrm.omrajput.data.TrailAuthor("a", "Kaira", "@kaira", "", "pastel-mint"),
            ),
            com.wyrm.omrajput.data.Trail(
                id = "t2", kind = "text", caption = "Who's up for a Battledome on Sunday?", photo = null, thumbUrl = null,
                likeCount = 4, commentCount = 0, liked = false, mine = false, createdAt = at(120),
                author = com.wyrm.omrajput.data.TrailAuthor("b", "SMT SUKKI", "@sukki", "", "pastel-sky"),
            ),
        )
        com.wyrm.omrajput.ui.TrailsStore.trails.addAll(trails)
    }
}
