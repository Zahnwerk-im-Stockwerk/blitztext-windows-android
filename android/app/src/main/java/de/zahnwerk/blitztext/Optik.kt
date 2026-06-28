package de.zahnwerk.blitztext

import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView

/**
 * Stanzt das quadratische Logo-JPG rund aus: die weissen Ecken verschwinden,
 * auf dunklem Hintergrund bleibt nur der orange-goldene Logo-Kreis sichtbar.
 * (Gleicher Trick wie beim schwebenden Knopf.)
 */
fun logoRundAusstanzen(bild: ImageView) {
    bild.scaleType = ImageView.ScaleType.CENTER_CROP
    bild.outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setOval(0, 0, view.width, view.height)
        }
    }
    bild.clipToOutline = true
}
