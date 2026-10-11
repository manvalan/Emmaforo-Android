package com.emmaforo.remote

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Battery0Bar
import androidx.compose.material.icons.outlined.Battery2Bar
import androidx.compose.material.icons.outlined.Battery4Bar
import androidx.compose.material.icons.outlined.Battery6Bar
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val page = Color(0xFFF2F2F7)
private val ink = Color(0xFF111111)
private val quiet = Color(0xFF8E8E93)

@Composable
fun EmmaforoScreen(session: LampSession) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) session.start() else session.noteStatus("Consenti il Bluetooth")
    }
    LaunchedEffect(Unit) {
        if (session.hasBlePermission()) session.start() else permissionLauncher.launch(session.requiredPermissions())
    }
    MaterialTheme(colorScheme = lightColorScheme(primary = ink, background = page, surface = Color.White)) {
        Scaffold(
            containerColor = page,
            bottomBar = {
                NavigationBar(containerColor = page) {
                    NavigationBarItem(
                        selected = session.tab == 0,
                        onClick = { session.tab = 0 },
                        icon = { SemaphoreIcon() },
                        label = { Text("Semaforo") },
                    )
                    NavigationBarItem(
                        selected = session.tab == 1,
                        onClick = {
                            session.tab = 1
                            session.openPowerTab()
                        },
                        icon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                        label = { Text("Info") },
                    )
                    NavigationBarItem(
                        selected = session.tab == 2,
                        onClick = { session.tab = 2 },
                        icon = { Icon(Icons.Outlined.Palette, contentDescription = null) },
                        label = { Text("Colori") },
                    )
                    NavigationBarItem(
                        selected = session.tab == 3,
                        onClick = {
                            session.tab = 3
                            session.openPowerTab()
                        },
                        icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                        label = { Text("Configurazione") },
                    )
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (session.tab) {
                    0 -> SemaphorePage(session, permissionLauncher::launch)
                    1 -> InfoPage(session)
                    2 -> ColorsPage(session)
                    else -> ConfigPage(session)
                }
            }
        }
    }
    if (session.confirmFirmware) {
        AlertDialog(
            onDismissRequest = { session.confirmFirmware = false },
            title = { Text("Aggiornare il firmware?") },
            text = { Text("La scheda accende il Wi‑Fi solo per scaricare questa versione sulla rete salvata, si riavvia e lo spegne di nuovo.") },
            confirmButton = {
                TextButton(onClick = {
                    session.confirmFirmware = false
                    session.approveFirmware()
                }) { Text("Aggiorna") }
            },
            dismissButton = {
                TextButton(onClick = { session.confirmFirmware = false }) { Text("Annulla") }
            },
        )
    }
    if (session.confirmRest) {
        AlertDialog(
            onDismissRequest = { session.confirmRest = false },
            title = { Text("Mettere in riposo?") },
            text = { Text("Il controller dorme e resta svegliabile da Bluetooth.") },
            confirmButton = {
                TextButton(onClick = {
                    session.confirmRest = false
                    session.rest()
                }) { Text("Riposo") }
            },
            dismissButton = {
                TextButton(onClick = { session.confirmRest = false }) { Text("Annulla") }
            },
        )
    }
}

@Composable
private fun SemaphorePage(session: LampSession, askPermission: (Array<String>) -> Unit) {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp)
                .padding(top = 28.dp, bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (session.connectionLine.startsWith("Cerco") || session.connectionLine.startsWith("Connessione") || session.connectionLine.startsWith("Individuo")) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = quiet)
                    Spacer(Modifier.width(8.dp))
                }
                Text(session.connectionLine, color = quiet, fontSize = 15.sp)
                Spacer(Modifier.weight(1f))
                val percent = session.battery.percent
                if (session.battery.valid && percent != null) {
                    Icon(batteryIcon(percent, session.battery.charging), contentDescription = null, tint = quiet, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("$percent%", color = quiet, fontSize = 15.sp)
                }
            }
            if (session.connectionLine == "Consenti il Bluetooth") {
                TextButton(onClick = {
                    val activity = context as? MainActivity
                    val permanent = session.requiredPermissions().any { permission ->
                        activity?.shouldShowRequestPermissionRationale(permission) == false && !session.hasBlePermission()
                    }
                    if (permanent && activity?.shouldShowRequestPermissionRationale(session.requiredPermissions().first()) == false) {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        )
                    } else {
                        askPermission(session.requiredPermissions())
                    }
                }) { Text("Consenti") }
            }
            if (session.connectionLine == "Attiva il Bluetooth") {
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                }) { Text("Apri Bluetooth") }
            }
            Spacer(Modifier.height(36.dp))
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                semaphoreLights.forEach { light ->
                    val selected = session.selectedLight == light.id && !session.resting && session.lampIsOn
                    ColorDisc(
                        color = if (session.resting) light.color.copy(alpha = 0.32f) else light.color,
                        filled = selected,
                        blinking = selected && session.blink,
                        size = 88.dp,
                        modifier = Modifier.releaseTap { session.tapLight(light.id) },
                        description = light.name,
                    )
                }
            }
            Spacer(Modifier.height(36.dp))
            Text("Intensità luminosa", color = ink)
            Slider(
                value = session.level,
                onValueChange = { session.changeLevel(it) },
                valueRange = 0f..100f,
                steps = 99,
            )
            Text("Regola la luce accesa adesso.", color = quiet, fontSize = 13.sp)
        }
        if (session.resting) {
            Box(Modifier.fillMaxSize().releaseTap { session.wake() })
        }
    }
}

@Composable
private fun InfoPage(session: LampSession) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FormCard {
            InfoRow("Nome", session.deviceName)
            InfoRow("Wi‑Fi", wifiSummary(session))
            InfoRow("Ricarica sicura", chargeWord(session, session.safeCharging))
            InfoRow("Pausa ricarica", chargeWord(session, session.chargePaused))
            InfoRow("Corrente di carica", if (session.hasChargeReport) if (session.gentleCharge) "Gentile" else "Normale" else "—")
            Text(session.chargerLine, color = quiet, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            InfoRow("Colori", "${session.savedColorCount} di 8")
            InfoRow("Numero di serie", if (session.serialNumber.isEmpty()) "—" else session.serialNumber, mono = true)
            InfoRow("Firmware", session.firmwareVersion.ifEmpty { "—" }, mono = true)
            if (session.firmwareUpdating) {
                Text("Firmware in aggiornamento: la lampada lo scarica via Wi‑Fi e si riavvia.", color = quiet, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            val result = session.firmwareResult
            if (result != null && !session.firmwareUpdating) {
                Text(result, color = quiet, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            val newer = session.newerFirmware
            if (newer != null && !session.firmwareUpdating) {
                Text("È disponibile il firmware $newer in emmaforo.michelebigi.it/firmware.", color = ink, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                if (session.updateNeedsNetwork) {
                    Text("Per aggiornare, salva prima una rete Wi‑Fi in Configurazione: la lampada la usa solo per scaricare il firmware.", color = quiet, modifier = Modifier.padding(horizontal = 16.dp))
                }
                TextButton(
                    onClick = { session.confirmFirmware = true },
                    enabled = session.ready && !session.updateNeedsNetwork,
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) { Text("Aggiorna") }
            }
            TextButton(
                onClick = { session.confirmRest = true },
                enabled = session.ready && !session.resting,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) { Text("Riposo") }
        }
        Text("Michele Bigi", color = quiet, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
        Text("© 2026 Michele Bigi", color = quiet, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp))
        Text("Apache License 2.0", color = quiet, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColorsPage(session: LampSession) {
    val previewOn = session.lampIsOn && session.lastCommand == rgbHex(session.mixRed, session.mixGreen, session.mixBlue)
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ColorDisc(
            color = Color(session.mixRed, session.mixGreen, session.mixBlue),
            filled = true,
            blinking = previewOn && session.blink,
            size = 72.dp,
        )
        Spacer(Modifier.height(28.dp))
        Slider(
            value = session.mixRed,
            onValueChange = { session.setMix(red = it) },
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = Color(0.92f, 0.22f, 0.18f),
                activeTrackColor = Color(0.92f, 0.22f, 0.18f),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Slider(
            value = session.mixGreen,
            onValueChange = { session.setMix(green = it) },
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = Color(0.18f, 0.72f, 0.34f),
                activeTrackColor = Color(0.18f, 0.72f, 0.34f),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Slider(
            value = session.mixBlue,
            onValueChange = { session.setMix(blue = it) },
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = Color(0.22f, 0.45f, 0.95f),
                activeTrackColor = Color(0.22f, 0.45f, 0.95f),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            session.slots.forEachIndexed { index, slot ->
                val selected = session.slotSelected(slot)
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    SlotFace(
                        slot = slot,
                        selected = selected,
                        blinking = selected && session.blink,
                        modifier = Modifier.exclusivePress(
                            allowsClear = slot != null,
                            onSingle = { session.chooseSlot(index, null) },
                            onDouble = { session.chooseSlot(index, !session.blink) },
                            onLong = { session.clearSlot(index) },
                        ),
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        repeat(2) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                lampPalette.drop(row * 8).take(8).forEach { swatch ->
                    val selected = session.paletteSelected(swatch)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        BorderedDisc(
                            color = swatch.color,
                            selected = selected,
                            blinking = selected && session.blink,
                            description = swatch.name,
                            modifier = Modifier.exclusivePress(
                                allowsClear = false,
                                onSingle = { session.choosePalette(swatch, null) },
                                onDouble = { session.choosePalette(swatch, !session.blink) },
                                onLong = {},
                            ),
                        )
                    }
                }
            }
            if (row == 0) Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun ConfigPage(session: LampSession) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Wi‑Fi", color = quiet, fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp))
        FormCard {
            InfoRow("Rete salvata", if (!session.hasWifiStatus) "—" else session.savedNetwork)
            if (session.boardHasPassword) InfoRow("Password", "••••••••", mono = true)
        }
        FormCard {
            if (session.scanningNetworks) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = quiet)
                    Spacer(Modifier.width(10.dp))
                    Text("Cerco reti…", color = ink)
                }
            } else if (session.networks.isEmpty()) {
                Text(
                    "Premi Cerca per vedere le reti.",
                    color = quiet,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                session.networks.forEach { network ->
                    TextButton(
                        onClick = { session.wifiSsid = network.ssid },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(network.ssid, color = ink)
                            Spacer(Modifier.weight(1f))
                            if (session.wifiSsid == network.ssid) Text("✓", color = quiet)
                        }
                    }
                }
            }
        }
        if (!session.scanNote.isNullOrEmpty() && !session.scanningNetworks && session.networks.isEmpty()) {
            Text(session.scanNote.orEmpty(), color = quiet, fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp))
        }
        OutlinedTextField(
            value = session.wifiPassword,
            onValueChange = { session.wifiPassword = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        if (session.connectedNetworkName.isNotEmpty()) {
            Text("Sulla scheda: ${session.connectedNetworkName}", color = quiet, fontSize = 13.sp)
        }
        Text("Serve solo per scaricare un firmware nuovo che hai approvato: per tutto il resto la lampada usa il Bluetooth e tiene il Wi‑Fi spento. Si imposta una volta e resta sulla scheda.", color = quiet, fontSize = 13.sp)
        val networkStatus = session.status
        if (networkStatus == "Salvo la rete…" || networkStatus == "Rete salvata sulla scheda" || networkStatus == "Errore invio rete") {
            Text(networkStatus, color = quiet, fontSize = 13.sp)
        }
        Row {
            TextButton(onClick = { session.selectNetwork() }, enabled = !session.scanningNetworks) { Text("Cerca") }
            TextButton(onClick = { session.saveNetwork() }, enabled = session.ready && session.wifiSsid.isNotEmpty()) { Text("Salva") }
            TextButton(onClick = { session.forgetNetwork() }, enabled = session.ready) { Text("Azzera rete", color = Color(0xFFB00020)) }
        }
        Text("Batteria", color = quiet, fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp))
        FormCard {
            InfoRow("Stima", session.battery.percent?.let { if (session.battery.valid) "$it%" else "—" } ?: "—")
            InfoRow("Tensione", voltageText(session.battery.millivolts))
            InfoRow("Stato", chargeStatusText(session.battery))
            Text(session.chargerLine, color = quiet, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        Text("La percentuale è una stima, non una misura.", color = quiet, fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp))
        FormCard {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Ricarica sicura", modifier = Modifier.weight(1f))
                Switch(checked = session.safeCharging, onCheckedChange = { session.changeSafeCharging(it) }, enabled = session.ready)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Pausa ricarica", modifier = Modifier.weight(1f))
                Switch(checked = session.chargePaused, onCheckedChange = { session.changeChargePaused(it) }, enabled = session.ready)
            }
            Text("Corrente di carica", modifier = Modifier.padding(start = 16.dp, top = 8.dp), color = quiet, fontSize = 13.sp)
            Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = session.gentleCharge, onClick = { session.setGentle(true) }, label = { Text("Gentile") }, enabled = session.ready)
                FilterChip(selected = !session.gentleCharge, onClick = { session.setGentle(false) }, label = { Text("Normale") }, enabled = session.ready)
            }
        }
        Text("La batteria si ferma sempre al 100%, che è il 95% della sua capacità. Ricarica sicura: resta intorno al 90% e sotto l'80% riprende con una ricarica molto gentile.", color = quiet, fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun FormCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White),
    ) { content() }
}

@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = ink)
        Spacer(Modifier.weight(1f))
        Text(value, color = quiet, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default)
    }
}

@Composable
private fun ColorDisc(
    color: Color,
    filled: Boolean,
    blinking: Boolean,
    size: Dp,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    Canvas(modifier.size(size)) {
        val diameter = this.size.minDimension
        if (filled && blinking) {
            val outer = diameter * 0.11f
            drawCircle(color, radius = diameter / 2f - outer / 2f, style = Stroke(outer))
            val inner = diameter * 0.07f
            val pad = diameter * 0.24f
            drawCircle(color, radius = diameter / 2f - pad - inner / 2f, style = Stroke(inner))
        } else if (filled) {
            drawCircle(color)
        } else {
            val stroke = 2.dp.toPx()
            drawCircle(color, radius = diameter / 2f - stroke / 2f, style = Stroke(stroke))
        }
    }
}

@Composable
private fun BorderedDisc(
    color: Color,
    selected: Boolean,
    blinking: Boolean,
    description: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier.size(34.dp), contentAlignment = Alignment.Center) {
        ColorDisc(color, filled = true, blinking = blinking, size = 28.dp, description = description)
        if (selected) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 3.dp.toPx()
                drawCircle(ink, radius = size.minDimension / 2f - stroke / 2f, style = Stroke(stroke))
            }
        }
    }
}

@Composable
private fun SlotFace(slot: SlotColor?, selected: Boolean, blinking: Boolean, modifier: Modifier = Modifier) {
    Box(modifier.size(34.dp), contentAlignment = Alignment.Center) {
        if (slot != null) {
            ColorDisc(slot.color, filled = true, blinking = blinking, size = 28.dp, description = "Colore salvato")
        } else {
            Canvas(Modifier.size(28.dp)) {
                drawCircle(quiet.copy(alpha = 0.35f), style = Stroke(1.5.dp.toPx()))
            }
        }
        if (selected && slot != null) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 3.dp.toPx()
                drawCircle(ink, radius = size.minDimension / 2f - stroke / 2f, style = Stroke(stroke))
            }
        }
    }
}

@Composable
private fun SemaphoreIcon() {
    Canvas(Modifier.size(width = 16.dp, height = 24.dp)) {
        drawRoundRect(Color(0xFF202124), cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx()))
        val colors = listOf(Color(0xFFEB382E), Color(0xFFF5B81F), Color(0xFF2EB857))
        colors.forEachIndexed { index, color ->
            drawCircle(color, radius = 2.dp.toPx(), center = Offset(size.width / 2f, 5.dp.toPx() + index * 7.dp.toPx()))
        }
    }
}

private fun Modifier.releaseTap(onRelease: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown()
        val up = waitForUpOrCancellation()
        if (up != null) onRelease()
    }
}

@Composable
private fun Modifier.exclusivePress(
    allowsClear: Boolean,
    onSingle: () -> Unit,
    onDouble: () -> Unit,
    onLong: () -> Unit,
): Modifier {
    val scope = rememberCoroutineScope()
    val single = rememberUpdatedState(onSingle)
    val double = rememberUpdatedState(onDouble)
    val long = rememberUpdatedState(onLong)
    val memory = remember { PressMemory() }
    return pointerInput(allowsClear) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            if (!memory.armed) {
                memory.armed = true
                memory.longFired = false
                memory.hold?.cancel()
                memory.hold = scope.launch {
                    delay(450)
                    if (!memory.armed) return@launch
                    memory.longFired = true
                    memory.wait?.cancel()
                    memory.wait = null
                    memory.taps = 0
                    if (allowsClear) long.value()
                }
            }
            val up = waitForUpOrCancellation()
            memory.hold?.cancel()
            memory.hold = null
            val wasLong = memory.longFired
            memory.armed = false
            if (wasLong || up == null) return@awaitEachGesture
            memory.taps += 1
            if (memory.taps >= 2) {
                memory.wait?.cancel()
                memory.wait = null
                memory.taps = 0
                double.value()
                return@awaitEachGesture
            }
            memory.wait?.cancel()
            memory.wait = scope.launch {
                delay(280)
                memory.taps = 0
                memory.wait = null
                single.value()
            }
        }
    }
}

private class PressMemory {
    var armed = false
    var longFired = false
    var taps = 0
    var hold: Job? = null
    var wait: Job? = null
}

private fun batteryIcon(percent: Int, charging: Boolean): ImageVector {
    if (charging) return Icons.Outlined.BatteryChargingFull
    return when {
        percent < 13 -> Icons.Outlined.Battery0Bar
        percent < 38 -> Icons.Outlined.Battery2Bar
        percent < 63 -> Icons.Outlined.Battery4Bar
        percent < 88 -> Icons.Outlined.Battery6Bar
        else -> Icons.Outlined.BatteryFull
    }
}

private fun wifiSummary(session: LampSession): String {
    if (!session.hasWifiStatus) return "—"
    return if (session.connectedNetworkName.isEmpty()) "Non salvata" else "Salvata"
}

private fun chargeWord(session: LampSession, enabled: Boolean): String {
    if (!session.hasChargeReport) return "—"
    return if (enabled) "Attiva" else "Spenta"
}

private fun voltageText(millivolts: Int?): String {
    if (millivolts == null || millivolts <= 0) return "—"
    return String.format("%.2f V", millivolts / 1000.0)
}

private fun chargeStatusText(battery: BatteryReading): String {
    return when (battery.chargeState) {
        0 -> "Non in carica"
        1 -> "Pre-carica"
        2 -> "Carica veloce"
        3 -> "Carica completata"
        else -> if (battery.charging) "In carica" else "—"
    }
}

@Suppress("unused")
private val keepManifest = Manifest.permission.BLUETOOTH_CONNECT
