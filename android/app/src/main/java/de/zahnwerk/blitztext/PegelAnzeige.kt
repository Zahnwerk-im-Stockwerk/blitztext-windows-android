package de.zahnwerk.blitztext

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Inhalt des kleinen Pegel-Fensterchens neben dem Daumen (Wispr-Flow-Muster):
 * roter Aufnahme-Punkt links, daneben 7 "Dioden"-Balken, die beim Sprechen
 * hoch- und runterlaufen. Warn-Zustand zeigt stattdessen "Kein Ton".
 */
class PegelAnzeige(context: Context) : View(context) {

    companion object {
        private const val ANZAHL = 7
        // Pegel ab dem ein Balken voll ausschlaegt (normale Sprechstimme liegt darueber)
        private const val VOLL_PEGEL = 14000f
    }

    private val werte = IntArray(ANZAHL)

    /** true = "Kein Ton"-Warnung statt Balken anzeigen. */
    var warnung = false
        set(wert) { field = wert; invalidate() }

    private val balkenFarbe = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.gruen) }
    private val punktFarbe = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.rot) }
    private val warnText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.gelb)
        textSize = 12 * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    /** Naechsten Live-Pegel (0..32767) ins laufende Band schieben. */
    fun pegelHinzufuegen(pegel: Int) {
        System.arraycopy(werte, 1, werte, 0, ANZAHL - 1)
        werte[ANZAHL - 1] = pegel
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val b = width.toFloat()
        val h = height.toFloat()

        // Roter Aufnahme-Punkt links
        canvas.drawCircle(h * 0.32f, h / 2f, h * 0.11f, punktFarbe)

        if (warnung) {
            canvas.drawText("Kein Ton", (b + h * 0.55f) / 2f, h / 2f + warnText.textSize / 3f, warnText)
            return
        }

        // Dioden-Balken rechts vom Punkt: Hoehe folgt dem Pegel (Wurzel = lebendiger)
        val links = h * 0.60f
        val breite = (b - links - h * 0.2f) / (2 * ANZAHL - 1)
        for (i in 0 until ANZAHL) {
            val anteil = sqrt(min(1f, werte[i] / VOLL_PEGEL))
            val hoehe = (h * 0.72f) * (0.12f + 0.88f * anteil)
            val x = links + i * 2 * breite
            canvas.drawRoundRect(
                x, (h - hoehe) / 2f, x + breite, (h + hoehe) / 2f,
                breite / 2f, breite / 2f, balkenFarbe
            )
        }
    }
}
