package com.seniorvisio.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.seniorvisio.BuildConfig
import com.seniorvisio.R
import com.seniorvisio.core.AdminConfig
import com.seniorvisio.service.RoomPresenceService

/**
 * Sous-titres de la pièce, en continu — remplace l'application
 * "Transcription instantanée" de Google (voir CompanionApps.kt, qui
 * documente pourquoi une première tentative maison avait été abandonnée
 * il y a plusieurs semaines au profit de cette app tierce). Repose
 * maintenant sur AssemblyAI, jugé fiable pour les sous-titres d'appel
 * (voir WebRtcCallEngine) — le même choix est étendu ici, à la demande
 * explicite ("AssemblyAI dans tous les cas").
 *
 * Ne capte jamais le micro elle-même : elle se lie à RoomPresenceService
 * (déjà un foreground service permanent qui capte ce micro en continu pour
 * le réveil au son) et lui délègue la transcription, pour ne jamais ouvrir
 * une deuxième capture concurrente du même micro — exactement le problème
 * qui rendait les sous-titres d'appel peu fiables.
 */
class RoomTranscriptionActivity : AppCompatActivity() {

    private lateinit var textTranscript: TextView
    private lateinit var textStatus: TextView
    private lateinit var scrollView: ScrollView
    private var service: RoomPresenceService? = null
    private var finalizedText = ""

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val bound = (binder as? RoomPresenceService.LocalBinder)?.getService() ?: return
            service = bound
            val apiKey = AdminConfig(this@RoomTranscriptionActivity).assemblyAiApiKey
            if (apiKey.isBlank()) {
                textStatus.text = "Clé API AssemblyAI non configurée — voir Réglages admin"
                return
            }
            textStatus.text = "En écoute…"
            bound.startRoomTranscription { text, isFinal -> runOnUiThread { updateTranscript(text, isFinal) } }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_room_transcription)
        textTranscript = findViewById(R.id.textRoomTranscript)
        textStatus = findViewById(R.id.textRoomTranscriptStatus)
        scrollView = findViewById(R.id.roomTranscriptScroll)
        findViewById<TextView>(R.id.textBuildRev).text = BuildConfig.BUILD_REV
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, RoomPresenceService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    /**
     * Arrête la transcription dès que l'écran n'est plus visible (retour à
     * l'accueil par le bouton Accueil) : inutile de continuer à payer pour de
     * la transcription que personne ne regarde.
     */
    override fun onStop() {
        super.onStop()
        service?.stopRoomTranscription()
        unbindService(connection)
        service = null
    }

    /**
     * Un texte provisoire (isFinal=false) remplace la ligne en cours sans
     * l'ajouter à l'historique : il est encore révisé au mot près tant que la
     * phrase continue. Un texte définitif s'ajoute une fois pour toutes —
     * c'est cet historique qui manquait à la première tentative maison.
     */
    private fun updateTranscript(text: String, isFinal: Boolean) {
        if (text.isBlank()) return
        val combined = if (finalizedText.isEmpty()) text else "$finalizedText\n$text"
        textTranscript.text = combined
        if (isFinal) finalizedText = combined
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }
}
