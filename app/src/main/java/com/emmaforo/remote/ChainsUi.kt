package com.emmaforo.remote

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** "Parla": the phone listens for a word and the lamp follows, like the iOS app. */
@Composable
fun VoiceButton(session: LampSession, modifier: Modifier = Modifier) {
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) session.listen() else session.noteStatus("Consenti il microfono per i comandi vocali")
    }
    TextButton(
        onClick = { if (session.recordPermission()) session.listen() else ask.launch(Manifest.permission.RECORD_AUDIO) },
        modifier = modifier,
    ) {
        Icon(if (session.listening) Icons.Outlined.Stop else Icons.Outlined.Mic, contentDescription = null, tint = ink)
        Spacer(Modifier.width(6.dp))
        Text(if (session.listening) "Ascolto…" else "Parla", color = ink)
    }
}

/** Configurazione > Lampada: shown only when the board has FFEB or FFEA, as on iOS. */
@Composable
fun LampSection(session: LampSession) {
    if (!session.chainsAvailable && !session.audioAvailable) return
    Text("Lampada", color = quiet, fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp))
    FormCard {
        if (session.chainsAvailable) {
            NavRow("Catena di colori") { session.configPage = 1 }
        }
        if (session.chainsAvailable && session.audioAvailable) HorizontalDivider(Modifier.padding(start = 16.dp))
        if (session.audioAvailable) {
            NavRow("Suoni") {
                session.configPage = 2
                session.refreshAudio()
            }
        }
    }
}

@Composable
private fun NavRow(label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = ink, modifier = Modifier.weight(1f))
        Text("›", color = quiet, fontSize = 20.sp)
    }
}

@Composable
private fun PageHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text("‹ Configurazione") }
        Spacer(Modifier.weight(1f))
        Text(title, color = ink, fontSize = 17.sp)
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.width(96.dp))
    }
}

@Composable
fun ChainPage(session: LampSession) {
    val chain = session.chain
    fun change(index: Int, step: ChainStep) {
        session.updateChain(chain.copy(steps = chain.steps.toMutableList().also { it[index] = step }))
    }
    // No notify on FFEB: while the page is open and a chain runs, read where it is.
    LaunchedEffect(session.ready) {
        while (session.ready) {
            session.refreshChain()
            delay(1000)
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PageHeader("Catena di colori") { session.configPage = 0 }
        FormCard {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                chain.steps.forEachIndexed { index, step ->
                    if (index > 0) HorizontalDivider()
                    StepEditor(
                        session = session,
                        number = index + 1,
                        step = step,
                        onChange = { change(index, it) },
                        onUp = if (index > 0) ({
                            session.updateChain(chain.copy(steps = chain.steps.toMutableList().also { java.util.Collections.swap(it, index, index - 1) }))
                        }) else null,
                        onDown = if (index < chain.steps.size - 1) ({
                            session.updateChain(chain.copy(steps = chain.steps.toMutableList().also { java.util.Collections.swap(it, index, index + 1) }))
                        }) else null,
                        onRemove = { session.updateChain(chain.copy(steps = chain.steps.filterIndexed { at, _ -> at != index })) },
                    )
                }
                if (chain.steps.size < chainStepLimit) {
                    TextButton(onClick = {
                        val step = chain.steps.lastOrNull()?.copy(id = java.util.UUID.randomUUID().toString()) ?: stepFromCommand("bianco")
                        session.updateChain(chain.copy(steps = chain.steps + step))
                    }) { Text("Aggiungi passo") }
                }
            }
        }
        FormCard {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Ripeti", modifier = Modifier.weight(1f))
                Switch(checked = chain.loop, onCheckedChange = { session.updateChain(chain.copy(loop = it)) })
            }
        }
        Row {
            TextButton(onClick = { session.playChain() }, enabled = session.ready && chain.playable) { Text("Avvia sulla lampada") }
            TextButton(onClick = { session.stopChain() }, enabled = session.ready) { Text("Ferma") }
        }
        val state = session.chainState
        val line = if (state != null && state.active && state.steps.isNotEmpty()) {
            "In corso: colore ${state.step.coerceIn(0, state.steps.size - 1) + 1} di ${state.steps.size}"
        } else {
            "Un giro dura ${tenthsText(chain.totalTenths)}."
        }
        Text(line, color = quiet, fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp))
        if (chain.loop && chain.totalTenths == 0 && chain.steps.isNotEmpty()) {
            Text("Con Ripeti serve almeno un tempo diverso da zero.", color = danger, fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp))
        }
        Text(
            "Ogni passo sfuma dal colore di prima al suo nel tempo di transizione, resta acceso per la pausa e suona il suo suono quando arriva. Fino a $chainStepLimit passi. Un comando di colore o il riposo fermano la catena. A voce: \u201Cavvia catena\u201D, \u201Cferma catena\u201D.",
            color = quiet,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun StepEditor(
    session: LampSession,
    number: Int,
    step: ChainStep,
    onChange: (ChainStep) -> Unit,
    onUp: (() -> Unit)?,
    onDown: (() -> Unit)?,
    onRemove: () -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$number", color = quiet, modifier = Modifier.width(20.dp))
            Box {
                Box(Modifier.size(36.dp).clickable { picking = true }, contentAlignment = Alignment.Center) {
                    ColorDisc(step.color, filled = true, blinking = false, size = 30.dp, description = "Colore del passo")
                }
                DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                    val saved = session.slots.filterNotNull()
                    (lampPalette.map { Triple(it.name, it.color, it.command) } +
                        saved.mapIndexed { index, slot -> Triple("Salvato ${index + 1}", slot.color, slot.command) }).forEach { (name, color, _) ->
                        DropdownMenuItem(
                            text = { Text(name) },
                            leadingIcon = { ColorDisc(color, filled = true, blinking = false, size = 18.dp) },
                            onClick = {
                                picking = false
                                onChange(step.copy(red = color.red, green = color.green, blue = color.blue))
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { onUp?.invoke() }, enabled = onUp != null) { Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = "Su") }
            IconButton(onClick = { onDown?.invoke() }, enabled = onDown != null) { Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Giù") }
            IconButton(onClick = onRemove) { Icon(Icons.Outlined.Delete, contentDescription = "Togli passo") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondsField("Transizione s", step.fadeTenths, Modifier.weight(1f)) { onChange(step.copy(fadeTenths = it)) }
            SecondsField("Pausa s", step.holdTenths, Modifier.weight(1f)) { onChange(step.copy(holdTenths = it)) }
        }
        SoundPicker(session, step.sound, label = "Suono all'arrivo") { onChange(step.copy(sound = it)) }
    }
}

@Composable
private fun SecondsField(label: String, tenths: Int, modifier: Modifier, onChange: (Int) -> Unit) {
    var text by remember { mutableStateOf(secondsText(tenths)) }
    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            text = typed
            val seconds = typed.replace(',', '.').toDoubleOrNull()
            if (seconds != null && seconds >= 0) onChange((seconds * 10).roundToInt().coerceIn(0, chainTenthsLimit))
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

private fun secondsText(tenths: Int): String =
    if (tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10},${tenths % 10}"

@Composable
private fun SoundPicker(session: LampSession, number: Int, label: String, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            Column(Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 6.dp)) {
                Text(label, color = quiet, fontSize = 12.sp)
                Text(session.soundLabel(number), color = ink)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                session.soundChoices.forEach { sound ->
                    DropdownMenuItem(
                        text = { Text(sound.label) },
                        onClick = {
                            open = false
                            onPick(sound.number)
                        },
                    )
                }
            }
        }
        IconButton(onClick = { session.playSound(number) }, enabled = session.audioAvailable && number != 0) {
            Icon(Icons.Outlined.VolumeUp, contentDescription = "Ascolta sulla lampada")
        }
    }
}

@Composable
fun SoundsPage(session: LampSession) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PageHeader("Suoni") { session.configPage = 0 }
        FormCard {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Volume", color = ink, modifier = Modifier.weight(1f))
                    Text(if (session.volume.roundToInt() == 0) "Muto" else "${session.volume.roundToInt()}%", color = quiet)
                }
                Slider(
                    value = session.volume,
                    onValueChange = { session.changeVolume(it) },
                    onValueChangeFinished = { session.commitVolume() },
                    valueRange = 0f..100f,
                    steps = 19,
                    enabled = session.ready,
                )
            }
        }

        Text("Quando la lampada diventa", color = quiet, fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp, top = 8.dp))
        FormCard {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                soundStates.forEachIndexed { index, (_, name) ->
                    if (index > 0) HorizontalDivider()
                    SoundPicker(session, session.stateSounds.getOrElse(index) { 0 }, label = if (name == "Altri colori") "Altro colore" else name) {
                        session.setStateSound(index, it)
                    }
                }
            }
        }

        Text("Suoni della lampada", color = quiet, fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp, top = 8.dp))
        FormCard {
            builtInSounds.filter { it.number != 0 }.forEachIndexed { index, sound ->
                if (index > 0) HorizontalDivider(Modifier.padding(start = 16.dp))
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(sound.label, color = ink, modifier = Modifier.weight(1f))
                    TextButton(onClick = { session.playSound(sound.number) }, enabled = session.ready) { Text("Prova") }
                }
            }
        }

        Text("Messaggi vocali", color = quiet, fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp, top = 8.dp))
        FormCard {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(voiceSlotCount) { slot ->
                    if (slot > 0) HorizontalDivider()
                    val filled = session.voiceMask and (1 shl slot) != 0
                    val text = session.phrases.getOrElse(slot) { "" }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = text,
                            onValueChange = { session.changePhrase(slot, it) },
                            label = { Text("Messaggio ${slot + 1}" + if (filled) " · sulla lampada" else "") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { session.previewPhrase(slot) }, enabled = text.isNotBlank()) {
                            Icon(Icons.Outlined.VolumeUp, contentDescription = "Ascolta sul telefono")
                        }
                    }
                    Row {
                        TextButton(
                            onClick = { session.sendPhrase(slot) },
                            enabled = session.ready && session.voicePartition && session.clipSlot == null && text.isNotBlank(),
                        ) { Text("Invia") }
                        TextButton(onClick = { session.playSound(firstVoiceSound + slot) }, enabled = session.ready && filled) { Text("Prova") }
                        TextButton(onClick = { session.deletePhrase(slot) }, enabled = session.ready && filled && session.clipSlot == null) {
                            Text("Cancella", color = if (filled) danger else quiet)
                        }
                        if (session.clipSlot == slot) {
                            Spacer(Modifier.weight(1f))
                            Text("${(session.clipProgress * 100).roundToInt()}%", color = quiet, modifier = Modifier.align(Alignment.CenterVertically))
                        }
                    }
                    if (session.clipSlot == slot) LinearProgressIndicator(progress = { session.clipProgress }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        session.clipNote?.let { Text(it, color = quiet, fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp)) }
        if (!session.voicePartition) {
            Text(
                "Questa scheda è stata aggiornata via Wi‑Fi e non ha lo spazio per i messaggi vocali: va aggiornata una volta via USB. I suoni funzionano lo stesso.",
                color = quiet,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 12.dp),
            )
        } else {
            Text(
                "Il telefono legge la frase con la sua sintesi vocale e la manda alla lampada, al massimo ${session.voiceMaxMs / 1000} secondi. Poi sceglila in \u201CQuando la lampada diventa\u201D, per esempio \u201CAvanti\u201D per il verde.",
                color = quiet,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

