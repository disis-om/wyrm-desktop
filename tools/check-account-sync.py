"""Wyrm Desktop: every preference file the app uses must be listed in
DesktopAccountSync.FILES (SYNC, WIPE or KEEP), or a player's setting would
not follow the account and a log out would leave it behind.

Scans the desktop's own sources and the Android files it compiles
(build/shared-android, filled by Gradle's syncAndroidSources). Run before a
build:  python "Wyrm Desktop/tools/check-account-sync.py"
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SHELL = os.path.join(ROOT, "shell")
SOURCES = [os.path.join(SHELL, "src", "main"), os.path.join(SHELL, "build", "shared-android")]
REGISTRY = os.path.join(SHELL, "src", "main", "kotlin", "com", "wyrm", "desktop", "DesktopAccountSync.kt")

NAME = r'"((?:wyrm|vlither)_[a-z0-9_]+)"'
LITERAL = re.compile(r'getSharedPreferences\(\s*' + NAME)
CONSTANT = re.compile(r'(?:const\s+val|val)\s+(PREFS[A-Z_]*|[A-Z_]*PREFS)\s*=\s*' + NAME)
USES_CONSTANT = re.compile(r'getSharedPreferences\(\s*([A-Z_]+)\s*,')


def used_names():
    found = {}
    for base in SOURCES:
        for folder, _, files in os.walk(base):
            for file in files:
                if not file.endswith((".kt", ".java")):
                    continue
                path = os.path.join(folder, file)
                text = open(path, encoding="utf-8", errors="replace").read()
                if path == REGISTRY:
                    continue
                for name in LITERAL.findall(text):
                    found.setdefault(name, path)
                constants = dict(CONSTANT.findall(text))
                for constant in USES_CONSTANT.findall(text):
                    if constant in constants:
                        found.setdefault(constants[constant], path)
    return found


def registered():
    text = open(REGISTRY, encoding="utf-8").read()
    return set(re.findall(r'PrefFile\(\s*' + NAME, text))


def main():
    if not os.path.isdir(SOURCES[1]):
        print("build/shared-android is missing: run gradlew compileKotlin once first.")
        return 2
    used = used_names()
    listed = registered()
    missing = sorted(set(used) - listed)
    for name in missing:
        print(f"NOT IN DesktopAccountSync.FILES: {name}  ({os.path.relpath(used[name], ROOT)})")
    print(f"{len(used)} preference files used, {len(listed)} listed, {len(missing)} missing.")
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main())
