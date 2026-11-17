#!/usr/bin/env python3
"""
Patch a Lycanites Mobs 2.0.8.10 jar so that it reports a fixed Minecraft UUID
to the author's online familiars service.

Only the Python 3 standard library is used. No internet, no Java needed.

Usage:
    python3 tools/patch_jar.py                 (interactive)
    python3 tools/patch_jar.py --yes           (use the default UUID, no questions)
    python3 tools/patch_jar.py --uuid <uuid> --yes
"""

import json
import os
import re
import shutil
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
IN_DIR = os.path.join(ROOT, "put-jar-here")
OUT_DIR = os.path.join(ROOT, "patched")
CLASS_DIR = os.path.join(ROOT, "classes")
GAME_CONFIG_NAME = "lycanitesmobs-familiar-uuid.txt"

MOD_ID = "lycanitesmobs"
MOD_VERSION = "2.0.8.10"
DEFAULT_UUID = "a0aed996-9645-44e2-9468-5679fc25f44a"

PKG = "com/lycanitesmobs/core/pets/"
REPLACE = [
    PKG + "PlayerFamiliars.class",
    PKG + "PlayerFamiliars$FamiliarLoader.class",
    PKG + "PlayerFamiliars$TrustAllCertificates.class",
]
DROP = [PKG + "PlayerFamiliars$1.class"]
PATCH_MARKER = PKG + "PlayerFamiliars$TrustAllCertificates.class"
REQUIRED = PKG + "PlayerFamiliars.class"
UUID_RE = re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                     r"[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")


def fail(message):
    print("")
    print("ERROR: " + message)
    print("")
    sys.exit(1)


def read_first_setting(path):
    """First non-empty line that is not a comment, or None."""
    try:
        with open(path, "r") as handle:
            for line in handle:
                line = line.strip()
                if line and not line.startswith("#"):
                    return line
    except IOError:
        pass
    return None


def find_jar():
    if not os.path.isdir(IN_DIR):
        fail("The folder 'put-jar-here' is missing. Re-download the patcher.")
    jars = sorted(name for name in os.listdir(IN_DIR)
                  if name.lower().endswith(".jar")
                  and os.path.isfile(os.path.join(IN_DIR, name)))
    if not jars:
        print("")
        print("No jar found in: " + IN_DIR)
        print("")
        print("Put your Lycanites Mobs jar (the file ending in .jar) into that folder")
        print("and run this again.")
        sys.exit(1)
    if len(jars) > 1:
        print("")
        print("More than one jar is in the 'put-jar-here' folder:")
        for name in jars:
            print("   " + name)
        print("")
        print("Keep only the Lycanites Mobs jar in there and run this again.")
        sys.exit(1)
    return os.path.join(IN_DIR, jars[0])


def inspect_jar(path):
    """Validates the jar and returns its entry names."""
    try:
        with zipfile.ZipFile(path) as jar:
            names = set(jar.namelist())
            if REQUIRED not in names:
                fail("This does not look like a Lycanites Mobs jar (the mod's classes are missing).")
            info_text = None
            if "mcmod.info" in names:
                info_text = jar.read("mcmod.info").decode("utf-8", "replace")
    except zipfile.BadZipFile:
        fail("That file is not a valid jar: " + os.path.basename(path))

    version = None
    if info_text:
        try:
            data = json.loads(info_text)
            if isinstance(data, list):
                data = data[0] if data else {}
            for entry in (data if isinstance(data, list) else [data]):
                if isinstance(entry, dict) and entry.get("modid") == MOD_ID:
                    version = entry.get("version")
                    break
            if version is None and isinstance(data, dict):
                version = data.get("version")
        except ValueError:
            version = None

    if version and MOD_VERSION not in str(version):
        fail("This patcher is built for Lycanites Mobs " + MOD_VERSION + ", but your jar says: "
             + str(version) + "\n"
             "Patching a different version can crash the game, so it was stopped.\n"
             "You need the " + MOD_VERSION + " jar for Minecraft 1.12.2.")
    return names


def default_uuid():
    value = read_first_setting(os.path.join(ROOT, "UUID.txt"))
    if value and UUID_RE.match(value):
        return value.lower()
    return DEFAULT_UUID


def ask_uuid(default, assume_yes):
    if assume_yes or not sys.stdin.isatty():
        return default
    print("")
    print("Which Minecraft UUID should the mod report?")
    print("(Press Enter to use: " + default + ")")
    for _ in range(3):
        try:
            answer = input("UUID: ").strip()
        except (EOFError, KeyboardInterrupt):
            print("")
            sys.exit(1)
        if not answer:
            return default
        if UUID_RE.match(answer):
            return answer.lower()
        print("That is not a valid UUID. It looks like: 8-4-4-4-12 groups of hex,")
        print("for example " + DEFAULT_UUID)
    fail("No valid UUID entered.")


def patch_jar(source, destination, names):
    already = PATCH_MARKER in names
    if already:
        shutil.copyfile(source, destination)
        return False

    with zipfile.ZipFile(source) as src, \
            zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as dst:
        written = set()
        for item in src.infolist():
            if item.filename in DROP:
                continue
            if item.filename in REPLACE:
                local = os.path.join(CLASS_DIR, *item.filename.split("/"))
                with open(local, "rb") as handle:
                    data = handle.read()
                written.add(item.filename)
            else:
                data = src.read(item.filename)
            dst.writestr(item, data)

        # The patched build adds a class the original jar does not contain.
        for name in REPLACE:
            if name in written:
                continue
            local = os.path.join(CLASS_DIR, *name.split("/"))
            with open(local, "rb") as handle:
                dst.writestr(name, handle.read())
    return True


def write_config(path, uuid):
    with open(path, "w") as handle:
        handle.write("# Minecraft UUID reported to the Lycanites familiars service.\n")
        handle.write("# This file must sit next to the patched mod jar in your mods folder.\n")
        handle.write("# Delete it to fall back to the built-in default.\n")
        handle.write(uuid + "\n")


def verify(path):
    with zipfile.ZipFile(path) as jar:
        names = set(jar.namelist())
        for required in REPLACE:
            if required not in names:
                return "missing " + required
        for dropped in DROP:
            if dropped in names:
                return "stale " + dropped + " still present"
        data = jar.read(REQUIRED)
        if b"func_110124_au" not in data:
            return "patched class looks wrong"
        return None


def main():
    assume_yes = "--yes" in sys.argv
    uuid = None
    if "--uuid" in sys.argv:
        index = sys.argv.index("--uuid")
        if index + 1 >= len(sys.argv):
            fail("--uuid needs a value")
        uuid = sys.argv[index + 1].strip()
        if not UUID_RE.match(uuid):
            fail("Not a valid UUID: " + uuid)
        uuid = uuid.lower()

    source = find_jar()
    names = inspect_jar(source)
    if uuid is None:
        uuid = ask_uuid(default_uuid(), assume_yes)

    os.makedirs(OUT_DIR, exist_ok=True)
    destination = os.path.join(OUT_DIR, os.path.basename(source))
    config = os.path.join(OUT_DIR, GAME_CONFIG_NAME)

    print("")
    print("Patching " + os.path.basename(source) + " ...")
    try:
        changed = patch_jar(source, destination, names)
        write_config(config, uuid)
        problem = verify(destination)
        if problem:
            raise RuntimeError(problem)
    except Exception as error:
        # Never leave a half-written jar behind: it must not reach the mods folder.
        for path in (destination, config):
            try:
                os.remove(path)
            except OSError:
                pass
        fail("Patching failed: %s Nothing was left in the '%s' folder."
             % (error, os.path.basename(OUT_DIR)))

    print("")
    if changed:
        print("Done. Patched jar written to:")
    else:
        print("That jar was already patched. A copy was written to:")
    print("   " + destination)
    print("")
    print("It will report this UUID:")
    print("   " + uuid)
    print("")
    print("NEXT STEPS")
    print("  1. Open your Minecraft 'mods' folder (the one that already contains")
    print("     your Lycanites Mobs jar).")
    print("  2. DELETE or move away the old lycanitesmobs jar. Do not keep both;")
    print("     two copies of the mod will crash the game.")
    print("  3. Copy these two files from the 'patched' folder into 'mods':")
    print("        " + os.path.basename(source))
    print("        " + GAME_CONFIG_NAME)
    print("  4. Start Minecraft 1.12.2 with Forge and log in.")
    print("")
    print("Check progress in the log file 'logs/latest.log'. You should see a line")
    print("containing: Reporting UUID " + uuid)
    print("")


if __name__ == "__main__":
    main()
