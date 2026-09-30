package com.iiifviewer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * Application de démonstration du visualiseur IIIF.
 *
 * Appui long sur l'écran : coller le lien de l'`info.json` d'une autre image.
 * On peut aussi ouvrir une image depuis adb :
 *   adb shell am start -n com.iiifviewer/.MainActivity -d "https://serveur/iiif/image/info.json"
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialUrl = intent?.dataString ?: DEFAULT_INFO_URL
        setContent { ViewerScreen(initialUrl) }
    }

    private companion object {
        // Serveur IIIF public de Stanford (Image API 2.x, lu aussi bien que la 3.0).
        const val DEFAULT_INFO_URL = "https://stacks.stanford.edu/image/iiif/hg676jb4964%2F0380_796-44/info.json"
    }
}

private val PanelColor = Color(0xFF1E1E1E)
private val AccentColor = Color(0xFF4C8DFF)

@Composable
private fun ViewerScreen(initialUrl: String) {
    var url by remember { mutableStateOf(initialUrl) }
    var showDialog by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Départ au centre de l'image, en zoom ×4 : on découvre l'image en dézoomant.
        IiifZoomViewer(
            manifestUrl = url,
            initialFocus = Offset.Unspecified,
            initialZoom = 4f,
            onError = { error = "Impossible d'ouvrir cette image : ${it.message ?: it.javaClass.simpleName}" },
            onLongPress = { showDialog = true },
        )
        error?.let { message ->
            BasicText(
                text = "$message\nAppui long pour coller un autre lien.",
                style = TextStyle(color = Color.White, fontSize = 15.sp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .background(Color(0xCC8B1A1A), RoundedCornerShape(8.dp))
                    .padding(12.dp),
            )
        }
    }

    if (showDialog) {
        OpenUrlDialog(
            currentUrl = url,
            onDismiss = { showDialog = false },
            onOpen = { newUrl ->
                showDialog = false
                error = null
                url = newUrl
            },
        )
    }
}

@Composable
private fun OpenUrlDialog(currentUrl: String, onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    fun clipboardText() = clipboard.getText()?.text?.trim()?.takeIf { it.startsWith("http", ignoreCase = true) }

    // Le presse-papiers est proposé d'emblée s'il contient une URL : appui long, « Ouvrir », c'est fait.
    var text by remember { mutableStateOf(clipboardText() ?: currentUrl) }
    val target = IiifInput.normalizeInfoUrl(text)

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(PanelColor, RoundedCornerShape(12.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BasicText("Ouvrir une image IIIF", style = TextStyle(color = Color.White, fontSize = 18.sp))
            BasicText(
                "Colle le lien de l'info.json (ou l'adresse de base de l'image).",
                style = TextStyle(color = Color(0xFFB0B0B0), fontSize = 13.sp),
            )
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                cursorBrush = SolidColor(AccentColor),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, if (target == null && text.isNotBlank()) Color(0xFFD9534F) else Color(0xFF555555), RoundedCornerShape(8.dp))
                    .padding(12.dp),
            )
            if (target == null && text.isNotBlank()) {
                BasicText("Ce n'est pas une adresse http(s) valide.", style = TextStyle(color = Color(0xFFE57373), fontSize = 13.sp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                DialogButton("Coller") { clipboardText()?.let { text = it } }
                DialogButton("Annuler", onClick = onDismiss)
                DialogButton("Ouvrir", enabled = target != null, filled = true) { target?.let(onOpen) }
            }
        }
    }
}

@Composable
private fun DialogButton(label: String, enabled: Boolean = true, filled: Boolean = false, onClick: () -> Unit) {
    val background = if (filled && enabled) AccentColor else Color.Transparent
    val color = if (enabled) Color.White else Color(0xFF777777)
    BasicText(
        text = label,
        style = TextStyle(color = color, fontSize = 15.sp),
        modifier = Modifier
            .background(background, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}
