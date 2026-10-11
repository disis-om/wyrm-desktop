package android.os;

/** Wyrm Desktop: Android's monotonic clocks, from the JVM's own. */
public final class SystemClock {
    private SystemClock() {}

    private static final long START = System.nanoTime();

    public static long elapsedRealtime() {
        return (System.nanoTime() - START) / 1_000_000L;
    }

    public static long uptimeMillis() {
        return elapsedRealtime();
    }
}
