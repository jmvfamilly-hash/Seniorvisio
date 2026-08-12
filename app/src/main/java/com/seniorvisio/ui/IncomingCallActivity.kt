package com.seniorvisio.ui

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import android.widget.TextView
import com.seniorvisio.R
import com.seniorvisio.core.AdminConfig
import com.seniorvisio.service.TimedCallAlertController

/**
 * Écran plein format affiché à chaque appel entrant : décompte visible
 * de `AdminConfig.countdownSeconds` (30s par défaut), avec un bouton
 * "Bloquer l'appel" que Jean peut presser à tout moment. Si le délai
 * s'écoule sans action, la connexion vidéo démarre automatiquement.
 */
class IncomingCallActivity : AppCompatActivity() {

    private val alertController = TimedCallAlertController()
    private lateinit var adminConfig: AdminConfig

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adminConfig = AdminConfig(this)

        // Réveille l'écran et l'affiche même si verrouillé, sans son.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        setContentView(R.layout.activity_incoming_call)

        val callerName = intent.getStringExtra("callerName") ?: "un proche"
        val textCallerName = findViewById<TextView>(R.id.textCallerName)
        val textCountdown = findViewById<TextView>(R.id.textCountdown)
        val buttonBlock = findViewById<Button>(R.id.buttonBlock)

        textCallerName.text = "Appel de $callerName"

        buttonBlock.setOnClickListener {
            alertController.cancel()
            handleBlocked()
        }

        alertController.startCountdown(
            callerName = callerName,
            durationSeconds = adminConfig.countdownSeconds,
            onTick = { remaining -> textCountdown.text = "$remaining s" },
            onTimeoutConnect = { connectVideoCall() },
            onBlocked = { /* déclenché via le bouton, voir ci-dessus */ }
        )
    }

    private fun connectVideoCall() {
        // TODO: brancher ici le CallEngine actif (voir core/CallEngine.kt)
        // ex: callEngine.answer()
        finish()
    }

    private fun handleBlocked() {
        // TODO: notifier l'appelant que l'appel a été bloqué (via le
        // CallEngine / signaling), puis fermer cet écran.
        finish()
    }

    override fun onDestroy() {
        alertController.cancel()
        super.onDestroy()
    }
}
