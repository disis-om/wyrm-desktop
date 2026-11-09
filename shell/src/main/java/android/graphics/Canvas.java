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
    private final Bitmap target;
    private final ArrayDeque<AffineTransform> saved = new ArrayDeque<>();

    public Canvas(Bitmap bitmap) {
        target = bitmap;
        g = bitmap.image().createGraphics();
        width = bitmap.getWidth();
        height = bitmap.getHeight();
    }

    public boolean isHardwareAccelerated() { return false; }

    public void drawBitmap(Bitmap bitmap, Rect source, RectF dest, Paint paint) {
        int sx0=source==null?0:source.left, sy0=source==null?0:source.top;
        int sx1=source==null?bitmap.getWidth():source.right;
        int sy1=source==null?bitmap.getHeight():source.bottom;
        boolean filter=paint!=null && (paint.getFlags() & Paint.FILTER_BITMAP_FLAG)!=0;
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, filter
            ? RenderingHints.VALUE_INTERPOLATION_BICUBIC : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(bitmap.image(), Math.round(dest.left), Math.round(dest.top),
            Math.round(dest.right), Math.round(dest.bottom), sx0,sy0,sx1,sy1,null);
    }

    /** Untextured colored triangles used by the shared software mesh fallback.
     * Live Desktop previews use the direct Skia adapter instead. */
    public void drawVertices(VertexMode mode, int vertexCount, float[] verts,
        int vertOffset, float[] texs, int texOffset, int[] colors, int colorOffset,
        short[] indices, int indexOffset, int indexCount, Paint paint) {
        int count=vertexCount/2;
        if (count<=0 || vertOffset<0 || vertOffset+vertexCount>verts.length) return;
        int orderCount=indices==null?count:indexCount;
        if (indices!=null && (indexOffset<0 || indexOffset+indexCount>indices.length)) return;
        int step=mode==VertexMode.TRIANGLES?3:1;
        for (int i=0;i+2<orderCount;i+=step) {
            int[] order=mode==VertexMode.TRIANGLE_FAN?new int[]{0,i+1,i+2}:new int[]{i,i+1,i+2};
            double[] xy=new double[6]; int[] rgba=new int[3]; boolean valid=true;
            for (int k=0;k<3;k++) {
                int n=indices==null?order[k]:(indices[indexOffset+order[k]] & 0xffff);
                if (n>=count || (colors!=null && (colorOffset<0 || colorOffset+n>=colors.length))) { valid=false; break; }
                xy[k*2]=verts[vertOffset+n*2]; xy[k*2+1]=verts[vertOffset+n*2+1];
                if (!Double.isFinite(xy[k*2]) || !Double.isFinite(xy[k*2+1])) { valid=false; break; }
                rgba[k]=colors==null?paint.getColor():colors[colorOffset+n];
            }
            if (!valid) continue;
            g.getTransform().transform(xy,0,xy,0,3);
            double area=(xy[2]-xy[0])*(xy[5]-xy[1])-(xy[3]-xy[1])*(xy[4]-xy[0]);
            if (!Double.isFinite(area) || Math.abs(area)<1e-10) continue;
            int x0=Math.max(0,(int)Math.floor(Math.min(xy[0],Math.min(xy[2],xy[4]))));
            int y0=Math.max(0,(int)Math.floor(Math.min(xy[1],Math.min(xy[3],xy[5]))));
            int x1=Math.min(width-1,(int)Math.ceil(Math.max(xy[0],Math.max(xy[2],xy[4]))));
            int y1=Math.min(height-1,(int)Math.ceil(Math.max(xy[1],Math.max(xy[3],xy[5]))));
            for (int y=y0;y<=y1;y++) for (int x=x0;x<=x1;x++) {
                double a=((xy[2]-x-.5)*(xy[5]-y-.5)-(xy[3]-y-.5)*(xy[4]-x-.5))/area;
                double b=((xy[4]-x-.5)*(xy[1]-y-.5)-(xy[5]-y-.5)*(xy[0]-x-.5))/area;
                double c=1-a-b;
                if (a<0 || b<0 || c<0) continue;
                int src=0;
                for (int shift=0;shift<=24;shift+=8) {
                    int v=(int)Math.round(((rgba[0]>>>shift)&255)*a+((rgba[1]>>>shift)&255)*b+((rgba[2]>>>shift)&255)*c);
                    src|=Math.max(0,Math.min(255,v))<<shift;
                }
                int dst=target.image().getRGB(x,y);
                double sa=(src>>>24)/255.0*(colors==null?1:paint.getAlpha()/255.0);
                double da=(dst>>>24)/255.0, oa=sa+da*(1-sa);
                int out=(int)Math.round(oa*255)<<24;
                if (oa>0) for (int shift=0;shift<=16;shift+=8)
                    out|=(int)Math.round((((src>>>shift)&255)*sa+((dst>>>shift)&255)*da*(1-sa))/oa)<<shift;
                target.image().setRGB(x,y,out);
            }
        }
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

    /** Android's vertex modes; the shared code draws triangles (ui/WyrmSkins.kt, through Skia on desktop). */
    public enum VertexMode { TRIANGLES, TRIANGLE_STRIP, TRIANGLE_FAN }

    public void drawCircle(float cx, float cy, float radius, Paint paint) {
        fill(new java.awt.geom.Ellipse2D.Float(cx - radius, cy - radius, radius * 2f, radius * 2f), paint);
    }

    public void drawRect(float left, float top, float right, float bottom, Paint paint) {
        fill(new java.awt.geom.Rectangle2D.Float(left, top, right - left, bottom - top), paint);
    }

    private void fill(java.awt.Shape shape, Paint paint) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                paint.isAntiAlias() ? RenderingHints.VALUE_ANTIALIAS_ON : RenderingHints.VALUE_ANTIALIAS_OFF);
        java.awt.Paint shaded = paint.getShader() != null ? paint.getShader().awtPaint() : null;
        g.setPaint(shaded != null ? shaded : new java.awt.Color(paint.getColor(), true));
        if (paint.getStyle()!=Paint.Style.STROKE) g.fill(shape);
        if (paint.getStyle()!=Paint.Style.FILL) {
            g.setStroke(new java.awt.BasicStroke(Math.max(0.01f,paint.getStrokeWidth())));
            g.draw(shape);
        }
    }
}
