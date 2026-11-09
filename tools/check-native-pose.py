"""Call the rebuilt Windows pose JNI export without starting the engine/window."""
from pathlib import Path
import os
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JDK = Path(os.environ.get("JAVA_HOME", "C:/Program Files/Android/Android Studio/jbr"))
activity = (ROOT / "shell/src/main/java/com/wyrm/omrajput/WyrmActivity.java").read_text()
declaration = re.search(r"private static native long nativeWyrmSkinPose\([\s\S]*?;", activity).group()
code = "package com.wyrm.omrajput; public class WyrmActivity {\n" + declaration + r'''
  static void check(boolean value) { if(!value) throw new AssertionError(); }
  public static void main(String[] args) {
    System.load(args[0]);System.load(args[1]);
    float[] xs=new float[38],ys=new float[38],xy=new float[60000];
    int[] colors=new int[30000];short[] order=new short[90000];
    for(int i=0;i<38;i++){xs[i]=400-i*9;ys[i]=120+(float)Math.sin(i*.15)*8;}
    int cases=0;
    for(int skin:new int[]{1,3,23,101,115}) for(boolean boost:new boolean[]{false,true}) {
      long packed=nativeWyrmSkinPose(skin,xs,ys,14.5f,3.5,0x55cc88,boost,xy,colors,order);
      int vertices=(int)(packed&65535),indices=(int)(packed>>>16);
      check(vertices>0 && vertices<=colors.length && indices>0 && indices<=order.length && indices%3==0);
      for(int i=0;i<vertices*2;i++)check(Float.isFinite(xy[i]));
      for(int i=0;i<indices;i++)check((order[i]&65535)<vertices);
      cases++;
    }
    check(nativeWyrmSkinPose(1,xs,ys,-1,3.5,0xffffff,false,xy,colors,order)==0);
    xs[1]=Float.NaN;
    check(nativeWyrmSkinPose(1,xs,ys,14.5f,3.5,0xffffff,false,xy,colors,order)==0);
    System.out.println("Windows pose JNI: "+cases+" real DLL geometry cases + invalid input PASS");
  }
}
'''
with tempfile.TemporaryDirectory(prefix="wyrm-jni-") as temp:
    output = Path(temp)
    source = output / "WyrmActivity.java"
    source.write_text(code, encoding="utf-8")
    subprocess.run([str(JDK / "bin/javac.exe"), "-d", str(output), str(source)], check=True)
    subprocess.run([str(JDK / "bin/java.exe"), "-cp", str(output),
                    "com.wyrm.omrajput.WyrmActivity", str(ROOT / "build/bin/SDL3.dll"),
                    str(ROOT / "build/bin/wyrm.dll")], check=True)
