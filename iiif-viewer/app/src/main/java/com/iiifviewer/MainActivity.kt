package com.iiifviewer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Offset

/**
 * Application de démonstration du visualiseur IIIF.
 *
 * L'image à ouvrir se change sans recompiler : URL d'un `info.json` en donnée d'intent, par exemple
 *   adb shell am start -n com.iiifviewer/.MainActivity -d "https://serveur/iiif/image/info.json"
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val infoUrl = intent?.dataString ?: DEFAULT_INFO_URL
        setContent {
            // Départ au centre de l'image, en zoom ×4 : on découvre l'image en dézoomant.
            IiifZoomViewer(manifestUrl = infoUrl, initialFocus = Offset.Unspecified, initialZoom = 4f)
        }
    }

    private companion object {
        // Serveur IIIF public de Stanford (Image API 2.x, lu aussi bien que la 3.0).
        const val DEFAULT_INFO_URL = "https://stacks.stanford.edu/image/iiif/hg676jb4964%2F0380_796-44/info.json"
    }
}
