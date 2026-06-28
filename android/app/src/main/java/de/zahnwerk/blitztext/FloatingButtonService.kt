package de.zahnwerk.blitztext

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import kotlin.math.abs

/**
 * Der schwebende Mikro-Knopf: goldener Kreis mit Zahn-Logo ueber allen Apps.
 * Halten = aufnehmen (roter Ring), loslassen = senden, ziehen = verschieben.
 */
class FloatingButtonService : Service() {

    companion object {
        @Volatile var laeuft = false
        @Volatile var instanz: FloatingButtonService? = null
        private const val KANAL_ID = "blitztext_knopf"

        // Stille-Erkennung: unter diesem Spitzenpegel (von 32767) gilt die Aufnahme als stumm.
        // Startwert, ggf. nach Live-Test justieren (leises Sprechen liegt deutlich darueber).
        private const val STILLE_SCHWELLE = 300
        // Erst nach dieser Zeit warnen, damit ein langsam anlaufendes Mikro nicht falsch alarmiert
        private const val STILLE_WARTEZEIT_MS = 1200L
    }

    /** Ruhe-Ring neu einfaerben (z.B. nach Umlegen des Privat-Schalters). */
    fun ringAktualisieren() {
        if (!sendet && !recorder.nimmtAuf) zeigeZustand("normal")
    }

    private lateinit var fensterManager: WindowManager
    private var behaelter: FrameLayout? = null
    private lateinit var params: WindowManager.LayoutParams
    private val recorder = WavRecorder()
    private val hauptThread = Handler(Looper.getMainLooper())
    @Volatile private var sendet = false

    // Pegel-Fensterchen neben dem Daumen (der Ring selbst ist beim Halten verdeckt)
    private var pegelPanel: FrameLayout? = null
    private var pegelAnzeige: PegelAnzeige? = null
    private var aufnahmeStart = 0L
    private var stilleGewarnt = false
    private val pegelLoop = object : Runnable {
        override fun run() {
            val anzeige = pegelAnzeige ?: return
            anzeige.pegelHinzufuegen(recorder.pegel)
            if (System.currentTimeMillis() - aufnahmeStart > STILLE_WARTEZEIT_MS) {
                val stumm = recorder.maxGesamt < STILLE_SCHWELLE
                if (stumm && !stilleGewarnt) {
                    stilleGewarnt = true
                    anzeige.warnung = true
                    doppelVibration()
                }
                if (!stumm && anzeige.warnung) anzeige.warnung = false // Mikro ist doch angelaufen
            }
            hauptThread.postDelayed(this, 80)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instanz = this
        fensterManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Mikrofon-Berechtigung fehlt", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        starteImVordergrund()
        if (behaelter == null) baueKnopf()
        laeuft = true
        return START_STICKY
    }

    override fun onDestroy() {
        laeuft = false
        instanz = null
        recorder.abbrechen()
        versteckePegelPanel()
        behaelter?.let { try { fensterManager.removeView(it) } catch (_: Exception) {} }
        behaelter = null
        super.onDestroy()
    }

    // ── Pegel-Fensterchen (Aufnahmeindikator) ─────────────────────────────────

    /** Blendet das Pegel-Fensterchen neben dem Knopf ein, auf der daumen-freien Seite. */
    private fun zeigePegelPanel(fehler: Boolean = false) {
        versteckePegelPanel()
        val breite = dp(116)
        val hoehe = dp(44)
        val anzeige = PegelAnzeige(this)
        val panel = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(getColor(R.color.karte))
                setStroke(dp(1), getColor(R.color.gold_dunkel))
            }
            addView(anzeige, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
        val knopfGroesse = dp(64)
        val schirmBreite = resources.displayMetrics.widthPixels
        val knopfRechts = params.x + knopfGroesse / 2 > schirmBreite / 2
        val panelParams = WindowManager.LayoutParams(
            breite, hoehe,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (knopfRechts) params.x - breite - dp(8) else params.x + knopfGroesse + dp(8)
            y = params.y + (knopfGroesse - hoehe) / 2
        }
        try {
            fensterManager.addView(panel, panelParams)
        } catch (_: Exception) {
            return // kein Panel = kein Drama, Aufnahme laeuft trotzdem
        }
        pegelAnzeige = anzeige
        pegelPanel = panel
        if (fehler) {
            anzeige.warnung = true
        } else {
            hauptThread.postDelayed(pegelLoop, 80)
        }
    }

    /** Entfernt das Pegel-Fensterchen, optional verzoegert (damit eine Warnung lesbar bleibt). */
    private fun versteckePegelPanel(verzoegerungMs: Long = 0) {
        hauptThread.removeCallbacks(pegelLoop)
        val panel = pegelPanel ?: return
        pegelPanel = null
        pegelAnzeige = null
        if (verzoegerungMs <= 0) {
            try { fensterManager.removeView(panel) } catch (_: Exception) {}
        } else {
            hauptThread.postDelayed({
                try { fensterManager.removeView(panel) } catch (_: Exception) {}
            }, verzoegerungMs)
        }
    }

    /** Doppel-Vibration als fuehlbares Warnsignal (z.B. "kein Ton"). */
    private fun doppelVibration() {
        val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        try {
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 90, 90, 90), -1))
        } catch (_: Exception) {}
    }

    // ── Pflicht-Benachrichtigung fuer den Dauer-Dienst ────────────────────────

    private fun starteImVordergrund() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(KANAL_ID, "BlitzText Mikro-Knopf", NotificationManager.IMPORTANCE_LOW)
        )
        val oeffnen = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val benachrichtigung: Notification = Notification.Builder(this, KANAL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.benachrichtigung_titel))
            .setContentText(getString(R.string.benachrichtigung_text))
            .setContentIntent(oeffnen)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(1, benachrichtigung, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, benachrichtigung)
        }
    }

    // ── Knopf bauen und bedienen ──────────────────────────────────────────────

    private fun dp(wert: Int): Int = (wert * resources.displayMetrics.density).toInt()

    private fun ring(farbe: Int, dickeDp: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(getColor(R.color.nachtblau))
        setStroke(dp(dickeDp), farbe)
    }

    private fun zeigeZustand(zustand: String) {
        val box = behaelter ?: return
        when (zustand) {
            "aufnahme" -> { box.background = ring(getColor(R.color.rot), 4); box.alpha = 1f }
            "senden" -> { box.background = ring(getColor(R.color.gelb), 4); box.alpha = 0.6f }
            else -> {
                // Ruhe-Ring: gruen = Privat-Modus (eigener Server), gold = Normalweg
                val farbe = if (Einstellungen.privat(this)) R.color.gruen else R.color.gold
                box.background = ring(getColor(farbe), if (Einstellungen.privat(this)) 3 else 2)
                box.alpha = 1f
            }
        }
    }

    private fun baueKnopf() {
        val groesse = dp(64)
        val rand = dp(4)

        val bild = ImageView(this).apply {
            setImageResource(R.drawable.logo_zahnwerk)
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true
        }

        val box = FrameLayout(this)
        box.addView(bild, FrameLayout.LayoutParams(groesse - 2 * rand, groesse - 2 * rand).apply {
            gravity = Gravity.CENTER
        })
        behaelter = box
        zeigeZustand("normal")

        val (gespeichertX, gespeichertY) = Einstellungen.knopfPosition(this)
        params = WindowManager.LayoutParams(
            groesse, groesse,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = gespeichertX
            y = gespeichertY
        }

        var startRohX = 0f; var startRohY = 0f
        var startX = 0; var startY = 0
        var zieht = false
        val schwelle = dp(12)

        box.setOnTouchListener { sicht, ereignis ->
            when (ereignis.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (sendet) return@setOnTouchListener true
                    startRohX = ereignis.rawX; startRohY = ereignis.rawY
                    startX = params.x; startY = params.y
                    zieht = false
                    if (recorder.start()) {
                        aufnahmeStart = System.currentTimeMillis()
                        stilleGewarnt = false
                        zeigeZustand("aufnahme")
                        sicht.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        zeigePegelPanel()
                    } else {
                        Toast.makeText(this, "Mikrofon nicht verfuegbar", Toast.LENGTH_SHORT).show()
                        doppelVibration()
                        zeigePegelPanel(fehler = true)
                        versteckePegelPanel(1500)
                    }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ereignis.rawX - startRohX).toInt()
                    val dy = (ereignis.rawY - startRohY).toInt()
                    if (!zieht && (abs(dx) > schwelle || abs(dy) > schwelle)) {
                        zieht = true
                        recorder.abbrechen() // Bewegung = verschieben, nicht diktieren
                        versteckePegelPanel()
                        zeigeZustand("normal")
                    }
                    if (zieht) {
                        params.x = startX + dx
                        params.y = startY + dy
                        fensterManager.updateViewLayout(box, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (zieht) {
                        Einstellungen.knopfPositionSpeichern(this, params.x, params.y)
                    } else {
                        val stumm = recorder.maxGesamt < STILLE_SCHWELLE
                        val wav = recorder.stop()
                        if (wav == null) {
                            versteckePegelPanel()
                            zeigeZustand("normal")
                            Toast.makeText(this, "Zu kurz: Knopf halten, sprechen, loslassen", Toast.LENGTH_SHORT).show()
                        } else if (stumm) {
                            // Kein Sprachsignal: nicht senden, sondern klar warnen
                            pegelAnzeige?.warnung = true
                            versteckePegelPanel(1500)
                            zeigeZustand("normal")
                            doppelVibration()
                            Toast.makeText(this, "Kein Ton aufgenommen - nicht gesendet. Mikrofon pruefen", Toast.LENGTH_LONG).show()
                        } else {
                            versteckePegelPanel()
                            sende(wav)
                        }
                    }
                    true
                }
                else -> false
            }
        }

        fensterManager.addView(box, params)
    }

    // ── Senden + Einfuegen ────────────────────────────────────────────────────

    private fun sende(wav: ByteArray) {
        sendet = true
        zeigeZustand("senden")
        Thread {
            val ergebnis: String? = try {
                BackendClient.transkribiere(
                    Einstellungen.backendUrl(this),
                    Einstellungen.appPasswort(this),
                    wav,
                    Einstellungen.modus(this),
                    Einstellungen.begriffe(this),
                    Einstellungen.privat(this),
                )
            } catch (e: Exception) {
                hauptThread.post {
                    Toast.makeText(this, "BlitzText-Fehler: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
                }
                null
            }
            hauptThread.post {
                sendet = false
                zeigeZustand("normal")
                if (ergebnis != null) fuegeEin(ergebnis)
            }
        }.start()
    }

    private fun fuegeEin(text: String) {
        if (text.isBlank()) {
            Toast.makeText(this, "Nichts erkannt", Toast.LENGTH_SHORT).show()
            return
        }
        val dienst = EinfuegenService.instanz
        val eingefuegt = try { dienst?.fuegeTextEin(text) == true } catch (_: Exception) { false }
        if (!eingefuegt) {
            // Kein fokussiertes Textfeld: Zwischenablage als Sicherheitsnetz fuellen
            // und das Ergebnis-Fenster oeffnen (sehen, nachbessern, speichern, drucken)
            val ablage = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            ablage.setPrimaryClip(ClipData.newPlainText("BlitzText", text))
            val privat = Einstellungen.privat(this)
            val anzeigen = Intent(this, ErgebnisActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(ErgebnisActivity.EXTRA_TEXT, text)
                putExtra(ErgebnisActivity.EXTRA_PRIVAT, privat)
            }
            try { startActivity(anzeigen) } catch (_: Exception) {}
            // Android darf Hintergrund-Starts STILL verwerfen (keine Exception!).
            // Deshalb kurz danach pruefen: Fenster nicht sichtbar -> Benachrichtigung,
            // deren Antippen das Fenster garantiert oeffnet (Nutzer-Aktion).
            hauptThread.postDelayed({
                if (!ErgebnisActivity.sichtbar) zeigeDiktatBenachrichtigung(text, privat)
            }, 900)
        }
    }

    /** Fallback wenn das Ergebnis-Fenster nicht direkt oeffnen durfte. */
    private fun zeigeDiktatBenachrichtigung(text: String, privat: Boolean) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel("blitztext_diktat", "BlitzText Diktat fertig", NotificationManager.IMPORTANCE_HIGH)
        )
        val oeffnen = PendingIntent.getActivity(
            this, 2,
            Intent(this, ErgebnisActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(ErgebnisActivity.EXTRA_TEXT, text)
                putExtra(ErgebnisActivity.EXTRA_PRIVAT, privat)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val bau = Notification.Builder(this, "blitztext_diktat")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Diktat fertig")
            .setContentIntent(oeffnen)
            .setAutoCancel(true)
        if (privat) {
            // Patienten-Diktat: kein Inhalt in der Benachrichtigung (Sperrbildschirm!)
            bau.setContentText("Antippen zum Anzeigen")
            bau.setVisibility(Notification.VISIBILITY_PRIVATE)
        } else {
            bau.setContentText(text.take(120))
            bau.setStyle(Notification.BigTextStyle().bigText(text.take(400)))
        }
        manager.notify(2, bau.build())
    }
}
