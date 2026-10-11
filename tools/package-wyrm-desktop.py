"""Builds dist/WyrmSetup-<version>.exe from the app image (W10, D8).

WyrmSetup.exe = build/bin/WyrmSetup.exe (native/setup_stub.c)
              + a zip of the app image, everything inside a folder "Wyrm/"
              + the zip's length (8 bytes, little endian)
              + the tag b"WYRMPAY1".

Run after `native\\build-native.cmd` and `gradlew createDistributable
-PwyrmVersion=<v> -PwyrmVersionCode=<n>` (shell/). Prints the setup's path,
size and SHA-256. `--dry-run` then unpacks it with WYRM_SETUP_DRYRUN=1
(nothing is installed), checks Wyrm/Wyrm.exe and the engine files came out,
and deletes the temporary folder.

    python tools/package-wyrm-desktop.py [--dry-run]
"""

import hashlib
import os
import struct
import subprocess
import sys
import tempfile
import time
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
STUB = ROOT / "build" / "bin" / "WyrmSetup.exe"
IMAGE = ROOT / "shell" / "build" / "compose" / "binaries" / "main" / "app" / "Wyrm"
VERSION_FILE = ROOT / "shell" / "build" / "generated" / "wyrm-version" / "wyrm-version.properties"
DIST = ROOT / "dist"
TAG = b"WYRMPAY1"

# What an app image must hold, or the setup would install a Wyrm that cannot play.
REQUIRED = [
    "Wyrm.exe",
    "app/resources/bin/wyrm.dll",
    "app/resources/bin/SDL3.dll",
    "app/resources/bin/wyrmwin.dll",
    "app/resources/wyrm/app/res",
    "runtime/bin/java.dll",
]


def fail(text):
    print("package-wyrm-desktop: " + text, file=sys.stderr)
    sys.exit(1)


def version():
    found = list((ROOT / "shell" / "build").rglob("wyrm-version.properties"))
    source = VERSION_FILE if VERSION_FILE.is_file() else (found[0] if found else None)
    if source is None:
        fail("wyrm-version.properties not found; run createDistributable first")
    values = dict(line.split("=", 1) for line in source.read_text().splitlines() if "=" in line)
    return values["version"].strip(), int(values["code"].strip())


def main():
    dry = "--dry-run" in sys.argv[1:]
    if not STUB.is_file():
        fail(f"{STUB} missing; run native\\build-native.cmd")
    if not IMAGE.is_dir():
        fail(f"{IMAGE} missing; run gradlew createDistributable")
    for item in REQUIRED:
        if not (IMAGE / item).exists():
            fail(f"the app image has no {item}")
    name, code = version()

    DIST.mkdir(exist_ok=True)
    out = DIST / f"WyrmSetup-{name}.exe"
    with tempfile.TemporaryDirectory(prefix="wyrm-package-") as work:
        payload = Path(work) / "payload.zip"
        count = 0
        with zipfile.ZipFile(payload, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
            for path in sorted(IMAGE.rglob("*")):
                if path.is_file():
                    z.write(path, "Wyrm/" + path.relative_to(IMAGE).as_posix())
                    count += 1
        data = payload.read_bytes()
    stub = STUB.read_bytes()
    out.write_bytes(stub + data + struct.pack("<Q", len(data)) + TAG)

    sha = hashlib.sha256(out.read_bytes()).hexdigest()
    print(f"{out}")
    print(f"version {name} ({code}), {count} files, {out.stat().st_size} bytes")
    print(f"sha256 {sha}")

    if dry:
        dry_run(out)


def dry_run(setup):
    """Unpacks only (WYRM_SETUP_DRYRUN=1) and checks the result; installs nothing."""
    temp = Path(tempfile.gettempdir())
    before = {p.name for p in temp.glob("WyrmSetup-*") if p.is_dir()}
    env = dict(os.environ, WYRM_SETUP_DRYRUN="1")
    started = time.time()
    result = subprocess.run([str(setup)], env=env, timeout=600)
    made = [p for p in temp.glob("WyrmSetup-*") if p.is_dir() and p.name not in before]
    if result.returncode != 0 or len(made) != 1:
        fail(f"dry run: exit {result.returncode}, new folders {[p.name for p in made]}")
    folder = made[0]
    try:
        missing = [item for item in REQUIRED if not (folder / "Wyrm" / item).exists()]
        leftovers = (folder / "payload.zip").exists()
        files = sum(1 for p in (folder / "Wyrm").rglob("*") if p.is_file())
        print(f"dry run: unpacked {files} files in {time.time() - started:.1f} s to {folder}")
        if missing:
            fail(f"dry run: missing {missing}")
        if leftovers:
            fail("dry run: payload.zip was not removed")
        print("dry run: ok")
    finally:
        subprocess.run(["cmd", "/c", "rmdir", "/s", "/q", str(folder)])
        print(f"dry run: removed {folder}" if not folder.exists() else f"dry run: could not remove {folder}")


if __name__ == "__main__":
    main()
