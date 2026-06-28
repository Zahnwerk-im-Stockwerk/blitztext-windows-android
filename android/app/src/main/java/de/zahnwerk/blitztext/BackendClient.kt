package de.zahnwerk.blitztext

import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Spricht das BlitzText-Backend auf dem VPS an, exakt wie der Windows-Client:
 * POST /transkribieren, Multipart (audio, modus, begriffe), Bearer-Passwort.
 */
object BackendClient {

    class BackendFehler(meldung: String) : IOException(meldung)

    /** Schickt die WAV-Aufnahme hin, bekommt den (veredelten) Text zurueck. */
    fun transkribiere(
        basisUrl: String,
        passwort: String,
        wav: ByteArray,
        modus: String,
        begriffe: String,
        privat: Boolean,
    ): String {
        val antwort = multipartPost(basisUrl, passwort, wav, modus, begriffe, privat)
        return antwort.optString("text", "")
    }

    /**
     * Selbsttest ohne OpenAI-Kosten: erst /health (erreichbar?), dann /transkribieren
     * mit leerem Audio. Antwort 400 "Leeres Audio" bedeutet: Passwort stimmt.
     */
    fun selbsttest(basisUrl: String, passwort: String): String {
        // Schritt 1: erreichbar?
        val healthVerbindung = URL("$basisUrl/health").openConnection() as HttpURLConnection
        try {
            healthVerbindung.connectTimeout = 8000
            healthVerbindung.readTimeout = 8000
            if (healthVerbindung.responseCode != 200) {
                return "Backend antwortet, aber /health liefert ${healthVerbindung.responseCode}"
            }
        } catch (e: Exception) {
            return "Backend nicht erreichbar: ${e.message ?: e.javaClass.simpleName}. Ist Tailscale auf dem Handy an?"
        } finally {
            healthVerbindung.disconnect()
        }
        // Schritt 2: Passwort pruefen (leeres Audio loest keinen OpenAI-Aufruf aus)
        return try {
            multipartPost(basisUrl, passwort, ByteArray(0), "klartext", "", false)
            "Verbindung und Passwort in Ordnung" // sollte nicht passieren (leeres Audio)
        } catch (e: BackendFehler) {
            when {
                e.message?.contains("401") == true -> "Backend erreichbar, aber das App-Passwort stimmt nicht"
                e.message?.contains("Leeres Audio") == true -> "Alles in Ordnung: Backend erreichbar, Passwort stimmt"
                else -> "Backend erreichbar, unerwartete Antwort: ${e.message}"
            }
        } catch (e: Exception) {
            "Fehler beim Passwort-Test: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun multipartPost(
        basisUrl: String,
        passwort: String,
        wav: ByteArray,
        modus: String,
        begriffe: String,
        privat: Boolean,
    ): JSONObject {
        val grenze = "----blitztext${System.currentTimeMillis()}"
        val verbindung = URL("$basisUrl/transkribieren").openConnection() as HttpURLConnection
        try {
            verbindung.requestMethod = "POST"
            verbindung.doOutput = true
            verbindung.connectTimeout = 10000
            verbindung.readTimeout = 120000
            verbindung.setRequestProperty("Authorization", "Bearer $passwort")
            verbindung.setRequestProperty("Content-Type", "multipart/form-data; boundary=$grenze")

            verbindung.outputStream.use { out ->
                schreibeFeld(out, grenze, "modus", modus)
                schreibeFeld(out, grenze, "begriffe", begriffe)
                schreibeFeld(out, grenze, "privat", if (privat) "1" else "0")
                schreibeDatei(out, grenze, "audio", "aufnahme.wav", wav)
                out.write("--$grenze--\r\n".toByteArray(Charsets.UTF_8))
            }

            val code = verbindung.responseCode
            val koerper = (if (code in 200..299) verbindung.inputStream else verbindung.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

            if (code !in 200..299) {
                val detail = try { JSONObject(koerper).optString("detail", koerper) } catch (_: Exception) { koerper }
                throw BackendFehler("HTTP $code: ${detail.take(300)}")
            }
            return JSONObject(koerper)
        } finally {
            verbindung.disconnect()
        }
    }

    private fun schreibeFeld(out: OutputStream, grenze: String, name: String, wert: String) {
        val teil = "--$grenze\r\n" +
            "Content-Disposition: form-data; name=\"$name\"\r\n\r\n" +
            "$wert\r\n"
        out.write(teil.toByteArray(Charsets.UTF_8))
    }

    private fun schreibeDatei(out: OutputStream, grenze: String, name: String, dateiname: String, daten: ByteArray) {
        val kopf = "--$grenze\r\n" +
            "Content-Disposition: form-data; name=\"$name\"; filename=\"$dateiname\"\r\n" +
            "Content-Type: audio/wav\r\n\r\n"
        out.write(kopf.toByteArray(Charsets.UTF_8))
        out.write(daten)
        out.write("\r\n".toByteArray(Charsets.UTF_8))
    }
}
