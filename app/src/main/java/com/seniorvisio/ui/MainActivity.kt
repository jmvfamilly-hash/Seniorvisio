package com.seniorvisio.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.widget.TextView

/**
 * Écran affiché quand aucun appel n'est en cours. Volontairement épuré
 * pour l'usage senior : pas de menu, pas de bouton, juste une horloge
 * ou un message d'accueil (à enrichir selon les retours terrain).
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this).apply {
            text = "Senior Visio\n(en attente d'appel)"
            textSize = 28f
            gravity = android.view.Gravity.CENTER
        }
        setContentView(tv)

        // TODO: démarrer IncomingCallService en tant que service persistant
        // dès le lancement (et au boot via BootReceiver, à ajouter).
    }
}
