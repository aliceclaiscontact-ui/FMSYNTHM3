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
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.tan
import kotlin.math.tanh
import kotlin.random.Random

const val STEPS = 16
const val NTRACKS = 6
const val LOCK_OFF = -999f
private const val SR = 44100
private const val TWO_PI = 2.0 * PI

// Индексы параметров дорожки (звук + эффекты)
object P {
    const val ALGO = 0
    const val R1 = 1      // R1..R4 = 1..4
    const val L1 = 5      // L1..L4 = 5..8
    const val FB = 9
    const val AMPDEC = 10
    const val MODDEC = 11
    const val FMAMT = 12
    const val LFORATE = 13
    const val LFODEPTH = 14
    const val LFODEST = 15
    const val CUT = 16
    const val RES = 17
    const val DRIVE = 18
    const val RM_FREQ = 19
    const val RM_MIX = 20
    const val CR_BITS = 21
    const val CR_RATE = 22
    const val CR_MIX = 23
    const val FL_RATE = 24
    const val FL_DEPTH = 25
    const val FL_BASE = 26
    const val FL_FB = 27
    const val FL_MIX = 28
    const val DL_TIME = 29
    const val DL_FB = 30
    const val DL_TONE = 31
    const val DL_MIX = 32
    const val RV_SIZE = 33
    const val RV_DAMP = 34
    const val RV_MIX = 35
    const val VOL = 36
    const val PAN = 37
    const val N = 38
}

// какие параметры можно менять на отдельном шаге (P-lock)
val LOCK_BASE = intArrayOf(P.FMAMT, P.CUT, P.AMPDEC, P.PAN)

internal class Comb(n: Int) {
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

internal class AllPass(n: Int) {
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

class FmTrack {
    val p = FloatArray(P.N)
    val notes = IntArray(STEPS) { -1 }
    val vel = FloatArray(STEPS) { 1f }
    val gate = FloatArray(STEPS) { 16f }
    val prob = FloatArray(STEPS) { 1f }
    val locks = Array(4) { FloatArray(STEPS) { LOCK_OFF } }
    @Volatile var len = 16
    @Volatile var mute = false
    @Volatile var solo = false
    @Volatile var pending = -1
    @Volatile var pos = -1

    // выход после панорамы
    @Volatile var outL = 0f
    @Volatile var outR = 0f

    // голос
    private var freq = 110.0
    private val ph = DoubleArray(4)
    private var amp = 0f
    private var mod = 0f
    private var atk = 1f
    private var fbPrev = 0f
    private var gateLeft = 0f
    private var rel = false
    private var lockMask = 0
    private val lockVal = FloatArray(4)
    private var lfoPh = 0.0

    // фильтр
    private var ic1 = 0f
    private var ic2 = 0f
    private var fA1 = 0f
    private var fA2 = 0f
    private var fA3 = 0f
    private var drv = 0f

    // коэффициенты на буфер
    private var ampC = 0.999f
    private var modC = 0.999f
    private var effFm = 1f
    private var lfoDest = 0
    private var lfoDepth = 0f
    private var lfoInc = 0.0
    private var panBase = 0f
    private var panGl = 0.707f
    private var panGr = 0.707f
    private var crLevels = 128f
    private var dTarget = 1000f
    private var dTone = 0.5f
    private var rvFb = 0.8f

    // эффекты дорожки
    private val dl = FloatArray(SR * 2)
    private var dw = 0
    private var dCur = -1f
    private var dLp = 0f
    private val fl = FloatArray(4096)
    private var flW = 0
    private var flPh = 0.0
    private var rmPh = 0.0
    private var crCnt = 0f
    private var crHold = 0f
    private val combs = arrayOf(Comb(1116), Comb(1188), Comb(1277), Comb(1356))
    private val aps = arrayOf(AllPass(556), AllPass(441))

    init { resetSound() }

    fun resetSound() {
        p[P.ALGO] = 0f
        p[P.R1] = 1f; p[P.R1 + 1] = 2f; p[P.R1 + 2] = 1f; p[P.R1 + 3] = 3f
        p[P.L1] = 0.9f; p[P.L1 + 1] = 0.5f; p[P.L1 + 2] = 0.4f; p[P.L1 + 3] = 0.3f
        p[P.FB] = 0f
        p[P.AMPDEC] = 0.4f; p[P.MODDEC] = 0.25f; p[P.FMAMT] = 1f
        p[P.LFORATE] = 2f; p[P.LFODEPTH] = 0.3f; p[P.LFODEST] = 0f
        p[P.CUT] = 1f; p[P.RES] = 0f; p[P.DRIVE] = 0f
        p[P.RM_FREQ] = 200f; p[P.RM_MIX] = 0f
        p[P.CR_BITS] = 8f; p[P.CR_RATE] = 4f; p[P.CR_MIX] = 0f
        p[P.FL_RATE] = 0.4f; p[P.FL_DEPTH] = 3f; p[P.FL_BASE] = 2f; p[P.FL_FB] = 0.3f; p[P.FL_MIX] = 0f
        p[P.DL_TIME] = 300f; p[P.DL_FB] = 0.4f; p[P.DL_TONE] = 0.6f; p[P.DL_MIX] = 0f
        p[P.RV_SIZE] = 0.7f; p[P.RV_DAMP] = 0.4f; p[P.RV_MIX] = 0f
        p[P.VOL] = 0.8f; p[P.PAN] = 0f
    }

    internal fun silence() { amp = 0f }

    fun start(note: Int, v: Float, gateSamples: Float, mask: Int) {
        freq = 440.0 * 2.0.pow((note - 69) / 12.0)
        amp = v
        mod = 1f
        atk = 0f
        rel = false
        gateLeft = gateSamples
        lockMask = mask
    }

    internal fun triggerStep(s: Int, sps: Double) {
        var mask = 0
        for (k in 0..3) {
            val v = locks[k][s]
            if (v != LOCK_OFF) {
                mask = mask or (1 shl k)
                lockVal[k] = v
            }
        }
        val g = gate[s]
        start(notes[s], vel[s], if (g >= 15.9f) 0f else (g * sps).toFloat(), mask)
    }

    private fun setPan(pn: Float) {
        val a = ((pn.coerceIn(-1f, 1f) + 1f) * (PI / 4)).toFloat()
        panGl = cos(a)
        panGr = sin(a)
    }

    internal fun prepare() {
        lfoDest = p[P.LFODEST].toInt()
        lfoDepth = p[P.LFODEPTH]
        lfoInc = p[P.LFORATE].toDouble() / SR
        val lv = if (lfoDest > 0 && lfoDepth > 0f) sin(TWO_PI * lfoPh).toFloat() else 0f
        effFm = if ((lockMask and 1) != 0) lockVal[0] else p[P.FMAMT]
        var cut = if ((lockMask and 2) != 0) lockVal[1] else p[P.CUT]
        if (lfoDest == 3) cut += lv * lfoDepth * 0.5f
        cut = cut.coerceIn(0f, 1f)
        val dec = (if ((lockMask and 4) != 0) lockVal[2] else p[P.AMPDEC]).coerceAtLeast(0.03f)
        panBase = if ((lockMask and 8) != 0) lockVal[3] else p[P.PAN]
        val fc = min(40.0 * 500.0.pow(cut.toDouble()), 0.45 * SR)
        val g = tan(PI * fc / SR).toFloat()
        val k = 1f / (0.7f + p[P.RES] * 9.3f)
        fA1 = 1f / (1f + g * (g + k))
        fA2 = g * fA1
        fA3 = g * fA2
        drv = p[P.DRIVE]
        ampC = exp(-1.0 / (SR * dec.toDouble())).toFloat()
        modC = exp(-1.0 / (SR * p[P.MODDEC].coerceAtLeast(0.03f).toDouble())).toFloat()
        crLevels = 2f.pow(p[P.CR_BITS] - 1f)
        dTarget = (p[P.DL_TIME] * SR / 1000f).coerceIn(1f, (dl.size - 2).toFloat())
        if (dCur < 0f) dCur = dTarget
        dTone = 0.05f + 0.95f * p[P.DL_TONE]
        rvFb = 0.7f + p[P.RV_SIZE] * 0.28f
        if (lfoDest != 5) setPan(panBase)
    }

    private fun voice(lv: Float): Float {
        val fmMul = if (lfoDest == 2) (1f + lv * lfoDepth).coerceAtLeast(0f) else 1f
        val m = mod * effFm * fmMul
        val l0 = p[P.L1]
        val l1 = p[P.L1 + 1]
        val l2 = p[P.L1 + 2]
        val l3 = p[P.L1 + 3]
        val o4 = sin(ph[3] + p[P.FB] * 2.5f * fbPrev).toFloat()
        fbPrev = o4
        val i3 = l3 * 6f * m
        val i2 = l2 * 6f * m
        val i1 = l1 * 6f * m
        val out: Float = when (p[P.ALGO].toInt()) {
            0 -> {
                val o3 = sin(ph[2] + o4 * i3).toFloat()
                val o2 = sin(ph[1] + o3 * i2).toFloat()
                val o1 = sin(ph[0] + o2 * i1).toFloat()
                o1 * l0
            }
            1 -> {
                val o3 = sin(ph[2] + o4 * i3).toFloat()
                val o2 = sin(ph[1]).toFloat()
                val o1 = sin(ph[0] + o2 * i1).toFloat()
                (o1 * l0 + o3 * l2) * 0.7f
            }
            2 -> {
                val a = o4 * i3
                val o1 = sin(ph[0] + a).toFloat()
                val o2 = sin(ph[1] + a).toFloat()
                val o3 = sin(ph[2] + a).toFloat()
                (o1 * l0 + o2 * l1 + o3 * l2) * 0.5f
            }
            else -> {
                val o1 = sin(ph[0]).toFloat()
                val o2 = sin(ph[1]).toFloat()
                val o3 = sin(ph[2]).toFloat()
                (o1 * l0 + o2 * l1 + o3 * l2 + o4 * l3) * 0.4f
            }
        }
        val mul = if (lfoDest == 1) exp((lv * lfoDepth * 0.6931f).toDouble()) else 1.0
        val w = TWO_PI * freq * mul / SR
        for (j in 0..3) {
            var x = ph[j] + w * p[P.R1 + j]
            if (x > TWO_PI) x %= TWO_PI
            ph[j] = x
        }
        return out
    }

    internal fun next() {
        var lv = 0f
        if (lfoDest > 0 && lfoDepth > 0f) {
            lfoPh += lfoInc
            if (lfoPh >= 1.0) lfoPh -= 1.0
            lv = sin(TWO_PI * lfoPh).toFloat()
        }
        var y = 0f
        if (amp > 0.0003f) {
            if (gateLeft > 0f) {
                gateLeft -= 1f
                if (gateLeft <= 0f) rel = true
            }
            if (atk < 1f) atk = min(1f, atk + 0.02f)
            var a = amp * atk
            if (lfoDest == 4) a *= 1f - lfoDepth * 0.5f * (1f + lv)
            var v = voice(lv) * a
            amp *= ampC
            if (rel) amp *= 0.998f
            mod *= modC
            // фильтр (SVF, резонанс)
            val v3 = v - ic2
            val v1 = fA1 * ic1 + fA2 * v3
            val v2 = ic2 + fA2 * ic1 + fA3 * v3
            ic1 = 2f * v1 - ic1
            ic2 = 2f * v2 - ic2
            v = v2
            if (drv > 0.01f) {
                val z = v * (1f + drv * 10f)
                v = z / (1f + abs(z))
            }
            y = v * 0.3f
        } else {
            ic1 = 0f
            ic2 = 0f
        }

        // кольцевой модулятор
        val rm = p[P.RM_MIX]
        if (rm > 0.001f) {
            rmPh += TWO_PI * p[P.RM_FREQ] / SR
            if (rmPh > TWO_PI) rmPh -= TWO_PI
            y = y * (1f - rm) + y * sin(rmPh).toFloat() * rm
        }

        // биткрашер
        val cm = p[P.CR_MIX]
        if (cm > 0.001f) {
            crCnt += 1f
            val cr = p[P.CR_RATE]
            if (crCnt >= cr) {
                crCnt -= cr
                crHold = round(y * crLevels) / crLevels
            }
            y = y * (1f - cm) + crHold * cm
        }

        // флэнджер / хорус
        val fm = p[P.FL_MIX]
        if (fm > 0.001f) {
            flPh += p[P.FL_RATE].toDouble() / SR
            if (flPh >= 1.0) flPh -= 1.0
            val lf = 0.5f + 0.5f * sin(TWO_PI * flPh).toFloat()
            val fo = readFrac(fl, flW, (p[P.FL_BASE] + p[P.FL_DEPTH] * lf) * SR / 1000f)
            fl[flW] = y + fo * p[P.FL_FB]
            flW = (flW + 1) % fl.size
            y += fo * fm
        } else {
            fl[flW] = 0f
            flW = (flW + 1) % fl.size
        }

        // дилей (от 1 мс)
        val dm = p[P.DL_MIX]
        if (dm > 0.001f) {
            dCur += (dTarget - dCur) * 0.002f
            val d = readFrac(dl, dw, dCur)
            dLp += dTone * (d - dLp)
            dl[dw] = y + softClip(dLp * p[P.DL_FB])
            dw = (dw + 1) % dl.size
            y += d * dm
        } else {
            dl[dw] = 0f
            dw = (dw + 1) % dl.size
        }

        // реверб
        val vm = p[P.RV_MIX]
        if (vm > 0.001f) {
            val rin = y * 0.05f
            var rs = 0f
            val damp = p[P.RV_DAMP]
            for (cb in combs) rs += cb.process(rin, rvFb, damp)
            for (ap in aps) rs = ap.process(rs)
            y += rs * vm * 3f
        }

        y *= p[P.VOL]
        if (lfoDest == 5) setPan(panBase + lv * lfoDepth)
        outL = y * panGl
        outR = y * panGr
    }
}

class FmEngine {
    val tracks = List(NTRACKS) { FmTrack() }

    @Volatile var bpm = 120f
    @Volatile var swing = 0f
    @Volatile var playing = false
    @Volatile var master = 0.8f

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
            p[P.ALGO] = 0f; p[P.FB] = 0.2f; p[P.AMPDEC] = 0.35f; p[P.MODDEC] = 0.12f; p[P.CUT] = 0.75f
            p[P.R1] = 1f; p[P.R1 + 1] = 1f; p[P.R1 + 2] = 2f; p[P.R1 + 3] = 1f
            p[P.L1] = 0.9f; p[P.L1 + 1] = 0.5f; p[P.L1 + 2] = 0.3f; p[P.L1 + 3] = 0.2f
            p[P.RV_MIX] = 0.1f
        }
        listOf(2 to 60, 6 to 63, 10 to 67, 14 to 65)
            .forEach { (s, n) -> tracks[1].notes[s] = n }
        tracks[1].apply {
            p[P.ALGO] = 1f; p[P.AMPDEC] = 0.5f; p[P.MODDEC] = 0.3f; p[P.CUT] = 0.9f
            p[P.R1] = 1f; p[P.R1 + 1] = 2f; p[P.R1 + 2] = 1f; p[P.R1 + 3] = 3f
            p[P.L1] = 0.8f; p[P.L1 + 1] = 0.5f; p[P.L1 + 2] = 0.6f; p[P.L1 + 3] = 0.35f
            p[P.PAN] = -0.3f
            p[P.DL_TIME] = 280f; p[P.DL_FB] = 0.45f; p[P.DL_MIX] = 0.25f
            p[P.RV_MIX] = 0.25f
        }
        for (s in 0 until STEPS step 2) tracks[2].notes[s] = 90
        tracks[2].apply {
            p[P.ALGO] = 0f; p[P.FB] = 1f; p[P.AMPDEC] = 0.06f; p[P.MODDEC] = 0.05f
            p[P.R1] = 1f; p[P.R1 + 1] = 2.76f; p[P.R1 + 2] = 5.4f; p[P.R1 + 3] = 8.93f
            p[P.L1] = 0.7f; p[P.L1 + 1] = 0.9f; p[P.L1 + 2] = 0.9f; p[P.L1 + 3] = 0.9f
            p[P.VOL] = 0.5f; p[P.PAN] = 0.4f
            p[P.DL_TIME] = 6f; p[P.DL_FB] = 0.85f; p[P.DL_TONE] = 0.8f; p[P.DL_MIX] = 0.4f
        }
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
                SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT
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
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, frames * 8))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            at.play()

            val buf = ShortArray(frames * 2)
            val audible = BooleanArray(NTRACKS)
            var counter = 0.0
            var tick = 0
            var was = false

            while (running) {
                val sps = SR * 60.0 / bpm / 4.0
                val sw = swing.coerceIn(0f, 0.5f).toDouble()
                val isPlaying = playing
                if (isPlaying && !was) {
                    for (t in tracks) t.pos = -1
                    counter = 0.0
                    tick = 0
                }
                if (!isPlaying && was) for (t in tracks) t.pos = -1
                was = isPlaying

                var anySolo = false
                for (k in 0 until NTRACKS) if (tracks[k].solo) anySolo = true

                for (k in 0 until NTRACKS) {
                    val t = tracks[k]
                    audible[k] = !t.mute && (!anySolo || t.solo)
                    val pn = t.pending
                    if (pn >= 0) {
                        t.pending = -1
                        if (audible[k]) t.start(pn, 1f, 0f, 0)
                    }
                    if (audible[k]) t.prepare() else t.silence()
                }
                val mst = master

                for (i in 0 until frames) {
                    if (isPlaying) {
                        counter -= 1.0
                        if (counter <= 0.0) {
                            counter += if ((tick and 1) == 0) sps * (1.0 + sw) else sps * (1.0 - sw)
                            tick++
                            for (k in 0 until NTRACKS) {
                                val t = tracks[k]
                                val plen = t.len.coerceIn(1, STEPS)
                                val s = (t.pos + 1) % plen
                                t.pos = s
                                if (audible[k] && t.notes[s] >= 0) {
                                    val pr = t.prob[s]
                                    if (pr >= 0.999f || Random.nextFloat() < pr) {
                                        t.triggerStep(s, sps)
                                        t.prepare()
                                    }
                                }
                            }
                        }
                    }

                    var l = 0f
                    var r = 0f
                    for (k in 0 until NTRACKS) {
                        if (audible[k]) {
                            val t = tracks[k]
                            t.next()
                            l += t.outL
                            r += t.outR
                        }
                    }
                    buf[2 * i] = (limit(l * mst).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                    buf[2 * i + 1] = (limit(r * mst).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                }
                at.write(buf, 0, frames * 2)

                if (recording) {
                    val bb = ByteArray(frames * 4)
                    for (j in 0 until frames * 2) {
                        val v = buf[j].toInt()
                        bb[2 * j] = v.toByte()
                        bb[2 * j + 1] = (v shr 8).toByte()
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
