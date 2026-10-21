#!/usr/bin/env python3
"""Run both real publisher plugins in debug mode with synthetic local CurseForge version metadata."""

import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import subprocess
import threading


TARGETS = {"1.7.10": ("Forge", 8), "1.12.2": ("Forge", 8),
           "1.20.1": ("Forge", 17), "1.21.1": ("NeoForge", 21)}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--minecraft", required=True, choices=TARGETS)
    parser.add_argument("--release-type", required=True, choices=("release", "alpha", "beta"))
    parser.add_argument("--notes", required=True, type=Path)
    args = parser.parse_args(argv)
    loader, java = TARGETS[args.minecraft]
    types = [
        {"id": 1, "name": "Minecraft", "slug": "minecraft"},
        {"id": 2, "name": "Java", "slug": "java"},
        {"id": 3, "name": "ModLoader", "slug": "modloader"},
        {"id": 4, "name": "Environment", "slug": "environment"},
    ]
    versions = [
        {"id": 101, "name": args.minecraft, "slug": args.minecraft, "gameVersionTypeID": 1},
        {"id": 102, "name": "Java " + str(java), "slug": "java" + str(java), "gameVersionTypeID": 2},
        {"id": 103, "name": loader, "slug": loader.lower(), "gameVersionTypeID": 3},
        {"id": 104, "name": "Server", "slug": "server", "gameVersionTypeID": 4},
    ]

    class Metadata(BaseHTTPRequestHandler):
        def do_GET(self):
            data = {"/api/game/version-types": types, "/api/game/versions": versions}.get(self.path)
            if data is None:
                self.send_error(404)
                return
            body = json.dumps(data).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_POST(self):
            self.send_error(405, "This preview server never accepts uploads")

        def log_message(self, format, *arguments):
            pass

    checkout = Path(__file__).resolve().parents[2]
    wrapper = checkout / ("gradlew.bat" if os.name == "nt" else "gradlew")
    with ThreadingHTTPServer(("127.0.0.1", 0), Metadata) as server:
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            print("Preview uses synthetic local CurseForge version IDs; remote authentication is not tested.", flush=True)
            subprocess.run([
                str(wrapper), "--no-daemon", "--build-cache", "--no-configuration-cache", "--stacktrace",
                "assemble", "releasePreview", "releaseModrinth", "releaseCurseforge",
                "-PreleaseDryRun=true", "-PreleaseType=" + args.release_type,
                "-PreleaseNotesFile=" + str((checkout / args.notes).resolve()),
                "-PreleaseCurseforgeApiEndpoint=http://127.0.0.1:" + str(server.server_port),
            ], cwd=checkout, check=True)
        finally:
            server.shutdown()
            worker.join(timeout=5)


if __name__ == "__main__":
    main()
