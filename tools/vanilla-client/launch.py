#!/usr/bin/env python3
"""Download and start a stock Minecraft Java client that joins a server.

Used to check that server-side content shows up on a client with no mods.
It fetches everything from Mojang, logs in offline (the server needs
online-mode=false) and pre-accepts the server resource pack.

Sounds and non-English translations are skipped to keep the download small.

    python3 tools/vanilla-client/launch.py --version 26.3 --server localhost:25565

On a machine without a display, run it inside Xvfb. The client asks for an
sRGB framebuffer and Xvfb's GLX visuals have none, so make SDL use EGL:

    Xvfb :99 -screen 0 1280x720x24 &
    DISPLAY=:99 SDL_VIDEO_FORCE_EGL=1 python3 tools/vanilla-client/launch.py
"""
import argparse
import hashlib
import json
import os
import platform
import struct
import subprocess
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
ASSET_HOST = "https://resources.download.minecraft.net"
SKIPPED_ASSETS = ("minecraft/sounds/", "realms/")
KEPT_ASSETS = ("minecraft/lang/en_us.json",)


def fetch(url, dest=None, sha1=None):
    if dest is not None:
        dest = Path(dest)
        if dest.exists() and (sha1 is None or hashlib.sha1(dest.read_bytes()).hexdigest() == sha1):
            return dest
    with urllib.request.urlopen(url) as response:
        data = response.read()
    if sha1 is not None and hashlib.sha1(data).hexdigest() != sha1:
        raise RuntimeError(f"sha1 mismatch for {url}")
    if dest is None:
        return data
    dest.parent.mkdir(parents=True, exist_ok=True)
    dest.write_bytes(data)
    return dest


def rules_allow(rules):
    if not rules:
        return True
    allowed = False
    for rule in rules:
        os_rule = rule.get("os", {})
        if "features" in rule:
            continue
        if os_rule.get("name") not in (None, "linux"):
            continue
        if os_rule.get("arch") not in (None, platform.machine()):
            continue
        allowed = rule["action"] == "allow"
    return allowed


def nbt_string(value):
    raw = value.encode("utf-8")
    return struct.pack(">H", len(raw)) + raw


def write_servers_dat(path, address):
    """servers.dat with one entry whose resource pack setting is 'Enabled'."""
    entry = (
        b"\x08" + nbt_string("name") + nbt_string("Test server")
        + b"\x08" + nbt_string("ip") + nbt_string(address)
        + b"\x01" + nbt_string("acceptTextures") + b"\x01"
        + b"\x00"
    )
    servers = b"\x09" + nbt_string("servers") + b"\x0a" + struct.pack(">i", 1) + entry
    path.write_bytes(b"\x0a" + nbt_string("") + servers + b"\x00")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", default="26.3")
    parser.add_argument("--server", default="localhost:25565")
    parser.add_argument("--username", default="Tester")
    parser.add_argument("--dir", default=str(Path(__file__).resolve().parent / "run"))
    parser.add_argument("--java", default="java")
    args = parser.parse_args()

    root = Path(args.dir)
    game_dir = root / "game"
    game_dir.mkdir(parents=True, exist_ok=True)

    manifest = json.loads(fetch(MANIFEST))
    entry = next(v for v in manifest["versions"] if v["id"] == args.version)
    meta = json.loads(fetch(entry["url"], root / "versions" / f"{args.version}.json", entry["sha1"]).read_bytes())

    client = meta["downloads"]["client"]
    classpath = []
    jobs = []
    for lib in meta["libraries"]:
        if not rules_allow(lib.get("rules")):
            continue
        artifact = lib.get("downloads", {}).get("artifact")
        if artifact:
            dest = root / "libraries" / artifact["path"]
            classpath.append(dest)
            jobs.append((artifact["url"], dest, artifact["sha1"]))
    client_jar = root / "versions" / f"{args.version}.jar"
    classpath.append(client_jar)
    jobs.append((client["url"], client_jar, client["sha1"]))

    index_info = meta["assetIndex"]
    index_path = root / "assets" / "indexes" / f"{index_info['id']}.json"
    index = json.loads(fetch(index_info["url"], index_path, index_info["sha1"]).read_bytes())
    for name, obj in index["objects"].items():
        if name.startswith(SKIPPED_ASSETS) or (name.startswith("minecraft/lang/") and name not in KEPT_ASSETS):
            continue
        h = obj["hash"]
        jobs.append((f"{ASSET_HOST}/{h[:2]}/{h}", root / "assets" / "objects" / h[:2] / h, h))

    print(f"Fetching {len(jobs)} files", flush=True)
    with ThreadPoolExecutor(8) as pool:
        list(pool.map(lambda job: fetch(*job), jobs))

    write_servers_dat(game_dir / "servers.dat", args.server)
    (game_dir / "options.txt").write_text(
        "onboardAccessibility:false\n"
        "skipMultiplayerWarning:true\n"
        "joinedFirstServer:true\n"
        "tutorialStep:none\n"
        "renderDistance:6\n"
        "pauseOnLostFocus:false\n"
        "showSubtitles:true\n"
    )

    command = [
        args.java, "-Xmx2G",
        "-cp", os.pathsep.join(str(p) for p in classpath),
        meta["mainClass"],
        "--username", args.username,
        "--version", args.version,
        "--gameDir", str(game_dir),
        "--assetsDir", str(root / "assets"),
        "--assetIndex", index_info["id"],
        "--uuid", hashlib.md5(f"OfflinePlayer:{args.username}".encode()).hexdigest(),
        "--accessToken", "0",
        "--userType", "legacy",
        "--versionType", "release",
        "--width", "1280", "--height", "720",
        "--quickPlayMultiplayer", args.server,
    ]
    print("Starting client", flush=True)
    sys.exit(subprocess.call(command))


if __name__ == "__main__":
    main()
