package com.example.fmsynth

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.tanh

const val STEPS = 16
const val NTRACKS = 6
private const val SR = 44100
private const val TWO_PI = 2.0 * PI

class FmTrack {
    @Volatile var algo = 0
    val ratio = floatArrayOf(1f, 2f, 1f, 3f)
    val level = floatArrayOf(0.9f, 0.5f, 0.4f, 0.3f)
    @Volatile var fb = 0f
    @Volatile var ampDec = 0.4f
    @Volatile var modDec = 0.25f
    @Volatile var cutoff = 1f
    @Volatile var drive = 0f
    @Volatile var vol = 0.8f
    @Volatile var mute = false
    @Volatile var solo = false
    val notes = IntArray(STEPS) { -1 }
    @Volatile var pending = -1

    internal var freq = 110.0
    internal val ph = DoubleArray(4)
    internal var amp = 0f
    internal var mod = 0f
    internal var atk = 1f
    internal var lp1 = 0f
    internal var lp2 = 0f
    internal var fbPrev = 0f
}

private class Comb(n: Int) {
    private val b = FloatArray(n)
    private var i = 0
    private var s = 0f
    fun process(x: Float, fb: Float, damp: Float): Float {
        val o = b[i]
        s = o * (1f - damp) + s * damp
        b[i] = x + s * fb
        if (++i >= b.size) i = 0
        return o
    }
}

private class AllPass(n: Int) {
    private val b = FloatArray(n)
    private var i = 0
    fun process(x: Float): Float {
        val bo = b[i]
        val o = -x + bo
        b[i] = x + bo * 0.5f
        if (++i >= b.size) i = 0
        return o
    }
}

private fun readFrac(b: FloatArray, w: Int, d: Float): Float {
    val n = b.size
    val dd = d.coerceIn(1f, n - 2f)
    val pos = w - dd
    val i0f = floor(pos)
    val fr = pos - i0f
    var i0 = i0f.toInt() % n
    if (i0 < 0) i0 += n
    val i1 = if (i0 + 1 >= n) 0 else i0 + 1
    return b[i0] * (1f - fr) + b[i1] * fr
}

private fun softClip(x: Float): Float = x / (1f + abs(x))

private fun limit(x: Float): Float {
    val a = abs(x)
    return if (a < 0.8f) x else sign(x) * (0.8f + 0.2f * tanh((a - 0.8f) / 0.2f))
}

class FmEngine {
    val tracks = List(NTRACKS) { FmTrack() }

    @Volatile var bpm = 120f
    @Volatile var playing = false
    @Volatile var step = -1

    // delay (время от 1 мс)
    @Volatile var delayMs = 300f
    @Volatile var delayFb = 0.35f
    @Volatile var delayTone = 0.6f
    @Volatile var delayMix = 0.2f
    // flanger / chorus
    @Volatile var flRate = 0.4f
    @Volatile var flDepth = 3f
    @Volatile var flBase = 2f
    @Volatile var flFb = 0.3f
    @Volatile var flMix = 0f
    // bitcrusher
    @Volatile var crBits = 8f
    @Volatile var crRate = 4f
    @Volatile var crMix = 0f
    // ring mod
    @Volatile var rmFreq = 200f
    @Volatile var rmMix = 0f
    // reverb
    @Volatile var revSize = 0.7f
    @Volatile var revDamp = 0.4f
    @Volatile var revMix = 0.2f
    @Volatile var master = 0.8f

    // запись
    @Volatile var recording = false
    @Volatile var recSamples = 0L
    private val recQueue = LinkedBlockingQueue<ByteArray>()
    private var recThread: Thread? = null

    @Volatile private var running = false
    private var thread: Thread? = null

    init {
        listOf(0 to 36, 3 to 36, 6 to 39, 8 to 36, 11 to 43, 14 to 41)
            .forEach { (s, n) -> tracks[0].notes[s] = n }
        tracks[0].apply {
            algo = 0; fb = 0.2f; ampDec = 0.35f; modDec = 0.12f; cutoff = 0.75f
            ratio[0] = 1f; ratio[1] = 1f; ratio[2] = 2f; ratio[3] = 1f
            level[0] = 0.9f; level[1] = 0.5f; level[2] = 0.3f; level[3] = 0.2f
        }
        listOf(2 to 60, 6 to 63, 10 to 67, 14 to 65)
            .forEach { (s, n) -> tracks[1].notes[s] = n }
        tracks[1].apply {
            algo = 1; ampDec = 0.5f; modDec = 0.3f; cutoff = 0.9f
            ratio[0] = 1f; ratio[1] = 2f; ratio[2] = 1f; ratio[3] = 3f
            level[0] = 0.8f; level[1] = 0.5f; level[2] = 0.6f; level[3] = 0.35f
        }
        for (s in 0 until STEPS step 2) tracks[2].notes[s] = 90
        tracks[2].apply {
            algo = 0; fb = 1f; ampDec = 0.06f; modDec = 0.05f; cutoff = 1f; vol = 0.5f
            ratio[0] = 1f; ratio[1] = 2.76f; ratio[2] = 5.4f; ratio[3] = 8.93f
            level[0] = 0.7f; level[1] = 0.9f; level[2] = 0.9f; level[3] = 0.9f
        }
    }

    private fun trig(t: FmTrack, note: Int) {
        t.freq = 440.0 * 2.0.pow((note - 69) / 12.0)
        t.amp = 1f
        t.mod = 1f
        t.atk = 0f
    }

    private fun voice(t: FmTrack): Float {
        val p = t.ph
        val l = t.level
        val m = t.mod
        val o4 = sin(p[3] + t.fb * 2.5f * t.fbPrev).toFloat()
        t.fbPrev = o4
        val i3 = l[3] * 6f * m
        val i2 = l[2] * 6f * m
        val i1 = l[1] * 6f * m
        val out: Float = when (t.algo) {
            0 -> {
                val o3 = sin(p[2] + o4 * i3).toFloat()
                val o2 = sin(p[1] + o3 * i2).toFloat()
                val o1 = sin(p[0] + o2 * i1).toFloat()
                o1 * l[0]
            }
            1 -> {
                val o3 = sin(p[2] + o4 * i3).toFloat()
                val o2 = sin(p[1]).toFloat()
                val o1 = sin(p[0] + o2 * i1).toFloat()
                (o1 * l[0] + o3 * l[2]) * 0.7f
            }
            2 -> {
                val a = o4 * i3
                val o1 = sin(p[0] + a).toFloat()
                val o2 = sin(p[1] + a).toFloat()
                val o3 = sin(p[2] + a).toFloat()
                (o1 * l[0] + o2 * l[1] + o3 * l[2]) * 0.5f
            }
            else -> {
                val o1 = sin(p[0]).toFloat()
                val o2 = sin(p[1]).toFloat()
                val o3 = sin(p[2]).toFloat()
                (o1 * l[0] + o2 * l[1] + o3 * l[2] + o4 * l[3]) * 0.4f
            }
        }
        val w = TWO_PI * t.freq / SR
        for (j in 0..3) {
            var x = p[j] + w * t.ratio[j]
            if (x > TWO_PI) x %= TWO_PI
            p[j] = x
        }
        return out
    }

    fun startRecording(file: File) {
        if (recording) return
        recQueue.clear()
        recSamples = 0L
        val out = BufferedOutputStream(FileOutputStream(file), 1 shl 16)
        recThread = Thread {
            try {
                while (true) {
                    val b = recQueue.take()
                    if (b.isEmpty()) break
                    out.write(b)
                }
            } catch (e: Exception) {
            } finally {
                try { out.close() } catch (e: Exception) {}
            }
        }.also { it.start() }
        recording = true
    }

    fun stopRecording() {
        if (!recording) return
        recording = false
        recQueue.put(ByteArray(0))
        recThread?.join()
        recThread = null
    }

    fun start() {
        if (running) return
        running = true
        thread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val frames = 256
            val minBuf = AudioTrack.getMinBufferSize(
                SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val at = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SR)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, frames * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            at.play()

            val buf = ShortArray(frames)
            val dl = FloatArray(SR * 2)
            var dw = 0
            var dCur = -1f
            var dLp = 0f
            val fl = FloatArray(4096)
            var flW = 0
            var flPh = 0.0
            var rmPh = 0.0
            var crCnt = 0f
            var crHold = 0f
            val combs = arrayOf(Comb(1116), Comb(1188), Comb(1277), Comb(1356))
            val aps = arrayOf(AllPass(556), AllPass(441))
            val lpC = FloatArray(NTRACKS)
            val ampC = FloatArray(NTRACKS)
            val modC = FloatArray(NTRACKS)
            val audible = BooleanArray(NTRACKS)
            var counter = 0.0
            var was = false

            while (running) {
                val sps = SR * 60.0 / bpm / 4.0
                val isPlaying = playing
                if (isPlaying && !was) { step = STEPS - 1; counter = 0.0 }
                if (!isPlaying && was) step = -1
                was = isPlaying

                var anySolo = false
                for (k in 0 until NTRACKS) if (tracks[k].solo) anySolo = true

                for (k in 0 until NTRACKS) {
                    val t = tracks[k]
                    audible[k] = !t.mute && (!anySolo || t.solo)
                    lpC[k] = (1.0 - exp(-TWO_PI * 40.0 * 500.0.pow(t.cutoff.toDouble()) / SR)).toFloat()
                    ampC[k] = exp(-1.0 / (SR * t.ampDec.toDouble())).toFloat()
                    modC[k] = exp(-1.0 / (SR * t.modDec.toDouble())).toFloat()
                    val pn = t.pending
                    if (pn >= 0) { t.pending = -1; trig(t, pn) }
                }

                val dTarget = (delayMs * SR / 1000f).coerceIn(1f, (dl.size - 2).toFloat())
                if (dCur < 0f) dCur = dTarget
                val dFbV = delayFb
                val dTone = 0.05f + 0.95f * delayTone
                val dMix = delayMix
                val flRateV = flRate
                val flDepthV = flDepth
                val flBaseV = flBase
                val flFbV = flFb
                val flMixV = flMix
                val crLevels = 2f.pow(crBits - 1f)
                val crRateV = crRate
                val crMixV = crMix
                val rmFreqV = rmFreq
                val rmMixV = rmMix
                val rFb = 0.7f + revSize * 0.28f
                val rDamp = revDamp
                val rMix = revMix
                val mst = master

                for (i in 0 until frames) {
                    if (isPlaying) {
                        counter -= 1.0
                        if (counter <= 0.0) {
                            counter += sps
                            val s = (step + 1) % STEPS
                            step = s
                            for (k in 0 until NTRACKS) {
                                val t = tracks[k]
                                val n = t.notes[s]
                                if (n >= 0 && audible[k]) trig(t, n)
                            }
                        }
                    }

                    var mix = 0f
                    for (k in 0 until NTRACKS) {
                        val t = tracks[k]
                        if (t.amp < 0.0003f) continue
                        if (!audible[k]) { t.amp = 0f; continue }
                        if (t.atk < 1f) t.atk = min(1f, t.atk + 0.02f)
                        var y = voice(t) * t.amp * t.atk
                        t.amp *= ampC[k]
                        t.mod *= modC[k]
                        val c = lpC[k]
                        t.lp1 += c * (y - t.lp1)
                        t.lp2 += c * (t.lp1 - t.lp2)
                        y = t.lp2
                        if (t.drive > 0.01f) {
                            val z = y * (1f + t.drive * 10f)
                            y = z / (1f + abs(z))
                        }
                        mix += y * t.vol * 0.3f
                    }

                    var x = mix

                    // ring mod
                    if (rmMixV > 0.001f) {
                        rmPh += TWO_PI * rmFreqV / SR
                        if (rmPh > TWO_PI) rmPh -= TWO_PI
                        x = x * (1f - rmMixV) + x * sin(rmPh).toFloat() * rmMixV
                    }

                    // bitcrusher
                    if (crMixV > 0.001f) {
                        crCnt += 1f
                        if (crCnt >= crRateV) {
                            crCnt -= crRateV
                            crHold = round(x * crLevels) / crLevels
                        }
                        x = x * (1f - crMixV) + crHold * crMixV
                    }

                    // flanger / chorus
                    if (flMixV > 0.001f) {
                        flPh += flRateV.toDouble() / SR
                        if (flPh >= 1.0) flPh -= 1.0
                        val lfo = 0.5f + 0.5f * sin(TWO_PI * flPh).toFloat()
                        val fOut = readFrac(fl, flW, (flBaseV + flDepthV * lfo) * SR / 1000f)
                        fl[flW] = x + fOut * flFbV
                        flW = (flW + 1) % fl.size
                        x += fOut * flMixV
                    }

                    // delay (от 1 мс: комб-фильтр, металл)
                    dCur += (dTarget - dCur) * 0.002f
                    val dOut = readFrac(dl, dw, dCur)
                    dLp += dTone * (dOut - dLp)
                    dl[dw] = x + softClip(dLp * dFbV)
                    dw = (dw + 1) % dl.size
                    x += dOut * dMix

                    // reverb
                    val rin = x * 0.05f
                    var rs = 0f
                    for (cb in combs) rs += cb.process(rin, rFb, rDamp)
                    for (ap in aps) rs = ap.process(rs)
                    x += rs * rMix * 3f

                    val yOut = limit(x * mst).coerceIn(-1f, 1f)
                    buf[i] = (yOut * 32767f).toInt().toShort()
                }
                at.write(buf, 0, frames)

                if (recording) {
                    val bb = ByteArray(frames * 2)
                    for (i in 0 until frames) {
                        val v = buf[i].toInt()
                        bb[2 * i] = v.toByte()
                        bb[2 * i + 1] = (v shr 8).toByte()
                    }
                    recQueue.offer(bb)
                    recSamples += frames
                }
            }
            at.stop()
            at.release()
        }.also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join()
        thread = null
    }
}
