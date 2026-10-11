package android.graphics;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/** Wyrm Desktop: android.graphics.BitmapFactory's decode, with bounds and inSampleSize. */
public final class BitmapFactory {
    private BitmapFactory() {}

    public static class Options {
        public boolean inJustDecodeBounds;
        public int inSampleSize = 1;
        public int outWidth = -1;
        public int outHeight = -1;
    }

    public static Bitmap decodeByteArray(byte[] data, int offset, int length) {
        return decodeByteArray(data, offset, length, null);
    }

    public static Bitmap decodeByteArray(byte[] data, int offset, int length, Options options) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data, offset, length))) {
            if (in == null) return null;
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                if (options != null) {
                    options.outWidth = reader.getWidth(0);
                    options.outHeight = reader.getHeight(0);
                    if (options.inJustDecodeBounds) return null;
                }
                ImageReadParam param = reader.getDefaultReadParam();
                int sample = options == null ? 1 : Math.max(1, options.inSampleSize);
                if (sample > 1) param.setSourceSubsampling(sample, sample, 0, 0);
                BufferedImage image = reader.read(0, param);
                return image == null ? null : new Bitmap(image);
            } finally {
                reader.dispose();
            }
        } catch (Exception e) {
            return null;
        }
    }

    public static Bitmap decodeFile(String path) {
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path));
            return decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception e) {
            return null;
        }
    }
}
