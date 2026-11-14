@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.fmsynth

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
fun noteName(n: Int) = NOTE_NAMES[n % 12] + (n / 12 - 1)

// ---------- Темы ----------

private fun hsl(h: Float, s: Float, l: Float): Color =
    Color.hsl(((h % 360f) + 360f) % 360f, s, l)

private fun paletteScheme(h: Float): ColorScheme = darkColorScheme(
    primary = hsl(h, .75f, .78f), onPrimary = hsl(h, .60f, .14f),
    primaryContainer = hsl(h, .45f, .30f), onPrimaryContainer = hsl(h, .85f, .90f),
    secondary = hsl(h, .30f, .76f), onSecondary = hsl(h, .30f, .15f),
    secondaryContainer = hsl(h, .22f, .28f), onSecondaryContainer = hsl(h, .35f, .90f),
    tertiary = hsl(h + 50f, .65f, .78f), onTertiary = hsl(h + 50f, .60f, .15f),
    tertiaryContainer = hsl(h + 50f, .40f, .30f), onTertiaryContainer = hsl(h + 50f, .80f, .90f),
    background = hsl(h, .18f, .08f), onBackground = hsl(h, .15f, .90f),
    surface = hsl(h, .18f, .08f), onSurface = hsl(h, .15f, .90f),
    surfaceVariant = hsl(h, .16f, .24f), onSurfaceVariant = hsl(h, .15f, .78f),
    outline = hsl(h, .10f, .58f), outlineVariant = hsl(h, .14f, .30f),
    surfaceContainerLowest = hsl(h, .18f, .05f),
    surfaceContainerLow = hsl(h, .18f, .10f),
    surfaceContainer = hsl(h, .18f, .12f),
    surfaceContainerHigh = hsl(h, .17f, .16f),
    surfaceContainerHighest = hsl(h, .16f, .20f),
    surfaceBright = hsl(h, .16f, .24f),
    surfaceDim = hsl(h, .18f, .06f)
)

fun schemeFor(i: Int): ColorScheme = when (i) {
    0 -> darkColorScheme()
    1 -> lightColorScheme()
    2 -> paletteScheme(270f)
    3 -> paletteScheme(140f)
    else -> paletteScheme(330f)
}

@Composable
fun AppTheme(theme: Int, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = schemeFor(theme),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(10.dp),
            small = RoundedCornerShape(16.dp),
            medium = RoundedCornerShape(24.dp),
            large = RoundedCornerShape(32.dp),
            extraLarge = RoundedCornerShape(44.dp)
        ),
        content = content
    )
}

// ---------- Activity ----------

class MainActivity : ComponentActivity() {
    private val engine = FmEngine()
    private val themeState = mutableIntStateOf(0)
    private val orientState = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("fmsynth", MODE_PRIVATE)
        themeState.intValue = prefs.getInt("theme", 0)
        orientState.intValue = prefs.getInt("orient", 0)
        applyOrientation(orientState.intValue)
        Store.read(this, "auto")?.let { Store.engineFromJson(engine, it) }
        engine.start()
        recoverRecordings(applicationContext)
        var recFile: File? = null
        setContent {
            AppTheme(themeState.intValue) {
                SynthApp(
                    engine = engine,
                    theme = themeState.intValue,
                    orient = orientState.intValue,
                    onTheme = {
                        themeState.intValue = it
                        prefs.edit().putInt("theme", it).apply()
                    },
                    onOrient = {
                        orientState.intValue = it
                        prefs.edit().putInt("orient", it).apply()
                        applyOrientation(it)
                    },
                    onRec = { on ->
                        if (on) {
                            val f = File(filesDir, "rec_${System.currentTimeMillis()}.pcm")
                            recFile = f
                            engine.startRecording(f)
                        } else {
                            recFile?.let { finishRecording(it) }
                        }
                    }
                )
            }
        }
    }

    private fun applyOrientation(m: Int) {
        requestedOrientation = when (m) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            2 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun finishRecording(pcm: File) {
        engine.stopRecording()
        val name = "FMSynth_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".wav"
        val ctx = applicationContext
        Thread {
            val res = WavSaver.save(ctx, pcm, name)
            runOnUiThread {
                Toast.makeText(
                    ctx,
                    if (res != null) "Сохранено: $res" else "Не удалось сохранить запись",
                    Toast.LENGTH_LONG
                ).show()
            }
            pcm.delete()
        }.start()
    }

    override fun onPause() {
        Store.write(this, "auto", Store.engineToJson(engine))
        super.onPause()
    }

    override fun onDestroy() {
        if (engine.recording) engine.stopRecording()
        engine.stop()
        super.onDestroy()
    }
}

// ---------- Главный экран ----------

@Composable
fun SynthApp(
    engine: FmEngine,
    theme: Int,
    orient: Int,
    onTheme: (Int) -> Unit,
    onOrient: (Int) -> Unit,
    onRec: (Boolean) -> Unit
) {
    var page by remember { mutableIntStateOf(0) }
    var tab by remember { mutableIntStateOf(0) }
    var settings by remember { mutableStateOf(false) }
    var projVer by remember { mutableIntStateOf(0) }
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val pages = listOf(
        "Паттерн" to "▦", "Звук" to "◉", "Эффекты" to "✦", "Микшер" to "≡", "Файлы" to "▤"
    )

    BackHandler(enabled = settings) { settings = false }

    if (settings) {
        SettingsScreen(theme, orient, onTheme, onOrient) { settings = false }
    } else {
        Scaffold(
            topBar = {
                if (!wide) CenterAlignedTopAppBar(
                    title = { Text("FM Synth") },
                    actions = {
                        IconButton(onClick = { settings = true }) { Text("⚙", fontSize = 22.sp) }
                    }
                )
            },
            bottomBar = {
                if (!wide) NavigationBar {
                    pages.forEachIndexed { i, p ->
                        NavigationBarItem(
                            selected = page == i,
                            onClick = { page = i },
                            icon = { Text(p.second, fontSize = 20.sp) },
                            label = { Text(p.first, fontSize = 11.sp) }
                        )
                    }
                }
            }
        ) { pad ->
            Row(Modifier.padding(pad).fillMaxSize()) {
                if (wide) NavigationRail {
                    pages.forEachIndexed { i, p ->
                        NavigationRailItem(
                            selected = page == i,
                            onClick = { page = i },
                            icon = { Text(p.second, fontSize = 20.sp) },
                            label = { Text(p.first) }
                        )
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Transport(engine, wide, projVer, onRec) { settings = true }
                    if (page != 3) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            for (i in 0 until NTRACKS) {
                                FilterChip(
                                    selected = tab == i,
                                    onClick = { tab = i },
                                    label = {
                                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                            Text("T${i + 1}")
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                    Column(
                        Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        when (page) {
                            0 -> SeqPage(engine, tab, wide)
                            1 -> FmPage(engine, tab, wide)
                            2 -> FxPage(engine, tab, wide)
                            3 -> MixerPage(engine, wide)
                            else -> FilesPage(engine, tab) { projVer++ }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Transport(
    engine: FmEngine,
    wide: Boolean,
    projVer: Int,
    onRec: (Boolean) -> Unit,
    onSettings: () -> Unit
) {
    var playing by remember(projVer) { mutableStateOf(engine.playing) }
    var bpm by remember(projVer) { mutableFloatStateOf(engine.bpm) }
    var rec by remember { mutableStateOf(engine.recording) }
    var sec by remember { mutableIntStateOf(0) }
    LaunchedEffect(rec) {
        while (rec) {
            sec = (engine.recSamples / 44100L).toInt()
            delay(250)
        }
    }
    val cs = MaterialTheme.colorScheme

    val play: @Composable (Modifier) -> Unit = { m ->
        Button(onClick = { playing = !playing; engine.playing = playing }, modifier = m) {
            Text(if (playing) "■ Стоп" else "▶ Играть")
        }
    }
    val recBtn: @Composable (Modifier) -> Unit = { m ->
        FilledTonalButton(
            onClick = { rec = !rec; onRec(rec) },
            modifier = m,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = if (rec) cs.error else cs.secondaryContainer,
                contentColor = if (rec) cs.onError else cs.onSecondaryContainer
            )
        ) {
            Text(if (rec) "■ %d:%02d".format(sec / 60, sec % 60) else "● Запись")
        }
    }
    val bpmRow: @Composable (Modifier) -> Unit = { m ->
        Row(m, verticalAlignment = Alignment.CenterVertically) {
            Text("BPM ${bpm.toInt()}", Modifier.width(76.dp))
            Slider(
                value = bpm,
                onValueChange = { bpm = it; engine.bpm = it },
                valueRange = 60f..200f
            )
        }
    }

    if (wide) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            play(Modifier)
            recBtn(Modifier)
            bpmRow(Modifier.weight(1f))
            IconButton(onClick = onSettings) { Text("⚙", fontSize = 22.sp) }
        }
    } else {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                play(Modifier.weight(1f))
                recBtn(Modifier.weight(1f))
            }
            bpmRow(Modifier.fillMaxWidth())
        }
    }
}

// ---------- Настройки ----------

@Composable
fun OptionRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    leading: (@Composable () -> Unit)? = null
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(if (selected) cs.primaryContainer else cs.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (leading != null) leading()
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = if (selected) cs.onPrimaryContainer else cs.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (selected) Text("✓", color = cs.onPrimaryContainer, fontSize = 20.sp)
    }
}

@Composable
fun SettingsScreen(
    theme: Int,
    orient: Int,
    onTheme: (Int) -> Unit,
    onOrient: (Int) -> Unit,
    onBack: () -> Unit
) {
    val themes = listOf("Тёмная", "Светлая", "Фиолетовая", "Зелёная", "Розовая")
    val orients = listOf("Авто", "Вертикально", "Горизонтально")
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Text("←", fontSize = 22.sp) }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Тема", style = MaterialTheme.typography.titleLarge)
            themes.forEachIndexed { i, n ->
                OptionRow(n, theme == i, { onTheme(i) }) {
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(schemeFor(i).primary)
                            .border(1.dp, schemeFor(i).outline, CircleShape)
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Ориентация экрана", style = MaterialTheme.typography.titleLarge)
            orients.forEachIndexed { i, n ->
                OptionRow(n, orient == i, { onOrient(i) })
            }
        }
    }
}

// ---------- Общие элементы ----------

@Composable
fun Param(
    label: String,
    key: Any,
    get: () -> Float,
    set: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    log: Boolean = false,
    unit: String = ""
) {
    val lo = range.start
    val hi = range.endInclusive
    fun toValue(p: Float): Float = if (log) lo * (hi / lo).pow(p) else lo + (hi - lo) * p
    fun toPos(v: Float): Float =
        (if (log) ln(v / lo) / ln(hi / lo) else (v - lo) / (hi - lo)).coerceIn(0f, 1f)
    var p by remember(key) { mutableFloatStateOf(toPos(get())) }
    val v = toValue(p)
    Column {
        Text(
            "$label: ${if (v >= 100f) "%.0f".format(v) else "%.2f".format(v)}$unit",
            style = MaterialTheme.typography.labelLarge
        )
        Slider(value = p, onValueChange = { p = it; set(toValue(it)) })
    }
}

// параметр дорожки по индексу
@Composable
fun TP(
    label: String,
    t: FmTrack,
    idx: Int,
    range: ClosedFloatingPointRange<Float>,
    key: Any = t,
    log: Boolean = false
) {
    Param(label, key, { t.p[idx] }, { t.p[idx] = it }, range, log)
}

@Composable
fun FxCard(title: String, hint: String = "", content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (hint.isNotEmpty()) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

@Composable
fun Cards(wide: Boolean, items: List<@Composable () -> Unit>) {
    if (!wide) {
        items.forEach { it() }
    } else {
        items.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { item -> Box(Modifier.weight(1f)) { item() } }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

// ---------- Паттерн ----------

@Composable
fun RowScope.StepCell(
    s: Int,
    note: Int,
    locked: Boolean,
    inRange: Boolean,
    selected: Boolean,
    playhead: Boolean,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val bg = when {
        playhead -> cs.tertiary
        note >= 0 -> cs.primaryContainer
        else -> cs.surfaceContainerHigh
    }
    val fg = when {
        playhead -> cs.onTertiary
        note >= 0 -> cs.onPrimaryContainer
        else -> cs.onSurfaceVariant
    }
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .weight(1f)
            .aspectRatio(0.8f)
            .alpha(if (inRange) 1f else 0.35f)
            .clip(shape)
            .background(bg)
            .then(if (selected) Modifier.border(2.dp, cs.primary, shape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            (if (note >= 0) noteName(note) else "${s + 1}") + (if (locked) "•" else ""),
            color = fg,
            fontSize = 12.sp
        )
    }
}

@Composable
fun LockRow(
    t: FmTrack,
    sel: Int,
    k: Int,
    label: String,
    range: ClosedFloatingPointRange<Float>
) {
    var on by remember(t, sel) { mutableStateOf(t.locks[k][sel] != LOCK_OFF) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Switch(
            checked = on,
            onCheckedChange = {
                on = it
                t.locks[k][sel] = if (it) t.p[LOCK_BASE[k]] else LOCK_OFF
            }
        )
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
    if (on) {
        Param(label, Triple(t, sel, k), { t.locks[k][sel] }, { t.locks[k][sel] = it }, range)
    }
}

@Composable
fun SeqPage(engine: FmEngine, ti: Int, wide: Boolean) {
    val t = engine.tracks[ti]
    var ver by remember { mutableIntStateOf(0) }
    var sel by remember(ti) { mutableIntStateOf(0) }
    var octave by remember { mutableIntStateOf(3) }
    var head by remember { mutableIntStateOf(-1) }
    var solo by remember(ti) { mutableStateOf(t.solo) }
    var mute by remember(ti) { mutableStateOf(t.mute) }
    var len by remember(ti) { mutableIntStateOf(t.len) }
    LaunchedEffect(ti) {
        while (true) {
            head = t.pos
            delay(30)
        }
    }
    val v = ver
    val perRow = if (wide) 16 else 8
    val perKeys = if (wide) 12 else 6

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Дорожка ${ti + 1}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            FilterChip(selected = solo, onClick = { solo = !solo; t.solo = solo }, label = { Text("Solo") })
            FilterChip(selected = mute, onClick = { mute = !mute; t.mute = mute }, label = { Text("Mute") })
        }
        Text("Длина паттерна: $len шагов", style = MaterialTheme.typography.labelLarge)
        Slider(
            value = len.toFloat(),
            onValueChange = { len = it.roundToInt().coerceIn(1, STEPS); t.len = len },
            valueRange = 1f..16f,
            steps = 14
        )
        for (row in 0 until STEPS / perRow) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in 0 until perRow) {
                    val s = row * perRow + c
                    StepCell(
                        s, t.notes[s], t.locks.any { it[s] != LOCK_OFF }, s < len, sel == s, head == s
                    ) {
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
            Text("Шаг ${sel + 1}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { if (octave > 0) octave-- }) { Text("Окт −") }
            Text("$octave")
            OutlinedButton(onClick = { if (octave < 7) octave++ }) { Text("Окт +") }
        }
        Text(
            "Выбери шаг и нажми ноту: она запишется, выбор сдвинется дальше. Точка у шага = есть P-lock",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        for (r in 0 until 12 / perKeys) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (k in r * perKeys until (r + 1) * perKeys) {
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { t.notes[sel] = -1; ver++ },
                modifier = Modifier.weight(1f)
            ) { Text("Стереть шаг") }
            OutlinedButton(
                onClick = { for (s in 0 until STEPS) t.notes[s] = -1; ver++ },
                modifier = Modifier.weight(1f)
            ) { Text("Очистить") }
            OutlinedButton(
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
            ) { Text("Случайно") }
        }
        Cards(wide, listOf<@Composable () -> Unit>(
            {
                FxCard("Шаг ${sel + 1}", "Длина 16 = звук не обрезается") {
                    val key = Triple(t, sel, ver)
                    Param("Громкость шага", key, { t.vel[sel] }, { t.vel[sel] = it }, 0f..1f)
                    Param("Длина (шагов)", key, { t.gate[sel] }, { t.gate[sel] = it }, 0.25f..16f, log = true)
                    Param("Вероятность", key, { t.prob[sel] }, { t.prob[sel] = it }, 0f..1f)
                }
            },
            {
                FxCard("P-lock шага ${sel + 1}", "Свои значения параметров только на этом шаге") {
                    LockRow(t, sel, 0, "FM-глубина", 0f..2f)
                    LockRow(t, sel, 1, "Фильтр", 0f..1f)
                    LockRow(t, sel, 2, "Затухание (с)", 0.03f..2f)
                    LockRow(t, sel, 3, "Панорама", -1f..1f)
                }
            }
        ))
    }
}

// ---------- Звук (FM) ----------

@Composable
fun FmPage(engine: FmEngine, ti: Int, wide: Boolean) {
    val t = engine.tracks[ti]
    var algo by remember(ti) { mutableIntStateOf(t.p[P.ALGO].toInt()) }
    var dest by remember(ti) { mutableIntStateOf(t.p[P.LFODEST].toInt()) }
    val descs = listOf(
        "4 → 3 → 2 → 1: длинная цепочка, самый яркий и жёсткий звук",
        "(4 → 3) + (2 → 1): две пары, колокольные тембры",
        "4 модулирует 1, 2 и 3: богатый хриплый тембр",
        "1 + 2 + 3 + 4: сумма синусов, органный звук"
    )
    val dests = listOf("Выкл", "Питч", "FM", "Фильтр", "Громк.", "Пан")
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FxCard("Алгоритм", "Как операторы влияют друг на друга") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (i in 0..3) {
                    FilterChip(
                        selected = algo == i,
                        onClick = { algo = i; t.p[P.ALGO] = i.toFloat() },
                        label = { Text("A${i + 1}") }
                    )
                }
            }
            Text(descs[algo], style = MaterialTheme.typography.bodyMedium)
        }
        Cards(wide, buildList<@Composable () -> Unit> {
            for (i in 0..3) {
                add {
                    FxCard("Оператор ${i + 1}", "Ratio: множитель частоты. Level: громкость или сила модуляции") {
                        TP("Ratio", t, P.R1 + i, 0.25f..12f)
                        TP("Level", t, P.L1 + i, 0f..1f)
                    }
                }
            }
            add {
                FxCard("Огибающие", "Как быстро звук и его яркость затухают") {
                    TP("FM-глубина (общая)", t, P.FMAMT, 0f..2f)
                    TP("Обратная связь Op4", t, P.FB, 0f..1f)
                    TP("Затухание громкости (с)", t, P.AMPDEC, 0.03f..2f)
                    TP("Затухание яркости (с)", t, P.MODDEC, 0.03f..2f)
                }
            }
            add {
                FxCard("LFO", "Медленная модуляция выбранного параметра") {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        dests.forEachIndexed { i, n ->
                            FilterChip(
                                selected = dest == i,
                                onClick = { dest = i; t.p[P.LFODEST] = i.toFloat() },
                                label = { Text(n) }
                            )
                        }
                    }
                    TP("Скорость (Гц)", t, P.LFORATE, 0.05f..20f, log = true)
                    TP("Глубина", t, P.LFODEPTH, 0f..1f)
                }
            }
        })
    }
}

// ---------- Эффекты дорожки ----------

@Composable
fun FxPage(engine: FmEngine, ti: Int, wide: Boolean) {
    val t = engine.tracks[ti]
    var ver by remember { mutableIntStateOf(0) }
    val key = Pair(t, ver)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Эффекты дорожки ${ti + 1}",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(onClick = {
                for (i in intArrayOf(P.RM_MIX, P.CR_MIX, P.FL_MIX, P.DL_MIX, P.RV_MIX, P.DRIVE)) t.p[i] = 0f
                t.p[P.CUT] = 1f
                t.p[P.RES] = 0f
                ver++
            }) { Text("Выключить все") }
        }
        Cards(wide, listOf<@Composable () -> Unit>(
            {
                FxCard("Фильтр и драйв", "Низкочастотный фильтр с резонансом и перегруз") {
                    TP("Фильтр", t, P.CUT, 0f..1f, key)
                    TP("Резонанс", t, P.RES, 0f..1f, key)
                    TP("Драйв", t, P.DRIVE, 0f..1f, key)
                }
            },
            {
                FxCard("Дилей", "Время меньше 15 мс и повторы 80%+ дают металлический звон") {
                    TP("Время (мс)", t, P.DL_TIME, 1f..900f, key, log = true)
                    TP("Повторы", t, P.DL_FB, 0f..0.95f, key)
                    TP("Тон повторов", t, P.DL_TONE, 0f..1f, key)
                    TP("Микс", t, P.DL_MIX, 0f..1f, key)
                }
            },
            {
                FxCard("Флэнджер / хорус", "Мало задержки: реактивный свист. Много: широкий хорус") {
                    TP("Скорость (Гц)", t, P.FL_RATE, 0.05f..5f, key, log = true)
                    TP("Глубина (мс)", t, P.FL_DEPTH, 0f..15f, key)
                    TP("Задержка (мс)", t, P.FL_BASE, 1f..25f, key)
                    TP("Обратная связь", t, P.FL_FB, 0f..0.9f, key)
                    TP("Микс", t, P.FL_MIX, 0f..1f, key)
                }
            },
            {
                FxCard("Биткрашер", "Грязный цифровой звук в стиле 8 бит") {
                    TP("Разрядность (бит)", t, P.CR_BITS, 2f..16f, key)
                    TP("Понижение частоты", t, P.CR_RATE, 1f..40f, key, log = true)
                    TP("Микс", t, P.CR_MIX, 0f..1f, key)
                }
            },
            {
                FxCard("Кольцевой модулятор", "Металлические, роботизированные тембры") {
                    TP("Частота (Гц)", t, P.RM_FREQ, 20f..2000f, key, log = true)
                    TP("Микс", t, P.RM_MIX, 0f..1f, key)
                }
            },
            {
                FxCard("Реверб") {
                    TP("Размер", t, P.RV_SIZE, 0f..1f, key)
                    TP("Демпфирование", t, P.RV_DAMP, 0f..0.9f, key)
                    TP("Микс", t, P.RV_MIX, 0f..1f, key)
                }
            }
        ))
    }
}

// ---------- Микшер ----------

@Composable
fun MixerRow(t: FmTrack, i: Int) {
    var mute by remember { mutableStateOf(t.mute) }
    var solo by remember { mutableStateOf(t.solo) }
    FxCard("Дорожка ${i + 1}") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = solo, onClick = { solo = !solo; t.solo = solo }, label = { Text("Solo") })
            FilterChip(selected = mute, onClick = { mute = !mute; t.mute = mute }, label = { Text("Mute") })
        }
        TP("Громкость", t, P.VOL, 0f..1f)
        TP("Панорама (−1 лево, 1 право)", t, P.PAN, -1f..1f)
    }
}

@Composable
fun MixerPage(engine: FmEngine, wide: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Cards(wide, buildList<@Composable () -> Unit> {
            for (i in 0 until NTRACKS) add { MixerRow(engine.tracks[i], i) }
            add {
                FxCard("Общее", "Swing сдвигает каждый второй шаг, как в драм-машинах") {
                    Param("Master", "m", { engine.master }, { engine.master = it }, 0f..1f)
                    Param("Swing", "s", { engine.swing }, { engine.swing = it }, 0f..0.5f)
                }
            }
        })
    }
}

// ---------- Файлы ----------

@Composable
fun SlotRow(name: String, stamp: String?, onSave: () -> Unit, onLoad: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            Text(
                stamp ?: "пусто",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        FilledTonalButton(onClick = onSave) { Text("Сохранить") }
        OutlinedButton(onClick = onLoad, enabled = stamp != null) { Text("Загрузить") }
    }
}

@Composable
fun FilesPage(engine: FmEngine, ti: Int, onLoaded: () -> Unit) {
    val ctx = LocalContext.current
    var ver by remember { mutableIntStateOf(0) }
    val t = engine.tracks[ti]
    fun toast(s: String) = Toast.makeText(ctx, s, Toast.LENGTH_SHORT).show()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NewProjectCard(engine, onLoaded)
        FxCard("Проекты", "Всё сразу: паттерны, звуки, эффекты и темп всех дорожек. Последнее состояние сохраняется само") {
            val tag = ver
            for (n in 1..8) {
                SlotRow(
                    "Проект $n",
                    Store.stamp(ctx, "proj_$n", tag),
                    onSave = {
                        Store.write(ctx, "proj_$n", Store.engineToJson(engine))
                        ver++
                        toast("Проект $n сохранён")
                    },
                    onLoad = {
                        val o = Store.read(ctx, "proj_$n")
                        if (o != null) {
                            Store.engineFromJson(engine, o)
                            onLoaded()
                            ver++
                            toast("Проект $n загружен")
                        }
                    }
                )
            }
        }
        FxCard(
            "Звуки: дорожка ${ti + 1}",
            "Сохраняет только звук и эффекты выбранной дорожки, без нот. Загрузить можно на любую дорожку"
        ) {
            val tag = ver
            for (n in 1..8) {
                SlotRow(
                    "Звук $n",
                    Store.stamp(ctx, "preset_$n", tag),
                    onSave = {
                        Store.write(ctx, "preset_$n", Store.trackToJson(t, false))
                        ver++
                        toast("Звук $n сохранён")
                    },
                    onLoad = {
                        val o = Store.read(ctx, "preset_$n")
                        if (o != null) {
                            Store.trackFromJson(t, o, false)
                            ver++
                            toast("Звук $n загружен на дорожку ${ti + 1}")
                        }
                    }
                )
            }
        }
    }
}
