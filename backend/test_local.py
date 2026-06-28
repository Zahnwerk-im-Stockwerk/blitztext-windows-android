"""Lokaler Selbsttest des Backends ohne echten OpenAI-Key (STUB_MODUS).

Prueft: Health, 401 ohne/falschem Passwort, Erfolg mit richtigem Passwort,
Modus-Routing (klartext = nur Transkript, umschreiben = veredelt), Fehler bei
unbekanntem Modus und leerem Audio.

Start:  python test_local.py
Braucht: fastapi, python-multipart, httpx (siehe requirements + httpx).
"""

import os
import sys

# Env VOR dem Import von app setzen (app liest os.getenv beim Import).
os.environ["STUB_MODUS"] = "1"
os.environ["APP_PASSWORT"] = "test-passwort-123"

from fastapi.testclient import TestClient  # noqa: E402

import app as backend  # noqa: E402

client = TestClient(backend.app)
PW = "test-passwort-123"
DUMMY = ("audio.wav", b"RIFF....nur-fuer-den-test", "audio/wav")

fehler = 0


def pruefe(name: str, bedingung: bool) -> None:
    global fehler
    status = "OK  " if bedingung else "FAIL"
    if not bedingung:
        fehler += 1
    print(f"[{status}] {name}")


# 1) Health ohne Auth
r = client.get("/health")
pruefe("Health 200 + stub=True", r.status_code == 200 and r.json().get("stub") is True)

# 2) Ohne Passwort -> 401
r = client.post("/transkribieren", files={"audio": DUMMY}, data={"modus": "klartext"})
pruefe("Ohne Passwort -> 401", r.status_code == 401)

# 3) Falsches Passwort -> 401
r = client.post(
    "/transkribieren",
    headers={"Authorization": "Bearer falsch"},
    files={"audio": DUMMY},
    data={"modus": "klartext"},
)
pruefe("Falsches Passwort -> 401", r.status_code == 401)

# 4) Richtiges Passwort + klartext -> roh == text (keine Veredelung)
r = client.post(
    "/transkribieren",
    headers={"Authorization": f"Bearer {PW}"},
    files={"audio": DUMMY},
    data={"modus": "klartext"},
)
ok = r.status_code == 200 and r.json()["text"] == r.json()["roh"]
pruefe("Klartext: 200 + text == roh", ok)

# 5) Richtiges Passwort + umschreiben -> veredelt (text != roh)
r = client.post(
    "/transkribieren",
    headers={"Authorization": f"Bearer {PW}"},
    files={"audio": DUMMY},
    data={"modus": "umschreiben"},
)
j = r.json()
ok = r.status_code == 200 and j["text"].startswith("[STUB-umschreiben]") and j["text"] != j["roh"]
pruefe("Umschreiben: 200 + veredelt", ok)

# 6) Unbekannter Modus -> 400
r = client.post(
    "/transkribieren",
    headers={"Authorization": f"Bearer {PW}"},
    files={"audio": DUMMY},
    data={"modus": "quatsch"},
)
pruefe("Unbekannter Modus -> 400", r.status_code == 400)

# 7) Leeres Audio -> 400
r = client.post(
    "/transkribieren",
    headers={"Authorization": f"Bearer {PW}"},
    files={"audio": ("leer.wav", b"", "audio/wav")},
    data={"modus": "klartext"},
)
pruefe("Leeres Audio -> 400", r.status_code == 400)

print()
print("ALLE TESTS GRUEN" if fehler == 0 else f"{fehler} TEST(S) FEHLGESCHLAGEN")
sys.exit(1 if fehler else 0)
