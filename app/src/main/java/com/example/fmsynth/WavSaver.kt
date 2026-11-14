package com.example.fmsynth

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavSaver {
    private fun header(dataLen: Long, sr: Int, ch: Int, bits: Int): ByteArray {
        val bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray())
        bb.putInt((36 + dataLen).toInt())
        bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray())
        bb.putInt(16)
        bb.putShort(1)
        bb.putShort(ch.toShort())
        bb.putInt(sr)
        bb.putInt(sr * ch * bits / 8)
        bb.putShort((ch * bits / 8).toShort())
        bb.putShort(bits.toShort())
        bb.put("data".toByteArray())
        bb.putInt(dataLen.toInt())
        return bb.array()
    }

    private fun write(out: OutputStream, pcm: File) {
        out.write(header(pcm.length(), 44100, 1, 16))
        pcm.inputStream().use { it.copyTo(out) }
    }

    fun save(ctx: Context, pcm: File, name: String): String? {
        return try {
            if (pcm.length() < 2L) return null
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/FMSynth")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val uri = ctx.contentResolver.insert(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cv
                ) ?: return null
                ctx.contentResolver.openOutputStream(uri)?.use { write(it, pcm) } ?: return null
                val done = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
                ctx.contentResolver.update(uri, done, null, null)
                "Music/FMSynth/$name"
            } else {
                val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: ctx.filesDir
                dir.mkdirs()
                val f = File(dir, name)
                f.outputStream().use { write(it, pcm) }
                f.absolutePath
            }
        } catch (e: Exception) {
            null
        }
    }
}
