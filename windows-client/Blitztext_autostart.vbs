' Startet Blitztext unsichtbar im Hintergrund (fuer den Autostart-Ordner).
' Voraussetzung: Blitztext_starten.bat wurde einmal ausgefuehrt (.venv existiert).
' Einrichten: Win+R -> shell:startup -> Verknuepfung zu dieser .vbs ablegen.
Set sh = CreateObject("WScript.Shell")
scriptDir = Left(WScript.ScriptFullName, InStrRev(WScript.ScriptFullName, "\"))
sh.CurrentDirectory = scriptDir
sh.Run "cmd /c "".venv\Scripts\python.exe"" blitz_tray.py >> blitz.log 2>&1", 0, False
