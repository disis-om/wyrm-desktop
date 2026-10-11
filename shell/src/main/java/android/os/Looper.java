package android.os;

/** Wyrm Desktop: the main looper is the Swing/Compose event thread. */
public final class Looper {
    private static final Looper MAIN = new Looper();

    private Looper() {}

    public static Looper getMainLooper() {
        return MAIN;
    }
}
