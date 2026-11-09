"""Execute Desktop AWT image/gradient/mesh compatibility; no GPU/window claim."""
from pathlib import Path
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JDK = Path(os.environ.get("JAVA_HOME", "C:/Program Files/Android/Android Studio/jbr"))
CODE = r'''
import android.graphics.*;
import java.io.*;
public class DesktopGraphicsTest {
  static int checks;
  static void check(boolean value) { checks++; if(!value) throw new AssertionError("check "+checks); }
  public static void main(String[] args) throws Exception {
    Bitmap image=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888);
    image.eraseColor(0xffff0000); check(image.getPixel(5,5)==0xffff0000);
    image.eraseColor(0); check(image.getPixel(5,5)==0);
    Paint paint=new Paint(); paint.setColor(0xffff0000);
    paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2);
    Canvas canvas=new Canvas(image);canvas.drawCircle(8,8,5,paint);
    check(image.getPixel(8,8)==0);check((image.getPixel(8,3)>>>24)>0);
    paint.setStyle(Paint.Style.FILL);
    paint.setShader(new LinearGradient(0,0,16,0,new int[]{0xffff0000,0xff0000ff},null,Shader.TileMode.CLAMP));
    canvas.drawRect(0,0,16,16,paint);
    check(Color.red(image.getPixel(1,8))>Color.blue(image.getPixel(1,8)));
    check(Color.blue(image.getPixel(14,8))>Color.red(image.getPixel(14,8)));
    ByteArrayOutputStream bytes=new ByteArrayOutputStream();
    check(image.compress(Bitmap.CompressFormat.PNG,100,bytes));
    Bitmap decoded=BitmapFactory.decodeStream(new ByteArrayInputStream(bytes.toByteArray()));
    check(decoded!=null && decoded.getPixel(9,8)==image.getPixel(9,8));
    check(BitmapFactory.decodeStream(new ByteArrayInputStream(new byte[]{1,2,3}))==null);
    Bitmap small=Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888);small.eraseColor(0xff00ff00);
    image.eraseColor(0);canvas.drawBitmap(small,null,new RectF(2,2,6,6),null);
    check(image.getPixel(3,3)==0xff00ff00 && image.getPixel(1,1)==0);
    image.eraseColor(0);paint.setShader(null);
    float[] xy={0,0,12,0,0,12};int[] colors={0xffff0000,0xff00ff00,0xff0000ff};
    canvas.drawVertices(Canvas.VertexMode.TRIANGLES,6,xy,0,null,0,colors,0,new short[]{0,1,2},0,3,paint);
    int pixel=image.getPixel(2,2);
    check(Color.alpha(pixel)==255 && Color.red(pixel)>0 && Color.green(pixel)>0 && Color.blue(pixel)>0);
    check(image.getPixel(14,14)==0);
    image.eraseColor(0);
    canvas.drawVertices(Canvas.VertexMode.TRIANGLES,6,xy,0,null,0,colors,0,new short[]{0,1,(short)65535},0,3,paint);
    check(image.getPixel(2,2)==0);
    canvas.save();canvas.scale(.5f,.5f);
    canvas.drawVertices(Canvas.VertexMode.TRIANGLES,6,xy,0,null,0,colors,0,new short[]{0,1,2},0,3,paint);
    canvas.restore();check(Color.alpha(image.getPixel(1,1))==255 && image.getPixel(7,7)==0);
    image.eraseColor(0);paint.setAlpha(128);
    canvas.drawVertices(Canvas.VertexMode.TRIANGLES,6,xy,0,null,0,colors,0,new short[]{0,1,2},0,3,paint);
    check(Color.alpha(image.getPixel(2,2))==128);
    System.out.println("Desktop graphics: "+checks+" pixel/validation checks PASS");
  }
}
'''
with tempfile.TemporaryDirectory(prefix="wyrm-graphics-") as temp:
    output = Path(temp)
    test = output / "DesktopGraphicsTest.java"
    test.write_text(CODE, encoding="utf-8")
    sources = list((ROOT / "shell/src/main/java/android/graphics").glob("*.java"))
    subprocess.run([str(JDK / "bin/javac.exe"), "-d", str(output),
                    *map(str, sources), str(test)], check=True)
    subprocess.run([str(JDK / "bin/java.exe"), "-Djava.awt.headless=true",
                    "-cp", str(output), "DesktopGraphicsTest"], check=True)
