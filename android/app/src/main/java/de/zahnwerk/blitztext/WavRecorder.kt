package de.zahnwerk.blitztext

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import kotlin.math.abs

/**
 * Nimmt vom Mikrofon auf und liefert eine fertige WAV-Datei als Bytes.
 * Gleiches Format wie der Windows-Client: 16 kHz, mono, 16 Bit PCM.
 */
class WavRecorder {

    companion object {
        const val SAMPLERATE = 16000   // Zielrate fuers Backend (mono, 16 Bit) - wie Windows-Client
        // 16 kHz ist als AUFNAHME-Rate NICHT auf allen Geraeten garantiert (manche Tablets
        // liefern dann Stille) - nur 44,1 kHz ist sicher. Daher mit einer geraeteseitig
        // unterstuetzten Rate aufnehmen (48 kHz bevorzugt = sauberes 3:1-Downsampling,
        // dann 44,1 kHz, 16 kHz nur als letzte Wahl) und auf SAMPLERATE herunterrechnen.
        private val KANDIDATEN = intArrayOf(48000, 44100, 16000)
        private const val KANAL = AudioFormat.CHANNEL_IN_MONO
        private const val FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var recorder: AudioRecord? = null
    private var leseThread: Thread? = null
    private val puffer = ByteArrayOutputStream()
    @Volatile private var laeuft = false
    private var aufnahmeRate = SAMPLERATE

    /** Spitzenpegel des letzten Blocks (0..32767), fuer die Live-Anzeige. */
    @Volatile var pegel: Int = 0
        private set

    /** Hoechster Pegel der gesamten Aufnahme, fuer die Stille-Erkennung. */
    @Volatile var maxGesamt: Int = 0
        private set

    /** Startet die Aufnahme. Mikrofon-Berechtigung muss vorher erteilt sein. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (laeuft) return true
        // Erste vom Geraet unterstuetzte Aufnahme-Rate aus der Kandidatenliste waehlen.
        var rec: AudioRecord? = null
        var minGroesse = 0
        for (rate in KANDIDATEN) {
            val groesse = AudioRecord.getMinBufferSize(rate, KANAL, FORMAT)
            if (groesse <= 0) continue
            val kandidat = try {
                AudioRecord(MediaRecorder.AudioSource.MIC, rate, KANAL, FORMAT, groesse * 4)
            } catch (_: Exception) { null }
            if (kandidat != null && kandidat.state == AudioRecord.STATE_INITIALIZED) {
                rec = kandidat
                minGroesse = groesse
                aufnahmeRate = rate
                break
            }
            kandidat?.release()
        }
        if (rec == null) return false
        puffer.reset()
        pegel = 0
        maxGesamt = 0
        val r = rec
        recorder = r
        laeuft = true
        r.startRecording()
        leseThread = Thread {
            val block = ByteArray(minGroesse)
            while (laeuft) {
                val gelesen = r.read(block, 0, block.size)
                if (gelesen > 0) {
                    // Spitzenpegel des Blocks aus den 16-Bit-Samples (little-endian)
                    var spitze = 0
                    var i = 0
                    while (i + 1 < gelesen) {
                        val wert = ((block[i + 1].toInt() shl 8) or (block[i].toInt() and 0xff)).toShort().toInt()
                        val betrag = abs(wert)
                        if (betrag > spitze) spitze = betrag
                        i += 2
                    }
                    pegel = spitze
                    if (spitze > maxGesamt) maxGesamt = spitze
                    synchronized(puffer) { puffer.write(block, 0, gelesen) }
                }
            }
        }.also { it.start() }
        return true
    }

    /** Beendet die Aufnahme und liefert WAV-Bytes, oder null wenn (fast) nichts aufgenommen wurde. */
    fun stop(): ByteArray? {
        if (!laeuft) return null
        laeuft = false
        leseThread?.join(1000)
        leseThread = null
        recorder?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        recorder = null
        val roh = synchronized(puffer) { puffer.toByteArray() }
        // Auf die Backend-Rate (16 kHz) herunterrechnen, falls hoeher aufgenommen wurde.
        val pcm = if (aufnahmeRate == SAMPLERATE) roh else downsample(roh, aufnahmeRate, SAMPLERATE)
        // Unter ~0,3 Sekunden: versehentlicher Tipper, nicht senden
        if (pcm.size < SAMPLERATE * 2 * 3 / 10) return null
        return wavMitHeader(pcm)
    }

    /** Bricht ab und verwirft alles Aufgenommene. */
    fun abbrechen() {
        if (!laeuft) return
        laeuft = false
        leseThread?.join(1000)
        leseThread = null
        recorder?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        recorder = null
        synchronized(puffer) { puffer.reset() }
    }

    val nimmtAuf: Boolean get() = laeuft

    /** Lineares Downsampling von mono 16-Bit-PCM (vonRate -> zuRate). */
    private fun downsample(pcm: ByteArray, vonRate: Int, zuRate: Int): ByteArray {
        if (vonRate == zuRate || pcm.size < 4) return pcm
        val quelleN = pcm.size / 2
        fun sample(i: Int): Int =
            ((pcm[i * 2 + 1].toInt() shl 8) or (pcm[i * 2].toInt() and 0xff)).toShort().toInt()
        val verhaeltnis = vonRate.toDouble() / zuRate
        val zielN = (quelleN / verhaeltnis).toInt()
        val out = ByteArray(zielN * 2)
        var i = 0
        while (i < zielN) {
            val pos = i * verhaeltnis
            val idx = pos.toInt()
            val frac = pos - idx
            val s0 = sample(idx)
            val s1 = if (idx + 1 < quelleN) sample(idx + 1) else s0
            var v = (s0 + (s1 - s0) * frac).toInt()
            if (v > 32767) v = 32767 else if (v < -32768) v = -32768
            out[i * 2] = (v and 0xff).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
            i++
        }
        return out
    }

    private fun wavMitHeader(pcm: ByteArray): ByteArray {
        val byteRate = SAMPLERATE * 2 // mono, 16 Bit
        val datenLaenge = pcm.size
        val gesamt = 36 + datenLaenge
        val kopf = ByteArray(44)

        fun schreibe(pos: Int, text: String) { text.toByteArray(Charsets.US_ASCII).copyInto(kopf, pos) }
        fun schreibe32(pos: Int, wert: Int) {
            kopf[pos] = (wert and 0xff).toByte()
            kopf[pos + 1] = ((wert shr 8) and 0xff).toByte()
            kopf[pos + 2] = ((wert shr 16) and 0xff).toByte()
            kopf[pos + 3] = ((wert shr 24) and 0xff).toByte()
        }
        fun schreibe16(pos: Int, wert: Int) {
            kopf[pos] = (wert and 0xff).toByte()
            kopf[pos + 1] = ((wert shr 8) and 0xff).toByte()
        }

        schreibe(0, "RIFF"); schreibe32(4, gesamt); schreibe(8, "WAVE")
        schreibe(12, "fmt "); schreibe32(16, 16); schreibe16(20, 1) // PCM
        schreibe16(22, 1) // mono
        schreibe32(24, SAMPLERATE); schreibe32(28, byteRate)
        schreibe16(32, 2); schreibe16(34, 16)
        schreibe(36, "data"); schreibe32(40, datenLaenge)

        return kopf + pcm
    }
}
