package com.example.fmsynth

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Store {
    private fun arr(a: FloatArray): JSONArray {
        val j = JSONArray()
        for (v in a) j.put(v.toDouble())
        return j
    }

    private fun arr(a: IntArray): JSONArray {
        val j = JSONArray()
        for (v in a) j.put(v)
        return j
    }

    private fun readF(j: JSONArray?, a: FloatArray) {
        if (j == null) return
        for (i in 0 until minOf(j.length(), a.size)) a[i] = j.optDouble(i, a[i].toDouble()).toFloat()
    }

    private fun readI(j: JSONArray?, a: IntArray) {
        if (j == null) return
        for (i in 0 until minOf(j.length(), a.size)) a[i] = j.optInt(i, a[i])
    }

    fun trackToJson(t: FmTrack, withPattern: Boolean): JSONObject {
        val o = JSONObject()
        o.put("p", arr(t.p))
        if (withPattern) {
            o.put("len", t.len)
            o.put("notes", arr(t.notes))
            o.put("vel", arr(t.vel))
            o.put("gate", arr(t.gate))
            o.put("prob", arr(t.prob))
            val lk = JSONArray()
            for (k in 0 until 4) lk.put(arr(t.locks[k]))
            o.put("locks", lk)
        }
        return o
    }

    fun trackFromJson(t: FmTrack, o: JSONObject, withPattern: Boolean) {
        readF(o.optJSONArray("p"), t.p)
        if (withPattern) {
            t.len = o.optInt("len", 16).coerceIn(1, STEPS)
            readI(o.optJSONArray("notes"), t.notes)
            readF(o.optJSONArray("vel"), t.vel)
            readF(o.optJSONArray("gate"), t.gate)
            readF(o.optJSONArray("prob"), t.prob)
            val lk = o.optJSONArray("locks")
            if (lk != null) {
                for (k in 0 until minOf(4, lk.length())) readF(lk.optJSONArray(k), t.locks[k])
            }
        }
    }

    fun engineToJson(e: FmEngine): JSONObject {
        val o = JSONObject()
        o.put("bpm", e.bpm.toDouble())
        o.put("swing", e.swing.toDouble())
        o.put("master", e.master.toDouble())
        val a = JSONArray()
        for (t in e.tracks) a.put(trackToJson(t, true))
        o.put("tracks", a)
        return o
    }

    fun engineFromJson(e: FmEngine, o: JSONObject) {
        e.bpm = o.optDouble("bpm", e.bpm.toDouble()).toFloat()
        e.swing = o.optDouble("swing", e.swing.toDouble()).toFloat()
        e.master = o.optDouble("master", e.master.toDouble()).toFloat()
        val a = o.optJSONArray("tracks") ?: return
        for (i in 0 until minOf(a.length(), e.tracks.size)) {
            val to = a.optJSONObject(i)
            if (to != null) trackFromJson(e.tracks[i], to, true)
        }
    }

    private fun file(ctx: Context, name: String) = File(ctx.filesDir, "$name.json")

    fun write(ctx: Context, name: String, o: JSONObject): Boolean =
        try {
            file(ctx, name).writeText(o.toString())
            true
        } catch (e: Exception) {
            false
        }

    fun read(ctx: Context, name: String): JSONObject? =
        try {
            val f = file(ctx, name)
            if (f.exists()) JSONObject(f.readText()) else null
        } catch (e: Exception) {
            null
        }

    fun stamp(ctx: Context, name: String, tag: Int = 0): String? {
        val f = file(ctx, name)
        return if (f.exists())
            SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(f.lastModified()))
        else null
    }
}
