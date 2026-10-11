package android.content;

/**
 * Wyrm Desktop: the small part of Android's Context that Wyrm Android's shared
 * files use: preferences (files in %APPDATA%\Wyrm), the cache and files
 * folders, the network service, and the one activity the shared code starts
 * (the system's notification settings).
 */
public abstract class Context {
    public static final int MODE_PRIVATE = 0;
    public static final String CONNECTIVITY_SERVICE = "connectivity";

    public abstract SharedPreferences getSharedPreferences(String name, int mode);

    private final android.content.res.AssetManager assets = new android.content.res.AssetManager();
    private final android.net.ConnectivityManager connectivity = new android.net.ConnectivityManager();

    public android.content.res.AssetManager getAssets() {
        return assets;
    }

    private static java.io.File localFolder(String name) {
        String base = System.getenv("LOCALAPPDATA");
        if (base == null) base = System.getProperty("user.home");
        java.io.File dir = new java.io.File(base, "Wyrm/" + name);
        dir.mkdirs();
        return dir;
    }

    /** %LOCALAPPDATA%\Wyrm\cache: disk caches (avatars, trail photos), safe to lose. */
    public java.io.File getCacheDir() {
        return localFolder("cache");
    }

    /** %LOCALAPPDATA%\Wyrm\files: the app's own files (crash notes). */
    public java.io.File getFilesDir() {
        return localFolder("files");
    }

    public String getPackageName() {
        return "com.wyrm.omrajput";
    }

    public Object getSystemService(String name) {
        return CONNECTIVITY_SERVICE.equals(name) ? connectivity : null;
    }

    /** Only Windows' notification settings are ever asked for. */
    public void startActivity(Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) return;
        if (action.equals(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                || action.equals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)) {
            try {
                new ProcessBuilder("cmd", "/c", "start", "", "ms-settings:notifications").start();
            } catch (Exception ignored) {
            }
        }
    }

    public Context getApplicationContext() {
        return this;
    }
}
