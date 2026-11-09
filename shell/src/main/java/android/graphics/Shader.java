package android.graphics;

/** Wyrm Desktop: android.graphics.Shader, as the desktop Canvas paints it. */
public class Shader {
    public enum TileMode { CLAMP, REPEAT, MIRROR }

    /** The AWT paint that fills a shape with this shader. */
    java.awt.Paint awtPaint() { return null; }
}
