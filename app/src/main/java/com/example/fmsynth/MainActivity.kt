package com.example.fmsynth

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val engine = FmEngine()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engine.start()
        setContent {
            val ctx = LocalContext.current
            val scheme = if (Build.VERSION.SDK_INT >= 31) dynamicDarkColorScheme(ctx)
            else darkColorScheme()
            MaterialTheme(colorScheme = scheme) {
                SynthScreen(engine)
            }
        }
    }

    override fun onDestroy() {
        engine.stop()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SynthScreen(engine: FmEngine) {
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("FM Synth") }) },
        containerColor = MaterialTheme.colorScheme.background
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                engine.tracks.forEachIndexed { i, _ ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text("T${i + 1}") })
                }
            }
            TrackPanel(engine.tracks[tab], tab)
        }
    }
}

@Composable
fun TrackPanel(track: FmTrack, key: Int) {
    var ratio by remember(key) { mutableFloatStateOf(track.ratio) }
    var index by remember(key) { mutableFloatStateOf(track.index) }
    var on by remember(key) { mutableStateOf(track.on) }
    Column(Modifier.padding(16.dp)) {
        Text("Ratio: ${"%.2f".format(ratio)}")
        Slider(value = ratio, onValueChange = { ratio = it; track.ratio = it }, valueRange = 0.5f..8f)
        Text("Index: ${"%.2f".format(index)}")
        Slider(value = index, onValueChange = { index = it; track.index = it }, valueRange = 0f..10f)
        Button(onClick = { on = !on; track.on = on }) {
            Text(if (on) "Stop" else "Play")
        }
    }
}
