"""NDK syntax-only checks: no Android Gradle or Apple build."""
from pathlib import Path
import json
import os
import subprocess

ROOT=Path(__file__).resolve().parents[2]
NDK=Path(os.environ['LOCALAPPDATA'])/'Android/Sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64'
android=ROOT/'Wyrm Android'
ios=ROOT/'Wyrm iOS/build-original-source'
out=ROOT/'Wyrm Desktop/build'
base=[str(NDK/'bin/clang.exe'),'--target=aarch64-none-linux-android26','--sysroot='+str(NDK/'sysroot'),
      '-fsyntax-only','-std=gnu11','-DCGLM_FORCE_DEPTH_ZERO_TO_ONE','-DCIMGUI_NO_EXPORT','-DMG_ENABLE_IPV6=1',
      '-DSETTINGS_VERSION="2.8"','-Wno-unused-parameter']
errors=0
for name,tree in [('Android',android),('iOS-generated',ios)]:
    flags=base+['-D__ANDROID__=1']
    if name=='Android': flags+=['-DWYRM_SDL_JNI=1']
    else: flags+=['-DWYRM_IOS=1','-DWYRM_MOBILE=1']
    for include in [tree/'app/src',tree/'thermite/include',tree/'thermite/src',android/'android-sdl/include',android/'android/app/.cxx-version']:
        flags+=['-I'+str(include)]
    # cglm and cimgui are source-local; Android's thermite remains the dependency tree.
    for include in [android/'thermite/include',android/'thermite/src']:
        flags+=['-I'+str(include)]
    for rel in ['platform/android_team.c','game/redraw.c','game/ui_overlay.c']:
        run=subprocess.run(flags+[str(tree/'app/src'/rel)],capture_output=True,text=True)
        (out/(name.replace('/','-')+'-'+Path(rel).stem+'-syntax.log')).write_text(run.stderr,encoding='utf-8')
        print(name,rel,'PASS' if run.returncode==0 else 'FAIL',flush=True)
        if run.returncode: print(run.stderr[-5000:]);errors+=1
raise SystemExit(bool(errors))
