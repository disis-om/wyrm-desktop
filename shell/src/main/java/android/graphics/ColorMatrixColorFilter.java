package android.graphics;

/** Wyrm Desktop: holds a ColorMatrix, as Android's filter does (the desktop paints with Compose's). */
public class ColorMatrixColorFilter {
    private final ColorMatrix matrix;

    public ColorMatrixColorFilter(ColorMatrix matrix) { this.matrix = new ColorMatrix(matrix.getArray()); }

    public ColorMatrix getColorMatrix() { return matrix; }
}
