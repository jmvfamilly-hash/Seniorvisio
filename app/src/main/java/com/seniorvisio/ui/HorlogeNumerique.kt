package com.seniorvisio.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.widget.TextViewCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Horloge numérique, alternative à HorlogeAiguilles sur l'écran d'accueil —
 * au choix de l'administrateur (voir AdminConfig.horlogeNumerique,
 * MainActivity.appliquerChoixHorloge). Les deux vivent côte à côte dans la
 * mise en page, une seule visible à la fois : rien à reconstruire au
 * changement de réglage, juste une bascule de visibilité.
 *
 * Sur 24h ("14:35"), jamais 12h : le réglage a été choisi pour éviter un
 * repère supplémentaire (matin/après-midi) à déchiffrer en plus de l'heure
 * elle-même.
 *
 * Auto-dimensionnée plutôt qu'à une taille fixe (voir
 * setAutoSizeTextTypeUniformWithConfiguration) : elle doit rester grande et
 * lisible à trois mètres quelle que soit la tablette, comme le cadran à
 * aiguilles qu'elle remplace.
 */
class HorlogeNumerique @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatTextView(context, attrs) {

    private val format = SimpleDateFormat("HH:mm", Locale.FRANCE)
    private val handler = Handler(Looper.getMainLooper())
    private val tic = object : Runnable {
        override fun run() {
            text = format.format(Date())
            handler.postDelayed(this, 1_000L)
        }
    }

    init {
        gravity = Gravity.CENTER
        typeface = Typeface.create(typeface, Typeface.BOLD)
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
            this, 40, 400, 4, TypedValue.COMPLEX_UNIT_SP,
        )
        setTextColor(Color.parseColor("#101A2E"))
    }

    /** Palette du moment (voir ScreenTheme) : même teinte que l'horloge à aiguilles. */
    fun setCouleur(couleur: Int) {
        setTextColor(couleur)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(tic)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tic)
        super.onDetachedFromWindow()
    }
}
