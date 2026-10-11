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
    public static native void nativeDesktopPointer(int action, float x, float y, int button, float wheel);
    public static native void nativeDesktopKey(int scancode, boolean down);
    public static native void nativeDesktopQuit();
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

    // ---- Team mode, NTL (android_team.c), same as on Android ----
    public static native String nativeTeamPresence();
    public static native void nativeSetTeamMembers(String packed);
    public static native void nativeSetTeamChat(String packed);
    public static native void nativeSetTeamHudStyle(float[] values);
    public static native void nativeCloseTeamChat(float seconds);
    /** Desktop only: where the roster and chat window were drawn (x, y, w, h each). */
    public static native float[] nativeDesktopTeamRects();

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
