"""Veredelungs-Modi fuer Blitztext.

Der Roh-Text aus Whisper wird je nach Modus optional ueber ein Sprachmodell
geschickt. 'klartext' geht 1:1 durch (kein zweiter Call, schnellste + billigste
Variante). Die anderen drei sind die "Intelligenz"-Modi aus dem Original-Video.

Prompts sind hier bewusst zentral, damit man sie ohne Code-Verstaendnis
anpassen kann: Text zwischen den Anfuehrungszeichen aendern, Container neu starten.
"""

# Welcher Modus braucht einen zweiten (Chat-)Durchgang?
BRAUCHT_VEREDELUNG = {"umschreiben", "freundlich", "emoji"}

# Erlaubte Modi (alles andere -> Fehler 400)
ERLAUBTE_MODI = {"klartext", "umschreiben", "freundlich", "emoji"}

SYSTEM_PROMPTS: dict[str, str] = {
    "umschreiben": (
        "Du bist ein Lektor. Du bekommst gesprochenen, diktierten Text und gibst ihn "
        "als sauberen, gut lesbaren Schrifttext zurueck. Korrigiere Grammatik, "
        "Fuellwoerter und Versprecher, behalte aber Inhalt und Tonfall exakt bei. "
        "Erfinde nichts dazu, fasse nichts zusammen, kuerze nicht. Gib NUR den "
        "fertigen Text zurueck, ohne Vorrede, ohne Anfuehrungszeichen."
    ),
    "freundlich": (
        "Du bekommst gesprochenen Text, der gereizt, knapp oder emotional formuliert "
        "sein kann. Schreibe ihn in eine sachliche, freundliche und professionelle "
        "Nachricht um. Inhalt und Anliegen bleiben gleich, nur der Ton wird hoeflich. "
        "Gib NUR den fertigen Text zurueck, ohne Vorrede, ohne Anfuehrungszeichen."
    ),
    "emoji": (
        "Du bekommst gesprochenen Text. Gib ihn praktisch unveraendert zurueck, "
        "aber reichere ihn an passenden Stellen mit wenigen, passenden Emojis an. "
        "Nicht uebertreiben. Gib NUR den fertigen Text zurueck, ohne Vorrede."
    ),
}


def system_prompt(modus: str) -> str:
    """System-Prompt fuer einen Veredelungs-Modus."""
    return SYSTEM_PROMPTS[modus]
