package com.aicamerapro

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

object ImageSaver {
    private const val FOLDER = "Pictures/AI Camera Pro"
    private val seq = AtomicInteger(0)

    /** প্রতিটি ক্যাপচারের জন্য অনন্য নাম; আসল ফাইল কখনো overwrite হয় না। */
    fun baseName(): String =
        "AICAM_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "_" + seq.incrementAndGet()

    fun save(ctx: Context, bytes: ByteArray, name: String): Uri? {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, FOLDER)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            val stream = resolver.openOutputStream(uri) ?: throw IOException("no stream")
            stream.use { it.write(bytes) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    /** (ফাইলের সংখ্যা, মোট বাইট) */
    fun storageInfo(ctx: Context): Pair<Int, Long> {
        var count = 0
        var total = 0L
        val proj = arrayOf(MediaStore.Images.Media.SIZE)
        val sel = MediaStore.Images.Media.RELATIVE_PATH + " LIKE ?"
        val args = arrayOf("$FOLDER%")
        ctx.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, sel, args, null)?.use { c ->
            while (c.moveToNext()) {
                count++
                total += c.getLong(0)
            }
        }
        return Pair(count, total)
    }
}
