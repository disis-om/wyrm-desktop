import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.zip.ZipFile

// Kotlin 2.4 + Compose Multiplatform 1.12: what Kyant's Liquid Glass
// (backdrop 2.x, the same library Wyrm Android uses) is built against.
plugins {
    kotlin("jvm") version "2.4.10"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
    id("org.jetbrains.compose") version "1.12.0"
}

group = "com.wyrm.omrajput"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
}

// Wyrm Android's Kotlin, reused as it is (AGENTS.md "Reuse the Android UI"):
// the listed files are synced from the Android tree at build time, never
// copied into this repo. Grow this list screen by screen.
val androidSrc = file("../../Wyrm Android/android/app/src/main/java")
val sharedAndroidFiles = listOf(
    "com/wyrm/omrajput/data/ArenaDirectory.kt",
    "com/wyrm/omrajput/data/WyrmSettings.kt",
    // Accounts: the backend client and its models (Google sign-in stays Android's).
    "com/wyrm/omrajput/data/WyrmRepository.kt",
    "com/wyrm/omrajput/data/TrailsApi.kt",
    "com/wyrm/omrajput/data/SupportModels.kt",
    "com/wyrm/omrajput/ui/CinematicAuthScreen.kt",
    "com/wyrm/omrajput/ui/PrivacyScreen.kt",
    // Settings (OM 2026-10-02: every setting, as Android has it).
    "com/wyrm/omrajput/ui/SettingsScreen.kt",
    "com/wyrm/omrajput/ui/SettingsDisplayScreen.kt",
    "com/wyrm/omrajput/ui/SettingsAssistScreen.kt",
    "com/wyrm/omrajput/ui/SettingsBotScreen.kt",
    "com/wyrm/omrajput/ui/SettingsFoodScreen.kt",
    "com/wyrm/omrajput/ui/ArenaUiScreen.kt",
    "com/wyrm/omrajput/ui/SettingsAccessibilityScreen.kt",
    "com/wyrm/omrajput/ui/JoystickLaser.kt",
    "com/wyrm/omrajput/ui/PlayFeel.kt",
    "com/wyrm/omrajput/data/PlayOrientation.kt",
    "com/wyrm/omrajput/ui/WyrmMark.kt",
    // Home: the arena card, the Near Original row and switch (moved out of HomeScreen.kt / SkinScreen.kt).
    "com/wyrm/omrajput/ui/HomeCards.kt",
    "com/wyrm/omrajput/ui/NearOriginal.kt",
    // The design system: Paper colours, fonts, iOS kit, Liquid Glass.
    "com/wyrm/omrajput/ui/WyrmSurface.kt",
    "com/wyrm/omrajput/ui/IosKit.kt",
    "com/wyrm/omrajput/ui/IosGlyphs.kt",
    "com/wyrm/omrajput/ui/IosControls.kt",
    "com/wyrm/omrajput/ui/SettingsDrill.kt",
    "com/wyrm/omrajput/ui/SettingsSearch.kt",
    "com/wyrm/omrajput/ui/AdjustPreview.kt",
    // LiquidGlass.kt: Wyrm Desktop has its own, without glass (OM, 2026-10-11).
    // glass/: Liquid Glass gesture helpers, not used on the desktop (OM, 2026-10-11).
    // Skin (OM 2026-10-02: "same to same"): the editor, preview and catalogues.
    "com/wyrm/omrajput/ui/IosSkinScreen.kt",
    "com/wyrm/omrajput/ui/SkinState.kt",
    "com/wyrm/omrajput/ui/SkinCatalog.kt",
    "com/wyrm/omrajput/ui/WyrmBeads.kt",
    "com/wyrm/omrajput/ui/AirSkin.kt",
    "com/wyrm/omrajput/ui/WyrmLook.kt",
    "com/wyrm/omrajput/ui/TagTable.kt",
    "com/wyrm/omrajput/ui/WyrmTags.kt",
    // Team mode, NTL (D2, OM 2026-10-11): the service and the roster/chat look.
    // TeamVaultKey is the desktop's own (Windows DPAPI), not Android's Keystore one.
    "com/wyrm/omrajput/data/TeamService.kt",
    // Arena picker (D4): saved custom arenas and the five recently joined.
    "com/wyrm/omrajput/data/SavedArenas.kt",
    // Alerts (D4): the notification model and the full Markdown renderer.
    "com/wyrm/omrajput/data/WyrmNotification.kt",
    "com/wyrm/omrajput/ui/WyrmMarkdown.kt",
    "com/wyrm/omrajput/ui/TeamHud.kt",
    // Social (D5): the Social hub, leaderboard, chats, profile, Trails, and the
    // stale-while-revalidate caches behind them (same files as the phone).
    "com/wyrm/omrajput/data/SocialCache.kt",
    "com/wyrm/omrajput/data/BadgeStore.kt",
    "com/wyrm/omrajput/data/FriendPresence.kt",
    "com/wyrm/omrajput/ui/FriendActivityLine.kt",
    "com/wyrm/omrajput/ui/WyrmAvatar.kt",
    "com/wyrm/omrajput/ui/SocialScreen.kt",
    "com/wyrm/omrajput/ui/IosSocialScreens.kt",
    "com/wyrm/omrajput/ui/IosProfile.kt",
    "com/wyrm/omrajput/ui/Trails.kt",
    "com/wyrm/omrajput/ui/PhotoCropScreen.kt",
    "com/wyrm/omrajput/ui/WyrmMenuSheet.kt",
    "com/wyrm/omrajput/ui/WyrmFields.kt",
    "com/wyrm/omrajput/ui/CountryCodes.kt",
    // Voice rooms' models and cards: the Social hub lists rooms (calls are D7).
    "com/wyrm/omrajput/data/VoiceModels.kt",
    "com/wyrm/omrajput/ui/VoiceRoomsKit.kt",
    // D6 Account + live: account-settings document pieces, the live inbox, run
    // receipts, notifications choices, Help & feedback, crash and drop reports,
    // and the log-out sheet. Desktop halves: DesktopSupport.kt (SupportContext,
    // CrashWatch), DesktopAccountSync.kt.
    "com/wyrm/omrajput/ui/TrailSkinCodec.kt",
    // The trail studio's looks (colour matrices), shared with the phones.
    "com/wyrm/omrajput/ui/TrailLooks.kt",
    "com/wyrm/omrajput/data/SupportStore.kt",
    "com/wyrm/omrajput/data/DropWatch.kt",
    "com/wyrm/omrajput/data/LiveInbox.kt",
    "com/wyrm/omrajput/data/PendingRunStore.kt",
    "com/wyrm/omrajput/data/NotificationPreferences.kt",
    "com/wyrm/omrajput/ui/NotificationSettingsScreen.kt",
    "com/wyrm/omrajput/ui/SupportScreens.kt",
    "com/wyrm/omrajput/ui/LogOutSheet.kt",
)
val syncAndroidSources by tasks.registering(Sync::class) {
    from(androidSrc) { include(sharedAndroidFiles) }
    into(layout.buildDirectory.dir("shared-android"))
}
sourceSets["main"].kotlin.srcDir(syncAndroidSources)

// Lucide, as Wyrm Android has it: the same AAR's vector XMLs plus a generated
// com.composables.icons.lucide.R, so `painterResource(LucideR.drawable.x)` in
// the shared files resolves to the identical icon.
val lucideAar: Configuration by configurations.creating { isTransitive = false }
val lucideKotlin = layout.buildDirectory.dir("generated/lucide/kotlin")
val lucideResources = layout.buildDirectory.dir("generated/lucide/resources")
val generateLucide by tasks.registering {
    val aar = lucideAar
    inputs.files(aar)
    outputs.dir(lucideKotlin)
    outputs.dir(lucideResources)
    doLast {
        val res = lucideResources.get().asFile.resolve("lucide").apply { deleteRecursively(); mkdirs() }
        val names = mutableListOf<String>()
        ZipFile(aar.singleFile).use { zip ->
            zip.entries().asSequence()
                .filter { it.name.startsWith("res/drawable/") && it.name.endsWith(".xml") }
                .sortedBy { it.name }
                .forEach { entry ->
                    val name = entry.name.substringAfterLast('/').removeSuffix(".xml")
                    names += name
                    res.resolve("$name.xml").outputStream().use { out -> zip.getInputStream(entry).use { it.copyTo(out) } }
                }
        }
        res.resolve("index.txt").writeText(names.joinToString("\n"))
        val kt = lucideKotlin.get().asFile.resolve("com/composables/icons/lucide/R.kt")
        kt.parentFile.mkdirs()
        kt.writeText(buildString {
            appendLine("package com.composables.icons.lucide")
            appendLine()
            appendLine("@Suppress(\"ClassName\", \"ObjectPropertyName\")")
            appendLine("object R {")
            appendLine("    object drawable {")
            names.forEachIndexed { i, n -> appendLine("        const val $n = $i") }
            appendLine("    }")
            appendLine("}")
        })
    }
}
sourceSets["main"].kotlin.srcDir(files(lucideKotlin).builtBy(generateLucide))
sourceSets["main"].resources.srcDir(files(lucideResources).builtBy(generateLucide))

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.foundation)
    // No Liquid Glass on the desktop (OM, 2026-10-11): only Kyant's continuous
    // corner shapes, so every rounded corner stays the same as Android's.
    implementation("io.github.kyant0:shapes:1.2.1")
    // org.json, which Android has built in (WyrmRepository and its models).
    implementation("org.json:json:20250517")
    // Dispatchers.Main = Swing's UI thread. Shared Android code (ArenaDirectory's
    // country flags) switches to Main; without this it threw on the desktop.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    // CommonMark + GFM, the versions Wyrm Android renders notifications with.
    implementation("org.commonmark:commonmark:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.24.0")
    implementation("org.commonmark:commonmark-ext-task-list-items:0.24.0")
    implementation("org.commonmark:commonmark-ext-autolink:0.24.0")
    // The live inbox websocket (/v1/me/live), the same client Wyrm Android uses (D6).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    lucideAar("com.composables:icons-lucide-android:2.2.1@aar")
}

// Where the engine's assets (app/res) live, and the native build output.
val wyrmAndroidRoot = file("../../Wyrm Android").absolutePath
val nativeBin = file("../build/bin").absolutePath

// Wyrm Desktop's version (D8): `-PwyrmVersion=1.0.0 -PwyrmVersionCode=100`.
// It reaches the app as a resource (BuildConfig reads it) and names the setup.
val wyrmVersion = (findProperty("wyrmVersion") as String?) ?: "1.0.0"
val wyrmVersionCode = (findProperty("wyrmVersionCode") as String?)?.toInt() ?: 100
val generatedVersion = layout.buildDirectory.dir("generated/wyrm-version")
val writeWyrmVersion by tasks.registering {
    val out = generatedVersion
    inputs.property("version", wyrmVersion)
    inputs.property("code", wyrmVersionCode)
    outputs.dir(out)
    doLast {
        val file = out.get().file("wyrm-version.properties").asFile
        file.parentFile.mkdirs()
        file.writeText("version=$wyrmVersion\ncode=$wyrmVersionCode\n")
    }
}
sourceSets["main"].resources.srcDir(files(generatedVersion).builtBy(writeWyrmVersion))

// The engine's files inside the app (D8): what the packaged Wyrm runs from,
// and what `gradlew run` uses too, so a dev run and an install are the same.
// app resources dir/common/wyrm/app/res/** (textures, shaders, fonts),
//                        /wyrm/android/app/src/main/res/font/*.ttf (the UI's fonts),
//                        /bin/{wyrm,SDL3,wyrmwin}.dll.
val wyrmPayload = layout.buildDirectory.dir("app-payload")
val prepareWyrmPayload by tasks.registering(Sync::class) {
    into(wyrmPayload.map { it.dir("common") })
    from("$wyrmAndroidRoot/app/res") { into("wyrm/app/res") }
    from("$wyrmAndroidRoot/android/app/src/main/res/font") {
        include("manrope.ttf", "bodoni_moda.ttf")
        into("wyrm/android/app/src/main/res/font")
    }
    from(nativeBin) {
        include("wyrm.dll", "SDL3.dll", "wyrmwin.dll")
        into("bin")
    }
}

compose.desktop {
    application {
        mainClass = "com.wyrm.desktop.MainKt"
        // Android Studio's jbr has no jpackage: the app image is made with a full JDK 21
        // (`-PwyrmPackageJdk=<path>`, else Temurin 21 where it installs by default).
        val packageJdk = (findProperty("wyrmPackageJdk") as String?)
            ?: file("C:/Program Files/Eclipse Adoptium").listFiles()?.filter { it.name.startsWith("jdk-21") && File(it, "bin/jpackage.exe").isFile }?.maxByOrNull { it.name }?.path
        if (packageJdk != null) javaHome = packageJdk
        // Only what holds on every computer: the paths come from the app's own resources (Main.kt).
        jvmArgs += listOf(
            "-Dskiko.renderApi=DIRECT3D", // OpenGL beside the engine's Vulkan crashed the Intel driver (2026-10-03)
            "-Xss4m",
        )
        nativeDistributions {
            // Wyrm ships its own setup (WyrmSetup.exe, D8), built from this app image
            // (`createDistributable`); no MSI.
            targetFormats(TargetFormat.Msi)
            packageName = "Wyrm"
            packageVersion = wyrmVersion
            description = "Wyrm for Windows"
            vendor = "OM Rajput"
            copyright = "© 2026 OM Rajput"
            appResourcesRootDir.set(wyrmPayload)
            modules("java.instrument", "java.management", "java.naming", "java.net.http", "java.sql", "jdk.unsupported", "jdk.crypto.ec", "jdk.accessibility")
            windows {
                iconFile.set(project.file("packaging/wyrm.ico"))
                menuGroup = "Wyrm"
                shortcut = true
                menu = true
                upgradeUuid = "9a6f1c2e-7b4d-4f3a-9c1e-5d2b8a7f6e31"
            }
        }
    }
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(prepareWyrmPayload) }

// The default skins as pictures, drawn at build time from the real atlases
// and shipped inside the app (OM: loaded at start, never made on the
// player's computer). The generator lives in its own source set (src/tools)
// so it sees the compiled app without depending on the app's resources.
val tools: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets["main"].output.classesDirs + configurations["runtimeClasspath"]
    runtimeClasspath += output + compileClasspath
}
val presetPictures = layout.buildDirectory.dir("generated/preset-pictures")
val generatePresetPictures by tasks.registering(JavaExec::class) {
    classpath = tools.runtimeClasspath
    mainClass.set("com.wyrm.desktop.PresetPicturesKt")
    jvmArgs("-Dwyrm.android.root=$wyrmAndroidRoot")
    args(presetPictures.get().asFile.absolutePath)
    inputs.file("$wyrmAndroidRoot/app/res/textures/tex_atlas_8k.png")
    inputs.files(sourceSets["main"].output.classesDirs)
    outputs.dir(presetPictures)
}
sourceSets["main"].resources.srcDir(files(presetPictures).builtBy(generatePresetPictures))

// PRIVACY.md ships inside the app, as on Android (assets/privacy.md).
tasks.named<ProcessResources>("processResources") {
    from(file("../../Wyrm Android/PRIVACY.md")) {
        into("assets")
        rename { "privacy.md" }
    }
    // About Wyrm's UPI QR, the same picture the phones show.
    from(file("../../Wyrm Android/android/app/src/main/res/drawable-nodpi/about_upi_qr.png")) {
        into("assets")
    }
    // Wyrm's app icon (the iOS 1024 px W on a rounded paper tile, packaging/):
    // the window and taskbar, the tray, Windows toasts and the title bar.
    from(file("packaging/wyrm_icon.png")) {
        into("assets")
    }
    // The update manifest's public key: the same key Wyrm Android checks its manifests with (D8).
    from(file("../../Wyrm Android/android/app/src/main/res/raw/wyrm_update_manifest_public.pem")) {
        into("assets")
    }
}

// An offscreen render of a page to a PNG (src/test/.../PageShot.kt), for
// checking a layout without the window: gradlew pageShot -Pshot=<file.png>
tasks.register<JavaExec>("pageShot") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.wyrm.desktop.PageShotKt")
    jvmArgs("-Dwyrm.android.root=$wyrmAndroidRoot")
    // -Pheight needs -Psection (the arguments are positional).
    args(listOfNotNull((findProperty("shot") as String?) ?: "page-shot.png", findProperty("section") as String?, findProperty("height") as String?))
}

// The desktop stand-ins for Android's Bitmap / Canvas / LruCache (D5 Social): gradlew shimProbe
tasks.register<JavaExec>("shimProbe") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.wyrm.desktop.ShimProbeKt")
    jvmArgs("-Dwyrm.android.root=$wyrmAndroidRoot")
}

// The keyboard path, checked with no window and no real input (KeyProbe.kt):
// the engine on its hidden SDL window, the AI arena, keys pressed as the window
// passes them. Its own APPDATA, so the player's settings are never touched.
tasks.register<JavaExec>("keyProbe") {
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.wyrm.desktop.KeyProbeKt")
    // The engine moves the working directory to Wyrm Android's root, so the
    // probe writes its files by an absolute path.
    jvmArgs("-Dwyrm.android.root=$wyrmAndroidRoot", "-Dwyrm.native.bin=$nativeBin",
        "-Dwyrm.probe.out=${layout.buildDirectory.get().asFile.absolutePath}")
    val appData = layout.buildDirectory.dir("probe-appdata").get().asFile
    doFirst { appData.mkdirs() }
    environment("APPDATA", appData.absolutePath)
}
