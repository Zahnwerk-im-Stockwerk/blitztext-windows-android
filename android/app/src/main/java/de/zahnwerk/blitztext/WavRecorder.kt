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
        const val SAMPLERATE = 16000
        private const val KANAL = AudioFormat.CHANNEL_IN_MONO
        private const val FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var recorder: AudioRecord? = null
    private var leseThread: Thread? = null
    private val puffer = ByteArrayOutputStream()
    @Volatile private var laeuft = false

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
        val minGroesse = AudioRecord.getMinBufferSize(SAMPLERATE, KANAL, FORMAT)
        if (minGroesse <= 0) return false
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLERATE, KANAL, FORMAT, minGroesse * 4)
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        puffer.reset()
        pegel = 0
        maxGesamt = 0
        recorder = rec
        laeuft = true
        rec.startRecording()
        leseThread = Thread {
            val block = ByteArray(minGroesse)
            while (laeuft) {
                val gelesen = rec.read(block, 0, block.size)
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
        val pcm = synchronized(puffer) { puffer.toByteArray() }
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
