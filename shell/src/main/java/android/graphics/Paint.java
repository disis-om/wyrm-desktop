package android.graphics;

/** Wyrm Desktop: android.graphics.Paint's flags, colour and shader, as the desktop Canvas reads them. */
public class Paint {
    public enum Style { FILL, STROKE, FILL_AND_STROKE }
    private Style style = Style.FILL;
    private float strokeWidth = 1f;
    public Style getStyle() { return style; }
    public void setStyle(Style value) { style = value; }
    public float getStrokeWidth() { return strokeWidth; }
    public void setStrokeWidth(float value) { strokeWidth = value; }
    public static final int ANTI_ALIAS_FLAG = 1;
    public static final int FILTER_BITMAP_FLAG = 2;

    private int flags;
    private int color = 0xFF000000;
    private Shader shader;

    public Paint() { this(0); }
    public Paint(int flags) { this.flags = flags; }

    public int getFlags() { return flags; }

    public boolean isAntiAlias() { return (flags & ANTI_ALIAS_FLAG) != 0; }
    public void setAntiAlias(boolean on) { flags = on ? flags | ANTI_ALIAS_FLAG : flags & ~ANTI_ALIAS_FLAG; }

    public int getColor() { return color; }
    public void setColor(int color) { this.color = color; }
    public int getAlpha() { return color >>> 24; }
    public void setAlpha(int alpha) {
        color = (Math.max(0, Math.min(255, alpha)) << 24) | (color & 0x00ffffff);
    }

    public Shader getShader() { return shader; }
    public Shader setShader(Shader shader) { this.shader = shader; return shader; }
}
