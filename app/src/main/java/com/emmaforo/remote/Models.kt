package com.emmaforo.remote

import androidx.compose.ui.graphics.Color
import java.util.UUID
import kotlin.math.roundToInt

data class PaletteColor(
    val id: String,
    val name: String,
    val red: Float,
    val green: Float,
    val blue: Float,
) {
    val color: Color get() = Color(red, green, blue)
    val command: String
        get() = if (id == "rosso" || id == "giallo" || id == "verde") id else rgbHex(red, green, blue)
}

data class SlotColor(
    val id: String = UUID.randomUUID().toString(),
    val red: Float,
    val green: Float,
    val blue: Float,
) {
    val color: Color get() = Color(red, green, blue)
    val command: String get() = rgbHex(red, green, blue)
}

data class WifiNetwork(val ssid: String, val rssi: Int)

data class BatteryReading(
    val percent: Int? = null,
    val millivolts: Int? = null,
    val charging: Boolean = false,
    val chargeState: Int? = null,
    val fault: Int = 0,
    val valid: Boolean = false,
)

val semaphoreLights = listOf(
    PaletteColor("rosso", "Rosso", 0.92f, 0.22f, 0.18f),
    PaletteColor("giallo", "Giallo", 0.96f, 0.72f, 0.12f),
    PaletteColor("verde", "Verde", 0.18f, 0.72f, 0.34f),
)

val lampPalette = listOf(
    PaletteColor("rosso", "Rosso", 0.92f, 0.22f, 0.18f),
    PaletteColor("arancio", "Arancio", 0.98f, 0.45f, 0.08f),
    PaletteColor("ambra", "Ambra", 0.98f, 0.62f, 0.12f),
    PaletteColor("giallo", "Giallo", 0.96f, 0.72f, 0.12f),
    PaletteColor("lime", "Lime", 0.62f, 0.86f, 0.16f),
    PaletteColor("verde", "Verde", 0.18f, 0.72f, 0.34f),
    PaletteColor("menta", "Menta", 0.20f, 0.82f, 0.62f),
    PaletteColor("ciano", "Ciano", 0.10f, 0.78f, 0.86f),
    PaletteColor("azzurro", "Azzurro", 0.25f, 0.58f, 0.98f),
    PaletteColor("blu", "Blu", 0.16f, 0.32f, 0.90f),
    PaletteColor("indaco", "Indaco", 0.28f, 0.18f, 0.62f),
    PaletteColor("viola", "Viola", 0.52f, 0.20f, 0.82f),
    PaletteColor("magenta", "Magenta", 0.90f, 0.12f, 0.52f),
    PaletteColor("rosa", "Rosa", 0.98f, 0.45f, 0.62f),
    PaletteColor("bianco-caldo", "Bianco caldo", 1f, 0.78f, 0.48f),
    PaletteColor("bianco", "Bianco", 1f, 1f, 1f),
)

const val colorSlotCount = 8

fun rgbHex(red: Float, green: Float, blue: Float): String {
    fun channel(value: Float) = (value.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
    return String.format("#%02X%02X%02X", channel(red), channel(green), channel(blue))
}

fun chargerNotice(
    fault: Int,
    safeCharging: Boolean,
    chargePaused: Boolean,
    percent: Int?,
    charging: Boolean,
    valid: Boolean,
): String {
    if (fault == 0) {
        val holding = safeCharging && !chargePaused && valid && percent != null && percent >= 90 && !charging
        return if (holding) "Ricarica sicura: in pausa intorno al 90%." else "Nessun guasto"
    }
    if (fault and 0x08 != 0) return "La tensione della batteria è troppo alta."
    when ((fault shr 4) and 0x3) {
        1 -> return "L'ingresso di alimentazione non è valido."
        2 -> return "Il caricatore si è fermato per temperatura."
        3 -> return "Il timer di sicurezza della carica è scaduto."
    }
    when (fault and 0x7) {
        0x6 -> return "La batteria è troppo calda."
        0x5 -> return "La batteria è troppo fredda."
        0x2 -> return "La batteria è tiepida."
        0x3 -> return "La batteria è fresca."
    }
    if (fault and 0x40 != 0) return "L'uscita boost del caricatore è sovraccarica."
    if (fault and 0x80 != 0) return "Il timer di controllo del caricatore è scaduto."
    return "Registro guasti del caricatore, valore $fault."
}

/** One step of a chain: fade to the colour in [fadeTenths], stay [holdTenths], play [sound] on arrival. */
data class ChainStep(
    val id: String = UUID.randomUUID().toString(),
    val red: Float,
    val green: Float,
    val blue: Float,
    val fadeTenths: Int = 10,
    val holdTenths: Int = 30,
    val sound: Int = 0,
) {
    val color: Color get() = Color(red, green, blue)
}

data class ColorChain(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val loop: Boolean = true,
    val steps: List<ChainStep> = emptyList(),
) {
    val totalTenths: Int get() = steps.sumOf { it.fadeTenths + it.holdTenths }
    val playable: Boolean get() = steps.isNotEmpty() && !(loop && totalTenths == 0)
}

/** What the lamp reports on FFEB. */
data class ChainState(val active: Boolean, val step: Int, val loop: Boolean, val steps: List<ChainStep>)

/** A sound number as the firmware knows it: 0 none, 1-5 built in, 16-20 voice slots 0-4. */
data class LampSound(val number: Int, val label: String)

const val chainStepLimit = 16
const val chainTenthsLimit = 65_535
const val voiceSlotCount = 5
const val firstVoiceSound = 16

val builtInSounds = listOf(
    LampSound(0, "Nessuno"),
    LampSound(1, "Bip"),
    LampSound(2, "Doppio bip"),
    LampSound(3, "Din-don"),
    LampSound(4, "Allarme"),
    LampSound(5, "Melodia"),
)

/** The lamp states that each have a sound, in the order FFEA reads them. */
val soundStates = listOf("rosso" to "Rosso", "giallo" to "Giallo", "verde" to "Verde", "spento" to "Spento", "altro" to "Altri colori")

/** Suggested sentences for the voice slots, the first three tied to the traffic-light colours. */
val defaultPhrases = listOf("Stop", "Attenzione", "Avanti", "", "")

fun stepFromCommand(command: String): ChainStep {
    val color = commandColor(command)
    return ChainStep(red = color.red, green = color.green, blue = color.blue)
}

fun commandColor(command: String): Color {
    lampPalette.firstOrNull { it.command == command || it.id == command }?.let { return it.color }
    if (command.length == 7 && command.startsWith("#")) {
        val value = command.substring(1).toIntOrNull(16) ?: return Color.Gray
        return Color(((value shr 16) and 0xFF) / 255f, ((value shr 8) and 0xFF) / 255f, (value and 0xFF) / 255f)
    }
    return Color.Gray
}

fun tenthsText(tenths: Int): String {
    val seconds = tenths / 10
    val rest = tenths % 10
    return if (seconds >= 60) "${seconds / 60} min ${seconds % 60} s" else if (rest == 0) "$seconds s" else "$seconds,$rest s"
}

private fun channelByte(value: Float): Byte = (value.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255).toByte()

/** FFEB write `01 flags n` then 8 bytes a step: r g b, u16 fade, u16 hold (tenths of a second), sound. */
fun chainBytes(chain: ColorChain): ByteArray {
    val steps = chain.steps.take(chainStepLimit)
    val bytes = ByteArray(3 + steps.size * 8)
    bytes[0] = 1
    bytes[1] = if (chain.loop) 1 else 0
    bytes[2] = steps.size.toByte()
    steps.forEachIndexed { index, step ->
        val at = 3 + index * 8
        bytes[at] = channelByte(step.red)
        bytes[at + 1] = channelByte(step.green)
        bytes[at + 2] = channelByte(step.blue)
        val fade = step.fadeTenths.coerceIn(0, chainTenthsLimit)
        val hold = step.holdTenths.coerceIn(0, chainTenthsLimit)
        bytes[at + 3] = (fade and 0xFF).toByte()
        bytes[at + 4] = (fade shr 8).toByte()
        bytes[at + 5] = (hold and 0xFF).toByte()
        bytes[at + 6] = (hold shr 8).toByte()
        bytes[at + 7] = step.sound.coerceIn(0, 255).toByte()
    }
    return bytes
}

/** FFEB read: `active step flags n` and the steps. */
fun decodeChainState(data: ByteArray): ChainState? {
    if (data.size < 4) return null
    fun u8(index: Int) = data[index].toInt() and 0xFF
    val count = u8(3).coerceAtMost((data.size - 4) / 8)
    val steps = (0 until count).map { index ->
        val at = 4 + index * 8
        ChainStep(
            red = u8(at) / 255f,
            green = u8(at + 1) / 255f,
            blue = u8(at + 2) / 255f,
            fadeTenths = u8(at + 3) or (u8(at + 4) shl 8),
            holdTenths = u8(at + 5) or (u8(at + 6) shl 8),
            sound = u8(at + 7),
        )
    }
    return ChainState(active = u8(0) != 0, step = u8(1), loop = u8(2) and 1 != 0, steps = steps)
}
