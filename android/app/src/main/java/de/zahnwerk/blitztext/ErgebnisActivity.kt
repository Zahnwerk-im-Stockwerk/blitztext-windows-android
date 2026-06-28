package de.zahnwerk.blitztext

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.MediaStore
import android.text.Html
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ergebnis-Fenster: oeffnet sich nach einem Diktat, wenn KEIN Textfeld fokussiert war
 * (statt des frueheren Zwischenablage-Hinweises). Text ansehen, nachbessern,
 * kopieren, teilen, als Datei speichern oder drucken.
 */
class ErgebnisActivity : Activity() {

    companion object {
        const val EXTRA_TEXT = "text"
        const val EXTRA_PRIVAT = "privat"

        /** true solange das Fenster sichtbar ist — der Dienst erkennt daran,
         *  ob Android den Fenster-Start stillschweigend verworfen hat. */
        @Volatile var sichtbar = false
    }

    override fun onResume() { super.onResume(); sichtbar = true }
    override fun onPause() { sichtbar = false; super.onPause() }

    private lateinit var feldText: EditText
    private var druckWebView: WebView? = null // Referenz halten bis der Druckauftrag erzeugt ist

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ergebnis)

        logoRundAusstanzen(findViewById(R.id.bild_logo))

        feldText = findViewById(R.id.feld_text)
        feldText.setText(intent.getStringExtra(EXTRA_TEXT) ?: "")

        findViewById<TextView>(R.id.text_zeit).text =
            SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(Date())
        if (intent.getBooleanExtra(EXTRA_PRIVAT, false)) {
            // Gruenes Badge: dieses Diktat lief komplett auf dem eigenen Server
            findViewById<TextView>(R.id.badge_privat).visibility = View.VISIBLE
        }

        findViewById<Button>(R.id.knopf_kopieren).setOnClickListener {
            val ablage = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            ablage.setPrimaryClip(ClipData.newPlainText("BlitzText", feldText.text.toString()))
            Toast.makeText(this, "Kopiert", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.knopf_teilen).setOnClickListener {
            val teilen = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, feldText.text.toString())
            }
            startActivity(Intent.createChooser(teilen, "Diktat teilen"))
        }

        findViewById<Button>(R.id.knopf_datei).setOnClickListener { speichereDatei() }
        findViewById<Button>(R.id.knopf_drucken).setOnClickListener { drucke() }
    }

    /** Weiteres Diktat, waehrend das Fenster offen ist: unten anhaengen statt ueberschreiben. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val neu = intent.getStringExtra(EXTRA_TEXT) ?: return
        val bisher = feldText.text.toString()
        feldText.setText(if (bisher.isBlank()) neu else "$bisher\n\n$neu")
        feldText.setSelection(feldText.text.length)
        findViewById<TextView>(R.id.text_zeit).text =
            SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(Date())
        if (intent.getBooleanExtra(EXTRA_PRIVAT, false)) {
            findViewById<TextView>(R.id.badge_privat).visibility = View.VISIBLE
        }
    }

    /** Speichert den aktuellen Text als .txt nach Dokumente/BlitzText (Files-App sichtbar). */
    private fun speichereDatei() {
        val name = "diktat-" + SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.GERMANY).format(Date()) + ".txt"
        try {
            val werte = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Documents/BlitzText")
            }
            val uri = contentResolver.insert(MediaStore.Files.getContentUri("external"), werte)
                ?: throw IllegalStateException("kein Speicherort")
            contentResolver.openOutputStream(uri)?.use {
                it.write(feldText.text.toString().toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(this, "Gespeichert: Dokumente/BlitzText/$name", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Speichern fehlgeschlagen: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Druckt ueber den Android-Druckdialog (WLAN-Drucker oder "Als PDF speichern"). */
    private fun drucke() {
        val web = WebView(this)
        druckWebView = web
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                val manager = getSystemService(Context.PRINT_SERVICE) as PrintManager
                manager.print(
                    "BlitzText-Diktat",
                    view.createPrintDocumentAdapter("BlitzText-Diktat"),
                    PrintAttributes.Builder().build()
                )
                druckWebView = null
            }
        }
        val text = Html.escapeHtml(feldText.text.toString()).replace("\n", "<br>")
        val html = "<html><body style=\"font-family:sans-serif;font-size:14px;\">$text</body></html>"
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }
}
