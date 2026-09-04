package com.seniorvisio.admin

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.webkit.WebView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.seniorvisio.BuildConfig
import com.seniorvisio.R
import com.seniorvisio.core.AdminConfig
import com.seniorvisio.core.KioskManager
import com.seniorvisio.core.WifiConfigurator

/**
 * Écran de réglages admin minimal : PIN d'accès et durée du décompte
 * (les autres champs d'AdminConfig pourront être ajoutés ici plus tard
 * de la même façon, sans toucher au reste de l'app).
 */
class AdminSettingsActivity : AppCompatActivity() {

    private lateinit var adminConfig: AdminConfig
    private lateinit var inputWifiSsid: EditText
    private lateinit var inputWifiPassword: EditText
    private lateinit var textWifiStatus: TextView
    private lateinit var webViewCaptivePortal: WebView
    private lateinit var buttonValidateCaptivePortal: Button

    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        val content = result.contents ?: return@registerForActivityResult
        val parsed = WifiConfigurator.parseWifiQrPayload(content)
        if (parsed == null) {
            Toast.makeText(this, "Ce QR n'est pas un QR Wi-Fi reconnu", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        inputWifiSsid.setText(parsed.first)
        inputWifiPassword.setText(parsed.second)
        connectToWifi(parsed.first, parsed.second)
    }

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchWifiQrScan()
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adminConfig = AdminConfig(this)
        setContentView(R.layout.activity_admin_settings)

        findViewById<TextView>(R.id.textInstalledVersion).text = "Version installée : ${BuildConfig.BUILD_REV}"

        val inputCountdown = findViewById<EditText>(R.id.inputCountdownSeconds)
        val inputPin = findViewById<EditText>(R.id.inputAdminPin)
        val buttonSave = findViewById<Button>(R.id.buttonSaveAdminSettings)

        val inputAssemblyAiKey = findViewById<EditText>(R.id.inputAssemblyAiKey)
        val inputWeatherApiKey = findViewById<EditText>(R.id.inputWeatherApiKey)
        val inputWeatherLocation = findViewById<EditText>(R.id.inputWeatherLocation)

        inputCountdown.setText(adminConfig.countdownSeconds.toString())
        inputPin.setText(adminConfig.adminPin)
        inputAssemblyAiKey.setText(adminConfig.assemblyAiApiKey)
        inputWeatherApiKey.setText(adminConfig.weatherApiKey)
        inputWeatherLocation.setText(adminConfig.weatherLocation)

        buttonSave.setOnClickListener {
            val seconds = inputCountdown.text.toString().toIntOrNull()
            if (seconds == null || seconds <= 0) {
                Toast.makeText(this, "Durée invalide", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            adminConfig.countdownSeconds = seconds
            adminConfig.adminPin = inputPin.text.toString().ifBlank { adminConfig.adminPin }
            adminConfig.assemblyAiApiKey = inputAssemblyAiKey.text.toString().trim()
            adminConfig.weatherApiKey = inputWeatherApiKey.text.toString().trim()
            adminConfig.weatherLocation = inputWeatherLocation.text.toString().trim()
            Toast.makeText(this, "Réglages enregistrés", Toast.LENGTH_SHORT).show()
            finish()
        }

        inputWifiSsid = findViewById(R.id.inputWifiSsid)
        inputWifiPassword = findViewById(R.id.inputWifiPassword)
        textWifiStatus = findViewById(R.id.textWifiStatus)
        webViewCaptivePortal = findViewById(R.id.webViewCaptivePortal)
        webViewCaptivePortal.settings.javaScriptEnabled = true
        buttonValidateCaptivePortal = findViewById(R.id.buttonValidateCaptivePortal)
        val buttonConnectWifi = findViewById<Button>(R.id.buttonConnectWifi)
        val buttonScanWifiQr = findViewById<Button>(R.id.buttonScanWifiQr)

        buttonValidateCaptivePortal.setOnClickListener { checkCaptivePortalCleared() }

        buttonConnectWifi.setOnClickListener {
            val ssid = inputWifiSsid.text.toString().trim()
            val password = inputWifiPassword.text.toString()
            if (ssid.isEmpty()) {
                Toast.makeText(this, "Nom du réseau manquant", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            connectToWifi(ssid, password)
        }

        buttonScanWifiQr.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED
            ) {
                launchWifiQrScan()
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        findViewById<Button>(R.id.buttonPickWifiNetwork).setOnClickListener { pickWifiNetwork() }
        findViewById<Button>(R.id.buttonOpenBrowser).setOnClickListener { openBrowserForNetworkLogin() }

        // Case décochée par défaut à l'ouverture : Jean (ou un aidant) peut
        // avoir cet écran ouvert avec quelqu'un d'autre présent — les mots de
        // passe restent masqués tant que ce n'est pas explicitement demandé.
        findViewById<CheckBox>(R.id.checkboxShowPasswords).setOnCheckedChangeListener { _, checked ->
            val plainOrPassword = { plain: Int, masked: Int -> if (checked) plain else masked }
            inputPin.inputType = plainOrPassword(
                InputType.TYPE_CLASS_NUMBER,
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            )
            inputWifiPassword.inputType = plainOrPassword(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            )
            inputAssemblyAiKey.inputType = plainOrPassword(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            )
            inputWeatherApiKey.inputType = plainOrPassword(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            )
            // setInputType ramène sinon le curseur au tout début du champ.
            inputPin.setSelection(inputPin.text.length)
            inputWifiPassword.setSelection(inputWifiPassword.text.length)
            inputAssemblyAiKey.setSelection(inputAssemblyAiKey.text.length)
            inputWeatherApiKey.setSelection(inputWeatherApiKey.text.length)
        }
    }

    /**
     * Liste les réseaux Wi-Fi visibles à proximité plutôt que de faire
     * retaper le SSID à la main (source d'erreurs de frappe/accents sur
     * l'écran tactile) — voir WifiConfigurator.scanNetworks.
     */
    private fun pickWifiNetwork() {
        Toast.makeText(this, "Recherche des réseaux à proximité…", Toast.LENGTH_SHORT).show()
        WifiConfigurator.scanNetworks(this) { ssids ->
            if (ssids.isEmpty()) {
                Toast.makeText(this, "Aucun réseau détecté à proximité", Toast.LENGTH_LONG).show()
                return@scanNetworks
            }
            AlertDialog.Builder(this)
                .setTitle("Choisir un réseau Wi-Fi")
                .setItems(ssids.toTypedArray()) { _, index ->
                    inputWifiSsid.setText(ssids[index])
                    inputWifiPassword.text?.clear()
                }
                .setNegativeButton("Annuler", null)
                .show()
        }
    }

    private fun launchWifiQrScan() {
        qrScanLauncher.launch(
            ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("Scannez le QR Wi-Fi")
                setBeepEnabled(false)
                setOrientationLocked(false)
            }
        )
    }

    private fun connectToWifi(ssid: String, password: String) {
        webViewCaptivePortal.visibility = android.view.View.GONE
        buttonValidateCaptivePortal.visibility = android.view.View.GONE
        textWifiStatus.setTextColor(0xFF555555.toInt())
        textWifiStatus.text = "Connexion à \"$ssid\" en cours…"
        WifiConfigurator.connect(this, ssid, password) { associated ->
            if (!associated) {
                textWifiStatus.setTextColor(0xFFE74C3C.toInt())
                textWifiStatus.text = "✗ Échec de connexion à \"$ssid\" — vérifiez le mot de passe" +
                    " (fonctionne uniquement sur la tablette déployée, en Device Owner)"
                return@connect
            }
            textWifiStatus.setTextColor(0xFF555555.toInt())
            textWifiStatus.text = "Réseau \"$ssid\" rejoint — vérification d'Internet…"
            WifiConfigurator.checkInternetReachable { internetOk ->
                if (internetOk) {
                    textWifiStatus.setTextColor(0xFF2ECC71.toInt())
                    textWifiStatus.text = "✓ Connecté à \"$ssid\" (Internet fonctionne)"
                } else {
                    showCaptivePortal(ssid)
                }
            }
        }
    }

    /**
     * Réseau associé mais sans accès Internet réel : cas des Wi-Fi de
     * résidence senior type Wifirst, ouverts au niveau radio mais bloqués
     * derrière un portail web tant qu'un code personnel n'est pas validé.
     * Affiché directement dans l'app (pas de navigateur système accessible
     * en mode kiosque, voir KioskManager) : n'importe quelle page http://
     * suffit, le portail intercepte la requête et affiche sa propre page.
     */
    private fun showCaptivePortal(ssid: String) {
        textWifiStatus.setTextColor(0xFFE67E22.toInt())
        textWifiStatus.text = "\"$ssid\" rejoint, mais Internet nécessite un portail de connexion — " +
            "saisissez votre code ci-dessous, puis validez"
        webViewCaptivePortal.visibility = android.view.View.VISIBLE
        buttonValidateCaptivePortal.visibility = android.view.View.VISIBLE
        webViewCaptivePortal.loadUrl("http://connectivitycheck.gstatic.com/generate_204")
    }

    private fun checkCaptivePortalCleared() {
        textWifiStatus.setTextColor(0xFF555555.toInt())
        textWifiStatus.text = "Nouvelle vérification d'Internet…"
        WifiConfigurator.checkInternetReachable { internetOk ->
            if (internetOk) {
                webViewCaptivePortal.visibility = android.view.View.GONE
                buttonValidateCaptivePortal.visibility = android.view.View.GONE
                textWifiStatus.setTextColor(0xFF2ECC71.toInt())
                textWifiStatus.text = "✓ Connecté à Internet"
            } else {
                Toast.makeText(this, "Toujours pas d'accès Internet — terminez le portail ci-dessus", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Repli quand l'écran captif intégré (une simple WebView, voir
     * showCaptivePortal) ne suffit pas pour se connecter au réseau de la
     * résidence — certains portails exigent un vrai navigateur. Ouvre une
     * fenêtre de maintenance temporaire (voir
     * KioskManager.grantTemporaryBrowserAccess) plutôt qu'un accès permanent,
     * qui viderait le mode kiosque de son sens : elle se referme d'elle-même
     * après 10 minutes, ou dès le retour sur l'écran d'accueil.
     */
    private fun openBrowserForNetworkLogin() {
        val browsers = resolveBrowsers()
        when {
            browsers.isEmpty() ->
                Toast.makeText(this, "Aucun navigateur trouvé sur cette tablette", Toast.LENGTH_LONG).show()
            browsers.size == 1 -> launchBrowser(browsers.values.first())
            else -> AlertDialog.Builder(this)
                .setTitle("Choisir un navigateur")
                .setItems(browsers.keys.toTypedArray()) { _, index ->
                    launchBrowser(browsers.values.toList()[index])
                }
                .setNegativeButton("Annuler", null)
                .show()
        }
    }

    /** Nom affiché à l'admin -> nom de paquet, pour les navigateurs capables d'ouvrir une page https. */
    private fun resolveBrowsers(): Map<String, String> {
        val probeIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://"))
        return packageManager.queryIntentActivities(probeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            .filter { it.activityInfo.packageName != packageName }
            .associate { resolveInfo ->
                resolveInfo.loadLabel(packageManager).toString() to resolveInfo.activityInfo.packageName
            }
    }

    private fun launchBrowser(browserPackage: String) {
        KioskManager.grantTemporaryBrowserAccess(this, browserPackage)
        textBrowserAccessStatus().text =
            "Navigateur autorisé 10 minutes — revenez ici (bouton Accueil) une fois la connexion validée."
        // Ouvre directement la page que le portail intercepte pour rediriger
        // vers son propre formulaire (même URL que l'écran captif intégré) :
        // évite à l'admin de devoir taper une adresse sur l'écran tactile.
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://connectivitycheck.gstatic.com/generate_204")).apply {
            setPackage(browserPackage)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            KioskManager.revokeTemporaryBrowserAccess(this)
            Toast.makeText(this, "Impossible d'ouvrir ce navigateur", Toast.LENGTH_LONG).show()
        }
    }

    private fun textBrowserAccessStatus() = findViewById<TextView>(R.id.textBrowserAccessStatus)
}
