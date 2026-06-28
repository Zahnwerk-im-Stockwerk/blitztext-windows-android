"""Echter End-to-End-Test gegen das laufende Backend (mit echtem OpenAI-Schluessel).

Liest App-Passwort + Backend-Adresse aus dem Windows-Client-Config (enthaelt KEINEN
OpenAI-Schluessel). Schickt eine WAV-Datei durch und gibt den erkannten Text aus.

Aufruf:  python real_test.py <pfad-zur-wav> [modus]
"""

import json
import os
import sys
from pathlib import Path

import requests

CONFIG = Path(os.getenv("APPDATA", str(Path.home()))) / "blitztext" / "config.json"


def main() -> int:
    if len(sys.argv) < 2:
        print("Aufruf: python real_test.py <pfad-zur-wav> [modus]")
        return 2
    wav = Path(sys.argv[1])
    modus = sys.argv[2] if len(sys.argv) > 2 else "klartext"
    if not wav.exists():
        print(f"WAV nicht gefunden: {wav}")
        return 2

    cfg = json.loads(CONFIG.read_text(encoding="utf-8"))
    url = cfg["backend_url"].rstrip("/") + "/transkribieren"
    print(f"Sende {wav.name} ({wav.stat().st_size} Bytes) an {url}, Modus {modus}...")
    resp = requests.post(
        url,
        headers={"Authorization": f"Bearer {cfg['app_passwort']}"},
        files={"audio": (wav.name, wav.read_bytes(), "audio/wav")},
        data={"modus": modus, "begriffe": cfg.get("begriffe", "")},
        timeout=120,
    )
    print(f"HTTP {resp.status_code}")
    if resp.status_code != 200:
        print(resp.text[:500])
        return 1
    print("Erkannter Text:", repr(resp.json().get("text", "")))
    return 0


if __name__ == "__main__":
    sys.exit(main())
