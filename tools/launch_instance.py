#!/usr/bin/env python3
"""Writes run.sh: starts a 1.12.2 Forge game folder directly with Java, offline.

usage: launch_instance.py <game dir> <out dir> [extra jvm args...]

Uses the libraries, natives and Java 8 that Prism Launcher already has on this Mac, without
going through the launcher or an account. Made for the self-test:

    python3 tools/launch_instance.py <game dir> /tmp/run \
        -Dbetter112.autotest=tools/autotest/menu.txt
    /tmp/run/run.sh

Point it at a copy of a pack, not the pack you play.
"""
import json, os, shlex, subprocess, sys, zipfile
P = os.path.expanduser("~/Library/Application Support/PrismLauncher")
game, out = sys.argv[1], sys.argv[2]
extra = sys.argv[3:]
OS = "osx-arm64"

def allowed(lib):
    rules = lib.get("rules")
    if not rules:
        return True
    ok = False
    for r in rules:
        name = (r.get("os") or {}).get("name")
        if name is None or name == OS or (name == "osx" and OS.startswith("osx")):
            # an "osx" rule also matches arm64 unless a more specific rule follows
            ok = r["action"] == "allow"
    return ok

def path(name, classifier=None):
    parts = name.split(":")
    group, art, ver = parts[0], parts[1], parts[2]
    cls = classifier or (parts[3] if len(parts) > 3 else None)
    fn = f"{art}-{ver}" + (f"-{cls}" if cls else "") + ".jar"
    return os.path.join(P, "libraries", group.replace(".", "/"), art, ver, fn)

cp, natives = [], []
metas = [("org.lwjgl", "2.9.4-nightly-20150209"), ("net.minecraft", "1.12.2"), ("net.minecraftforge", "14.23.5.2860")]
main_jar = None
for uid, ver in metas:
    d = json.load(open(f"{P}/meta/{uid}/{ver}.json"))
    for lib in d.get("libraries", []):
        if not allowed(lib):
            continue
        if "natives" in lib:
            cls = lib["natives"].get(OS) or lib["natives"].get("osx")
            if cls:
                natives.append(path(lib["name"], cls))
            continue
        cp.append(path(lib["name"]))
    if "mainJar" in d:
        main_jar = path(d["mainJar"]["name"])
cp.append(main_jar)
missing = [p for p in cp + natives if not os.path.exists(p)]
if missing:
    print("MISSING:", *missing, sep="\n  ")
ndir = os.path.join(out, "natives")
os.makedirs(ndir, exist_ok=True)
for n in natives:
    with zipfile.ZipFile(n) as z:
        for e in z.namelist():
            if e.endswith("/") or e.startswith("META-INF"):
                continue
            z.extract(e, ndir)
java = os.path.join(P, "java/jre-legacy/zulu-8.jre/Contents/Home/bin/java")
cmd = [java, "-Xmx3g", "-Xms1g", f"-Djava.library.path={ndir}", "-Dorg.lwjgl.opengl.Display.allowSoftwareOpenGL=true",
       "-Dfml.ignoreInvalidMinecraftCertificates=true", "-Dfml.ignorePatchDiscrepancies=true", "-Xdock:name=Better112Test"] + extra + [
       "-cp", ":".join(p for p in cp if os.path.exists(p)), "net.minecraft.launchwrapper.Launch",
       "--username", "Tester", "--version", "1.12.2", "--gameDir", game, "--assetsDir", os.path.join(P, "assets"),
       "--assetIndex", "1.12", "--uuid", "00000000000000000000000000000000", "--accessToken", "0", "--userType", "legacy",
       "--versionType", "Forge", "--width", "1280", "--height", "720",
       "--tweakClass", "net.minecraftforge.fml.common.launcher.FMLTweaker"]
with open(os.path.join(out, "run.sh"), "w") as f:
    f.write("#!/bin/sh\ncd " + shlex.quote(game) + "\nexec " + " ".join(shlex.quote(c) for c in cmd) + "\n")
os.chmod(os.path.join(out, "run.sh"), 0o755)
print("classpath entries:", len(cp), "natives:", os.listdir(ndir))
