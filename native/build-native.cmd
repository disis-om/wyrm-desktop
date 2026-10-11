@echo off
rem Builds wyrm.dll + SDL3.dll for Wyrm Desktop (Release, x64).
rem Toolchain: MSVC Build Tools (headers, libs, vcvars) + the Android NDK's
rem clang-cl (the engine is GNU-flavoured C) + lld-link (NDK ld.lld under the
rem link name, copied into build\toolchain once) + CMake + Ninja.
setlocal
set "HERE=%~dp0"
set "ROOT=%HERE%.."
set "OUT=%ROOT%\build\native"
set "TOOLS=%ROOT%\build\toolchain"
set "NDKBIN=%LOCALAPPDATA%\Android\Sdk\ndk\28.2.13676358\toolchains\llvm\prebuilt\windows-x86_64\bin"
set "VCVARS=C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat"
if not defined JAVA_HOME set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot"
if not defined VULKAN_SDK set "VULKAN_SDK=C:\VulkanSDK\1.4.350.0"

if not exist "%TOOLS%\lld-link.exe" (
  mkdir "%TOOLS%" 2>nul
  copy /y "%NDKBIN%\ld.lld.exe" "%TOOLS%\lld-link.exe" >nul
  copy /y "%NDKBIN%\libwinpthread-1.dll" "%TOOLS%\" >nul
  copy /y "%NDKBIN%\libxml2.dll" "%TOOLS%\" >nul
)

call "%VCVARS%" >nul || exit /b 1
set "PATH=%TOOLS%;%NDKBIN%;%PATH%"
set "NDKF=%NDKBIN:\=/%"
set "TOOLSF=%TOOLS:\=/%"
set "HEREF=%HERE:\=/%"
set "OUTF=%OUT:\=/%"

cmake -S "%HEREF%." -B "%OUTF%" -G Ninja ^
  -DCMAKE_BUILD_TYPE=Release ^
  -DCMAKE_C_COMPILER="%NDKF%/clang-cl.exe" ^
  -DCMAKE_CXX_COMPILER="%NDKF%/clang-cl.exe" ^
  -DCMAKE_LINKER="%TOOLSF%/lld-link.exe" ^
  -DCMAKE_RC_COMPILER="%NDKF%/llvm-rc.exe" ^
  -DCMAKE_AR="%NDKF%/llvm-lib.exe" ^
  -DCMAKE_MT="mt.exe" || exit /b 1

cmake --build "%OUT%" --target wyrm wyrmwin WyrmSetup -- -k 0 || exit /b 1

mkdir "%ROOT%\build\bin" 2>nul
copy /y "%OUT%\wyrm.dll" "%ROOT%\build\bin\" >nul
copy /y "%OUT%\wyrmwin.dll" "%ROOT%\build\bin\" >nul
copy /y "%OUT%\WyrmSetup.exe" "%ROOT%\build\bin\" >nul
copy /y "%OUT%\SDL\SDL3.dll" "%ROOT%\build\bin\" >nul 2>nul
for /r "%OUT%" %%f in (SDL3.dll) do copy /y "%%f" "%ROOT%\build\bin\" >nul
echo native ok
