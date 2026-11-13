@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.fmsynth

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.random.Random

val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
fun noteName(n: Int) = NOTE_NAMES[n % 12] + (n / 12 - 1)

class MainActivity : ComponentActivity() {
    private val engine = FmEngine()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine.start()
        setContent {
            val ctx = LocalContext.current
            val dark = isSystemInDarkTheme()
            val scheme = if (Build.VERSION.SDK_INT >= 31) {
                if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            } else {
                if (dark) darkColorScheme() else lightColorScheme()
            }
            MaterialTheme(colorScheme = scheme) { SynthApp(engine) }
        }
    }

    override fun onDestroy() {
        engine.stop()
        super.onDestroy()
    }
}

@Composable
fun SynthApp(engine: FmEngine) {
    var tab by remember { mutableIntStateOf(0) }
    var page by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var bpm by remember { mutableFloatStateOf(120f) }

    Scaffold(topBar = { CenterAlignedTopAppBar(title = { Text("FM Synth") }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(
                Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(onClick = { playing = !playing; engine.playing = playing }) {
                    Text(if (playing) "■ Stop" else "▶ Play")
                }
                Text("BPM ${bpm.toInt()}")
                Slider(
                    value = bpm,
                    onValueChange = { bpm = it; engine.bpm = it },
                    valueRange = 60f..200f,
                    modifier = Modifier.weight(1f)
                )
            }
            TabRow(selectedTabIndex = tab) {
                for (i in 0 until NTRACKS) {
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text("T${i + 1}") })
                }
            }
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("SEQ", "FM", "FX").forEachIndexed { i, n ->
                    FilterChip(selected = page == i, onClick = { page = i }, label = { Text(n) })
                }
            }
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                when (page) {
                    0 -> SeqPage(engine, tab)
                    1 -> FmPage(engine, tab)
                    else -> FxPage(engine, tab)
                }
            }
        }
    }
}

@Composable
fun Param(
    label: String,
    key: Any,
    get: () -> Float,
    set: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>
) {
    var v by remember(key) { mutableFloatStateOf(get()) }
    Column {
        Text("$label: ${"%.2f".format(v)}", style = MaterialTheme.typography.labelLarge)
        Slider(value = v, onValueChange = { v = it; set(it) }, valueRange = range)
    }
}

@Composable
fun RowScope.StepCell(
    s: Int,
    note: Int,
    selected: Boolean,
    playhead: Boolean,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val bg = when {
        playhead -> cs.tertiary
        note >= 0 -> cs.primaryContainer
        else -> cs.surfaceVariant
    }
    val fg = when {
        playhead -> cs.onTertiary
        note >= 0 -> cs.onPrimaryContainer
        else -> cs.onSurfaceVariant
    }
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .weight(1f)
            .aspectRatio(0.8f)
            .clip(shape)
            .background(bg)
            .then(if (selected) Modifier.border(2.dp, cs.primary, shape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(if (note >= 0) noteName(note) else "${s + 1}", color = fg, fontSize = 12.sp)
    }
}

@Composable
fun SeqPage(engine: FmEngine, ti: Int) {
    val t = engine.tracks[ti]
    var ver by remember { mutableIntStateOf(0) }
    var sel by remember(ti) { mutableIntStateOf(0) }
    var octave by remember { mutableIntStateOf(3) }
    var head by remember { mutableIntStateOf(-1) }
    LaunchedEffect(Unit) {
        while (true) {
            head = engine.step
            delay(20)
        }
    }
    val v = ver

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (row in 0 until 2) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in 0 until 8) {
                    val s = row * 8 + c
                    StepCell(s, t.notes[s], sel == s, head == s) {
                        sel = s
                        val n = t.notes[s]
                        if (n >= 0) t.pending = n
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Step ${sel + 1}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { if (octave > 0) octave-- }) { Text("Oct -") }
            Text("$octave")
            OutlinedButton(onClick = { if (octave < 7) octave++ }) { Text("Oct +") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = { t.notes[sel] = -1; ver++ },
                modifier = Modifier.weight(1f)
            ) { Text("Clear step") }
            FilledTonalButton(
                onClick = {
                    for (s in 0 until STEPS) t.notes[s] = -1
                    ver++
                },
                modifier = Modifier.weight(1f)
            ) { Text("Clear all") }
            FilledTonalButton(
                onClick = {
                    val sc = intArrayOf(0, 3, 5, 7, 10)
                    for (s in 0 until STEPS) {
                        t.notes[s] = if (Random.nextFloat() < 0.55f)
                            (octave + 1) * 12 + sc.random() + (if (Random.nextBoolean()) 12 else 0)
                        else -1
                    }
                    ver++
                },
                modifier = Modifier.weight(1f)
            ) { Text("Random") }
        }
        Text("Клавиши: выбери шаг и нажми ноту", style = MaterialTheme.typography.labelMedium)
        for (r in 0 until 2) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (k in r * 6 until r * 6 + 6) {
                    FilledTonalButton(
                        onClick = {
                            val n = (octave + 1) * 12 + k
                            t.notes[sel] = n
                            t.pending = n
                            sel = (sel + 1) % STEPS
                            ver++
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(0.dp)
                    ) { Text(NOTE_NAMES[k], fontSize = 13.sp) }
                }
            }
        }
    }
}

@Composable
fun FmPage(engine: FmEngine, ti: Int) {
    val t = engine.tracks[ti]
    var algo by remember(ti) { mutableIntStateOf(t.algo) }
    val descs = listOf("4 > 3 > 2 > 1", "(4 > 3) + (2 > 1)", "4 > 1, 2, 3", "1 + 2 + 3 + 4")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Algorithm", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (i in 0..3) {
                        FilterChip(
                            selected = algo == i,
                            onClick = { algo = i; t.algo = i },
                            label = { Text("A${i + 1}") }
                        )
                    }
                }
                Text(descs[algo], style = MaterialTheme.typography.bodyMedium)
            }
        }
        for (i in 0..3) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Operator ${i + 1}", style = MaterialTheme.typography.titleMedium)
                    Param("Ratio", ti, { t.ratio[i] }, { t.ratio[i] = it }, 0.25f..12f)
                    Param("Level", ti, { t.level[i] }, { t.level[i] = it }, 0f..1f)
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Envelope", style = MaterialTheme.typography.titleMedium)
                Param("Op4 feedback", ti, { t.fb }, { t.fb = it }, 0f..1f)
                Param("Amp decay (s)", ti, { t.ampDec }, { t.ampDec = it }, 0.03f..2f)
                Param("Mod decay (s)", ti, { t.modDec }, { t.modDec = it }, 0.03f..2f)
            }
        }
    }
}

@Composable
fun FxPage(engine: FmEngine, ti: Int) {
    val t = engine.tracks[ti]
    var mute by remember(ti) { mutableStateOf(t.mute) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Track ${ti + 1}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text("Mute  ")
                    Switch(checked = mute, onCheckedChange = { mute = it; t.mute = it })
                }
                Param("Volume", ti, { t.vol }, { t.vol = it }, 0f..1f)
                Param("Filter cutoff", ti, { t.cutoff }, { t.cutoff = it }, 0f..1f)
                Param("Drive", ti, { t.drive }, { t.drive = it }, 0f..1f)
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Delay", style = MaterialTheme.typography.titleMedium)
                Param("Time (ms)", "g", { engine.delayMs }, { engine.delayMs = it }, 60f..900f)
                Param("Feedback", "g", { engine.delayFb }, { engine.delayFb = it }, 0f..0.9f)
                Param("Mix", "g", { engine.delayMix }, { engine.delayMix = it }, 0f..1f)
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Reverb", style = MaterialTheme.typography.titleMedium)
                Param("Size", "g", { engine.revSize }, { engine.revSize = it }, 0f..1f)
                Param("Damping", "g", { engine.revDamp }, { engine.revDamp = it }, 0f..0.9f)
                Param("Mix", "g", { engine.revMix }, { engine.revMix = it }, 0f..1f)
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Master", style = MaterialTheme.typography.titleMedium)
                Param("Volume", "g", { engine.master }, { engine.master = it }, 0f..1f)
            }
        }
    }
}
