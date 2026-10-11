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
