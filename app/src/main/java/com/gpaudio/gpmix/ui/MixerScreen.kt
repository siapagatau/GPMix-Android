package com.gpaudio.gpmix.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gpaudio.gpmix.MixerViewModel
import com.gpaudio.gpmix.audio.NativeEngine
import kotlin.math.log10

private val Bg = Color(0xFF15181C)
private val Panel = Color(0xFF1E2329)
private val Dim = Color(0xFF9AA4B0)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MixerScreen(vm: MixerViewModel, onToggle: () -> Unit, onPickPad: (Int) -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Bg).verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("GP Mix", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.weight(1f))
            if (vm.running) Text("≈ %.0f ms".format(vm.latencyMs), color = Dim, fontSize = 12.sp)
        }
        Text(vm.status, color = Dim, fontSize = 12.sp)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onToggle) { Text(if (vm.running) "Stop" else "Mulai") }
            OutlinedButton(onClick = { vm.nextInput() }) {
                Text("Input: ${vm.inputs.getOrNull(vm.inputIndex)?.second ?: "-"}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            for (i in 0..2) {
                FilterChip(selected = vm.scene == i, onClick = { vm.recallScene(i) }, label = { Text("Scene ${i + 1}") })
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { vm.saveScene(vm.scene) }) { Text("Simpan") }
        }

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (i in 0..2) Strip(vm, i)
        }

        Text("SAMPLE PADS", color = Dim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        for (row in 0..1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (c in 0..3) {
                    val idx = row * 4 + c
                    val name = vm.padNames[idx]
                    Box(
                        Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(10.dp))
                            .background(if (name.isEmpty()) Color(0xFF232A31) else Color(0xFF2E6F95))
                            .combinedClickable(onClick = { NativeEngine.triggerPad(idx) }, onLongClick = { onPickPad(idx) })
                            .padding(4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (name.isEmpty()) "${idx + 1}" else name,
                            color = Color.White, fontSize = 11.sp, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Tap = putar · tahan = pilih file WAV", color = Dim, fontSize = 11.sp, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { vm.stopPads() }) { Text("Stop pad") }
        }
    }
}

@Composable
private fun Strip(vm: MixerViewModel, i: Int) {
    val s = vm.channels[i]
    Column(
        Modifier.width(124.dp).clip(RoundedCornerShape(10.dp)).background(Panel).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(s.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        VerticalFader(
            db = s.faderDb, meter = vm.meters[i],
            onChange = { v -> vm.update(i) { it.copy(faderDb = v) } },
            modifier = Modifier.height(200.dp).width(84.dp),
        )
        Text(if (s.faderDb <= -59.9f) "-inf dB" else "%.1f dB".format(s.faderDb), color = Dim, fontSize = 12.sp)
        Button(
            onClick = { vm.update(i) { it.copy(mute = !it.mute) } },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = if (s.mute) Color(0xFFD32F2F) else Color(0xFF2B3138)),
        ) { Text("MUTE", fontSize = 12.sp) }

        if (i < 2) {
            LabeledSlider("PAN", s.pan, -1f..1f, if (s.pan == 0f) "C" else "%+.0f".format(s.pan * 100)) { v ->
                vm.update(i) { it.copy(pan = v) }
            }
        }
        if (i == 0) {
            SwitchRow("HPF", s.hpfOn) { on -> vm.update(0) { it.copy(hpfOn = on) } }
            LabeledSlider("Freq", s.hpfHz, 40f..400f, "%.0f Hz".format(s.hpfHz)) { v -> vm.update(0) { it.copy(hpfHz = v) } }
            SwitchRow("COMP", s.compOn) { on -> vm.update(0) { it.copy(compOn = on) } }
            LabeledSlider("Thresh", s.compThr, -50f..0f, "%.0f dB".format(s.compThr)) { v -> vm.update(0) { it.copy(compThr = v) } }
            LabeledSlider("Ratio", s.compRatio, 1f..10f, "%.1f:1".format(s.compRatio)) { v -> vm.update(0) { it.copy(compRatio = v) } }
            LabeledSlider("Reverb", s.send, 0f..1f, "%.0f%%".format(s.send * 100)) { v -> vm.update(0) { it.copy(send = v) } }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LabeledSlider(
    label: String, value: Float, range: ClosedFloatingPointRange<Float>, text: String, onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Dim, fontSize = 10.sp)
            Text(text, color = Dim, fontSize = 10.sp)
        }
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange,
            valueRange = range, modifier = Modifier.height(28.dp))
    }
}

private fun yToDb(y: Float, h: Float): Float = -60f + (1f - (y / h).coerceIn(0f, 1f)) * 66f

/** Fader vertikal -60..+6 dB dengan meter di kanan. Double-tap = 0 dB. */
@Composable
private fun VerticalFader(db: Float, meter: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val cb by rememberUpdatedState(onChange)
    Canvas(
        modifier
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { o -> cb(yToDb(o.y, size.height.toFloat())) },
                    onVerticalDrag = { change, _ -> change.consume(); cb(yToDb(change.position.y, size.height.toFloat())) },
                )
            }
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { cb(0f) }) }
    ) {
        val w = size.width
        val h = size.height
        val meterW = 14.dp.toPx()
        drawRect(Color(0xFF0B0D10), Offset(w - meterW, 0f), Size(meterW, h))
        val mdb = 20f * log10(meter + 1e-6f)
        val mf = ((mdb + 60f) / 60f).coerceIn(0f, 1f)
        val col = when {
            mdb > -3f -> Color(0xFFE53935)
            mdb > -12f -> Color(0xFFFFC107)
            else -> Color(0xFF4CAF50)
        }
        drawRect(col, Offset(w - meterW, h * (1f - mf)), Size(meterW, h * mf))

        val tx = (w - meterW) / 2f
        drawLine(Color(0xFF444B55), Offset(tx, 0f), Offset(tx, h), 4.dp.toPx())
        val zeroY = h * (1f - 60f / 66f)
        drawLine(Color(0xFF777F8A), Offset(tx - 24.dp.toPx(), zeroY), Offset(tx + 24.dp.toPx(), zeroY), 1.dp.toPx())
        val hy = h * (1f - (db + 60f) / 66f)
        drawRoundRect(
            Color(0xFFE0E4EA), Offset(tx - 18.dp.toPx(), hy - 8.dp.toPx()),
            Size(36.dp.toPx(), 16.dp.toPx()), CornerRadius(3.dp.toPx()),
        )
    }
}
