package android.graphics;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.util.ArrayDeque;

/** Wyrm Desktop: the part of android.graphics.Canvas the shared code draws bitmaps with. */
public class Canvas {
    private final Graphics2D g;
    private final int width;
    private final int height;
    private final ArrayDeque<AffineTransform> saved = new ArrayDeque<>();

    public Canvas(Bitmap bitmap) {
        g = bitmap.image().createGraphics();
        width = bitmap.getWidth();
        height = bitmap.getHeight();
    }

    public int save() {
        saved.push(g.getTransform());
        return saved.size();
    }

    public void restore() {
        if (!saved.isEmpty()) g.setTransform(saved.pop());
    }

    public void translate(float dx, float dy) { g.translate(dx, dy); }

    public void scale(float sx, float sy) { g.scale(sx, sy); }

    public void drawColor(int color) {
        AffineTransform at = g.getTransform();
        g.setTransform(new AffineTransform());
        g.setColor(new java.awt.Color(color, true));
        g.fillRect(0, 0, width, height);
        g.setTransform(at);
    }

    public void drawBitmap(Bitmap bitmap, float left, float top, Paint paint) {
        boolean filter = paint != null && (paint.getFlags() & Paint.FILTER_BITMAP_FLAG) != 0;
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                filter ? RenderingHints.VALUE_INTERPOLATION_BICUBIC : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        AffineTransform at = g.getTransform();
        g.translate(left, top);
        g.drawImage(bitmap.image(), 0, 0, null);
        g.setTransform(at);
    }
}
