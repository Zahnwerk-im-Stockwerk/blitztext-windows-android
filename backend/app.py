"""Blitztext-Backend: Sprache zu Text ueber OpenAI, geschuetzt per App-Passwort.

Laeuft als kleiner Container auf dem VPS. Der OpenAI-Schluessel liegt
NUR hier als Umgebungs-Variable. Die App (Android / Windows) schickt Audio + das
gemeinsame App-Passwort, bekommt Text zurueck.

Endpunkte:
  GET  /health          -> ohne Auth, fuer Container-Healthcheck
  POST /transkribieren  -> mit Auth, Audio rein, Text raus

Umgebungs-Variablen (siehe .env.example):
  OPENAI_API_KEY   geheimer OpenAI-Schluessel (nur hier auf dem Server)
  APP_PASSWORT     gemeinsames Passwort, das die App mitschickt (aenderbar)
  STT_MODELL       OpenAI-Transkriptionsmodell (default: whisper-1)
  CHAT_MODELL      OpenAI-Chatmodell fuer die Veredelung (default: gpt-4o-mini)
  STUB_MODUS       "1" = kein echter OpenAI-Call, gibt Test-Text zurueck (fuer Tests)
  PRIVAT_ERZWINGEN "1" = jedes Diktat laeuft lokal (kein OpenAI), egal was der Client
                   schickt. Fuer Backends, die nie in die Cloud duerfen (z.B. Labor-Tablets).
"""

import hmac
import io
import os
import threading
import time

# Lokaler Betrieb (ohne Docker): .env automatisch laden, falls vorhanden.
# Im Container kommen die Werte ueber env_file -> load_dotenv findet nichts und schadet nicht.
try:
    from dotenv import load_dotenv

    load_dotenv()
except Exception:
    pass

from fastapi import Depends, FastAPI, Form, HTTPException, UploadFile
from fastapi.concurrency import run_in_threadpool
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from modi import BRAUCHT_VEREDELUNG, ERLAUBTE_MODI, system_prompt

APP_PASSWORT = os.getenv("APP_PASSWORT", "")
OPENAI_API_KEY = os.getenv("OPENAI_API_KEY", "")
STT_MODELL = os.getenv("STT_MODELL", "whisper-1")
CHAT_MODELL = os.getenv("CHAT_MODELL", "gpt-4o-mini")
STUB_MODUS = os.getenv("STUB_MODUS", "") == "1"

# Privat-Modus: alles bleibt auf diesem Server, kein OpenAI-Aufruf.
LOKAL_STT_MODELL = os.getenv("LOKAL_STT_MODELL", "small")
LOKAL_CHAT_MODELL = os.getenv("LOKAL_CHAT_MODELL", "qwen2.5:3b-instruct")
OLLAMA_URL = os.getenv("OLLAMA_URL", "http://ollama:11434")

# "1" = JEDES Diktat laeuft lokal, egal was der Client schickt. Fuer ein Backend, das
# strukturell nie ueber OpenAI gehen darf (z.B. Labor-Tablets mit Patientenbezug).
PRIVAT_ERZWINGEN = os.getenv("PRIVAT_ERZWINGEN", "") == "1"

# Maximale Audiogroesse (Schutz gegen versehentliche Riesen-Uploads). 25 MB =
# OpenAI-Whisper-Limit, lange Diktate gehen auch so locker rein.
MAX_AUDIO_BYTES = 25 * 1024 * 1024

app = FastAPI(title="Blitztext-Backend", docs_url=None, redoc_url=None)
_bearer = HTTPBearer(auto_error=False)


def pruefe_passwort(creds: HTTPAuthorizationCredentials | None = Depends(_bearer)) -> None:
    """Vergleicht das mitgeschickte Bearer-Passwort mit APP_PASSWORT (timing-sicher)."""
    if not APP_PASSWORT:
        # Fehlkonfiguration: lieber alles sperren als ungeschuetzt laufen.
        raise HTTPException(status_code=503, detail="Server ohne App-Passwort konfiguriert")
    if creds is None or not hmac.compare_digest(creds.credentials, APP_PASSWORT):
        raise HTTPException(status_code=401, detail="Falsches oder fehlendes App-Passwort")


def _openai_client():
    from openai import OpenAI

    return OpenAI(api_key=OPENAI_API_KEY)


def _transkribiere(audio_bytes: bytes, dateiname: str, begriffe: str) -> str:
    if STUB_MODUS:
        return "[STUB] erkannter Text aus dem Audio."
    client = _openai_client()
    ergebnis = client.audio.transcriptions.create(
        model=STT_MODELL,
        file=(dateiname, audio_bytes),
        language="de",
        prompt=begriffe or None,  # Eigennamen als Hinweis (z.B. "Zahnwerk im Stockwerk")
    )
    return ergebnis.text.strip()


# ── Privat-Zweig: laeuft komplett auf diesem Server, strukturell ohne OpenAI ──

_lokales_whisper = None
_whisper_sperre = threading.Lock()


def _hole_lokales_whisper():
    """Laedt das lokale Whisper-Modell einmal und haelt es im Speicher."""
    global _lokales_whisper
    with _whisper_sperre:
        if _lokales_whisper is None:
            from faster_whisper import WhisperModel

            print(f"Lade lokales Whisper-Modell '{LOKAL_STT_MODELL}'...")
            _lokales_whisper = WhisperModel(
                LOKAL_STT_MODELL, device="cpu", compute_type="int8", download_root="/modelle"
            )
            print("Lokales Whisper-Modell bereit.")
        return _lokales_whisper


def _transkribiere_lokal(audio_bytes: bytes, begriffe: str) -> str:
    if STUB_MODUS:
        return "[STUB-privat] erkannter Text aus dem Audio."
    modell = _hole_lokales_whisper()
    segmente, _info = modell.transcribe(
        io.BytesIO(audio_bytes),
        language="de",
        initial_prompt=begriffe or None,
        vad_filter=True,
    )
    return " ".join(s.text.strip() for s in segmente).strip()


def _veredele_lokal(text: str, modus: str) -> str:
    if STUB_MODUS:
        return f"[STUB-privat-{modus}] {text}"
    import requests

    antwort = requests.post(
        f"{OLLAMA_URL}/api/chat",
        json={
            "model": LOKAL_CHAT_MODELL,
            "stream": False,
            "keep_alive": "2h",  # Modell warmhalten: 1-2 s statt ~15 s Kaltstart
            "options": {"temperature": 0},
            "messages": [
                {"role": "system", "content": system_prompt(modus)},
                {"role": "user", "content": text},
            ],
        },
        timeout=120,
    )
    antwort.raise_for_status()
    return (antwort.json().get("message", {}).get("content") or "").strip()


# Modell beim Start im Hintergrund vorladen (erster Privat-Diktat-Aufruf wird sonst langsam)
if os.getenv("LOKAL_STT_VORLADEN", "") == "1" and not STUB_MODUS:
    threading.Thread(target=_hole_lokales_whisper, daemon=True).start()


def _veredele(text: str, modus: str) -> str:
    if STUB_MODUS:
        return f"[STUB-{modus}] {text}"
    client = _openai_client()
    antwort = client.chat.completions.create(
        model=CHAT_MODELL,
        temperature=0,
        messages=[
            {"role": "system", "content": system_prompt(modus)},
            {"role": "user", "content": text},
        ],
    )
    return (antwort.choices[0].message.content or "").strip()


@app.get("/health")
def health() -> dict:
    """Lebenszeichen ohne Auth. Sagt nicht, ob ein Schluessel hinterlegt ist."""
    return {"status": "ok", "stub": STUB_MODUS}


@app.post("/transkribieren", dependencies=[Depends(pruefe_passwort)])
async def transkribieren(
    audio: UploadFile,
    modus: str = Form("klartext"),
    begriffe: str = Form(""),
    privat: str = Form("0"),
) -> dict:
    """Audio entgegennehmen, transkribieren, optional veredeln, Text zurueckgeben.

    privat="1": kompletter Lauf auf diesem Server (lokales Whisper + Ollama),
    der OpenAI-Codepfad wird strukturell nicht beruehrt. Im Privat-Zweig wird
    bewusst kein Textinhalt geloggt, nur die Dauer.
    """
    # Serverseitige Erzwingung: dieses Backend laesst NICHTS ueber OpenAI laufen,
    # egal was der Client als privat-Flag schickt (z.B. fuer Labor-Tablets).
    if PRIVAT_ERZWINGEN:
        privat = "1"

    if modus not in ERLAUBTE_MODI:
        raise HTTPException(status_code=400, detail=f"Unbekannter Modus: {modus}")

    audio_bytes = await audio.read()
    if not audio_bytes:
        raise HTTPException(status_code=400, detail="Leeres Audio")
    if len(audio_bytes) > MAX_AUDIO_BYTES:
        raise HTTPException(status_code=413, detail="Audio zu gross (max 25 MB)")

    if privat == "1":
        start = time.monotonic()
        try:
            roh = await run_in_threadpool(_transkribiere_lokal, audio_bytes, begriffe)
            if modus in BRAUCHT_VEREDELUNG and roh:
                text = await run_in_threadpool(_veredele_lokal, roh, modus)
            else:
                text = roh
        except Exception as e:  # bewusst ohne Inhalte, nur Fehlertyp
            raise HTTPException(
                status_code=502,
                detail=f"Privat-Verarbeitung fehlgeschlagen: {type(e).__name__}",
            )
        print(f"Privat-Diktat: {time.monotonic() - start:.1f} s (Modus {modus})")
        return {"text": text, "roh": roh, "modus": modus, "privat": True}

    dateiname = audio.filename or "audio.wav"
    try:
        roh = await run_in_threadpool(_transkribiere, audio_bytes, dateiname, begriffe)
        if modus in BRAUCHT_VEREDELUNG and roh:
            text = await run_in_threadpool(_veredele, roh, modus)
        else:
            text = roh
    except Exception as e:  # OpenAI-Fehler verstaendlich weiterreichen
        raise HTTPException(status_code=502, detail=_openai_fehler_text(e))

    return {"text": text, "roh": roh, "modus": modus, "privat": False}


def _openai_fehler_text(e: Exception) -> str:
    """Uebersetzt typische OpenAI-Fehler in eine klare deutsche Meldung."""
    status = getattr(e, "status_code", None)
    code = getattr(e, "code", "") or ""
    if status == 401 or code == "invalid_api_key":
        return ("OpenAI lehnt den Schluessel ab (401). OPENAI_API_KEY in der .env pruefen: "
                "genau EIN 'sk-' am Anfang, keine Leerzeichen, kein Platzhaltertext.")
    if status == 429 or code == "insufficient_quota":
        return ("OpenAI: kein Guthaben oder Rate-Limit (429). Unter "
                "platform.openai.com/billing Guthaben aufladen.")
    return f"OpenAI-Fehler: {type(e).__name__}: {str(e)[:200]}"
