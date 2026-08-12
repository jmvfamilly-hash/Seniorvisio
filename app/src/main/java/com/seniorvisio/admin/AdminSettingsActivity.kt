package com.seniorvisio.admin

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.seniorvisio.R
import com.seniorvisio.core.AdminConfig

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

        inputCountdown.setText(adminConfig.countdownSeconds.toString())
        inputPin.setText(adminConfig.adminPin)

        buttonSave.setOnClickListener {
            val seconds = inputCountdown.text.toString().toIntOrNull()
            if (seconds == null || seconds <= 0) {
                Toast.makeText(this, "Durée invalide", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            adminConfig.countdownSeconds = seconds
            adminConfig.adminPin = inputPin.text.toString().ifBlank { adminConfig.adminPin }
            Toast.makeText(this, "Réglages enregistrés", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
