package android.graphics;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * Wyrm Desktop: the part of android.graphics.Bitmap the shared Trails and
 * photo-crop code uses, backed by an ARGB BufferedImage.
 */
public final class Bitmap {
    public enum Config { ARGB_8888 }
    public enum CompressFormat { JPEG, PNG }

    private final BufferedImage image;

    public Bitmap(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_INT_ARGB) {
            image = source;
        } else {
            BufferedImage argb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = argb.createGraphics();
            g.drawImage(source, 0, 0, null);
            g.dispose();
            image = argb;
        }
    }

    public BufferedImage image() { return image; }

    public int getWidth() { return image.getWidth(); }
    public int getHeight() { return image.getHeight(); }
    public int getPixel(int x, int y) { return image.getRGB(x, y); }
    public void prepareToDraw() {}
    public void recycle() {}

    public static Bitmap createBitmap(int width, int height, Config config) {
        return new Bitmap(new BufferedImage(Math.max(1, width), Math.max(1, height), BufferedImage.TYPE_INT_ARGB));
    }

    public static Bitmap createScaledBitmap(Bitmap src, int width, int height, boolean filter) {
        BufferedImage out = new BufferedImage(Math.max(1, width), Math.max(1, height), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        if (filter) {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        }
        g.drawImage(src.image, 0, 0, out.getWidth(), out.getHeight(), null);
        g.dispose();
        return new Bitmap(out);
    }

    /** JPEG has no alpha: flattened onto black, as the callers already do. PNG keeps it. */
    public boolean compress(CompressFormat format, int quality, OutputStream stream) {
        try {
            if (format == CompressFormat.PNG) return ImageIO.write(image, "png", stream);
            BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.setColor(java.awt.Color.BLACK);
            g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
            g.drawImage(image, 0, 0, null);
            g.dispose();
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(Math.max(0, Math.min(100, quality)) / 100f);
            try (ImageOutputStream out = ImageIO.createImageOutputStream(stream)) {
                writer.setOutput(out);
                writer.write(null, new IIOImage(rgb, null, null), param);
            } finally {
                writer.dispose();
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
