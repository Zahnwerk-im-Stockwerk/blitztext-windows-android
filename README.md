# BlitzText für Windows + Android

Sprache zu Text "in jede App": Knopf halten, sprechen, loslassen — der Text steht da,
wo der Cursor ist. Am Windows-PC per Tastenkürzel, am Handy per schwebendem Mikro-Knopf.

## Die Geschichte dahinter

Das hier ist ein **eigenständiger Nachbau** von Magnussens BlitzText
([github.com/cmagnussen/blitztext-app](https://github.com/cmagnussen/blitztext-app), MIT).
Das Original ist macOS-only (Swift + WhisperKit) und nicht portierbar.

Gebaut hat diesen Nachbau **Wolfgang Madlener, Inhaber des Dentallabors "Zahnwerk im Stockwerk"** —
kein Entwickler, sondern jemand, der ein Werkzeug für den Laboralltag wollte: Diktieren am
**Windows-PC für die Mitarbeiter** und am **Handy für unterwegs**, mit einem gemeinsamen
Dienst dahinter. Entstanden mit Claude Code, in Python + Kotlin neu aufgebaut.

## Was hier anders ist (auch als bei den meisten anderen Forks)

- **Windows + Android** statt nur Mac — die mobile Seite gibt es als Android-App (Kotlin),
  der Desktop-Client läuft auf Windows (Python).
- **Ein gemeinsames Backend auf einem dauerhaft laufenden Server (VPS)**, das sich alle
  Geräte teilen — von überall privat erreichbar (per Tailscale), nicht nur lokal auf einem
  einzelnen Rechner. Das ist der eigentliche Unterschied: ein Always-on-Dienst für mehrere
  Geräte statt einer reinen Desktop-App. In der Praxis: das Handy diktiert von unterwegs
  übers Backend, mehrere Windows-PCs im Labor diktieren übers selbe Backend.
- **Privat-Modus**: Spracherkennung *und* Textveredelung laufen dann komplett auf dem
  **eigenen** Backend (faster-whisper + Ollama) statt bei OpenAI — kein Cloud-KI-Dienst,
  kein Datenabfluss. Gedacht für sensible/berufliche Diktate (im Dentallabor z.B. mit
  Patientenbezug). Wer maximale Kontrolle will, stellt das Backend ins eigene Netz statt auf
  einen externen VPS — dann verlassen die Daten das Haus nie.
- **Drucken direkt aus dem Diktat**: am Handy öffnet sich nach dem Sprechen ein
  Ergebnis-Fenster — ansehen, nachbessern, kopieren, teilen, als Datei speichern oder
  **direkt auf den Netzwerkdrucker drucken**.

Danke an Magnussen für das Original und die Idee.

## Aufbau

```
├── backend/          FastAPI-Dienst (läuft auf dem VPS, hält den OpenAI-Schlüssel)
├── windows-client/   Tray-App mit Hotkey (läuft auf PC/Laptop)
└── android/          Android-App: schwebender Mikro-Knopf (APK baut GitHub Actions)
```

Drei Teile, ein gemeinsames Backend:
- **Backend** nimmt Audio, lässt es transkribieren (+ optional veredeln), gibt Text zurück.
  Der OpenAI-Schlüssel liegt **nur hier**. Schutz: ein gemeinsames App-Passwort.
- **Windows-Client** und **Android-App** nehmen Audio auf und holen den Text vom Backend.

Beide Clients in schlichter dunkler "Zahnwerk"-Optik (Nachtblau + Gold). Bewusst **keine
externen Android-Bibliotheken** — nur Android-Framework + Kotlin-Standardbibliothek.

---

## Schritt 1 — OpenAI-Konto (~5 Min)

Die Abrechnung läuft auf dich.

1. `platform.openai.com` öffnen — das ist das **Entwickler-Konto**, NICHT das normale
   ChatGPT (getrennte Anmeldung + Abrechnung). Login per Google möglich.
2. **Settings → Billing → Payment methods** → Kreditkarte hinterlegen.
3. **Billing** → einmal **Guthaben aufladen** (z.B. 10 EUR). Prepaid. Spracherkennung
   kostet ~0,5 Cent pro Minute — 10 EUR halten lange.
4. **Settings → API keys → Create new secret key** → Schlüssel **kopieren**
   (wird nur einmal angezeigt).

**Wichtig:** Den Schlüssel nirgends in Code/Chat kleben. Er kommt direkt auf den VPS in die
`.env` (Schritt 2). So bleibt er nur auf dem Server, nie im Code, Log oder auf GitHub.

---

## Schritt 2 — Backend auf dem VPS

Eigener Container. Auf dem VPS:

```bash
# Projektordner anlegen, z.B. ~/blitztext-backend, Dateien aus backend/ hineinkopieren
cd ~/blitztext-backend
cp .env.example .env
nano .env          # OPENAI_API_KEY und APP_PASSWORT eintragen, speichern
docker compose up -d --build
docker compose logs -f     # "Application startup complete" → läuft
```

Test direkt auf dem VPS:
```bash
curl http://127.0.0.1:8099/health        # → {"status":"ok",...}
```

Der Dienst lauscht nur auf `127.0.0.1:8099` des VPS — **nicht öffentlich**.

---

## Schritt 3 — Von überall erreichbar (Tailscale)

Damit Laptop/Handy den Dienst von überall privat erreichen, ohne offenen Port:

```bash
# auf dem VPS, einmalig
tailscale serve --bg 8099
tailscale serve status        # zeigt die https://<vps>.<tailnet>.ts.net-Adresse
```

Diese `https://...ts.net`-Adresse trägst du in die Clients als `backend_url` ein.
Voraussetzung: Tailscale-App auch auf Laptop/Handy, gleicher Account.

*Alternative ohne Tailscale:* Subdomain + Caddy mit automatischem HTTPS — dann ist der
Dienst öffentlich erreichbar und allein durchs App-Passwort geschützt.

---

## Schritt 4 — Windows-Client

Auf dem PC/Laptop im Ordner `windows-client/`:

1. **`Blitztext_starten.bat`** doppelklicken — richtet beim ersten Mal alles ein und startet.
2. Beim ersten Start wird `%APPDATA%\blitztext\config.json` angelegt. Dort eintragen:
   - `backend_url` = deine `https://...ts.net`-Adresse (oder `http://127.0.0.1:8099` zum lokalen Test)
   - `app_passwort` = dasselbe wie in der `.env` auf dem VPS
   - `modus` = `klartext` / `umschreiben` / `freundlich` / `emoji`
   - `hotkey` = Standard `ctrl+space`
3. Neu starten. **Hotkey halten → sprechen → loslassen** → Text wird eingefügt.
4. Dauerhaft: `Blitztext_autostart.vbs` in den Autostart legen (Win+R → `shell:startup`).

Bedienung ohne Datei-Editieren:
- **Tray-Icon** (unten rechts) → Rechtsklick → **Modus** direkt umschalten.
- Tray → **Einstellungen...** öffnet ein Fenster (Backend-Adresse, Passwort, Begriffe,
  Modus, "Backend prüfen"-Knopf). Der "Backend prüfen"-Test kostet nichts (leeres Audio).
- Änderungen gelten ab dem nächsten Diktat, kein Neustart nötig.

---

## Schritt 5 — Android-App

Schwebender Mikro-Knopf, der in **jeder** App diktiert: Knopf **halten** (Ring wird rot),
sprechen, **loslassen** → Text erscheint im aktiven Eingabefeld an der Cursor-Position.
Knopf **ziehen** verschiebt ihn. War kein Textfeld fokussiert, öffnet sich das
**Ergebnis-Fenster** (ansehen, nachbessern, kopieren, teilen, speichern, **drucken**).

**Bauen (passiert in der GitHub-Cloud, nichts auf deinem PC — kein Android-SDK nötig):**
1. Der Code liegt in `android/`. Bei jedem Push mit Änderungen dort baut der Workflow
   `.github/workflows/android-apk.yml` automatisch die APK.
2. Fertige APK: GitHub → **Actions** → Lauf "BlitzText Android APK" → Artefakt
   `blitztext-apk` herunterladen (enthält `app-debug.apk`).

**Aufs Handy bringen:**
1. **Tailscale-App** aus dem Play Store, mit demselben Konto wie der VPS anmelden, einschalten.
2. Die `app-debug.apk` aufs Handy (USB-Kabel oder sich selbst mailen) und antippen.
   Einmalig "Aus dieser Quelle installieren" erlauben (Debug-Signatur, eigene App, kein Play Store).
3. App öffnen → App-Passwort eintragen → **Selbsttest** drücken (muss grün werden,
   kostet nichts).
4. Die drei Berechtigungen erteilen (die App zeigt zu jeder den "Erteilen"-Knopf):
   - **Mikrofon** (Aufnahme)
   - **Über anderen Apps anzeigen** (der schwebende Knopf)
   - **Bedienungshilfe** "BlitzText: Text einfügen" aktivieren (setzt den Text ins Feld)
5. **Knopf starten** → in WhatsApp/Notizen testen: halten, sprechen, loslassen.

Ohne aktivierte Bedienungshilfe landet der Text ersatzweise in der Zwischenablage
(lange tippen → Einfügen).

---

## Eigene Signatur für Updates (optional)

Dieses Repo baut die APK **ohne festen Signatur-Schlüssel** — `assembleDebug` nutzt den
automatischen Android-Debug-Schlüssel. Das reicht, um die App selbst zu bauen und zu
installieren. Nachteil: Eine neue APK lässt sich nur dann *über* eine alte installieren,
wenn beide denselben Schlüssel haben — sonst muss man vorher deinstallieren.

Wer durchgängige Updates ohne Deinstallieren will, legt einen **eigenen** Keystore an und
speist ihn über **GitHub-Secrets** in den Workflow ein (z.B. den Keystore base64-kodiert
als Secret, Passwörter als weitere Secrets, im Workflow vor dem Build dekodieren und in
`android/app/build.gradle.kts` als `signingConfig` setzen). Der private Schlüssel gehört
**nie** ins Repo — `*.keystore` ist in `.gitignore` bewusst gesperrt.

---

## App-Passwort ändern (jederzeit)

Auf dem VPS in `~/blitztext-backend/.env` die Zeile `APP_PASSWORT=` ändern, dann:
```bash
docker compose restart
```
Danach in jedem Client (Windows-`config.json`, Android-Einstellungen) das neue Passwort eintragen.

---

## Datenschutz + Privat-Modus

**Normalweg:** Audio geht über den VPS an **OpenAI (Cloud)**. Für allgemeine Texte
(E-Mails, Notizen) gedacht.

**Privat-Schalter** (Handy: Schalter im Einstellungs-Bildschirm, Ring um den Knopf wird
grün; PC: Haken im Tray-Menü oder Einstellungsfenster):
- Spracherkennung macht ein **eigenes Whisper im Container** (faster-whisper)
- Veredelung macht ein **eigenes Sprachmodell** (Ollama, qwen2.5:3b) im Nachbar-Container
- **OpenAI wird nicht angerufen**, und im Privat-Zweig loggt das Backend keinerlei Textinhalt
- Kombinierbar mit allen vier Modi (z.B. Privat + Umschreiben)
- Preis der Privatheit: etwas langsamer, Veredelung etwas schwächer als OpenAI

**Erzwungener Privat-Modus (für geteilte Geräte):** Mit der Backend-Einstellung
`PRIVAT_ERZWINGEN=1` läuft *jedes* Diktat lokal — egal, was am Client eingestellt ist.
Gedacht für ein lokales Backend, das z.B. mehrere Tablets im Betrieb bedient, die
garantiert nie über die Cloud gehen dürfen (im Dentallabor mit Patientenbezug).

Damit dürfen auch Diktate mit sensiblem Bezug gesprochen werden — sie bleiben auf dem
eigenen Server. Dieser Server muss **kein externer VPS** sein: Er kann genauso ein Rechner
im eigenen Labor sein, dann läuft alles nur im lokalen Netz (WLAN) und verlässt das Haus
nie. **Wenn ein externer VPS genutzt und beruflich gearbeitet wird, zwei einmalige
Hausaufgaben:** (1) AVV mit dem VPS-Anbieter abschließen, (2) prüfen, dass der VPS in einem
EU-Rechenzentrum liegt.

---

## Modi

| Modus | Wirkung |
|---|---|
| `klartext` | 1:1, schnellste + billigste Variante (nur Transkription) |
| `umschreiben` | sauberer, gut lesbarer Schrifttext (Füllwörter/Versprecher raus) |
| `freundlich` | gereizt Gesprochenes wird höflich + sachlich |
| `emoji` | Text mit passenden Emojis angereichert |

Prompts der Veredelungs-Modi stehen in `backend/modi.py` und sind frei anpassbar.

---

## Lizenz

MIT (wie das Original). Siehe [LICENSE](LICENSE). Dank an
[Magnussen](https://github.com/cmagnussen/blitztext-app) für die ursprüngliche Idee.
