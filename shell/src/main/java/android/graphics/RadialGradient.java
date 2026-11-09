package android.graphics;

import java.awt.MultipleGradientPaint;
import java.awt.RadialGradientPaint;

/** Wyrm Desktop: android.graphics.RadialGradient (CLAMP), drawn with AWT's RadialGradientPaint. */
public class RadialGradient extends Shader {
    private final RadialGradientPaint paint;

    public RadialGradient(float centerX, float centerY, float radius, int[] colors, float[] stops, TileMode tileMode) {
        java.awt.Color[] awt = new java.awt.Color[colors.length];
        for (int i = 0; i < colors.length; i++) awt[i] = new java.awt.Color(colors[i], true);
        float[] fractions = stops;
        if (fractions == null) {
            fractions = new float[colors.length];
            for (int i = 0; i < colors.length; i++) fractions[i] = colors.length == 1 ? 0f : (float) i / (colors.length - 1);
        }
        MultipleGradientPaint.CycleMethod cycle = tileMode == TileMode.REPEAT ? MultipleGradientPaint.CycleMethod.REPEAT
                : tileMode == TileMode.MIRROR ? MultipleGradientPaint.CycleMethod.REFLECT
                : MultipleGradientPaint.CycleMethod.NO_CYCLE;
        paint = new RadialGradientPaint(centerX, centerY, radius, fractions, awt, cycle);
    }

    @Override
    java.awt.Paint awtPaint() { return paint; }
}
