"""Kurz-Diagnose: importiert die Module + listet Audio-Eingabegeraete (Mikrofone)."""
import sys
sys.stdout.reconfigure(encoding="utf-8")

print("Python:", sys.version.split()[0])

for mod in ["sounddevice", "keyboard", "pyperclip", "PIL", "pystray", "requests"]:
    try:
        __import__(mod)
        print(f"  import {mod}: OK")
    except Exception as e:
        print(f"  import {mod}: FEHLER {e}")

import sounddevice as sd
print("\nAudio-Geraete:")
try:
    for i, d in enumerate(sd.query_devices()):
        kanal_in = d.get("max_input_channels", 0)
        marke = "  <== EINGANG" if kanal_in > 0 else ""
        print(f"  [{i}] in={kanal_in} out={d.get('max_output_channels',0)}  {d['name']}{marke}")
    try:
        default_in = sd.default.device[0]
        print(f"\nStandard-Eingang: Index {default_in}")
    except Exception as e:
        print(f"\nKein Standard-Eingang: {e}")
except Exception as e:
    print(f"  Fehler beim Abfragen: {e}")
