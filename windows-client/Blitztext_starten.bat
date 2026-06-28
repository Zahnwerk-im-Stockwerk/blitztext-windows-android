@echo off
rem Blitztext Windows-Client: einmal doppelklicken richtet alles ein und startet.
cd /d "%~dp0"

rem Python finden (py-Launcher bevorzugt, sonst python aus PATH)
where py >nul 2>nul && (set PY=py) || (set PY=python)

if not exist .venv (
  echo Richte Umgebung ein (einmalig)...
  %PY% -m venv .venv
)

.venv\Scripts\python.exe -m pip install --quiet -r requirements.txt
.venv\Scripts\python.exe blitz_tray.py
pause
