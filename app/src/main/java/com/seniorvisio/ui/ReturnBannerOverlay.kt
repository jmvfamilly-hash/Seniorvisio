package com.seniorvisio.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * Le bandeau « Revenir à l'écran de Jean », dessiné par-dessus Transcription
 * instantanée.
 *
 * Sans lui, le seul chemin de retour serait le bouton Accueil de la barre de
 * navigation — laquelle est justement masquée sur cet appareil (voir
 * SystemBars), et que Jean n'a de toute façon aucune raison de connaître. Une
 * bascule automatique sans retour visible ferait de l'application de Google un
 * cul-de-sac : c'est le contraire du principe de l'appareil.
 *
 * En bas de l'écran, et pas en haut : c'est là que Transcription instantanée
 * fait défiler son texte le plus récent... raison pour laquelle il est étroit
 * et translucide. Le compromis est réel et assumé — il masque une ligne, mais
 * un retour introuvable coûte bien plus cher qu'une ligne.
 *
 * ═══ L'autorisation, et le fait qu'elle puisse manquer ═══
 *
 * Dessiner par-dessus une autre application demande une autorisation qui ne
 * s'accorde pas toute seule : ni par le code, ni même par un propriétaire
 * d'appareil, parce qu'elle est précisément la porte des attaques par
 * recouvrement. Elle se donne une fois, à la main, depuis les Réglages.
 *
 * Tout ici est donc écrit pour fonctionner **sans** elle : le bandeau ne
 * s'affiche pas, [available] le dit, et les autres chemins de retour — durée,
 * extinction de l'écran, bouton Accueil — restent entiers. Une fonction de
 * confort ne doit jamais empêcher la bascule elle-même de marcher.
 */
class ReturnBannerOverlay(private val context: Context) {

    private var banner: View? = null

    /** L'autorisation de dessiner par-dessus les autres applications est-elle accordée ? */
    fun available(): Boolean = Settings.canDrawOverlays(context)

    fun show(onReturn: () -> Unit) {
        if (banner != null) return
        if (!available()) {
            Log.i(TAG, "Bandeau de retour impossible : autorisation de superposition non accordée")
            return
        }
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return

        val view = TextView(context).apply {
            text = "◀  Revenir à l'écran de Jean"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP)
            gravity = Gravity.CENTER
            setPadding(PADDING_PX, PADDING_PX, PADDING_PX, PADDING_PX)
            background = GradientDrawable().apply {
                cornerRadius = CORNER_RADIUS_PX
                // Assez opaque pour rester lisible sur n'importe quel fond,
                // assez transparent pour qu'on devine le texte qu'il recouvre.
                setColor(Color.argb(220, 20, 20, 20))
            }
            setOnClickListener { onReturn() }
        }

        val layout = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            // NOT_FOCUSABLE : le bandeau ne doit jamais prendre le clavier ni
            // le focus à l'application qu'il recouvre. Sans ce drapeau, il
            // capterait les touches destinées à Transcription instantanée.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
            y = MARGIN_PX
        }

        try {
            windowManager.addView(view, layout)
            banner = view
        } catch (e: Exception) {
            // Autorisation retirée entre-temps, ou refusée par une surcouche
            // constructeur. Le mode continue sans bandeau plutôt que de tomber.
            Log.w(TAG, "Bandeau de retour refusé par le système", e)
        }
    }

    fun hide() {
        val view = banner ?: return
        banner = null
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            Log.w(TAG, "Retrait du bandeau de retour", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

    companion object {
        private const val TAG = "ReturnBanner"
        private const val TEXT_SIZE_SP = 20f
        private const val PADDING_PX = 28
        private const val MARGIN_PX = 24
        private const val CORNER_RADIUS_PX = 24f

        /** Ouvre la page de réglage de l'autorisation, pour qu'un proche l'accorde une fois. */
        fun permissionSettingsIntent(context: Context): Intent =
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
