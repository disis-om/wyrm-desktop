package android.graphics;

/** Wyrm Desktop: android.graphics.Color's packed-int helpers, same maths. */
public final class Color {
    public static final int BLACK = 0xFF000000;
    public static final int WHITE = 0xFFFFFFFF;
    public static final int TRANSPARENT = 0;

    private Color() {}

    public static int alpha(int color) { return color >>> 24; }
    public static int red(int color) { return (color >> 16) & 0xFF; }
    public static int green(int color) { return (color >> 8) & 0xFF; }
    public static int blue(int color) { return color & 0xFF; }

    public static int rgb(int red, int green, int blue) {
        return 0xFF000000 | (red << 16) | (green << 8) | blue;
    }

    public static int argb(int alpha, int red, int green, int blue) {
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    public static void colorToHSV(int color, float[] hsv) {
        RGBToHSV(red(color), green(color), blue(color), hsv);
    }

    public static void RGBToHSV(int red, int green, int blue, float[] hsv) {
        float r = red / 255f, g = green / 255f, b = blue / 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float delta = max - min;
        float h;
        if (delta == 0f) h = 0f;
        else if (max == r) h = 60f * (((g - b) / delta) % 6f);
        else if (max == g) h = 60f * (((b - r) / delta) + 2f);
        else h = 60f * (((r - g) / delta) + 4f);
        if (h < 0f) h += 360f;
        hsv[0] = h;
        hsv[1] = max == 0f ? 0f : delta / max;
        hsv[2] = max;
    }

    public static int HSVToColor(float[] hsv) {
        return HSVToColor(0xFF, hsv);
    }

    public static int HSVToColor(int alpha, float[] hsv) {
        float h = ((hsv[0] % 360f) + 360f) % 360f;
        float s = Math.max(0f, Math.min(1f, hsv[1]));
        float v = Math.max(0f, Math.min(1f, hsv[2]));
        float c = v * s;
        float x = c * (1f - Math.abs((h / 60f) % 2f - 1f));
        float m = v - c;
        float r, g, b;
        if (h < 60f) { r = c; g = x; b = 0f; }
        else if (h < 120f) { r = x; g = c; b = 0f; }
        else if (h < 180f) { r = 0f; g = c; b = x; }
        else if (h < 240f) { r = 0f; g = x; b = c; }
        else if (h < 300f) { r = x; g = 0f; b = c; }
        else { r = c; g = 0f; b = x; }
        return argb(alpha, Math.round((r + m) * 255f), Math.round((g + m) * 255f), Math.round((b + m) * 255f));
    }
}
