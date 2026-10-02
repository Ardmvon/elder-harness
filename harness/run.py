#!/usr/bin/env python3
"""Run the JVM harness against a private mock server on an ephemeral port."""
from __future__ import annotations

import os
import socket
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def main() -> int:
    port = free_port()
    url = f"http://127.0.0.1:{port}"
    server = subprocess.Popen(
        [sys.executable, str(ROOT / "harness" / "mock_server.py"), str(port)],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    try:
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            try:
                with socket.create_connection(("127.0.0.1", port), timeout=0.2):
                    break
            except Exception:
                if server.poll() is not None:
                    return server.returncode or 1
                time.sleep(0.05)
        else:
            print("mock server did not become ready", file=sys.stderr)
            return 1

        env = os.environ.copy()
        env["ELDERHARNESS_MOCK_URL"] = url
        gradlew = "gradlew.bat" if os.name == "nt" else "./gradlew"
        result = subprocess.run(
            [str(ROOT / gradlew), ":harness:run", "--offline", "--console=plain"],
            cwd=ROOT,
            env=env,
        )
        return result.returncode
    finally:
        server.terminate()
        try:
            server.wait(timeout=2)
        except subprocess.TimeoutExpired:
            server.kill()
            server.wait()


if __name__ == "__main__":
    raise SystemExit(main())
