package com.seniorvisio.service

import android.os.CountDownTimer
import com.seniorvisio.core.CallAlertController

/**
 * Implémentation simple du compte à rebours via android.os.CountDownTimer.
 * Une seule responsabilité : décompter et notifier, rien d'autre — l'UI
 * (IncomingCallActivity) et le service (IncomingCallService) restent
 * responsables de ce qu'ils font de ces callbacks.
 */
class TimedCallAlertController : CallAlertController {

    private var timer: CountDownTimer? = null

    override fun startCountdown(
        callerName: String,
        durationSeconds: Int,
        onTick: (remainingSeconds: Int) -> Unit,
        onTimeoutConnect: () -> Unit,
        onBlocked: () -> Unit
    ) {
        cancel()
        val durationMs = durationSeconds * 1000L

        timer = object : CountDownTimer(durationMs, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                onTick((millisUntilFinished / 1000L).toInt())
            }

            override fun onFinish() {
                onTimeoutConnect()
            }
        }.start()

        // onBlocked est déclenché depuis l'UI (bouton "Bloquer"), pas ici —
        // voir IncomingCallActivity qui appelle controller.cancel() puis
        // exécute sa propre logique de blocage.
    }

    override fun cancel() {
        timer?.cancel()
        timer = null
    }
}
