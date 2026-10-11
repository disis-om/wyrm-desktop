package android.graphics;

/** Wyrm Desktop: android.graphics.Paint's flags, as the desktop Canvas reads them. */
public class Paint {
    public static final int ANTI_ALIAS_FLAG = 1;
    public static final int FILTER_BITMAP_FLAG = 2;

    private final int flags;

    public Paint() { this(0); }
    public Paint(int flags) { this.flags = flags; }

    public int getFlags() { return flags; }
}
