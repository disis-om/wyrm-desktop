package android.content.res;

/** Wyrm Desktop: the window's size in dp, as Android's Configuration reports the screen. */
public final class Configuration {
    public int screenWidthDp;
    public int screenHeightDp;

    public Configuration(int screenWidthDp, int screenHeightDp) {
        this.screenWidthDp = screenWidthDp;
        this.screenHeightDp = screenHeightDp;
    }
}
