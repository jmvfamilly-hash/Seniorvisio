package com.seniorvisio.admin

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

        val inputWifiSsid = findViewById<EditText>(R.id.inputWifiSsid)
        val inputWifiPassword = findViewById<EditText>(R.id.inputWifiPassword)
        val buttonConnectWifi = findViewById<Button>(R.id.buttonConnectWifi)
        buttonConnectWifi.setOnClickListener {
            val ssid = inputWifiSsid.text.toString().trim()
            val password = inputWifiPassword.text.toString()
            if (ssid.isEmpty()) {
                Toast.makeText(this, "Nom du réseau manquant", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val ok = WifiConfigurator.connect(this, ssid, password)
            Toast.makeText(
                this,
                if (ok) "Connexion au Wi-Fi \"$ssid\" en cours…" else "Échec — fonction disponible uniquement sur la tablette déployée",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
