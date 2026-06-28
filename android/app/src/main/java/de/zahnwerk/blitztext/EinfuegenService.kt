package de.zahnwerk.blitztext

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Bedienungshilfe-Dienst: setzt den diktierten Text in das gerade fokussierte
 * Eingabefeld, an der Cursor-Position. Reagiert auf keine Ereignisse und liest
 * nichts mit; er wird nur aktiv, wenn BlitzText Text einfuegen will.
 */
class EinfuegenService : AccessibilityService() {

    companion object {
        @Volatile var instanz: EinfuegenService? = null

        /** true wenn der Dienst in den Bedienungshilfen aktiviert ist */
        val aktiv: Boolean get() = instanz != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instanz = this
    }

    override fun onDestroy() {
        instanz = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // bewusst leer: wir beobachten nichts
    }

    override fun onInterrupt() {}

    /**
     * Fuegt Text ins fokussierte Eingabefeld ein (an der Cursor-Position).
     * @return true wenn eingefuegt, false wenn kein passendes Feld fokussiert ist.
     */
    fun fuegeTextEin(text: String): Boolean {
        val wurzel = rootInActiveWindow ?: return false
        val fokus = wurzel.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (!fokus.isEditable) return false
        // NIE in BlitzTexts eigene Felder schreiben (Einstellungen/Ergebnis-Fenster) —
        // sonst landet ein Diktat z.B. im Backend-Adress-Feld. Stattdessen liefert
        // false das Ergebnis-Fenster (das eigene Diktate selbst anhaengt).
        if (fokus.packageName == packageName) return false

        // Hinweis-Text (Platzhalter leerer Felder) zaehlt nicht als Inhalt
        val vorhanden = if (fokus.isShowingHintText) "" else (fokus.text?.toString() ?: "")
        val von = fokus.textSelectionStart.let { if (it in 0..vorhanden.length) it else vorhanden.length }
        val bis = fokus.textSelectionEnd.let { if (it in von..vorhanden.length) it else von }
        val neu = vorhanden.substring(0, von) + text + vorhanden.substring(bis)

        val argumente = Bundle()
        argumente.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, neu)
        val ok = fokus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, argumente)
        if (ok) {
            val cursor = von + text.length
            val auswahl = Bundle()
            auswahl.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
            auswahl.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
            fokus.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, auswahl)
        }
        return ok
    }
}
