package com.example.fmsynth

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

class FmTrack(var freq: Float) {
    @Volatile var ratio = 2f
    @Volatile var index = 2f
    @Volatile var on = false
    internal var carPhase = 0.0
    internal var modPhase = 0.0
    internal var env = 0f
}

class FmEngine {
    val tracks = List(4) { FmTrack(55f * (1 shl it)) }

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread {
            val sr = 44100
            val frames = 256
            val minBuf = AudioTrack.getMinBufferSize(
                sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sr)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, frames * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track.play()
            val buf = ShortArray(frames)
            val twoPi = 2.0 * PI
            while (running) {
                for (i in 0 until frames) {
                    var mix = 0f
                    for (t in tracks) {
                        val target = if (t.on) 1f else 0f
                        t.env += (target - t.env) * 0.002f
                        if (t.env < 0.0005f && !t.on) continue
                        val mod = sin(t.modPhase).toFloat()
                        val out = sin(t.carPhase + t.index * mod).toFloat()
                        mix += out * t.env * 0.2f
                        t.carPhase += twoPi * t.freq / sr
                        t.modPhase += twoPi * t.freq * t.ratio / sr
                        if (t.carPhase > twoPi) t.carPhase -= twoPi
                        if (t.modPhase > twoPi) t.modPhase -= twoPi
                    }
                    buf[i] = (mix.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                }
                track.write(buf, 0, frames)
            }
            track.stop()
            track.release()
        }.also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join()
        thread = null
    }
}
