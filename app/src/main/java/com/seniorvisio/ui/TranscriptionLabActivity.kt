package com.seniorvisio.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.seniorvisio.R
import com.seniorvisio.core.AdminConfig
import com.seniorvisio.core.AssemblyAiClient
import com.seniorvisio.core.SpeakerUtterance
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Labo d'étude, sans lien avec l'usage normal de l'appli : compare la
 * qualité de transcription de chaque moteur de reconnaissance vocale
 * disponible sur la tablette (natif Samsung, Google, etc.) et d'AssemblyAI,
 * sur un seul et même enregistrement — pour que la comparaison porte sur le
 * moteur, pas sur la variabilité naturelle de deux prises différentes.
 *
 * Objectif à terme (voir README) : repérer un moteur/mécanisme capable de
 * distinguer la voix de Jean de celle des autres personnes présentes dans
 * la pièce. AssemblyAI fournit une diarisation (identification de
 * locuteurs distincts, "Locuteur A/B...") qui permet un premier repérage
 * manuel ("c'est Jean qui parle ici") — pas encore une reconnaissance vocale
 * personnelle qui apprendrait sa voix pour la retrouver automatiquement
 * d'une session à l'autre, une étape ultérieure distincte.
 *
 * Les moteurs sur appareil ne peuvent pas être nourris d'un fichier audio
 * directement (l'API SpeechRecognizer n'écoute que le micro) : l'enregistrement
 * est donc rejoué à travers le haut-parleur pendant que chaque moteur écoute
 * à son tour — imparfait (acoustique de la pièce, qualité du haut-parleur)
 * mais garantit que tous entendent exactement la même phrase.
 */
class TranscriptionLabActivity : AppCompatActivity() {

    private lateinit var adminConfig: AdminConfig
    private lateinit var engineListContainer: LinearLayout
    private lateinit var resultsContainer: LinearLayout
    private lateinit var textRecordingStatus: TextView
    private lateinit var textLabStatus: TextView
    private lateinit var buttonRecord: Button
    private lateinit var buttonCompare: Button
    private lateinit var buttonCopyJson: Button

    private var mediaRecorder: MediaRecorder? = null
    private var recordedFile: File? = null
    private var isRecording = false

    private val handler = Handler(Looper.getMainLooper())
    private val resultsJson = JSONArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adminConfig = AdminConfig(this)
        setContentView(R.layout.activity_transcription_lab)

        engineListContainer = findViewById(R.id.engineListContainer)
        resultsContainer = findViewById(R.id.resultsContainer)
        textRecordingStatus = findViewById(R.id.textRecordingStatus)
        textLabStatus = findViewById(R.id.textLabStatus)
        buttonRecord = findViewById(R.id.buttonRecord)
        buttonCompare = findViewById(R.id.buttonCompare)
        buttonCopyJson = findViewById(R.id.buttonCopyJson)

        populateEngineList()

        buttonRecord.setOnClickListener { if (isRecording) stopRecording() else startRecording() }
        buttonCompare.setOnClickListener { runComparison() }
        buttonCopyJson.setOnClickListener { copyResultsAsJson() }
    }

    // ---- Détection des moteurs disponibles ----

    private fun availableEngines(): List<ComponentName> {
        val services = packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
        return services.map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
    }

    private fun populateEngineList() {
        availableEngines().forEach { component ->
            val checkBox = CheckBox(this).apply {
                text = component.packageName
                isChecked = true
                tag = component
            }
            engineListContainer.addView(checkBox)
        }
        if (engineListContainer.childCount == 0) {
            engineListContainer.addView(TextView(this).apply { text = "Aucun moteur détecté sur cet appareil" })
        }
    }

    private fun selectedEngines(): List<ComponentName> =
        (0 until engineListContainer.childCount)
            .mapNotNull { engineListContainer.getChildAt(it) as? CheckBox }
            .filter { it.isChecked }
            .mapNotNull { it.tag as? ComponentName }

    // ---- Enregistrement de la phrase test ----

    private fun startRecording() {
        val file = File(cacheDir, "lab_recording_${System.currentTimeMillis()}.m4a")
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Échec du démarrage de l'enregistrement", Toast.LENGTH_SHORT).show()
            return
        }
        mediaRecorder = recorder
        recordedFile = file
        isRecording = true
        buttonRecord.text = "⏹ Arrêter l'enregistrement"
        textRecordingStatus.text = "Enregistrement en cours…"
        buttonCompare.isEnabled = false
    }

    private fun stopRecording() {
        try {
            mediaRecorder?.apply { stop(); release() }
        } catch (_: Exception) {
            // Arrêté avant d'avoir capté de son : le fichier ne sera pas exploitable.
        }
        mediaRecorder = null
        isRecording = false
        buttonRecord.text = "🎤 Enregistrer une phrase test"
        textRecordingStatus.text = "Phrase enregistrée (${recordedFile?.name})"
        buttonCompare.isEnabled = true
    }

    // ---- Comparaison ----

    private fun runComparison() {
        val file = recordedFile ?: return
        resultsContainer.removeAllViews()
        resultsJson.let { while (it.length() > 0) it.remove(0) }
        buttonCopyJson.isEnabled = false
        val engines = selectedEngines().toMutableList()
        textLabStatus.text = "Test des moteurs sur l'appareil…"
        testNextEngine(engines, file)
    }

    private fun testNextEngine(remaining: MutableList<ComponentName>, file: File) {
        if (remaining.isEmpty()) {
            runAssemblyAi(file)
            return
        }
        val component = remaining.removeAt(0)
        testEngine(component, file) { transcript ->
            addResultRow(component.packageName, transcript ?: "(aucun résultat)")
            testNextEngine(remaining, file)
        }
    }

    private fun testEngine(component: ComponentName, file: File, onDone: (String?) -> Unit) {
        val recognizer = SpeechRecognizer.createSpeechRecognizer(this, component)
        var finished = false
        val timeout = Runnable {
            if (finished) return@Runnable
            finished = true
            recognizer.destroy()
            onDone(null)
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                // Laisse le moteur s'installer avant de rejouer l'enregistrement,
                // sinon le tout début de la phrase peut être manqué.
                handler.postDelayed({ playRecording(file) }, 300)
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onError(error: Int) {
                if (finished) return
                finished = true
                handler.removeCallbacks(timeout)
                recognizer.destroy()
                onDone(null)
            }
            override fun onResults(results: Bundle?) {
                if (finished) return
                finished = true
                handler.removeCallbacks(timeout)
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                recognizer.destroy()
                onDone(text)
            }
            override fun onPartialResults(partialResults: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR")
        }
        recognizer.startListening(intent)
        handler.postDelayed(timeout, 20_000)
    }

    private fun playRecording(file: File) {
        try {
            MediaPlayer().apply {
                setDataSource(file.absolutePath)
                @Suppress("DEPRECATION")
                setAudioStreamType(AudioManager.STREAM_MUSIC)
                setOnCompletionListener { release() }
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Échec de la relecture pour le test moteur", e)
        }
    }

    private fun runAssemblyAi(file: File) {
        val apiKey = adminConfig.assemblyAiApiKey
        if (apiKey.isBlank()) {
            addResultRow("AssemblyAI", "(clé API non configurée — voir Réglages admin)")
            finishComparison()
            return
        }
        textLabStatus.text = "Envoi à AssemblyAI, patiente (peut prendre 30-60s)…"
        Thread {
            try {
                val result = AssemblyAiClient(apiKey).transcribe(file)
                handler.post {
                    addResultRow(
                        "AssemblyAI",
                        result.fullText,
                        result.confidence,
                        result.utterances,
                    )
                    finishComparison()
                }
            } catch (e: Exception) {
                handler.post {
                    addResultRow("AssemblyAI", "Échec : ${e.message}")
                    finishComparison()
                }
            }
        }.start()
    }

    private fun finishComparison() {
        textLabStatus.text = "Comparaison terminée."
        buttonCopyJson.isEnabled = true
    }

    // ---- Affichage des résultats ----

    private fun addResultRow(
        engineName: String,
        transcript: String,
        confidence: Double? = null,
        utterances: List<SpeakerUtterance> = emptyList(),
    ) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            setBackgroundColor(0xFFF0F0F0.toInt())
        }
        card.addView(TextView(this).apply {
            text = engineName
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        if (confidence != null) {
            card.addView(TextView(this).apply {
                text = "Confiance globale : ${"%.0f".format(confidence * 100)}%"
                textSize = 12f
            })
        }
        card.addView(TextView(this).apply {
            text = transcript
            textSize = 14f
            setPadding(0, 8, 0, 0)
        })
        utterances.forEach { utterance ->
            card.addView(TextView(this).apply {
                text = "  ${utterance.speaker} : ${utterance.text}"
                textSize = 13f
                setTextColor(0xFF444444.toInt())
            })
        }
        val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        params.bottomMargin = 16
        resultsContainer.addView(card, params)

        resultsJson.put(JSONObject().apply {
            put("engine", engineName)
            put("transcript", transcript)
            if (confidence != null) put("confidence", confidence)
            put("utterances", JSONArray(utterances.map {
                JSONObject().apply { put("speaker", it.speaker); put("text", it.text) }
            }))
        })
    }

    private fun copyResultsAsJson() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("transcription-lab", resultsJson.toString(2)))
        Toast.makeText(this, "JSON copié", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { mediaRecorder?.release() } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "TranscriptionLab"
    }
}
