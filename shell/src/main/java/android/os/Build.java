package android.os;

/**
 * Wyrm Desktop: the Build.VERSION checks the shared Android files make. The
 * desktop has every feature those checks guard (blur, lens, runtime shaders).
 */
public final class Build {
    private Build() {}

    public static final class VERSION {
        public static final int SDK_INT = 36;
        /** The Windows version, for support reports. */
        public static final String RELEASE = System.getProperty("os.name", "Windows") + " " + System.getProperty("os.version", "");
    }

    public static final String MANUFACTURER = "PC";
    public static final String MODEL = System.getProperty("os.arch", "");

    public static final class VERSION_CODES {
        public static final int R = 30;
        public static final int S = 31;
        public static final int TIRAMISU = 33;
    }
}
