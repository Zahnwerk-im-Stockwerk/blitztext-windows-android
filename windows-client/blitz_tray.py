"""Blitztext Windows-Client: Hotkey halten -> sprechen -> loslassen -> Text einfuegen.

Sitzt als kleines Tray-Icon unten rechts. Standard-Hotkey: STRG+LEERTASTE gedrueckt
halten nimmt auf, loslassen schickt das Audio ans Backend und fuegt den Text dort ein,
wo gerade der Cursor steht (ueber die Zwischenablage + Strg+V).

Konfiguration liegt in %APPDATA%\\blitztext\\config.json (ausserhalb des Repos, damit
das App-Passwort nicht eingecheckt wird). Beim ersten Start wird eine Vorlage angelegt.

Selbsttest ohne Mikro/Hotkey (nur Netzwerkweg gegen ein laufendes Backend):
    python blitz_tray.py --selftest
"""

import io
import json
import os
import sys
import threading
import time
import wave
from pathlib import Path

import requests


def _ausgabe_einrichten() -> None:
    """Macht print()/Logging fenster-los-sicher.

    Unter pythonw.exe (stille Variante, kein Konsolenfenster) ist sys.stdout None;
    jedes print() wuerde sonst beim Start crashen. Dann leiten wir die Ausgabe in
    eine Logdatei um, sonst nur Encoding/Buffering setzen.
    """
    if sys.stdout is None or sys.stderr is None:
        ziel = Path(os.getenv("APPDATA", str(Path.home()))) / "blitztext"
        ziel.mkdir(parents=True, exist_ok=True)
        logf = open(ziel / "blitz.log", "a", encoding="utf-8", buffering=1)
        sys.stdout = logf
        sys.stderr = logf
    try:
        sys.stdout.reconfigure(encoding="utf-8", line_buffering=True)
    except Exception:
        pass


_ausgabe_einrichten()

CONFIG_DIR = Path(os.getenv("APPDATA", str(Path.home()))) / "blitztext"
CONFIG_PFAD = CONFIG_DIR / "config.json"

# Zahnwerk-Palette: Nachtblau + Gold
FARBE_GOLD = "#C8A84B"
FARBE_GOLD_DUNKEL = "#9B7D20"
FARBE_NACHTBLAU = "#0C1526"
FARBE_KARTE = "#152035"
FARBE_TEXT = "#EEF2FF"
FARBE_GEDAEMPFT = "#7A8FAF"
FARBE_GRUEN = "#34D399"
FARBE_ROT = "#F87171"

LOGO_PFAD = Path(__file__).resolve().parent / "logo-zahnwerk-kreis.jpg"
ERLAUBTE_MODI = ["klartext", "umschreiben", "freundlich", "emoji"]

# Stille-Erkennung: unter diesem Spitzenpegel (von 32767) gilt die Aufnahme als stumm.
# Startwert, ggf. nach Live-Test justieren (leises Sprechen liegt deutlich darueber).
STILLE_SCHWELLE = 300

STANDARD_CONFIG = {
    "backend_url": "http://127.0.0.1:8099",
    "app_passwort": "bitte-aendern",
    "modus": "klartext",
    "hotkey": "ctrl+space",
    "begriffe": "",
    "auto_paste": True,
    "samplerate": 16000,
    "privat": False,  # True = alles bleibt auf dem eigenen Server, kein OpenAI
}


def lade_config() -> dict:
    if not CONFIG_PFAD.exists():
        CONFIG_DIR.mkdir(parents=True, exist_ok=True)
        CONFIG_PFAD.write_text(json.dumps(STANDARD_CONFIG, indent=2, ensure_ascii=False), encoding="utf-8")
        print(f"Vorlage-Konfig angelegt: {CONFIG_PFAD}")
        print("Bitte backend_url + app_passwort eintragen, dann neu starten.")
    cfg = dict(STANDARD_CONFIG)
    cfg.update(json.loads(CONFIG_PFAD.read_text(encoding="utf-8")))
    return cfg


def speichere_config(cfg: dict) -> None:
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    CONFIG_PFAD.write_text(json.dumps(cfg, indent=2, ensure_ascii=False), encoding="utf-8")


def config_neu_laden(cfg: dict) -> None:
    """Frischen Stand von der Platte uebernehmen (z.B. nach Aenderung im Einstellungsfenster)."""
    try:
        cfg.update(json.loads(CONFIG_PFAD.read_text(encoding="utf-8")))
    except Exception:
        pass


def pcm_zu_wav_bytes(frames: bytes, samplerate: int) -> bytes:
    """Roh-PCM (int16 mono) in einen WAV-Container packen (im Speicher)."""
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)  # int16
        w.setframerate(samplerate)
        w.writeframes(frames)
    return buf.getvalue()


def sende_ans_backend(wav_bytes: bytes, cfg: dict) -> str:
    """Audio ans Backend schicken, erkannten Text zurueck."""
    url = cfg["backend_url"].rstrip("/") + "/transkribieren"
    resp = requests.post(
        url,
        headers={"Authorization": f"Bearer {cfg['app_passwort']}"},
        files={"audio": ("aufnahme.wav", wav_bytes, "audio/wav")},
        data={
            "modus": cfg.get("modus", "klartext"),
            "begriffe": cfg.get("begriffe", ""),
            "privat": "1" if cfg.get("privat") else "0",
        },
        timeout=120,
    )
    if resp.status_code == 401:
        raise RuntimeError("Falsches App-Passwort (401). In der Konfig pruefen.")
    resp.raise_for_status()
    return resp.json().get("text", "")


def text_einfuegen(text: str, auto_paste: bool) -> None:
    """Text in die Zwischenablage, optional automatisch Strg+V senden."""
    import pyperclip

    pyperclip.copy(text)
    if not auto_paste:
        return
    import keyboard

    time.sleep(0.05)
    keyboard.send("ctrl+v")


class Aufnahme:
    """Mikrofon-Aufnahme als Hintergrund-Stream (Hold-to-Talk)."""

    def __init__(self, samplerate: int):
        self.samplerate = samplerate
        self._frames: list[bytes] = []
        self._stream = None
        self.pegel = 0       # Spitzenpegel des letzten Blocks (0..32767), fuer die Live-Anzeige
        self.max_gesamt = 0  # hoechster Pegel der ganzen Aufnahme, fuer die Stille-Erkennung

    def start(self) -> None:
        import sounddevice as sd

        self._frames = []
        self.pegel = 0
        self.max_gesamt = 0

        def callback(indata, frames, time_info, status):  # noqa: ANN001
            daten = bytes(indata)
            self._frames.append(daten)
            # Spitzenpegel der 16-Bit-Samples (Stdlib, kein audioop: das gibt es ab 3.13 nicht mehr)
            try:
                spitze = max(abs(w) for w in memoryview(daten).cast("h"))
            except ValueError:
                spitze = 0
            self.pegel = spitze
            if spitze > self.max_gesamt:
                self.max_gesamt = spitze

        self._stream = sd.RawInputStream(
            samplerate=self.samplerate, channels=1, dtype="int16", callback=callback
        )
        self._stream.start()

    def stop(self) -> bytes:
        if self._stream is not None:
            self._stream.stop()
            self._stream.close()
            self._stream = None
        return b"".join(self._frames)


def _doppel_beep() -> None:
    """Hoerbares Warnsignal (z.B. "kein Ton"). Faellt still aus, wenn kein Sound moeglich."""
    try:
        import winsound

        winsound.Beep(700, 120)
        time.sleep(0.06)
        winsound.Beep(700, 120)
    except Exception:
        pass


_tray_icon = None  # wird in _tray_icon_starten gesetzt


def _melde(text: str) -> None:
    """Meldung in Konsole/Log + als Tray-Benachrichtigung (falls Tray laeuft)."""
    print(text)
    icon = _tray_icon
    if icon is not None:
        try:
            icon.notify(text, "Blitztext")
        except Exception:
            pass


class PegelFenster:
    """Kleines Always-on-top-Fensterchen unten rechts waehrend der Aufnahme:
    roter Punkt + 7 "Dioden"-Balken, die beim Sprechen hoch- und runterlaufen.

    EIN dauerhafter Thread mit EINER versteckten Tk-Instanz; pro Aufnahme wird
    nur ein-/ausgeblendet. (Tk pro Aufnahme neu erzeugen/zerstoeren provoziert
    den Tcl_AsyncDelete-Crash, weil Tcl-Objekte threadfremd aufgeraeumt wuerden.)
    """

    BALKEN = 7
    VOLL_PEGEL = 14000.0  # Pegel ab dem ein Balken voll ausschlaegt

    def __init__(self, aufnahme: "Aufnahme"):
        self._aufnahme = aufnahme
        self._sichtbar = threading.Event()
        self._start_zeit = 0.0
        self._gewarnt = False
        threading.Thread(target=self._lauf, daemon=True).start()

    def zeigen(self) -> None:
        self._start_zeit = time.time()
        self._gewarnt = False
        self._sichtbar.set()

    def verstecken(self) -> None:
        self._sichtbar.clear()

    def _lauf(self) -> None:
        try:
            import tkinter as tk
        except Exception:
            return
        try:
            root = tk.Tk()
            root.overrideredirect(True)  # randlos
            root.attributes("-topmost", True)
            breite, hoehe = 190, 54
            root.update_idletasks()
            x = root.winfo_screenwidth() - breite - 24
            y = root.winfo_screenheight() - hoehe - 90  # ueber der Taskleiste
            root.geometry(f"{breite}x{hoehe}+{x}+{y}")
            canvas = tk.Canvas(root, width=breite, height=hoehe, bg=FARBE_KARTE,
                               highlightthickness=1, highlightbackground=FARBE_GOLD_DUNKEL)
            canvas.pack(fill="both", expand=True)
            root.withdraw()  # erst bei der ersten Aufnahme zeigen
            werte = [0] * self.BALKEN
            zustand = {"sichtbar": False}

            def tick() -> None:
                if not self._sichtbar.is_set():
                    if zustand["sichtbar"]:
                        zustand["sichtbar"] = False
                        root.withdraw()
                    root.after(120, tick)
                    return
                if not zustand["sichtbar"]:
                    zustand["sichtbar"] = True
                    werte[:] = [0] * self.BALKEN
                    root.deiconify()
                    root.attributes("-topmost", True)
                werte.pop(0)
                werte.append(self._aufnahme.pegel)
                stumm = self._aufnahme.max_gesamt < STILLE_SCHWELLE
                canvas.delete("all")
                canvas.create_oval(12, hoehe // 2 - 5, 22, hoehe // 2 + 5, fill=FARBE_ROT, outline="")
                if time.time() - self._start_zeit > 1.2 and stumm:
                    if not self._gewarnt:
                        self._gewarnt = True
                        threading.Thread(target=_doppel_beep, daemon=True).start()
                    canvas.create_text((breite + 26) // 2, hoehe // 2,
                                       text="Kein Ton - Mikro pruefen",
                                       fill="#FBBF24", font=("Segoe UI", 9, "bold"))
                else:
                    canvas.create_text((breite + 26) // 2, 11, text="Aufnahme laeuft",
                                       fill=FARBE_GEDAEMPFT, font=("Segoe UI", 7))
                    links = 34
                    b_breite = (breite - links - 14) / (2 * self.BALKEN - 1)
                    y_unten = hoehe - 8
                    max_h = hoehe - 28
                    for i, wert in enumerate(werte):
                        anteil = min(1.0, wert / self.VOLL_PEGEL) ** 0.5
                        b_hoehe = max_h * (0.12 + 0.88 * anteil)
                        bx = links + i * 2 * b_breite
                        canvas.create_rectangle(bx, y_unten - b_hoehe, bx + b_breite, y_unten,
                                                fill=FARBE_GRUEN, outline="")
                root.after(80, tick)

            tick()
            root.mainloop()
        except Exception as e:
            print(f"(Pegel-Fenster-Fehler: {e})")


def run_tray(cfg: dict) -> None:
    """Hauptbetrieb: Hotkey halten = aufnehmen, loslassen = senden + einfuegen."""
    import keyboard

    aufnahme = Aufnahme(cfg["samplerate"])
    pegel_fenster = PegelFenster(aufnahme)
    laeuft = {"aktiv": False}
    lock = threading.Lock()

    def bei_druecken(_=None):  # noqa: ANN001
        with lock:
            if laeuft["aktiv"]:
                return
            laeuft["aktiv"] = True
        try:
            aufnahme.start()
            pegel_fenster.zeigen()
            print("Aufnahme laeuft... (Taste halten)")
        except Exception as e:  # Mikro nicht verfuegbar o.ae.
            laeuft["aktiv"] = False
            _doppel_beep()
            _melde(f"Aufnahme-Fehler: {e}")

    def bei_loslassen(_=None):  # noqa: ANN001
        with lock:
            if not laeuft["aktiv"]:
                return
            laeuft["aktiv"] = False
        pegel_fenster.verstecken()
        config_neu_laden(cfg)  # Modus-/Passwort-Aenderungen ohne Neustart uebernehmen
        wav = pcm_zu_wav_bytes(aufnahme.stop(), cfg["samplerate"])
        if len(wav) < 2000:  # quasi nichts aufgenommen
            print("Zu kurz, verworfen.")
            return
        if aufnahme.max_gesamt < STILLE_SCHWELLE:
            # Kein Sprachsignal: nicht senden, sondern klar warnen
            threading.Thread(target=_doppel_beep, daemon=True).start()
            _melde("Kein Ton aufgenommen - nicht gesendet. Mikrofon pruefen.")
            return
        print("Verarbeite...")
        try:
            text = sende_ans_backend(wav, cfg)
            if text:
                text_einfuegen(text, cfg.get("auto_paste", True))
                print(f"Eingefuegt: {text[:80]}")
            else:
                print("Kein Text erkannt.")
        except Exception as e:
            print(f"Fehler: {e}")

    # Hold-to-Talk: getrennte Press-/Release-Handler auf die letzte Taste des Hotkeys.
    hotkey = cfg.get("hotkey", "ctrl+space")
    letzte_taste = hotkey.split("+")[-1]
    keyboard.add_hotkey(hotkey, bei_druecken, suppress=False, trigger_on_release=False)
    keyboard.on_release_key(letzte_taste, bei_loslassen)

    print(f"Blitztext laeuft. Hotkey: {hotkey} (halten = sprechen). Beenden mit Strg+C.")
    _tray_icon_starten(cfg)
    keyboard.wait()  # blockiert bis Prozess-Ende


def _tray_logo_bild():
    """Zahnwerk-Logo (Gold-Kreis mit Zahn) als Tray-Bild; Fallback: gezeichnetes Mikro."""
    from PIL import Image, ImageDraw

    try:
        logo = Image.open(LOGO_PFAD).convert("RGBA").resize((64, 64))
        # Weisse Ecken des quadratischen JPGs rund ausstanzen
        maske = Image.new("L", (64, 64), 0)
        ImageDraw.Draw(maske).ellipse((0, 0, 63, 63), fill=255)
        rund = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
        rund.paste(logo, (0, 0), maske)
        return rund
    except Exception:
        img = Image.new("RGB", (64, 64), FARBE_NACHTBLAU)
        d = ImageDraw.Draw(img)
        d.ellipse((18, 10, 46, 44), fill=FARBE_GOLD)
        d.rectangle((28, 40, 36, 54), fill=FARBE_GOLD)
        return img


def _tray_icon_starten(cfg: dict) -> None:
    """Optionales Tray-Icon. Faellt still aus, wenn pystray/Pillow fehlen."""
    try:
        import pystray
    except Exception:
        print("(Kein Tray-Icon: pystray/Pillow nicht installiert - laeuft trotzdem.)")
        return

    def beenden(icon, _item):  # noqa: ANN001
        icon.stop()
        os._exit(0)

    def modus_setzen(modus: str):
        def handler(_icon, _item):  # noqa: ANN001
            cfg["modus"] = modus
            speichere_config(cfg)
            print(f"Modus umgestellt: {modus}")
        return handler

    def modus_aktiv(modus: str):
        return lambda _item: cfg.get("modus") == modus

    def einstellungen_oeffnen(_icon, _item):  # noqa: ANN001
        import subprocess

        subprocess.Popen([sys.executable, str(Path(__file__).resolve()), "--einstellungen"])

    def privat_umschalten(_icon, _item):  # noqa: ANN001
        cfg["privat"] = not cfg.get("privat", False)
        speichere_config(cfg)
        print(f"Privat-Modus: {'AN (nur eigener Server)' if cfg['privat'] else 'aus'}")

    modus_menue = pystray.Menu(
        *[
            pystray.MenuItem(m.capitalize(), modus_setzen(m), checked=modus_aktiv(m), radio=True)
            for m in ERLAUBTE_MODI
        ]
    )
    menu = pystray.Menu(
        pystray.MenuItem("Blitztext - Zahnwerk im Stockwerk", None, enabled=False),
        pystray.MenuItem("Modus", modus_menue),
        pystray.MenuItem(
            "Privat (nur eigener Server)",
            privat_umschalten,
            checked=lambda _item: bool(cfg.get("privat", False)),
        ),
        pystray.MenuItem("Einstellungen...", einstellungen_oeffnen),
        pystray.MenuItem("Beenden", beenden),
    )
    icon = pystray.Icon("blitztext", _tray_logo_bild(), "Blitztext", menu)
    global _tray_icon
    _tray_icon = icon
    threading.Thread(target=icon.run, daemon=True).start()


def pruefe_backend(cfg: dict) -> tuple[bool, str]:
    """Erreichbarkeit + Passwort pruefen, OHNE OpenAI-Kosten.

    /health ohne Auth, dann /transkribieren mit leerem Audio: Antwort 400
    "Leeres Audio" heisst Passwort stimmt, 401 heisst Passwort falsch.
    """
    basis = cfg["backend_url"].rstrip("/")
    try:
        gesund = requests.get(f"{basis}/health", timeout=8)
        if gesund.status_code != 200:
            return False, f"Backend antwortet, /health liefert {gesund.status_code}"
    except Exception as e:
        return False, f"Backend nicht erreichbar: {e}"
    try:
        resp = requests.post(
            f"{basis}/transkribieren",
            headers={"Authorization": f"Bearer {cfg['app_passwort']}"},
            files={"audio": ("leer.wav", b"", "audio/wav")},
            data={"modus": "klartext", "begriffe": ""},
            timeout=15,
        )
    except Exception as e:
        return False, f"Fehler beim Passwort-Test: {e}"
    if resp.status_code == 401:
        return False, "Backend erreichbar, aber das App-Passwort stimmt nicht"
    if resp.status_code == 400 and "Leeres Audio" in resp.text:
        return True, "Alles in Ordnung: Backend erreichbar, Passwort stimmt"
    return True, f"Backend erreichbar (Antwort {resp.status_code})"


def einstellungs_fenster(cfg: dict) -> int:
    """Einstellungs-/Status-Fenster in Zahnwerk-Optik (tkinter, bei Python dabei)."""
    import tkinter as tk

    fenster = tk.Tk()
    fenster.title("Blitztext - Einstellungen")
    fenster.configure(bg=FARBE_NACHTBLAU, padx=24, pady=20)
    fenster.resizable(False, False)

    # Kopf: Logo + Titel
    kopf = tk.Frame(fenster, bg=FARBE_NACHTBLAU)
    kopf.pack(fill="x", pady=(0, 16))
    logo_bild = None
    try:
        from PIL import Image, ImageTk

        logo_bild = ImageTk.PhotoImage(Image.open(LOGO_PFAD).resize((48, 48)))
        tk.Label(kopf, image=logo_bild, bg=FARBE_NACHTBLAU).pack(side="left", padx=(0, 12))
    except Exception:
        pass
    titel = tk.Frame(kopf, bg=FARBE_NACHTBLAU)
    titel.pack(side="left")
    tk.Label(titel, text="Blitztext", bg=FARBE_NACHTBLAU, fg=FARBE_TEXT,
             font=("Segoe UI", 16, "bold")).pack(anchor="w")
    tk.Label(titel, text="Zahnwerk im Stockwerk", bg=FARBE_NACHTBLAU, fg=FARBE_GOLD,
             font=("Segoe UI", 9)).pack(anchor="w")

    def feld(beschriftung: str, wert: str, geheim: bool = False) -> tk.Entry:
        tk.Label(fenster, text=beschriftung, bg=FARBE_NACHTBLAU, fg=FARBE_GEDAEMPFT,
                 font=("Segoe UI", 9)).pack(anchor="w")
        e = tk.Entry(fenster, width=46, bg=FARBE_KARTE, fg=FARBE_TEXT,
                     insertbackground=FARBE_TEXT, relief="flat",
                     show="*" if geheim else "")
        e.insert(0, wert)
        e.pack(fill="x", ipady=4, pady=(2, 10))
        return e

    feld_url = feld("Backend-Adresse", cfg.get("backend_url", ""))
    feld_pw = feld("App-Passwort", cfg.get("app_passwort", ""), geheim=True)
    feld_begriffe = feld("Eigennamen-Hilfe (Begriffe)", cfg.get("begriffe", ""))

    tk.Label(fenster, text="Veredelungs-Modus", bg=FARBE_NACHTBLAU, fg=FARBE_GEDAEMPFT,
             font=("Segoe UI", 9)).pack(anchor="w")
    modus_wert = tk.StringVar(value=cfg.get("modus", "klartext"))
    modus_zeile = tk.Frame(fenster, bg=FARBE_NACHTBLAU)
    modus_zeile.pack(anchor="w", pady=(2, 10))
    for m in ERLAUBTE_MODI:
        tk.Radiobutton(modus_zeile, text=m.capitalize(), value=m, variable=modus_wert,
                       bg=FARBE_NACHTBLAU, fg=FARBE_TEXT, selectcolor=FARBE_KARTE,
                       activebackground=FARBE_NACHTBLAU, activeforeground=FARBE_GOLD,
                       font=("Segoe UI", 9)).pack(side="left", padx=(0, 8))

    privat_wert = tk.BooleanVar(value=bool(cfg.get("privat", False)))
    tk.Checkbutton(fenster, text="Privat: nur eigener Server (fuer Patienten-Diktate)",
                   variable=privat_wert, bg=FARBE_NACHTBLAU, fg=FARBE_TEXT,
                   selectcolor=FARBE_KARTE, activebackground=FARBE_NACHTBLAU,
                   activeforeground=FARBE_GOLD, font=("Segoe UI", 9)).pack(anchor="w")
    tk.Label(fenster, text="Spracherkennung + Veredelung laufen dann auf dem VPS, OpenAI wird nicht angerufen.",
             bg=FARBE_NACHTBLAU, fg=FARBE_GEDAEMPFT, font=("Segoe UI", 8)).pack(anchor="w", pady=(0, 8))

    tk.Label(fenster, text=f"Hotkey: {cfg.get('hotkey', 'ctrl+space')} (halten = sprechen)",
             bg=FARBE_NACHTBLAU, fg=FARBE_GEDAEMPFT, font=("Segoe UI", 9)).pack(anchor="w", pady=(0, 12))

    status = tk.Label(fenster, text="", bg=FARBE_NACHTBLAU, fg=FARBE_GEDAEMPFT,
                      font=("Segoe UI", 9), wraplength=360, justify="left")

    def werte_uebernehmen() -> None:
        cfg["backend_url"] = feld_url.get().strip().rstrip("/")
        cfg["app_passwort"] = feld_pw.get()
        cfg["begriffe"] = feld_begriffe.get().strip()
        cfg["modus"] = modus_wert.get()
        cfg["privat"] = bool(privat_wert.get())

    def testen() -> None:
        werte_uebernehmen()
        status.config(text="Teste Verbindung...", fg=FARBE_GEDAEMPFT)

        def lauf() -> None:
            ok, meldung = pruefe_backend(cfg)
            status.after(0, lambda: status.config(text=meldung, fg=FARBE_GRUEN if ok else FARBE_ROT))

        threading.Thread(target=lauf, daemon=True).start()

    def speichern() -> None:
        werte_uebernehmen()
        speichere_config(cfg)
        status.config(text="Gespeichert. Gilt ab dem naechsten Diktat (laufender Client liest "
                           "die Konfig bei jedem Diktat neu).", fg=FARBE_GRUEN)

    knoepfe = tk.Frame(fenster, bg=FARBE_NACHTBLAU)
    knoepfe.pack(fill="x", pady=(4, 6))
    tk.Button(knoepfe, text="Backend pruefen", command=testen, bg=FARBE_KARTE, fg=FARBE_GOLD,
              relief="flat", font=("Segoe UI", 10), padx=12, pady=4).pack(side="left", padx=(0, 8))
    tk.Button(knoepfe, text="Speichern", command=speichern, bg=FARBE_GOLD, fg=FARBE_NACHTBLAU,
              relief="flat", font=("Segoe UI", 10, "bold"), padx=16, pady=4).pack(side="left")
    status.pack(anchor="w", pady=(6, 0))

    fenster.mainloop()
    return 0


def selftest(cfg: dict) -> int:
    """Netzwerkweg gegen ein laufendes Backend pruefen (ohne Mikro/Hotkey)."""
    wav = pcm_zu_wav_bytes(b"\x00\x00" * 8000, cfg["samplerate"])  # 0.5s Stille
    print(f"Sende Test-Audio an {cfg['backend_url']} (Modus {cfg.get('modus')})...")
    try:
        text = sende_ans_backend(wav, cfg)
        print(f"Antwort: {text!r}")
        return 0 if text else 1
    except Exception as e:
        print(f"FEHLER: {e}")
        return 1


def miktest(cfg: dict, sekunden: int = 5) -> int:
    """Sichtbarer Mikrofon-Test: Countdown, aufnehmen, ans Backend, Text anzeigen."""
    import sounddevice as sd

    try:
        geraet = sd.query_devices(kind="input")["name"]
    except Exception:
        geraet = "Standard"
    print("=" * 50, flush=True)
    print(f"Mikrofon: {geraet}", flush=True)
    print(f"Backend:  {cfg['backend_url']}  (Modus {cfg.get('modus')})", flush=True)
    print("=" * 50, flush=True)

    auf = Aufnahme(cfg["samplerate"])
    print("Gleich geht's los - halte einen kurzen Satz bereit.", flush=True)
    time.sleep(1.0)
    try:
        auf.start()
    except Exception as e:
        print(f"\nMIKROFON-FEHLER: {e}", flush=True)
        return 1
    print(f"\n>>> SPRICH JETZT ({sekunden} Sekunden) <<<", flush=True)
    for s in range(sekunden, 0, -1):
        print(f"    {s} ...", flush=True)
        time.sleep(1.0)
    wav = pcm_zu_wav_bytes(auf.stop(), cfg["samplerate"])
    print(f"\nAufgenommen: {len(wav)} Bytes. Schicke ans Backend...", flush=True)
    try:
        text = sende_ans_backend(wav, cfg)
    except Exception as e:
        print(f"FEHLER: {e}", flush=True)
        return 1
    print("\n" + "=" * 50, flush=True)
    print("ERKANNTER TEXT:", flush=True)
    print(f"  {text!r}", flush=True)
    print("=" * 50, flush=True)
    try:
        import pyperclip

        pyperclip.copy(text)
        print("(Text liegt jetzt in der Zwischenablage - mit Strg+V einfuegbar)", flush=True)
    except Exception:
        pass
    return 0 if text.strip() else 1


def main() -> int:
    cfg = lade_config()
    if "--selftest" in sys.argv:
        return selftest(cfg)
    if "--miktest" in sys.argv:
        return miktest(cfg)
    if "--einstellungen" in sys.argv:
        return einstellungs_fenster(cfg)
    run_tray(cfg)
    return 0


if __name__ == "__main__":
    sys.exit(main())
