@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.fmsynth

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Полный сброс: пустые паттерны, звуки по умолчанию, эффекты выключены
fun FmEngine.newProject() {
    playing = false
    bpm = 120f
    swing = 0f
    master = 0.8f
    for (t in tracks) {
        t.resetSound()
        for (s in 0 until STEPS) {
            t.notes[s] = -1
            t.vel[s] = 1f
            t.gate[s] = 16f
            t.prob[s] = 1f
            for (k in 0..3) t.locks[k][s] = LOCK_OFF
        }
        t.len = 16
        t.mute = false
        t.solo = false
        t.pos = -1
        t.silence()
    }
}

// Недозаписанные записи (приложение закрылось до кнопки ■) превращаем в WAV
fun recoverRecordings(ctx: Context) {
    val files = ctx.filesDir.listFiles { f -> f.name.startsWith("rec_") && f.name.endsWith(".pcm") }
        ?: return
    if (files.isEmpty()) return
    Thread {
        var n = 0
        for (f in files) {
            if (f.length() > 4096L) {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(f.lastModified()))
                if (WavSaver.save(ctx, f, "FMSynth_recovered_$stamp.wav") != null) n++
            }
            f.delete()
        }
        if (n > 0) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(ctx, "Восстановлено записей: $n (Музыка/FMSynth)", Toast.LENGTH_LONG).show()
            }
        }
    }.start()
}

@Composable
fun NewProjectCard(engine: FmEngine, onDone: () -> Unit) {
    var ask by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    FxCard("Новый проект", "Очищает всё: ноты, звуки, эффекты, темп. Слоты сохранений не трогает") {
        Button(onClick = { ask = true }) { Text("Начать с нуля") }
    }
    if (ask) {
        AlertDialog(
            onDismissRequest = { ask = false },
            title = { Text("Начать новый проект?") },
            text = { Text("Текущее состояние будет очищено. Если оно нужно, сначала сохрани его в слот ниже.") },
            confirmButton = {
                TextButton(onClick = {
                    ask = false
                    engine.newProject()
                    onDone()
                    Toast.makeText(ctx, "Новый проект", Toast.LENGTH_SHORT).show()
                }) { Text("Очистить") }
            },
            dismissButton = { TextButton(onClick = { ask = false }) { Text("Отмена") } }
        )
    }
}
