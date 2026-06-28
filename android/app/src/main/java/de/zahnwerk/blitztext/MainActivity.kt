package de.zahnwerk.blitztext

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast

/** Einstellungs-Bildschirm: Verbindung, Modus, Berechtigungen, Knopf an/aus. */
class MainActivity : Activity() {

    private lateinit var feldUrl: EditText
    private lateinit var feldPasswort: EditText
    private lateinit var feldBegriffe: EditText
    private lateinit var gruppeModus: RadioGroup
    private lateinit var textTeststatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        feldUrl = findViewById(R.id.feld_url)
        feldPasswort = findViewById(R.id.feld_passwort)
        feldBegriffe = findViewById(R.id.feld_begriffe)
        gruppeModus = findViewById(R.id.gruppe_modus)
        textTeststatus = findViewById(R.id.text_teststatus)

        logoRundAusstanzen(findViewById<ImageView>(R.id.bild_logo))

        // Aufklapp-Koepfe: Verbindung komplett (Schutz vor versehentlichem Verstellen),
        // bei Privat + Schwebe-Knopf nur die Erklaertexte
        klappbar(R.id.kopf_verbindung, R.id.inhalt_verbindung, R.string.abschnitt_verbindung)
        klappbar(R.id.kopf_privat, R.id.text_privat_erklaerung, R.string.abschnitt_privat)
        klappbar(R.id.kopf_knopf, R.id.text_knopf_erklaerung, R.string.abschnitt_knopf)

        feldUrl.setText(Einstellungen.backendUrl(this))
        feldPasswort.setText(Einstellungen.appPasswort(this))
        feldBegriffe.setText(Einstellungen.begriffe(this))
        gruppeModus.check(modusZuId(Einstellungen.modus(this)))
        // Modus-Antippen gilt sofort, ohne extra "Speichern"
        gruppeModus.setOnCheckedChangeListener { _, id ->
            Einstellungen.modusSpeichern(this, idZuModus(id))
        }

        // Privat-Schalter gilt ebenfalls sofort
        val schalterPrivat = findViewById<android.widget.Switch>(R.id.schalter_privat)
        schalterPrivat.isChecked = Einstellungen.privat(this)
        schalterPrivat.setOnCheckedChangeListener { _, an ->
            Einstellungen.privatSpeichern(this, an)
            FloatingButtonService.instanz?.ringAktualisieren()
        }

        findViewById<Button>(R.id.knopf_speichern).setOnClickListener {
            speichern()
            Toast.makeText(this, "Gespeichert", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.knopf_selbsttest).setOnClickListener {
            speichern()
            textTeststatus.setTextColor(getColor(R.color.text_gedaempft))
            textTeststatus.text = "Teste Verbindung..."
            val url = Einstellungen.backendUrl(this)
            val pw = Einstellungen.appPasswort(this)
            Thread {
                val ergebnis = BackendClient.selbsttest(url, pw)
                runOnUiThread {
                    textTeststatus.text = ergebnis
                    val gut = ergebnis.startsWith("Alles in Ordnung")
                    textTeststatus.setTextColor(getColor(if (gut) R.color.gruen else R.color.rot))
                }
            }.start()
        }

        findViewById<Button>(R.id.knopf_mikrofon).setOnClickListener {
            val rechte = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) rechte.add(Manifest.permission.POST_NOTIFICATIONS)
            requestPermissions(rechte.toTypedArray(), 1)
        }

        findViewById<Button>(R.id.knopf_overlay).setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }

        findViewById<Button>(R.id.knopf_einfuegen).setOnClickListener {
            Toast.makeText(this, "In der Liste \"BlitzText: Text einfügen\" aktivieren", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<Button>(R.id.knopf_schwebeknopf).setOnClickListener {
            val dienst = Intent(this, FloatingButtonService::class.java)
            if (FloatingButtonService.laeuft) {
                stopService(dienst)
            } else {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Bitte zuerst Mikrofon erlauben", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                if (!Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "Bitte zuerst \"Über anderen Apps anzeigen\" erlauben", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                speichern()
                startForegroundService(dienst)
            }
            // kleiner Moment, bis der Dienst seinen Status gesetzt hat
            findViewById<Button>(R.id.knopf_schwebeknopf).postDelayed({ aktualisiereAnzeige() }, 400)
        }
    }

    override fun onResume() {
        super.onResume()
        aktualisiereAnzeige()
    }

    override fun onRequestPermissionsResult(code: Int, rechte: Array<out String>, ergebnisse: IntArray) {
        super.onRequestPermissionsResult(code, rechte, ergebnisse)
        aktualisiereAnzeige()
    }

    /** Macht einen Kartentitel zum Aufklapp-Kopf: Tippen zeigt/versteckt den Inhalt (Pfeil ▸/▾). */
    private fun klappbar(kopfId: Int, inhaltId: Int, titelRes: Int) {
        val kopf = findViewById<TextView>(kopfId)
        val inhalt = findViewById<View>(inhaltId)
        val titel = getString(titelRes)
        fun zeichne() {
            kopf.text = if (inhalt.visibility == View.VISIBLE) "$titel  ▾" else "$titel  ▸"
        }
        zeichne()
        kopf.setOnClickListener {
            inhalt.visibility = if (inhalt.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            zeichne()
        }
    }

    private fun speichern() {
        Einstellungen.speichern(
            this,
            feldUrl.text.toString(),
            feldPasswort.text.toString(),
            idZuModus(gruppeModus.checkedRadioButtonId),
            feldBegriffe.text.toString(),
        )
    }

    private fun aktualisiereAnzeige() {
        val mikrofonOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val overlayOk = Settings.canDrawOverlays(this)
        val einfuegenOk = EinfuegenService.aktiv

        zeigeRecht(R.id.status_mikrofon, R.id.knopf_mikrofon, getString(R.string.recht_mikrofon), mikrofonOk)
        zeigeRecht(R.id.status_overlay, R.id.knopf_overlay, getString(R.string.recht_overlay), overlayOk)
        zeigeRecht(R.id.status_einfuegen, R.id.knopf_einfuegen, getString(R.string.recht_einfuegen), einfuegenOk)

        findViewById<Button>(R.id.knopf_schwebeknopf).text =
            getString(if (FloatingButtonService.laeuft) R.string.knopf_stoppen else R.string.knopf_starten)
    }

    private fun zeigeRecht(statusId: Int, knopfId: Int, name: String, ok: Boolean) {
        val status = findViewById<TextView>(statusId)
        status.text = if (ok) "$name  ✓" else "$name  ✗"
        status.setTextColor(getColor(if (ok) R.color.gruen else R.color.rot))
        findViewById<Button>(knopfId).isEnabled = !ok
        findViewById<Button>(knopfId).alpha = if (ok) 0.4f else 1f
    }

    private fun modusZuId(modus: String): Int = when (modus) {
        "umschreiben" -> R.id.modus_umschreiben
        "freundlich" -> R.id.modus_freundlich
        "emoji" -> R.id.modus_emoji
        else -> R.id.modus_klartext
    }

    private fun idZuModus(id: Int): String = when (id) {
        R.id.modus_umschreiben -> "umschreiben"
        R.id.modus_freundlich -> "freundlich"
        R.id.modus_emoji -> "emoji"
        else -> "klartext"
    }
}
