package com.seniorvisio.admin

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.seniorvisio.R
import com.seniorvisio.core.AdminConfig
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adminConfig = AdminConfig(this)
        setContentView(R.layout.activity_admin_settings)

        val inputCountdown = findViewById<EditText>(R.id.inputCountdownSeconds)
        val inputPin = findViewById<EditText>(R.id.inputAdminPin)
        val buttonSave = findViewById<Button>(R.id.buttonSaveAdminSettings)

        val inputAssemblyAiKey = findViewById<EditText>(R.id.inputAssemblyAiKey)

        inputCountdown.setText(adminConfig.countdownSeconds.toString())
        inputPin.setText(adminConfig.adminPin)
        inputAssemblyAiKey.setText(adminConfig.assemblyAiApiKey)

        buttonSave.setOnClickListener {
            val seconds = inputCountdown.text.toString().toIntOrNull()
            if (seconds == null || seconds <= 0) {
                Toast.makeText(this, "Durée invalide", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            adminConfig.countdownSeconds = seconds
            adminConfig.adminPin = inputPin.text.toString().ifBlank { adminConfig.adminPin }
            adminConfig.assemblyAiApiKey = inputAssemblyAiKey.text.toString().trim()
            Toast.makeText(this, "Réglages enregistrés", Toast.LENGTH_SHORT).show()
            finish()
        }

        inputWifiSsid = findViewById(R.id.inputWifiSsid)
        inputWifiPassword = findViewById(R.id.inputWifiPassword)
        textWifiStatus = findViewById(R.id.textWifiStatus)
        val buttonConnectWifi = findViewById<Button>(R.id.buttonConnectWifi)
        val buttonScanWifiQr = findViewById<Button>(R.id.buttonScanWifiQr)

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
        textWifiStatus.setTextColor(0xFF555555.toInt())
        textWifiStatus.text = "Connexion à \"$ssid\" en cours…"
        WifiConfigurator.connect(this, ssid, password) { success ->
            if (success) {
                textWifiStatus.setTextColor(0xFF2ECC71.toInt())
                textWifiStatus.text = "✓ Connecté à \"$ssid\""
            } else {
                textWifiStatus.setTextColor(0xFFE74C3C.toInt())
                textWifiStatus.text = "✗ Échec de connexion à \"$ssid\" — vérifiez le mot de passe" +
                    " (fonctionne uniquement sur la tablette déployée, en Device Owner)"
            }
        }
    }
}
