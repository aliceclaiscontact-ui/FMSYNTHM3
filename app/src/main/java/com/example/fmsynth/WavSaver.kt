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
    private const val CH = 2
    private const val SRATE = 44100
    private const val BITS = 16

    private fun header(dataLen: Long): ByteArray {
        val bb = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray())
        bb.putInt((36 + dataLen).toInt())
        bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray())
        bb.putInt(16)
        bb.putShort(1)
        bb.putShort(CH.toShort())
        bb.putInt(SRATE)
        bb.putInt(SRATE * CH * BITS / 8)
        bb.putShort((CH * BITS / 8).toShort())
        bb.putShort(BITS.toShort())
        bb.put("data".toByteArray())
        bb.putInt(dataLen.toInt())
        return bb.array()
    }

    private fun write(out: OutputStream, pcm: File) {
        val len = pcm.length() - pcm.length() % 4
        out.write(header(len))
        pcm.inputStream().use { it.copyTo(out) }
    }

    fun save(ctx: Context, pcm: File, name: String): String? {
        return try {
            if (pcm.length() < 4L) return null
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
