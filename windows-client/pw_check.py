"""Prueft das app_passwort in der Client-Config, ohne es anzuzeigen."""
import json
import os
from pathlib import Path

p = Path(os.getenv("APPDATA", str(Path.home()))) / "blitztext" / "config.json"
cfg = json.loads(p.read_text(encoding="utf-8"))
pw = cfg.get("app_passwort", "")
print("Config-Datei:", p)
print("backend_url :", cfg.get("backend_url"))
print("noch Platzhalter:", pw == "HIER-DEIN-VPS-PASSWORT")
print("Passwort-Laenge :", len(pw))
print("Leerzeichen am Rand:", pw != pw.strip())
