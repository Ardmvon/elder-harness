"""银龄专线可信圈子服务端（见 main.py）。"""


import os


def load_env(path: str = ".env") -> None:
    """Reads KEY=VALUE lines into the environment; real environment variables win."""
    try:
        with open(path, encoding="utf-8") as handle:
            for line in handle:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                key, value = line.split("=", 1)
                os.environ.setdefault(key.strip(), value.strip())
    except FileNotFoundError:
        pass
