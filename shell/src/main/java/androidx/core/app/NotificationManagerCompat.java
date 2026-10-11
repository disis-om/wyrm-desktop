package androidx.core.app;

/**
 * Wyrm Desktop: "may this app notify?" On Windows that is whether the
 * system tray (where Wyrm's toasts come from) is there; Windows' own
 * Settings › Notifications can still silence Wyrm, and Java cannot read that.
 */
public final class NotificationManagerCompat {
    private NotificationManagerCompat() {}

    public static NotificationManagerCompat from(android.content.Context context) {
        return new NotificationManagerCompat();
    }

    public boolean areNotificationsEnabled() {
        try {
            return java.awt.SystemTray.isSupported();
        } catch (Throwable e) {
            return false;
        }
    }
}
