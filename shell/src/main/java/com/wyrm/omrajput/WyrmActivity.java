package com.wyrm.omrajput;

import com.wyrm.desktop.Engine;

/**
 * The engine's Java peer on the desktop.
 *
 * Wyrm Android's platform C files (android_home.c, android_skin.c, ...) reach
 * a class named com.wyrm.omrajput.WyrmActivity through JNI. On a phone that is
 * the SDL activity; here it is this class, with the same static method names
 * and signatures, so the engine runs unchanged. Natives are the engine's own
 * JNI functions; the *FromNative callbacks hand everything to {@link Engine}.
 */
public final class WyrmActivity {
    private WyrmActivity() {}

    // ---- Desktop bridge (Wyrm Desktop/native/desktop_bridge.c) ----
    public static native int nativeDesktopRun(String workDir, int width, int height);
    public static native void nativeDesktopResize(int width, int height);
    public static native long nativeDesktopCopyFrame(byte[] dst, long lastSeq);
    public static native float[] nativeDesktopLaser();
    public static native void nativeDesktopPointer(int action, float x, float y, int button, float wheel);
    public static native void nativeDesktopKey(int scancode, boolean down);
    public static native void nativeDesktopQuit();
    /** Direct presentation (2026-11-05): a displayable AWT Canvas's HWND via JAWT; 0 if none. */
    public static native long nativeDesktopCanvasHwnd(java.awt.Component canvas);
    /** Present to this HWND (0 = hidden window + readback); waits for the switch. */
    public static native boolean nativeDesktopSetTarget(long hwnd);
    /** Laser in Normal mode, pointer over the focused game, and the hint line, for a direct match. */
    public static native void nativeDesktopOverlay(boolean laserNormal, boolean hover, String hint);
    /** Windows DPAPI (CryptProtectData / CryptUnprotectData) for this Windows user; null on failure. */
    public static native byte[] nativeDesktopProtect(byte[] data);
    public static native byte[] nativeDesktopUnprotect(byte[] blob);

    // ---- The engine's own natives (android_home.c), same as on Android ----
    public static native void nativeEnterArena(String nickname, String address, long attemptId);
    public static native void nativeEnterAiMode(String nickname);
    public static native void nativeSetNickname(String nickname);
    public static native void nativeSelectArena(String address);
    public static native void nativeDeathPlay();
    public static native void nativeDeathHome();
    public static native void nativeOpenScreen(int screen);

    // ---- Skin (android_skin.c, android_look.c), same as on Android ----
    public static native void nativeSkinSetPreset(int preset);
    public static native void nativeSkinSetMode(boolean custom);
    public static native void nativeSkinSetCode(String code);
    public static native void nativeSkinSetColors(int[] colors);
    public static native void nativeSkinSync(boolean save);
    public static native void nativeSkinSetBackground(int index);
    public static native void nativeSkinSetAccessory(int accessory);
    public static native void nativeSkinCommit();
    public static native void nativeSkinSetPostcard(boolean on);
    public static native void nativeSetWyrmLook(int hair, int hairRgb, int ears, int glasses);
    public static native void nativeSetNearOriginal(boolean on, int server);

    // ---- Settings and the AI-arena editors (android_settings.c, android_home.c) ----
    public static native String nativeSettingsSnapshot();
    public static native void nativeSetSetting(String id, float a, float b, float c, float d, int count);
    public static native void nativeEnterAiLayoutEditor(String nickname);
    public static native void nativeExitAiLayoutEditor();
    public static native void nativeSetEditorBare(boolean bare);
    public static native String nativeHotkeysSnapshot();
    public static native String nativeKeyOptions();
    public static native String nativeSettingsVersion();
    public static native void nativeSettingsAction(int action);
    public static native void nativeSetHotkey(int action, int key, int mode, boolean visible, float x, float y);

    // ---- Settings pages (android_home.c, android_settings.c), same as on Android ----
    public static native void nativeSetArenaTheme(int[] colours, boolean dark);
    public static native void nativeSetJoystickLaser(boolean on, float length);
    /** Settings › Display › Look ahead (and the phones' arrow motion / zoom style), Android's JNI. */
    public static native void nativeSetPlayFeel(boolean originalArrow, boolean lookAhead, int zoomStyle);
    public static native void nativeSetFramePolicy(boolean vsync, int cap);
    /** Settings › Display › Camera › Auto zoom (game/wyrm_extras.c), Android's JNI. */
    public static native void nativeSetAutoZoom(boolean on);
    /** Settings › Developer › Diagnostics overlay (game/wyrm_diag.c), Android's JNI. */
    public static native void nativeSetDiagnostics(boolean on);
    public static native void nativePushDiagnostics(float[] values, String text);

    // ---- Team mode, NTL (android_team.c), same as on Android ----
    public static native String nativeTeamPresence();
    /** Wyrm friends on the minimap (Android, OM 2026-11-07): x/y/icon lines, tab separated; game/wyrm_extras.c. */
    private static native void nativeSetFriendMarks(String packed);
    /** Your own photo as the arena floor (Android, OM 2026-11-08); "" forgets it. game/wyrm_extras.c. */
    private static native void nativeSetCustomFloor(String path, int width, int height);
    public static void setCustomFloor(String path, int width, int height) {
        try { nativeSetCustomFloor(path, width, height); } catch (UnsatisfiedLinkError ignored) { }
    }
    public static void setFriendMarks(String packed) {
        try { nativeSetFriendMarks(packed); } catch (UnsatisfiedLinkError ignored) { }
    }
    public static native void nativeSetTeamMembers(String packed);
    public static native void nativeSetTeamChat(String packed);
    public static native void nativeSetTeamHudStyle(float[] values);
    public static native void nativeCloseTeamChat(float seconds);
    // The arena chat window's GLOBAL and ONLINE tabs (android_team.c).
    public static native void nativeSetGlobalChat(String packed);
    public static native void nativeSetOnlinePlayers(String packed);
    public static native int nativeChatTab();
    public static native void nativeSetChatTab(int tab);
    public static native void nativeToggleChatPanel();
    /** Desktop only: where the roster and chat window were drawn (x, y, w, h each). */
    public static native float[] nativeDesktopTeamRects();

    // ---- The game window (OM, 2026-11-12; native/desktop_ui.c) ----
    /** The lobby and the match on the engine's own window, at the Compose window's place, or back to Home. */
    public static native void nativeDesktopGameWindow(boolean show, long composeHwnd, boolean maximized,
                                                      int restoreX, int restoreY, int restoreW, int restoreH,
                                                      boolean fullscreen);
    /** {x, y, w, h, maximized, restore x, y, w, h, fullscreen, shown}, physical pixels. */
    public static native int[] nativeDesktopGameWindowState();
    /** 0 lobby, 1 arena picker, 2 chat box: "key=value" lines (NativeGame.kt). */
    public static native void nativeDesktopUiModel(int kind, String text);
    /** An RGBA picture for the native pages (the W, icons, flags). */
    public static native void nativeDesktopUiImage(String name, int width, int height, byte[] rgba);
    /** Wyrm's pointer (RGBA + hotspot), or none for Windows' own. */
    public static native void nativeDesktopUiCursor(byte[] rgba, int width, int height, int hotX, int hotY);
    /** The game window's taskbar icon (RGBA). */
    public static native void nativeDesktopUiIcon(byte[] rgba, int width, int height);
    /** Settings › Developer › Swapchain images: 0 = the reference client's count. */
    public static native void nativeDesktopSetSwapImages(int images);
    /** The game window's pages: what the player did (engine thread). */
    public static void desktopUiActionFromNative(int action, String text) {
        com.wyrm.desktop.NativeGame.onAction(action, text);
    }

    /** Live Wyrm skin preview (OM, 2026-10-25; game/wyrm_skin_preview.c), as Android. */
    private static native long nativeWyrmSkinPreview(int skin, float w, float h, double now, int rgb,
                                                     float[] xy, int[] argb, short[] idx);

    private static native long nativeWyrmSkinPose(int skin, float[] xs, float[] ys,
            float radius, double now, int rgb, boolean boosting, float[] xy, int[] argb, short[] idx);
    public static long wyrmSkinPose(int skin, float[] xs, float[] ys, float radius,
            double now, int rgb, boolean boosting, float[] xy, int[] argb, short[] idx) {
        try { return nativeWyrmSkinPose(skin, xs, ys, radius, now, rgb, boosting, xy, argb, idx); }
        catch (UnsatisfiedLinkError unavailable) { return 0; }
    }

    /** One frame of the live preview: vertex count in the low 16 bits, index count above; 0 without the engine. */
    public static long wyrmSkinPreview(int skin, float w, float h, double now, int rgb,
                                       float[] xy, int[] argb, short[] idx) {
        try {
            return nativeWyrmSkinPreview(skin, w, h, now, rgb, xy, argb, idx);
        } catch (UnsatisfiedLinkError e) {
            return 0;
        }
    }

    /** Custom arena id (network/callback.c), sent to every arena since 2026-10-24, as Android. */
    private static native void nativeSetCustomArenas(String id, String list);

    public static void setCustomArenas(String id, String list) {
        nativeSetCustomArenas(id, list);
    }

    // ---- Engine -> app callbacks (signatures must match the C) ----
    public static void setScreenFromNative(int screen) { Engine.onScreen(screen); }
    public static void setHomeStateFromNative(String nickname, String arena, int score, int kills) {
        Engine.onHomeState(nickname, arena, score, kills);
    }
    public static void setDeathFromNative(boolean active, int score, int kills, double seconds, boolean autoRespawn) {
        Engine.onDeath(active, score, kills, autoRespawn);
    }
    public static void setArenaRefusedFromNative(String endpoint, int seconds) { Engine.onRefused(endpoint, seconds); }
    public static void setArenaEnterIgnoredFromNative() { Engine.onEnterIgnored(); }
    public static void setArenaPortAvailableFromNative(boolean available) { Engine.onPortAvailable(available); }
    public static void recordRunFromNative(int score, int kills, double seconds) { Engine.onRun(score, kills, seconds); }
    public static void setArenaDropFromNative(String snapshot) { Engine.onDrop(snapshot); }
    public static void requestArenaSkinsFromNative(int[] snakeIds) {}
    public static void publishArenaSkinFromNative(String nickname, int snakeId, String code) {}
    public static void setSkinStateFromNative(boolean custom, int preset, String code, int accessory, int[] colors, int background) {
        Engine.onSkinState(custom, preset, code, accessory, colors, background);
    }
    public static void setSkinTablesFromNative(int[] palette, byte[] presets, byte[] names) {}
    /** The chat window's message box was clicked (or chat let go): the composer opens or closes. */
    public static void setTeamChatFromNative(boolean open) { com.wyrm.desktop.DesktopTeam.onEngineChat(open); }
    public static void setVoiceHudAnchorFromNative(float x, float y, float diameter) {}
    public static void notifyTitleScreenReadyFromNative(byte[] settings) { Engine.onTitleReady(); }
    public static void checkForUpdatesFromNative() {}
    public static void downloadAvailableUpdateFromNative(byte[] settings, byte[] team) {}
    public static void createBackupFromNative(byte[] settings, byte[] team) {}
    public static void checkBackupsFromNative() {}
    public static void restoreLatestBackupFromNative() {}
    public static void chooseBackupFolderFromNative() {}
}
