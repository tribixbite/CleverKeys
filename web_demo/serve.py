#!/usr/bin/env python3
"""
Local static server for the web demo, reproducing what CI actually deploys.

`.github/workflows/deploy-web-demo.yml` builds the published `/demo/` directory
by copying `web_demo/demo/` and then flattening `web_demo/*.{onnx,js,json,...}`
on top of it. So `demo/index.html` fetches `swipe_encoder_android.onnx`,
`en_enhanced.json`, `swipe-vocabulary.js` and friends as *siblings*, even though
in the repo they live one level up.

Rather than duplicate ~11 MB of binaries into `demo/`, this server serves
`web_demo/` as the document root and, for any `/demo/<name>` that does not
exist under `demo/`, falls back to `web_demo/<name>`. The result is
byte-for-byte the layout the deployed site has.

    python3 web_demo/serve.py [--port 8765] [--bind 127.0.0.1]
    -> http://127.0.0.1:8765/demo/

Also sets the WASM MIME type (some Pythons don't know it, and a wrong
Content-Type makes onnxruntime-web fall back off `instantiateStreaming`) and
disables caching so an edited file is picked up on reload.
"""

from __future__ import annotations

import argparse
import functools
import http.server
import os
import posixpath
import socketserver
import sys
from pathlib import Path
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parent
DEMO_DIR = ROOT / "demo"


class DemoRequestHandler(http.server.SimpleHTTPRequestHandler):
    """Serves `web_demo/`, with `/demo/<name>` falling back to `web_demo/<name>`."""

    extensions_map = {
        **http.server.SimpleHTTPRequestHandler.extensions_map,
        ".wasm": "application/wasm",
        ".onnx": "application/octet-stream",
        ".bin": "application/octet-stream",
        ".mjs": "text/javascript",
        ".js": "text/javascript",
        ".json": "application/json",
    }

    def translate_path(self, path: str) -> str:  # noqa: D102 (stdlib override)
        resolved = super().translate_path(path)
        if os.path.exists(resolved):
            return resolved
        # Mirror the deploy-time flatten of web_demo/* into demo/.
        clean = unquote(path.split("?", 1)[0].split("#", 1)[0])
        normalized = posixpath.normpath(clean)
        if normalized.startswith("/demo/"):
            name = normalized[len("/demo/"):]
            if name and "/" not in name:
                fallback = ROOT / name
                if fallback.is_file():
                    return str(fallback)
        return resolved

    def end_headers(self) -> None:  # noqa: D102 (stdlib override)
        self.send_header("Cache-Control", "no-store, max-age=0")
        super().end_headers()

    def log_message(self, fmt: str, *args) -> None:  # noqa: D102 (stdlib override)
        # Keep the console readable: only surface non-2xx responses.
        status = str(args[1]) if len(args) > 1 else ""
        if not status.startswith("2"):
            super().log_message(fmt, *args)


class ReusableTCPServer(socketserver.ThreadingTCPServer):
    """Threaded so a slow 10 MB .wasm fetch can't block the page's other requests."""

    allow_reuse_address = True
    daemon_threads = True


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--bind", default="127.0.0.1")
    args = ap.parse_args()

    if not DEMO_DIR.is_dir():
        print(f"error: {DEMO_DIR} not found", file=sys.stderr)
        return 1

    handler = functools.partial(DemoRequestHandler, directory=str(ROOT))
    with ReusableTCPServer((args.bind, args.port), handler) as httpd:
        print(f"serving {ROOT} at http://{args.bind}:{args.port}/demo/", flush=True)
        try:
            httpd.serve_forever()
        except KeyboardInterrupt:
            print("\nstopped", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
