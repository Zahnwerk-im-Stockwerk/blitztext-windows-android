package de.zahnwerk.blitztext

import android.content.Context
import android.content.SharedPreferences

/** Lokale App-Einstellungen. Das Passwort bleibt im app-privaten Speicher des Handys. */
object Einstellungen {

    const val STANDARD_URL = "https://DEIN-VPS.DEIN-TAILNET.ts.net:8099"
    const val STANDARD_BEGRIFFE = ""
    val ERLAUBTE_MODI = listOf("klartext", "umschreiben", "freundlich", "emoji")

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("blitztext", Context.MODE_PRIVATE)

    fun backendUrl(ctx: Context): String =
        prefs(ctx).getString("backend_url", STANDARD_URL)?.trimEnd('/') ?: STANDARD_URL

    fun appPasswort(ctx: Context): String =
        prefs(ctx).getString("app_passwort", "") ?: ""

    fun modus(ctx: Context): String =
        prefs(ctx).getString("modus", "klartext") ?: "klartext"

    fun begriffe(ctx: Context): String =
        prefs(ctx).getString("begriffe", STANDARD_BEGRIFFE) ?: STANDARD_BEGRIFFE

    fun speichern(ctx: Context, url: String, passwort: String, modus: String, begriffe: String) {
        prefs(ctx).edit()
            .putString("backend_url", url.trim().trimEnd('/'))
            .putString("app_passwort", passwort)
            .putString("modus", if (modus in ERLAUBTE_MODI) modus else "klartext")
            .putString("begriffe", begriffe.trim())
            .apply()
    }

    fun modusSpeichern(ctx: Context, modus: String) {
        if (modus in ERLAUBTE_MODI) prefs(ctx).edit().putString("modus", modus).apply()
    }

    /** Privat-Modus: alles bleibt auf dem eigenen Server, kein OpenAI. */
    fun privat(ctx: Context): Boolean = prefs(ctx).getBoolean("privat", false)

    fun privatSpeichern(ctx: Context, an: Boolean) {
        prefs(ctx).edit().putBoolean("privat", an).apply()
    }

    fun knopfPosition(ctx: Context): Pair<Int, Int> {
        val p = prefs(ctx)
        return Pair(p.getInt("knopf_x", 30), p.getInt("knopf_y", 400))
    }

    fun knopfPositionSpeichern(ctx: Context, x: Int, y: Int) {
        prefs(ctx).edit().putInt("knopf_x", x).putInt("knopf_y", y).apply()
    }
}
