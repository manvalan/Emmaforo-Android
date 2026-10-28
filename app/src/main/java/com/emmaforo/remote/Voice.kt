package com.emmaforo.remote

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.text.Normalizer
import java.util.Locale

/** What a spoken sentence asks of the lamp. */
sealed class VoiceAction {
    data class Light(val command: String, val name: String, val blink: Boolean?) : VoiceAction()
    data class Blink(val on: Boolean) : VoiceAction()
    object StartChain : VoiceAction()
    object StopChain : VoiceAction()
    object Off : VoiceAction()
}

const val voiceHelp = "Non ho capito: di' rosso, giallo, verde o spento"

/** The same words as the iOS app. */
private val voiceWords: Map<String, String> = buildMap {
    listOf("rosso", "stop", "fermo", "ferma", "alt").forEach { put(it, "rosso") }
    listOf("giallo", "attenzione", "aspetta").forEach { put(it, "giallo") }
    listOf("verde", "avanti", "vai", "via", "libero").forEach { put(it, "verde") }
    listOf("spento", "spegni", "spegniti", "spegnere").forEach { put(it, "spento") }
}

/** Tries each recognizer guess in order and keeps the first one that means something. */
fun parseVoice(guesses: List<String>): VoiceAction? {
    for (guess in guesses) {
        parseSentence(guess)?.let { return it }
    }
    return null
}

private fun parseSentence(raw: String): VoiceAction? {
    val words = plain(raw).split(' ').filter { it.isNotEmpty() }
    // Android only, on top of the iOS words: "avvia catena", "ferma catena".
    if (words.any { it == "catena" }) {
        val stop = words.any { it == "ferma" || it == "stop" || it == "spegni" || it == "basta" }
        return if (stop) VoiceAction.StopChain else VoiceAction.StartChain
    }
    // The last word that means something counts.
    val state = words.asReversed().firstNotNullOfOrNull { voiceWords[it] } ?: return null
    if (state == "spento") return VoiceAction.Off
    val light = semaphoreLights.first { it.id == state }
    return VoiceAction.Light(light.command, light.name, null)
}

private fun plain(text: String): String {
    val folded = Normalizer.normalize(text.lowercase(Locale.ITALIAN), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
    return folded.replace(Regex("[^a-z0-9]+"), " ").trim()
}

/**
 * Listens for at most six seconds through Android's SpeechRecognizer, on the device when it can.
 * [onResult] gets the guesses (empty when nothing was heard).
 */
class VoiceListener(private val context: Context, private val onResult: (List<String>) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var deadline: Runnable? = null
    var onListening: (Boolean) -> Unit = {}

    fun available(): Boolean = SpeechRecognizer.isRecognitionAvailable(context) ||
        (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context))

    fun start() {
        stop()
        val engine = if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        recognizer = engine
        engine.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onError(error: Int) = finish(emptyList())
            override fun onResults(results: Bundle?) =
                finish(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty())
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        onListening(true)
        engine.startListening(intent)
        val timeout = Runnable { recognizer?.stopListening() }
        deadline = timeout
        main.postDelayed(timeout, 6_000)
    }

    private fun finish(guesses: List<String>) {
        stop()
        onResult(guesses)
    }

    fun stop() {
        deadline?.let { main.removeCallbacks(it) }
        deadline = null
        recognizer?.destroy()
        if (recognizer != null) onListening(false)
        recognizer = null
    }
}
