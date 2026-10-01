package com.emmaforo.remote

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import androidx.glance.appwidget.updateAll

class LampSession(private val app: Context) {
    val store = LampStore(app)
    val ble = BleLink(app, this)
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var searchTimeout: Runnable? = null
    private var remoteJob: Job? = null
    private var singleJob: Job? = null
    private var quietMix = false

    var connectionRequested = false
    var scanWhenReady = false

    var status by mutableStateOf("Cerco Emmaforo…")
        private set
    var ready by mutableStateOf(false)
        private set
    var outOfRange by mutableStateOf(false)
        private set
    var wifiUp by mutableStateOf(false)
        private set
    var resting by mutableStateOf(false)
        private set
    var blink by mutableStateOf(false)
    var level by mutableFloatStateOf(100f)
    var lastCommand by mutableStateOf<String?>(null)
        private set
    var selectedLight by mutableStateOf<String?>(null)
        private set
    var selectedSlotId by mutableStateOf<String?>(null)
        private set
    var selectedPaletteId by mutableStateOf<String?>(null)
        private set
    var slots by mutableStateOf<List<SlotColor?>>(List(colorSlotCount) { null })
    var mixRed by mutableFloatStateOf(0.92f)
    var mixGreen by mutableFloatStateOf(0.22f)
    var mixBlue by mutableFloatStateOf(0.18f)
    var networks by mutableStateOf<List<WifiNetwork>>(emptyList())
        private set
    var scanningNetworks by mutableStateOf(false)
        private set
    var scanNote by mutableStateOf<String?>(null)
        private set
    var wifiSsid by mutableStateOf("")
    var wifiPassword by mutableStateOf("")
    var savedNetwork by mutableStateOf("—")
        private set
    var boardHasPassword by mutableStateOf(false)
        private set
    var hasWifiStatus by mutableStateOf(false)
        private set
    var connectedNetworkName by mutableStateOf("")
        private set
    var deviceName by mutableStateOf("Emmaforo")
        private set
    var serialNumber by mutableStateOf("")
        private set
    var battery by mutableStateOf(BatteryReading())
        private set
    var safeCharging by mutableStateOf(true)
    var chargePaused by mutableStateOf(false)
    var gentleCharge by mutableStateOf(false)
    var hasChargeReport by mutableStateOf(false)
        private set
    var confirmRest by mutableStateOf(false)
    var tab by mutableIntStateOf(0)
    private var editingSlotId: String? = null
    private var lastBlink = false
    private var pending: Pair<String, Boolean>? = null

    val lampIsOn: Boolean get() = lastCommand != null && lastCommand != "spento"

    val connectionLine: String
        get() = when {
            resting -> "In riposo"
            ready -> "Connesso"
            wifiUp -> "Connesso via WiFi"
            outOfRange -> "Fuori portata"
            else -> status
        }

    val savedColorCount: Int get() = slots.count { it != null }

    val chargerLine: String
        get() = chargerNotice(
            battery.fault,
            safeCharging,
            chargePaused,
            battery.percent,
            battery.charging,
            battery.valid,
        )

    init {
        level = store.lightLevel.toFloat()
        blink = store.lampBlink
        lastBlink = blink
        slots = store.loadSlots()
        val color = store.lampColor
        if (store.lampOn && color.isNotEmpty()) {
            lastCommand = color
            focusCommand(color)
        }
    }

    fun requiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun hasBlePermission(): Boolean {
        return requiredPermissions().all {
            ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun start() {
        connectionRequested = true
        if (!hasBlePermission()) {
            ready = false
            outOfRange = false
            status = "Consenti il Bluetooth"
            return
        }
        if (!ble.adapterEnabled()) {
            ready = false
            outOfRange = false
            status = "Attiva il Bluetooth"
            return
        }
        if (ble.ready) {
            ready = true
            outOfRange = false
            status = "Connesso"
            return
        }
        status = "Cerco Emmaforo…"
        outOfRange = false
        ble.startScan()
        armSearch()
    }

    fun onBackground() {
        searchTimeout?.let { main.removeCallbacks(it) }
        ble.stopScan()
    }

    fun noteStatus(text: String) {
        status = text
    }

    fun onReady(isReady: Boolean) {
        ready = isReady
        if (isReady) {
            searchTimeout?.let { main.removeCallbacks(it) }
            outOfRange = false
            status = "Connesso"
            pending?.let { (command, blinkOn) ->
                pending = null
                ble.setLed(command, blinkOn, brightnessByte())
            }
        } else {
            status = "Comandi incompleti"
        }
    }

    fun onYielded() {
        ready = false
        outOfRange = true
        status = "Fuori portata"
    }

    fun onDropped() {
        ready = false
        scanningNetworks = false
        hasWifiStatus = false
        if (!connectionRequested) return
        outOfRange = false
        status = "Cerco Emmaforo…"
        ble.startScan()
        armSearch()
    }

    fun applyBattery(text: String) {
        val values = text.split('\t')
        if (values.size < 2) return
        val percent = values[0].toIntOrNull() ?: return
        val millivolts = values[1].toIntOrNull() ?: return
        battery = BatteryReading(
            percent = percent.coerceIn(0, 100),
            millivolts = millivolts,
            charging = values.size > 2 && values[2] == "1",
            chargeState = values.getOrNull(3)?.toIntOrNull(),
            fault = values.getOrNull(4)?.toIntOrNull() ?: 0,
            valid = values.size < 9 || values[8] == "1",
        )
        if (values.size > 5) safeCharging = values[5] == "1"
        if (values.size > 6) chargePaused = values[6] == "1"
        if (values.size > 7) {
            gentleCharge = values[7] == "1"
            hasChargeReport = true
        }
    }

    fun applyNetwork(text: String) {
        val fields = text.split('\t')
        val first = fields.firstOrNull() ?: return
        if (first == "0" || first == "1") {
            connectedNetworkName = fields.getOrNull(1) ?: ""
            val ip = fields.getOrNull(2).orEmpty()
            if (ip.isNotEmpty()) store.stationUrl = "http://$ip"
            boardHasPassword = fields.getOrNull(3) == "1"
            hasWifiStatus = true
            savedNetwork = if (connectedNetworkName.isEmpty()) "Nessuna" else connectedNetworkName
        } else {
            connectedNetworkName = text.trim()
            boardHasPassword = false
            hasWifiStatus = false
            savedNetwork = "—"
        }
    }

    fun applyName(name: String) {
        if (name.isNotEmpty()) deviceName = name
    }

    fun applySerial(serial: String) {
        serialNumber = serial
    }

    fun applySafe(enabled: Boolean) {
        safeCharging = enabled
    }

    fun applyPreset(data: ByteArray) {
        if (data.size != 25) return
        if (store.colorsDirty) {
            ble.writePreset(presetBytes())
            return
        }
        val decoded = decodePreset(data)
        val boardCount = decoded.count { it != null }
        if (boardCount == 0 && slots.any { it != null }) {
            store.colorsDirty = true
            ble.writePreset(presetBytes())
            return
        }
        val editing = editingSlotId
        val selected = selectedSlotId
        slots = decoded
        if (editing != null && slots.none { it?.id == editing }) editingSlotId = null
        if (selected != null && slots.none { it?.id == selected }) selectedSlotId = null
        store.saveSlots(slots)
    }

    fun flushPreset() {
        if (store.colorsDirty && ble.ready) ble.writePreset(presetBytes())
    }

    fun ackPreset() {
        store.colorsDirty = false
    }

    fun onScanStarted() {
        scanningNetworks = true
        scanNote = null
        networks = emptyList()
        status = "Scansione reti via Bluetooth..."
    }

    fun onScanFinished(found: List<WifiNetwork>, note: String) {
        scanningNetworks = false
        val best = LinkedHashMap<String, WifiNetwork>()
        found.sortedByDescending { it.rssi }.forEach { network ->
            if (!best.containsKey(network.ssid)) best[network.ssid] = network
        }
        networks = best.values.toList()
        scanNote = note
        status = note
    }

    fun tapLight(id: String) {
        if (resting) {
            wake()
            return
        }
        if (singleJob != null) {
            singleJob?.cancel()
            singleJob = null
            blink = !blink
            selectNamed(id)
            sendLight(id, blink)
            return
        }
        singleJob = scope.launch {
            delay(280)
            singleJob = null
            if (lampIsOn && lastCommand == id) {
                clearSelection()
                sendLight("spento", false)
            } else {
                selectNamed(id)
                sendLight(id, blink)
            }
        }
    }

    fun chooseSlot(index: Int, blinkOn: Boolean?) {
        if (blinkOn != null) {
            blink = blinkOn
            val lamp = materialize(index) ?: return
            focusSlot(lamp)
            selectedLight = null
            sendLight(lamp.command, blink)
            return
        }
        val current = slots.getOrNull(index)
        if (lampIsOn && current != null && current.id == selectedSlotId) {
            clearSelection()
            sendLight("spento", false)
            return
        }
        val lamp = materialize(index) ?: return
        focusSlot(lamp)
        selectedLight = null
        sendLight(lamp.command, blink)
    }

    fun choosePalette(swatch: PaletteColor, blinkOn: Boolean?) {
        if (blinkOn != null) {
            blink = blinkOn
            focusPalette(swatch)
            sendLight(swatch.command, blink)
            return
        }
        if (lampIsOn && selectedPaletteId == swatch.id) {
            clearSelection()
            sendLight("spento", false)
            return
        }
        focusPalette(swatch)
        sendLight(swatch.command, blink)
    }

    fun clearSlot(index: Int) {
        val lamp = slots.getOrNull(index) ?: return
        val wasOn = lampIsOn && selectedPaletteId == null && selectedSlotId == lamp.id
        if (editingSlotId == lamp.id) editingSlotId = null
        if (selectedSlotId == lamp.id) selectedSlotId = null
        slots = slots.toMutableList().also { it[index] = null }
        storeColors()
        if (!wasOn) return
        clearSelection()
        sendLight("spento", false)
    }

    fun setMix(red: Float? = null, green: Float? = null, blue: Float? = null) {
        if (red != null) mixRed = red
        if (green != null) mixGreen = green
        if (blue != null) mixBlue = blue
        if (quietMix) return
        val editing = editingSlotId ?: return
        val index = slots.indexOfFirst { it?.id == editing }
        val lamp = slots.getOrNull(index) ?: return
        val previous = lamp.command
        val updated = lamp.copy(red = mixRed, green = mixGreen, blue = mixBlue)
        slots = slots.toMutableList().also { it[index] = updated }
        storeColors()
        if (selectedPaletteId == null && selectedSlotId == updated.id && lastCommand == previous) {
            selectedLight = null
            sendLight(updated.command, blink)
        }
    }

    fun changeLevel(value: Float) {
        level = value.coerceIn(0f, 100f)
        store.lightLevel = level.roundToInt()
        val command = lastCommand ?: selectedLight ?: return
        sendLight(command, if (command == "spento") false else lastBlink)
    }

    fun selectNetwork() {
        if (ready) ble.scanNetworks() else {
            scanWhenReady = true
            start()
        }
    }

    fun saveNetwork() {
        ble.configure(wifiSsid, wifiPassword)
    }

    fun forgetNetwork() {
        wifiSsid = ""
        wifiPassword = ""
        store.stationUrl = ""
        ble.forgetNetwork()
        connectedNetworkName = ""
        boardHasPassword = false
        hasWifiStatus = true
        savedNetwork = "Nessuna"
    }

    fun changeSafeCharging(enabled: Boolean) {
        safeCharging = enabled
        ble.setSafe(enabled)
    }

    fun changeChargePaused(paused: Boolean) {
        chargePaused = paused
        ble.writePower(if (paused) "pause:1" else "pause:0")
    }

    fun setGentle(gentle: Boolean) {
        gentleCharge = gentle
        hasChargeReport = true
        ble.writePower(if (gentle) "gentle:1" else "gentle:0")
    }

    fun rest() {
        resting = true
        ble.writePower("riposo")
    }

    fun wake() {
        if (!resting) return
        resting = false
        singleJob?.cancel()
        singleJob = null
        val command = lastCommand ?: selectedLight ?: return
        sendLight(command, if (lastCommand == null) blink else lastBlink)
    }

    fun openPowerTab() {
        if (ready) ble.readPower()
        else if (wifiUp && store.stationUrl.isNotEmpty()) refreshBattery()
    }

    var widgetSend = false
        private set

    fun paintWidgetColor(color: String): Int {
        val turningOff = lampIsOn && lastCommand == color
        if (turningOff) {
            clearSelection()
            lastCommand = "spento"
            store.saveLamp(false, "", blink, brightnessByte())
        } else {
            blink = store.lampBlink
            focusCommand(color)
            lastCommand = color
            lastBlink = blink
            store.saveLamp(true, color, blink, brightnessByte())
        }
        widgetSend = true
        return store.lampToken
    }

    fun paintWidgetBlink(): Int {
        val send = lampIsOn
        blink = !blink
        lastBlink = blink
        val on = lampIsOn && !lastCommand.isNullOrEmpty() && lastCommand != "spento"
        val color = if (on) lastCommand.orEmpty() else ""
        store.saveLamp(on, color, blink, brightnessByte())
        widgetSend = send
        return store.lampToken
    }

    suspend fun deliverPainted(token: Int, previousOn: Boolean, previousColor: String, previousBlink: Boolean, previousLevel: Int) {
        val command = if (store.lampOn) store.lampColor else "spento"
        val blinkOn = if (command == "spento") false else store.lampBlink
        val ok = deliver(command, blinkOn, store.lampLevel)
        if (!ok && store.lampToken == token) {
            store.saveLamp(previousOn, previousColor, previousBlink, previousLevel)
            blink = previousBlink
            lastBlink = previousBlink
            if (previousOn && previousColor.isNotEmpty()) {
                lastCommand = previousColor
                focusCommand(previousColor)
            } else {
                lastCommand = if (previousOn) previousColor else "spento"
                if (!previousOn) clearSelection()
            }
            refreshWidget()
        }
    }

    fun slotSelected(slot: SlotColor?): Boolean {
        if (!lampIsOn || selectedPaletteId != null || slot == null || selectedSlotId == null) return false
        return slot.id == selectedSlotId
    }

    fun paletteSelected(swatch: PaletteColor): Boolean {
        if (!lampIsOn || selectedSlotId != null || selectedPaletteId == null) return false
        return selectedPaletteId == swatch.id
    }

    private fun selectNamed(id: String) {
        selectedLight = id
        selectedPaletteId = id
        selectedSlotId = null
    }

    private fun focusCommand(command: String) {
        val named = semaphoreLights.firstOrNull { it.id == command }
        if (named != null) {
            selectNamed(named.id)
            return
        }
        val swatch = lampPalette.firstOrNull { it.command == command }
        if (swatch != null) {
            selectedPaletteId = swatch.id
            selectedSlotId = null
            selectedLight = if (swatch.id == "rosso" || swatch.id == "giallo" || swatch.id == "verde") swatch.id else null
            return
        }
        val slot = slots.firstOrNull { it?.command == command }
        if (slot != null) {
            focusSlot(slot)
            selectedLight = null
        }
    }

    private fun focusSlot(lamp: SlotColor) {
        selectedSlotId = lamp.id
        selectedPaletteId = null
        editingSlotId = lamp.id
        quietMix = true
        mixRed = lamp.red
        mixGreen = lamp.green
        mixBlue = lamp.blue
        main.post { quietMix = false }
    }

    private fun focusPalette(swatch: PaletteColor) {
        selectedPaletteId = swatch.id
        selectedSlotId = null
        selectedLight = if (swatch.id == "rosso" || swatch.id == "giallo" || swatch.id == "verde") swatch.id else null
    }

    private fun clearSelection() {
        selectedSlotId = null
        selectedPaletteId = null
        selectedLight = null
    }

    private fun materialize(index: Int): SlotColor? {
        if (index !in slots.indices) return null
        slots[index]?.let { return it }
        val created = SlotColor(red = mixRed, green = mixGreen, blue = mixBlue)
        slots = slots.toMutableList().also { it[index] = created }
        storeColors()
        return created
    }

    private fun storeColors() {
        store.saveSlots(slots)
        store.colorsDirty = true
        ble.writePreset(presetBytes())
    }

    private fun presetBytes(): ByteArray {
        val bytes = ByteArray(25)
        slots.take(colorSlotCount).forEachIndexed { index, lamp ->
            if (lamp == null) return@forEachIndexed
            bytes[0] = (bytes[0].toInt() or (1 shl index)).toByte()
            val offset = 1 + index * 3
            bytes[offset] = (lamp.red.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255).toByte()
            bytes[offset + 1] = (lamp.green.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255).toByte()
            bytes[offset + 2] = (lamp.blue.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255).toByte()
        }
        return bytes
    }

    private fun decodePreset(data: ByteArray): List<SlotColor?> {
        val slots = MutableList<SlotColor?>(colorSlotCount) { null }
        if (data.size < 25) return slots
        for (index in 0 until colorSlotCount) {
            if (data[0].toInt() and (1 shl index) == 0) continue
            val offset = 1 + index * 3
            slots[index] = SlotColor(
                red = (data[offset].toInt() and 0xFF) / 255f,
                green = (data[offset + 1].toInt() and 0xFF) / 255f,
                blue = (data[offset + 2].toInt() and 0xFF) / 255f,
            )
        }
        return slots
    }

    private fun brightnessByte(): Int = (level.coerceIn(0f, 100f) / 100f * 255f).roundToInt()

    private fun sendLight(command: String, blinkOn: Boolean) {
        lastCommand = command
        if (command != "spento") lastBlink = blinkOn
        val on = command != "spento"
        store.saveLamp(on, if (on) command else "", blinkOn, brightnessByte())
        refreshWidget()
        if (ble.ready) {
            pending = null
            ble.setLed(command, blinkOn, brightnessByte())
            return
        }
        pending = command to blinkOn
        if (outOfRange) {
            ble.yield()
            deliverPending()
            return
        }
        start()
    }

    private fun deliverPending() {
        remoteJob?.cancel()
        remoteJob = scope.launch(Dispatchers.IO) {
            repeat(4) {
                if (ble.ready) return@launch
                val waiting = pending
                if (waiting != null) {
                    val endpoint = WifiLink.send(store, waiting.first, waiting.second, brightnessByte())
                    if (endpoint != null) {
                        withContext(Dispatchers.Main) {
                            wifiUp = true
                            pending = null
                            status = "Connesso via WiFi"
                        }
                        return@launch
                    }
                } else {
                    val endpoint = WifiLink.resolve(store)
                    if (endpoint != null) {
                        withContext(Dispatchers.Main) {
                            wifiUp = true
                            status = "Connesso via WiFi"
                        }
                        return@launch
                    }
                }
                delay(3_000)
            }
            withContext(Dispatchers.Main) {
                if (!ble.ready) wifiUp = false
            }
        }
    }

    private suspend fun deliver(command: String, blinkOn: Boolean, levelByte: Int): Boolean {
        if (hasBlePermission() && ble.adapterEnabled()) {
            val bleOk = suspendCancellableCoroutine { cont ->
                ble.sendOrConnect(command, blinkOn, levelByte, 8_000) { ok ->
                    if (cont.isActive) cont.resume(ok)
                }
            }
            if (bleOk) {
                withContext(Dispatchers.Main) {
                    ready = true
                    outOfRange = false
                    status = "Connesso"
                }
                return true
            }
        }
        val endpoint = withContext(Dispatchers.IO) { WifiLink.send(store, command, blinkOn, levelByte) }
        if (endpoint != null) {
            withContext(Dispatchers.Main) {
                wifiUp = true
                outOfRange = true
                if (!ble.ready) status = "Connesso via WiFi"
            }
            return true
        }
        return false
    }

    private fun refreshBattery() {
        val endpoint = store.stationUrl
        if (endpoint.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val reading = WifiLink.battery(endpoint) ?: return@launch
            withContext(Dispatchers.Main) { battery = reading }
        }
    }

    private fun refreshWidget() {
        val context = app
        scope.launch(Dispatchers.IO) {
            runCatching { EmmaforoWidget().updateAll(context) }
        }
    }

    private fun armSearch() {
        searchTimeout?.let { main.removeCallbacks(it) }
        val timeout = Runnable {
            if (!ble.ready) {
                ble.stopScan()
                ready = false
                outOfRange = true
                status = "Fuori portata"
                deliverPending()
            }
        }
        searchTimeout = timeout
        main.postDelayed(timeout, 12_000)
    }
}
