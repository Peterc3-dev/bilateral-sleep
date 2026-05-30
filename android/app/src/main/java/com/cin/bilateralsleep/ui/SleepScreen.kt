@file:OptIn(ExperimentalLayoutApi::class)

package com.cin.bilateralsleep.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cin.bilateralsleep.audio.SleepController
import com.cin.bilateralsleep.data.BuiltInPresets
import com.cin.bilateralsleep.data.PresetStore
import com.cin.bilateralsleep.ui.theme.Phosphor
import androidx.compose.runtime.collectAsState

@Composable
fun SleepScreen() {
    val context = LocalContext.current
    val store = remember { PresetStore(context) }

    val running by SleepController.isRunning.collectAsState()
    val remaining by SleepController.remainingMillis.collectAsState()
    val timerMinutes by SleepController.timerMinutes.collectAsState()

    val altRate by SleepController.altRate.collectAsState()
    val density by SleepController.crackleDensity.collectAsState()
    val center by SleepController.carrierCenter.collectAsState()
    val q by SleepController.carrierQ.collectAsState()
    val brown by SleepController.brownLevel.collectAsState()
    val panHard by SleepController.panShapeHard.collectAsState()
    val volume by SleepController.volume.collectAsState()
    val binaural by SleepController.binauralHz.collectAsState()

    var advancedOpen by remember { mutableStateOf(false) }
    var saveMode by remember { mutableStateOf(false) }
    var slotVersion by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Phosphor.Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "B I L A T E R A L   S L E E P",
            style = MaterialTheme.typography.titleLarge,
            color = Phosphor.Green,
        )
        Spacer(Modifier.height(22.dp))

        // ---- big START / STOP ----
        val accent = if (running) Phosphor.Amber else Phosphor.Green
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                .clip(RoundedCornerShape(20.dp))
                .border(2.dp, accent, RoundedCornerShape(20.dp))
                .background(if (running) Phosphor.SurfaceHigh else Phosphor.Surface)
                .clickable { SleepController.toggle(context) },
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (running) "■   STOP" else "▶   START",
                style = MaterialTheme.typography.titleLarge,
                color = accent,
            )
            if (running && timerMinutes > 0) {
                Text(
                    remainingText(remaining),
                    style = MaterialTheme.typography.labelSmall,
                    color = Phosphor.GreenDim,
                )
            }
        }

        Spacer(Modifier.height(28.dp))

        // ---- primary sliders ----
        LabeledSlider("SWAY", "%.2f Hz".format(altRate), altRate.toFloat(), 0.2f..2.0f) {
            SleepController.setAltRate(it.toDouble())
        }
        LabeledSlider("CRACKLE", "%.0f /s".format(density), density.toFloat(), 1f..40f) {
            SleepController.setCrackleDensity(it.toDouble())
        }
        LabeledSlider("WARMTH", warmthText(center), center.toFloat(), 500f..4000f) {
            SleepController.setCarrierCenter(it.toDouble())
        }
        LabeledSlider("VOLUME", "%.0f %%".format(volume * 100), volume.toFloat(), 0f..1f) {
            SleepController.setVolume(it.toDouble())
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("FADE TO SLEEP")
        val timerOptions = listOf(0, 15, 30, 45, 60, 90)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            timerOptions.forEach { m ->
                Chip(
                    label = if (m == 0) "off" else "$m min",
                    selected = timerMinutes == m,
                ) { SleepController.setTimerMinutes(m) }
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("PRESETS")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BuiltInPresets.all.forEach { preset ->
                Chip(label = preset.name, selected = false) {
                    SleepController.applyParams(preset.params)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Chip(label = if (saveMode) "saving…" else "save", selected = saveMode) {
                saveMode = !saveMode
            }
            slotVersion.let {
                for (slot in 0 until PresetStore.SLOTS) {
                    val filled = store.isFilled(slot)
                    Chip(
                        label = "slot ${slot + 1}${if (filled) " ●" else ""}",
                        selected = false,
                    ) {
                        if (saveMode) {
                            store.save(slot, SleepController.snapshot())
                            slotVersion++
                            saveMode = false
                        } else {
                            store.load(slot)?.let { p -> SleepController.applyParams(p) }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        // ---- advanced (static show/hide, no animation) ----
        Text(
            if (advancedOpen) "▾  ADVANCED" else "▸  ADVANCED",
            style = MaterialTheme.typography.labelLarge,
            color = Phosphor.GreenDim,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { advancedOpen = !advancedOpen }
                .padding(vertical = 8.dp),
        )
        if (advancedOpen) {
            LabeledSlider("RESONANCE", "%.1f".format(q), q.toFloat(), 0.5f..12f) {
                SleepController.setCarrierQ(it.toDouble())
            }
            LabeledSlider("RUMBLE", "%.0f %%".format(brown * 100), brown.toFloat(), 0f..1f) {
                SleepController.setBrownLevel(it.toDouble())
            }
            LabeledSlider(
                "BINAURAL",
                if (binaural <= 0.0) "off" else "%.1f Hz".format(binaural),
                binaural.toFloat(), 0f..12f,
            ) { SleepController.setBinauralHz(it.toDouble()) }

            Spacer(Modifier.height(8.dp))
            SectionLabel("PAN SHAPE")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Chip(label = "cosine", selected = !panHard) { SleepController.setPanShapeHard(false) }
                Chip(label = "hard", selected = panHard) { SleepController.setPanShapeHard(true) }
            }
        }

        Spacer(Modifier.height(28.dp))
        Text(
            "use headphones · let the sound drift between your ears",
            style = MaterialTheme.typography.labelSmall,
            color = Phosphor.GreenDim,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun warmthText(centerHz: Double): String =
    if (centerHz >= 1000) "%.1f kHz".format(centerHz / 1000.0) else "%.0f Hz".format(centerHz)

private fun remainingText(ms: Long): String {
    if (ms <= 0) return ""
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d left".format(m, s)
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = Phosphor.GreenDim,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    )
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = Phosphor.GreenBright)
            Text(valueText, style = MaterialTheme.typography.bodyMedium, color = Phosphor.Amber)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Phosphor.Green,
                activeTrackColor = Phosphor.Green,
                inactiveTrackColor = Phosphor.GreenDim,
            ),
        )
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val border = if (selected) Phosphor.Amber else Phosphor.GreenDim
    val fg = if (selected) Phosphor.Amber else Phosphor.GreenBright
    val bg = if (selected) Phosphor.SurfaceHigh else Phosphor.Surface
    Text(
        label,
        style = MaterialTheme.typography.bodyMedium,
        color = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}
