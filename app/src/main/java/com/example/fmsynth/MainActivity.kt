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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow
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
        engine.start()
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
                            val f = File(cacheDir, "rec_${System.currentTimeMillis()}.pcm")
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
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val pages = listOf("Паттерн" to "▦", "Звук" to "◉", "Эффекты" to "✦", "Микшер" to "≡")

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
                            label = { Text(p.first) }
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
                    Transport(engine, wide, onRec) { settings = true }
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
                            else -> MixerPage(engine)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Transport(engine: FmEngine, wide: Boolean, onRec: (Boolean) -> Unit, onSettings: () -> Unit) {
    var playing by remember { mutableStateOf(engine.playing) }
    var bpm by remember { mutableFloatStateOf(engine.bpm) }
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
fun SeqPage(engine: FmEngine, ti: Int, wide: Boolean) {
    val t = engine.tracks[ti]
    var ver by remember { mutableIntStateOf(0) }
    var sel by remember(ti) { mutableIntStateOf(0) }
    var octave by remember { mutableIntStateOf(3) }
    var head by remember { mutableIntStateOf(-1) }
    var solo by remember(ti) { mutableStateOf(t.solo) }
    var mute by remember(ti) { mutableStateOf(t.mute) }
    LaunchedEffect(Unit) {
        while (true) {
            head = engine.step
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
        for (row in 0 until STEPS / perRow) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in 0 until perRow) {
                    val s = row * perRow + c
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
            Text("Шаг ${sel + 1}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { if (octave > 0) octave-- }) { Text("Окт −") }
            Text("$octave")
            OutlinedButton(onClick = { if (octave < 7) octave++ }) { Text("Окт +") }
        }
        Text(
            "Выбери шаг и нажми ноту: она запишется, выбор сдвинется дальше",
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
    }
}

// ---------- Звук (FM) ----------

@Composable
fun FmPage(engine: FmEngine, ti: Int, wide: Boolean) {
    val t = engine.tracks[ti]
    var algo by remember(ti) { mutableIntStateOf(t.algo) }
    val descs = listOf(
        "4 → 3 → 2 → 1: длинная цепочка, самый яркий и жёсткий звук",
        "(4 → 3) + (2 → 1): две пары, колокольные тембры",
        "4 модулирует 1, 2 и 3: богатый хриплый тембр",
        "1 + 2 + 3 + 4: сумма синусов, органный звук"
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FxCard("Алгоритм", "Как операторы влияют друг на друга") {
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
        Cards(wide, buildList<@Composable () -> Unit> {
            for (i in 0..3) {
                add {
                    FxCard("Оператор ${i + 1}", "Ratio: множитель частоты. Level: громкость или сила модуляции") {
                        Param("Ratio", ti, { t.ratio[i] }, { t.ratio[i] = it }, 0.25f..12f)
                        Param("Level", ti, { t.level[i] }, { t.level[i] = it }, 0f..1f)
                    }
                }
            }
        })
        FxCard("Огибающие", "Как быстро звук и его яркость затухают") {
            Param("Обратная связь Op4", ti, { t.fb }, { t.fb = it }, 0f..1f)
            Param("Затухание громкости (с)", ti, { t.ampDec }, { t.ampDec = it }, 0.03f..2f)
            Param("Затухание яркости (с)", ti, { t.modDec }, { t.modDec = it }, 0.03f..2f)
        }
    }
}

// ---------- Эффекты ----------

@Composable
fun FxPage(engine: FmEngine, ti: Int, wide: Boolean) {
    val t = engine.tracks[ti]
    val e = engine
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Cards(wide, listOf<@Composable () -> Unit>(
            {
                FxCard("Дорожка ${ti + 1}: тон", "Фильтр и перегруз только для этой дорожки") {
                    Param("Фильтр", ti, { t.cutoff }, { t.cutoff = it }, 0f..1f)
                    Param("Драйв", ti, { t.drive }, { t.drive = it }, 0f..1f)
                }
            },
            {
                FxCard("Дилей", "Время меньше 15 мс и повторы 80%+ дают металлический звон") {
                    Param("Время (мс)", "g", { e.delayMs }, { e.delayMs = it }, 1f..900f, log = true)
                    Param("Повторы", "g", { e.delayFb }, { e.delayFb = it }, 0f..0.95f)
                    Param("Тон повторов", "g", { e.delayTone }, { e.delayTone = it }, 0f..1f)
                    Param("Микс", "g", { e.delayMix }, { e.delayMix = it }, 0f..1f)
                }
            },
            {
                FxCard("Флэнджер / хорус", "Мало задержки: реактивный свист. Много: широкий хорус") {
                    Param("Скорость (Гц)", "g", { e.flRate }, { e.flRate = it }, 0.05f..5f, log = true)
                    Param("Глубина (мс)", "g", { e.flDepth }, { e.flDepth = it }, 0f..15f)
                    Param("Задержка (мс)", "g", { e.flBase }, { e.flBase = it }, 1f..25f)
                    Param("Обратная связь", "g", { e.flFb }, { e.flFb = it }, 0f..0.9f)
                    Param("Микс", "g", { e.flMix }, { e.flMix = it }, 0f..1f)
                }
            },
            {
                FxCard("Биткрашер", "Грязный цифровой звук в стиле 8 бит") {
                    Param("Разрядность (бит)", "g", { e.crBits }, { e.crBits = it }, 2f..16f)
                    Param("Понижение частоты", "g", { e.crRate }, { e.crRate = it }, 1f..40f, log = true)
                    Param("Микс", "g", { e.crMix }, { e.crMix = it }, 0f..1f)
                }
            },
            {
                FxCard("Кольцевой модулятор", "Металлические, роботизированные тембры") {
                    Param("Частота (Гц)", "g", { e.rmFreq }, { e.rmFreq = it }, 20f..2000f, log = true)
                    Param("Микс", "g", { e.rmMix }, { e.rmMix = it }, 0f..1f)
                }
            },
            {
                FxCard("Реверб") {
                    Param("Размер", "g", { e.revSize }, { e.revSize = it }, 0f..1f)
                    Param("Демпфирование", "g", { e.revDamp }, { e.revDamp = it }, 0f..0.9f)
                    Param("Микс", "g", { e.revMix }, { e.revMix = it }, 0f..1f)
                }
            },
            {
                FxCard("Общая громкость") {
                    Param("Master", "g", { e.master }, { e.master = it }, 0f..1f)
                }
            }
        ))
    }
}

// ---------- Микшер ----------

@Composable
fun MixerPage(engine: FmEngine) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (i in 0 until NTRACKS) MixerRow(engine.tracks[i], i)
    }
}

@Composable
fun MixerRow(t: FmTrack, i: Int) {
    var mute by remember { mutableStateOf(t.mute) }
    var solo by remember { mutableStateOf(t.solo) }
    var vol by remember { mutableFloatStateOf(t.vol) }
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("T${i + 1}", style = MaterialTheme.typography.titleMedium)
            FilterChip(selected = solo, onClick = { solo = !solo; t.solo = solo }, label = { Text("Solo") })
            FilterChip(selected = mute, onClick = { mute = !mute; t.mute = mute }, label = { Text("Mute") })
            Slider(
                value = vol,
                onValueChange = { vol = it; t.vol = it },
                modifier = Modifier.weight(1f)
            )
        }
    }
}
