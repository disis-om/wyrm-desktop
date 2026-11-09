package android.graphics;

public final class LinearGradient extends Shader {
    private final java.awt.LinearGradientPaint paint;
    public LinearGradient(float x0, float y0, float x1, float y1,
                          int[] colors, float[] stops, TileMode tileMode) {
        java.awt.Color[] awt = new java.awt.Color[colors.length];
        for (int i=0;i<colors.length;i++) awt[i]=new java.awt.Color(colors[i],true);
        float[] fractions=stops;
        if (fractions==null) {
            fractions=new float[colors.length];
            for (int i=0;i<colors.length;i++) fractions[i]=(float)i/(colors.length-1);
        }
        java.awt.MultipleGradientPaint.CycleMethod cycle = tileMode==TileMode.REPEAT
            ? java.awt.MultipleGradientPaint.CycleMethod.REPEAT : tileMode==TileMode.MIRROR
            ? java.awt.MultipleGradientPaint.CycleMethod.REFLECT
            : java.awt.MultipleGradientPaint.CycleMethod.NO_CYCLE;
        paint=new java.awt.LinearGradientPaint(x0,y0,x1,y1,fractions,awt,cycle);
    }
    @Override java.awt.Paint awtPaint() { return paint; }
}
