package com.emmaforo.remote

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.roundToInt

const val lampSampleRate = 8000

/**
 * Turns a sentence into what the lamp plays: the phone's text-to-speech writes a WAV file,
 * which becomes 8 kHz mono signed 8-bit samples, trimmed and loudness-normalised.
 */
class PhraseSynth(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false

    private suspend fun engine(): TextToSpeech? {
        tts?.let { if (ready) return it }
        return suspendCancellableCoroutine { cont ->
            var created: TextToSpeech? = null
            created = TextToSpeech(context) { status ->
                val engine = created
                if (status == TextToSpeech.SUCCESS && engine != null) {
                    engine.language = Locale.ITALIAN
                    tts = engine
                    ready = true
                    if (cont.isActive) cont.resume(engine)
                } else if (cont.isActive) {
                    cont.resume(null)
                }
            }
        }
    }

    /** Speaks [text] on the phone, to hear it before sending it. */
    suspend fun preview(text: String) {
        engine()?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "preview")
    }

    suspend fun render(text: String, maxBytes: Int): ByteArray? {
        val engine = engine() ?: return null
        val file = File(context.cacheDir, "phrase.wav")
        file.delete()
        val done = suspendCancellableCoroutine { cont ->
            val id = "phrase-${System.nanoTime()}"
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == id && cont.isActive) cont.resume(true)
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == id && cont.isActive) cont.resume(false)
                }
            })
            if (engine.synthesizeToFile(text, Bundle(), file, id) != TextToSpeech.SUCCESS && cont.isActive) {
                cont.resume(false)
            }
        }
        if (!done) return null
        return withContext(Dispatchers.Default) {
            runCatching { lampSamples(file.readBytes(), maxBytes) }.getOrNull()
        }
    }

    fun close() {
        tts?.shutdown()
        tts = null
        ready = false
    }
}

/** Plain 16-bit PCM WAV to 8 kHz signed 8-bit, at most [maxBytes] samples. */
fun lampSamples(wav: ByteArray, maxBytes: Int): ByteArray? {
    val buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
    if (wav.size < 44 || String(wav, 0, 4, Charsets.US_ASCII) != "RIFF") return null
    var channels = 1
    var rate = 0
    var bits = 0
    var dataAt = -1
    var dataLength = 0
    var at = 12
    while (at + 8 <= wav.size) {
        val id = String(wav, at, 4, Charsets.US_ASCII)
        val length = buffer.getInt(at + 4)
        if (id == "fmt ") {
            channels = buffer.getShort(at + 10).toInt()
            rate = buffer.getInt(at + 12)
            bits = buffer.getShort(at + 22).toInt()
        } else if (id == "data") {
            dataAt = at + 8
            // Some engines stream the file and leave the length at 0 or -1.
            dataLength = if (length <= 0) wav.size - dataAt else minOf(length, wav.size - dataAt)
            break
        }
        at += 8 + length + (length and 1)
    }
    if (dataAt < 0 || bits != 16 || rate <= 0 || channels <= 0) return null
    val frames = dataLength / (2 * channels)
    val mono = FloatArray(frames) { frame ->
        var sum = 0f
        for (channel in 0 until channels) sum += buffer.getShort(dataAt + (frame * channels + channel) * 2) / 32768f
        sum / channels
    }
    // Leave out the silence the engine puts around the words.
    val peak = mono.maxOfOrNull { abs(it) } ?: return null
    if (peak <= 0f) return null
    val gate = peak * 0.02f
    val first = mono.indexOfFirst { abs(it) > gate }.coerceAtLeast(0)
    val last = mono.indexOfLast { abs(it) > gate }.coerceAtLeast(first)
    val pad = rate / 50
    val start = (first - pad).coerceAtLeast(0)
    val end = (last + pad).coerceAtMost(mono.size - 1)

    // Average over each output period before picking samples, a cheap low-pass against aliasing.
    val ratio = rate.toDouble() / lampSampleRate
    val count = minOf(((end - start + 1) / ratio).toInt(), maxBytes)
    if (count <= 0) return null
    val out = FloatArray(count)
    for (index in 0 until count) {
        val from = start + (index * ratio).toInt()
        val to = minOf(start + ((index + 1) * ratio).toInt(), end + 1).coerceAtLeast(from + 1)
        var sum = 0f
        for (sample in from until to) sum += mono[sample]
        out[index] = sum / (to - from)
    }
    val loudest = out.maxOf { abs(it) }.coerceAtLeast(1e-4f)
    val gain = 0.95f / loudest
    // Short fades so the speaker does not click.
    val fade = minOf(count / 4, lampSampleRate / 100)
    return ByteArray(count) { index ->
        var value = out[index] * gain
        if (index < fade) value *= index / fade.toFloat()
        if (index >= count - fade) value *= (count - 1 - index) / fade.toFloat()
        (value * 127f).roundToInt().coerceIn(-127, 127).toByte()
    }
}
