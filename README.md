# Wyrm for Windows

Wyrm Desktop: the same C engine as Wyrm Android and Wyrm iOS (Vulkan) under a
Compose Desktop shell that reuses Wyrm Android's Kotlin UI, laid out for a
window, mouse and keyboard.

## Download

Get `WyrmSetup-<version>.exe` from the Releases page and run it. Wyrm installs
for your user only (`%LOCALAPPDATA%\Programs\Wyrm`, no administrator rights),
adds Start menu and desktop shortcuts and an entry in Settings › Apps for
uninstalling. It updates itself from inside the app (Settings › Version ›
Updates; Beta updates is on by default).

The setup is not code-signed yet, so Windows SmartScreen may warn the first
time: choose "More info" → "Run anyway".

## Build from source

Wyrm Desktop builds the engine and the shared UI straight from Wyrm Android's
tree, so clone both side by side:

```
<folder>\Wyrm Android     <- github.com/disis-om/wyrm-android
<folder>\Wyrm Desktop     <- this repository
```

Needs: MSVC Build Tools 2022, the Android NDK 28.2 (its clang-cl), CMake,
Ninja, the Vulkan SDK, and a full JDK 21 (Temurin; it has `jpackage`).

```
python native\patch_engine_desktop.py   :: once: adds the WYRM_DESKTOP blocks
native\build-native.cmd                 :: wyrm.dll, SDL3.dll, wyrmwin.dll, WyrmSetup.exe -> build\bin
cd shell
gradlew run                             :: run from source
gradlew createDistributable -PwyrmVersion=1.0.0 -PwyrmVersionCode=100
cd ..
python tools\package-wyrm-desktop.py --dry-run   :: dist\WyrmSetup-<version>.exe, unpack check
```

## Updates

`update/latest.json` (stable) and `update/beta.json` (beta), each with a
`.sig` (SHA256withECDSA). Wyrm trusts a manifest only when its signature
verifies against the public key built into the app, the setup comes from
this repository's releases, and the download matches the manifest's size and
SHA-256.

## License

GPL-3.0, see `LICENSE`. © 2026 OM Rajput.
