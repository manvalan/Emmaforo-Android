package com.emmaforo.remote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID

class BleLink(
    private val context: Context,
    private val session: LampSession,
) {
    private val main = Handler(Looper.getMainLooper())
    private val serviceUuid = uuid("FFF0")
    private val ssidUuid = uuid("FFF1")
    private val passwordUuid = uuid("FFF2")
    private val applyUuid = uuid("FFF3")
    private val scanRequestUuid = uuid("FFF4")
    private val scanResultsUuid = uuid("FFF5")
    private val ledUuid = uuid("FFF6")
    private val networkUuid = uuid("FFF7")
    private val batteryUuid = uuid("FFF9")
    private val powerUuid = uuid("FFFA")
    private val nameUuid = uuid("FFFB")
    private val safeUuid = uuid("FFFC")
    private val presetUuid = uuid("FFFD")
    private val serialUuid = uuid("FFFE")
    private val firmwareVersionUuid = uuid("FFEF")
    private val firmwareApproveUuid = uuid("FFEE")
    private val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    var ready: Boolean = false
        private set

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var gatt: BluetoothGatt? = null
    private val chars = HashMap<UUID, BluetoothGattCharacteristic>()
    private val queue = ArrayDeque<Op>()
    private var writing = false
    private var pendingLed: ByteArray? = null
    private var scanning = false
    private var connecting = false
    private var yielding = false
    private var scanBuffer = ""
    private var scanSession = 0
    private var scanTimeout: Runnable? = null
    private var lampWaiter: ((Boolean) -> Unit)? = null
    private var queuedLamp: Triple<String, Boolean, Int>? = null
    private var lampTimeout: Runnable? = null
    private var setupDone = false

    private sealed class Op {
        class Write(val characteristic: BluetoothGattCharacteristic, val value: ByteArray, val kind: String) : Op()
        class Descriptor(val descriptor: BluetoothGattDescriptor, val value: ByteArray) : Op()
        class Read(val characteristic: BluetoothGattCharacteristic) : Op()
    }

    fun adapterEnabled(): Boolean = adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun startScan() {
        val radio = adapter ?: return
        if (!radio.isEnabled || !session.hasBlePermission()) return
        if (ready) return
        val scanner = radio.bluetoothLeScanner ?: return
        if (!scanning) {
            val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUuid)).build())
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            try {
                scanner.startScan(filters, settings, scanCallback)
                scanning = true
            } catch (_: SecurityException) {
                session.noteStatus("Consenti il Bluetooth")
                return
            }
        }
        val remembered = session.store.peripheralAddress
        if (remembered.isNotEmpty() && !connecting && gatt == null) {
            connect(radio.getRemoteDevice(remembered))
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
        }
    }

    @SuppressLint("MissingPermission")
    fun yield() {
        if (ready) return
        yielding = true
        stopScan()
        gatt?.let { link ->
            try {
                link.disconnect()
            } catch (_: SecurityException) {
            }
        }
        if (gatt == null) {
            yielding = false
        }
    }

    fun setLed(command: String, blink: Boolean, level: Int) {
        if (!ready || chars[ledUuid] == null) {
            session.noteStatus("Collega prima EmmaForo")
            return
        }
        val payload = "0,$command,${if (blink) 1 else 0},1000,${level.coerceIn(0, 255)}"
        pendingLed = payload.toByteArray(Charsets.UTF_8)
        main.post { pump() }
    }

    fun sendOrConnect(command: String, blink: Boolean, level: Int, waitMs: Long, done: (Boolean) -> Unit) {
        main.post {
            lampWaiter?.invoke(false)
            lampWaiter = done
            queuedLamp = Triple(command, blink, level)
            lampTimeout?.let { main.removeCallbacks(it) }
            val timeout = Runnable {
                queuedLamp = null
                finishLamp(false)
            }
            lampTimeout = timeout
            main.postDelayed(timeout, waitMs)
            session.connectionRequested = true
            if (ready) dispatchQueued() else startScan()
        }
    }

    fun configure(ssid: String, password: String) {
        val name = chars[ssidUuid]
        val secret = chars[passwordUuid]
        val apply = chars[applyUuid]
        if (!ready || name == null || secret == null || apply == null) {
            session.noteStatus("Collega prima EmmaForo")
            return
        }
        queue.removeAll { it is Op.Write && (it.kind == "net" || it.kind == "apply") }
        session.noteStatus("Salvo la rete…")
        enqueue(name, ssid.toByteArray(Charsets.UTF_8), "net")
        enqueue(secret, password.toByteArray(Charsets.UTF_8), "net")
        enqueue(apply, byteArrayOf(1), "apply")
    }

    fun forgetNetwork() {
        configure("", "")
    }

    @SuppressLint("MissingPermission")
    fun scanNetworks() {
        if (!ready || chars[scanRequestUuid] == null || chars[scanResultsUuid] == null) {
            session.noteStatus("Collega prima EmmaForo")
            return
        }
        scanSession += 1
        val sessionId = scanSession
        scanBuffer = ""
        session.onScanStarted()
        val results = chars[scanResultsUuid]
        if (results != null) armNotify(results)
        enqueue(chars[scanRequestUuid]!!, byteArrayOf(1), "scan")
        scanTimeout?.let { main.removeCallbacks(it) }
        val timeout = Runnable {
            if (session.scanningNetworks && scanSession == sessionId) {
                session.onScanFinished(emptyList(), "Timeout scansione reti")
                scanBuffer = ""
            }
        }
        scanTimeout = timeout
        main.postDelayed(timeout, 20_000)
    }

    fun writePreset(bytes: ByteArray) {
        val preset = chars[presetUuid] ?: return
        if (!ready || bytes.size != 25) return
        queue.removeAll { it is Op.Write && it.kind == "preset" }
        enqueue(preset, bytes, "preset")
    }

    fun setSafe(enabled: Boolean) {
        val characteristic = chars[safeUuid] ?: return
        if (!ready) return
        enqueue(characteristic, byteArrayOf(if (enabled) 1 else 0), "safe")
    }

    fun writePower(text: String) {
        val characteristic = chars[powerUuid] ?: return
        if (!ready) return
        enqueue(characteristic, text.toByteArray(Charsets.UTF_8), "power")
    }

    fun approveFirmware(version: String): Boolean {
        val characteristic = chars[firmwareApproveUuid] ?: return false
        if (!ready) return false
        enqueue(characteristic, version.toByteArray(Charsets.UTF_8), "firmware")
        return true
    }

    /** Lets go of the lamp so a 1.0 board keeps Wi-Fi up for the download. */
    @SuppressLint("MissingPermission")
    fun release() {
        yielding = true
        stopScan()
        val link = gatt
        if (link == null) {
            yielding = false
            return
        }
        try {
            link.disconnect()
        } catch (_: SecurityException) {
        }
    }

    fun readPower() {
        chars[batteryUuid]?.let { enqueueRead(it) }
        chars[safeUuid]?.let { enqueueRead(it) }
    }

    private fun dispatchQueued() {
        val item = queuedLamp ?: return
        if (!ready) return
        setLed(item.first, item.second, item.third)
    }

    private fun finishLamp(ok: Boolean) {
        val waiter = lampWaiter ?: return
        lampTimeout?.let { main.removeCallbacks(it) }
        lampTimeout = null
        queuedLamp = null
        lampWaiter = null
        waiter(ok)
    }

    private fun enqueue(characteristic: BluetoothGattCharacteristic, value: ByteArray, kind: String) {
        queue.add(Op.Write(characteristic, value, kind))
        pump()
    }

    private fun enqueueRead(characteristic: BluetoothGattCharacteristic) {
        queue.add(Op.Read(characteristic))
        pump()
    }

    @SuppressLint("MissingPermission")
    private fun pump() {
        if (writing) return
        val led = pendingLed
        val ledChar = chars[ledUuid]
        if (led != null && ledChar != null && ready) {
            pendingLed = null
            writeNow(ledChar, led, "led")
            return
        }
        when (val next = queue.removeFirstOrNull()) {
            null -> return
            is Op.Write -> writeNow(next.characteristic, next.value, next.kind)
            is Op.Descriptor -> writeDescriptor(next)
            is Op.Read -> {
                writing = true
                val started = try {
                    gatt?.readCharacteristic(next.characteristic) == true
                } catch (_: SecurityException) {
                    false
                }
                if (!started) {
                    writing = false
                    pump()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeNow(characteristic: BluetoothGattCharacteristic, value: ByteArray, kind: String) {
        val link = gatt ?: return
        writing = true
        val type = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        val ok = try {
            if (Build.VERSION.SDK_INT >= 33) {
                link.writeCharacteristic(characteristic, value, type) == BluetoothStatusCodes.SUCCESS
            } else {
                characteristic.writeType = type
                characteristic.value = value
                link.writeCharacteristic(characteristic)
            }
        } catch (_: SecurityException) {
            false
        }
        if (!ok) {
            writing = false
            fail(kind)
            pump()
        } else if (type == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
            writing = false
            succeed(kind)
            pump()
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeDescriptor(op: Op.Descriptor) {
        val link = gatt ?: return
        writing = true
        val ok = try {
            if (Build.VERSION.SDK_INT >= 33) {
                link.writeDescriptor(op.descriptor, op.value) == BluetoothStatusCodes.SUCCESS
            } else {
                op.descriptor.value = op.value
                link.writeDescriptor(op.descriptor)
            }
        } catch (_: SecurityException) {
            false
        }
        if (!ok) {
            writing = false
            pump()
        }
    }

    private fun succeed(kind: String) {
        when (kind) {
            "apply" -> session.noteStatus("Rete salvata sulla scheda")
            "preset" -> session.ackPreset()
            "led" -> finishLamp(true)
            "firmware" -> session.onFirmwareApproved()
        }
    }

    private fun fail(kind: String) {
        when (kind) {
            "led" -> {
                session.noteStatus("Errore comando luce")
                finishLamp(false)
            }
            "net", "apply" -> session.noteStatus("Errore invio rete")
            "scan" -> session.onScanFinished(emptyList(), "Errore scansione reti")
            "firmware" -> session.onFirmwareApproveFailed()
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice) {
        if (connecting || ready) return
        connecting = true
        session.noteStatus("Connessione…")
        try {
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (_: SecurityException) {
            connecting = false
            session.noteStatus("Consenti il Bluetooth")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post { consider(result) }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            main.post { results.forEach { consider(it) } }
        }
    }

    @SuppressLint("MissingPermission")
    private fun consider(result: ScanResult) {
        if (ready || connecting) return
        val name = result.scanRecord?.deviceName ?: result.device.name ?: ""
        val services = result.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()
        val match = name.contains("emmaforo", ignoreCase = true) || services.contains(serviceUuid)
        if (!match && result.scanRecord?.serviceUuids?.isEmpty() != false && name.isEmpty()) {
            connect(result.device)
            return
        }
        if (!match) return
        session.store.peripheralAddress = result.device.address
        connect(result.device)
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    this@BleLink.gatt = gatt
                    session.noteStatus("Individuo i comandi...")
                    try {
                        gatt.requestMtu(185)
                        gatt.discoverServices()
                    } catch (_: SecurityException) {
                        drop(gatt)
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    drop(gatt)
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            main.post { continueSetup() }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            main.post {
                val service = gatt.getService(serviceUuid)
                if (service == null) {
                    session.noteStatus("Fuori portata")
                    try {
                        gatt.disconnect()
                    } catch (_: SecurityException) {
                    }
                    return@post
                }
                chars.clear()
                service.characteristics.forEach { chars[it.uuid] = it }
                ready = chars[ssidUuid] != null && chars[passwordUuid] != null && chars[applyUuid] != null
                connecting = false
                stopScan()
                session.onReady(ready)
                if (!ready) return@post
                main.postDelayed({ continueSetup() }, 500)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            main.post {
                writing = false
                val kind = kindFor(characteristic.uuid)
                if (status == BluetoothGatt.GATT_SUCCESS) succeed(kind) else fail(kind)
                pump()
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            main.post {
                writing = false
                pump()
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT < 33) return
            main.post {
                writing = false
                if (status == BluetoothGatt.GATT_SUCCESS) ingest(characteristic.uuid, value)
                pump()
            }
        }

        @Deprecated("Legacy read callback")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT >= 33) return
            main.post {
                writing = false
                if (status == BluetoothGatt.GATT_SUCCESS) ingest(characteristic.uuid, characteristic.value ?: byteArrayOf())
                pump()
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            if (Build.VERSION.SDK_INT < 33) return
            main.post { ingest(characteristic.uuid, value) }
        }

        @Deprecated("Legacy notify callback")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= 33) return
            main.post { ingest(characteristic.uuid, characteristic.value ?: return@post) }
        }
    }

    private fun continueSetup() {
        if (setupDone || !ready) return
        setupDone = true
        listOf(scanResultsUuid, batteryUuid, networkUuid).forEach { id ->
            chars[id]?.let { armNotify(it) }
        }
        listOf(batteryUuid, networkUuid, nameUuid, serialUuid, firmwareVersionUuid, presetUuid, safeUuid).forEach { id ->
            chars[id]?.let { enqueueRead(it) }
        }
        if (session.scanWhenReady) {
            session.scanWhenReady = false
            scanNetworks()
        }
        dispatchQueued()
        session.flushPreset()
        pump()
    }

    @SuppressLint("MissingPermission")
    private fun armNotify(characteristic: BluetoothGattCharacteristic) {
        try {
            gatt?.setCharacteristicNotification(characteristic, true)
        } catch (_: SecurityException) {
            return
        }
        val descriptor = characteristic.getDescriptor(cccd) ?: return
        queue.add(Op.Descriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE))
    }

    private fun kindFor(id: UUID): String = when (id) {
        ledUuid -> "led"
        ssidUuid, passwordUuid -> "net"
        applyUuid -> "apply"
        presetUuid -> "preset"
        scanRequestUuid -> "scan"
        firmwareApproveUuid -> "firmware"
        else -> "other"
    }

    private fun ingest(id: UUID, value: ByteArray) {
        val text = value.toString(Charsets.UTF_8)
        when (id) {
            batteryUuid -> session.applyBattery(text)
            networkUuid -> session.applyNetwork(text)
            nameUuid -> session.applyName(text.trim())
            serialUuid -> session.applySerial(text.trim())
            firmwareVersionUuid -> session.applyFirmwareVersion(text.trim())
            presetUuid -> if (value.size == 25) session.applyPreset(value)
            safeUuid -> if (value.isNotEmpty()) session.applySafe(value[0].toInt() != 0)
            scanResultsUuid -> ingestScan(text)
        }
    }

    private fun ingestScan(text: String) {
        if (text.isEmpty()) return
        scanBuffer += text
        if (!scanBuffer.contains("--END--")) {
            session.noteStatus("Ricevo reti...")
            return
        }
        scanTimeout?.let { main.removeCallbacks(it) }
        val lines = scanBuffer.replace("\r", "").split('\n')
        val error = lines.firstOrNull { it.startsWith("--ERROR--\t") }
        if (error != null) {
            session.onScanFinished(emptyList(), "Errore scansione: ${error.removePrefix("--ERROR--\t")}")
            scanBuffer = ""
            return
        }
        val networks = lines.mapNotNull { line ->
            val cleaned = line.trim()
            if (cleaned.isEmpty() || cleaned == "--END--") return@mapNotNull null
            val parts = cleaned.split('\t', limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val rssi = parts[1].toIntOrNull() ?: return@mapNotNull null
            WifiNetwork(parts[0], rssi)
        }
        session.onScanFinished(networks, if (networks.isEmpty()) "Nessuna rete trovata" else "Reti trovate")
        scanBuffer = ""
    }

    @SuppressLint("MissingPermission")
    private fun drop(link: BluetoothGatt) {
        ready = false
        connecting = false
        writing = false
        setupDone = false
        queue.clear()
        pendingLed = null
        chars.clear()
        try {
            link.close()
        } catch (_: SecurityException) {
        }
        if (gatt == link) gatt = null
        if (yielding) {
            yielding = false
            session.onYielded()
            return
        }
        session.onDropped()
    }

    private fun uuid(short: String): UUID = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")
}
