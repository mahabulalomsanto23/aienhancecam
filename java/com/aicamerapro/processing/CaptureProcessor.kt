package com.aicamerapro.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.aicamerapro.CameraMode
import com.aicamerapro.ImageSaver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

class CaptureJob(
    val bytes: ByteArray,
    val mode: CameraMode,
    val blur: BlurStrength,
    val saveBoth: Boolean,
    val maxSide: Int,
    val strength: Float
)

class CaptureResult(val uri: Uri?, val thumb: Bitmap?, val message: String)

private class Outcome(val bytes: ByteArray?, val failNote: String)

/** ব্যাকগ্রাউন্ডে একে একে প্রসেস করে (মেমরি বাঁচাতে একসঙ্গে একটি)। UI কখনো আটকায় না। */
object CaptureProcessor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val main = Handler(Looper.getMainLooper())

    fun submit(ctx: Context, job: CaptureJob, done: (CaptureResult) -> Unit) {
        val app = ctx.applicationContext
        scope.launch {
            val res = mutex.withLock {
                try {
                    process(app, job)
                } catch (t: Throwable) {
                    val uri = ImageSaver.save(app, job.bytes, ImageSaver.baseName() + "_original.jpg")
                    CaptureResult(uri, null, "প্রসেসিং ব্যর্থ, আসল ছবি রাখা হয়েছে")
                }
            }
            main.post { done(res) }
        }
    }

    private fun process(ctx: Context, job: CaptureJob): CaptureResult {
        val base = ImageSaver.baseName()

        if (job.mode == CameraMode.NORMAL) {
            val uri = ImageSaver.save(ctx, job.bytes, base + "_normal.jpg")
            return CaptureResult(uri, decode(job.bytes, 0, 8), if (uri != null) "ছবি সংরক্ষিত হয়েছে" else "সংরক্ষণে সমস্যা হয়েছে")
        }

        var originalUri: Uri? = null
        if (job.saveBoth) originalUri = ImageSaver.save(ctx, job.bytes, base + "_original.jpg")

        val outcome = try {
            enhance(job)
        } catch (t: Throwable) {
            Outcome(null, "প্রসেসিং সম্পন্ন হয়নি, আসল ছবি রাখা হয়েছে")
        }

        val enhanced = outcome.bytes
        if (enhanced == null) {
            val uri = originalUri ?: ImageSaver.save(ctx, job.bytes, base + "_original.jpg")
            return CaptureResult(uri, decode(job.bytes, 0, 8), outcome.failNote)
        }
        val uri = ImageSaver.save(ctx, enhanced, base + "_" + job.mode.tag + ".jpg")
        return CaptureResult(
            uri, decode(enhanced, 0, 8),
            if (uri != null) job.mode.label + " ছবি সংরক্ষিত হয়েছে" else "সংরক্ষণে সমস্যা হয়েছে"
        )
    }

    private fun enhance(job: CaptureJob): Outcome {
        val bmp = decode(job.bytes, job.maxSide, 1) ?: return Outcome(null, "ছবি পড়া যায়নি, আসল ছবি রাখা হয়েছে")
        try {
            val result: Bitmap? = when (job.mode) {
                CameraMode.AI -> enhanceAi(bmp, job.strength)
                CameraMode.VIVID -> enhanceVivid(bmp, job.strength)
                CameraMode.PORTRAIT -> PortraitProcessor.process(bmp, job.blur)
                CameraMode.NORMAL -> null
            }
            if (result == null) {
                val note = if (job.mode == CameraMode.PORTRAIT) "বিষয় শনাক্ত হয়নি, ব্লার ছাড়াই আসল ছবি রাখা হয়েছে"
                else "ছবির মান ভালো থাকায়/যাচাই ব্যর্থ হওয়ায় আসল ছবি রাখা হয়েছে"
                return Outcome(null, note)
            }
            val out = ByteArrayOutputStream()
            result.compress(Bitmap.CompressFormat.JPEG, 95, out)
            result.recycle()
            return Outcome(out.toByteArray(), "")
        } finally {
            bmp.recycle()
        }
    }

    private fun enhanceAi(bmp: Bitmap, strength: Float): Bitmap? {
        val a = Analyzer.analyze(bmp)
        // মানুষের ছবি মনে হলে অন-ডিভাইস মডেল দিয়ে বিষয় আলাদা করে subject-aware সংশোধন
        val subject = if (a.scene == Scene.PORTRAIT) SubjectSegmenter.segment(bmp) else null
        for (s in floatArrayOf(strength, strength * 0.5f)) {
            val out = Pipeline.run(bmp, ParamsFactory.forAi(a, s), subject)
            if (Validator.ok(a, Analyzer.analyze(out), 1.45f)) return out
            out.recycle()
        }
        return null
    }

    private fun enhanceVivid(bmp: Bitmap, strength: Float): Bitmap? {
        val a = Analyzer.analyze(bmp)
        for (s in floatArrayOf(strength, strength * 0.5f)) {
            val out = Pipeline.run(bmp, ParamsFactory.forVivid(a, s), null)
            if (Validator.ok(a, Analyzer.analyze(out), 1.7f)) return out
            out.recycle()
        }
        return null
    }

    /** JPEG ডিকোড করে EXIF অনুযায়ী সোজা করে; maxSide>0 হলে ছোট করে; sample>1 হলে থাম্বনেইলের জন্য। */
    private fun decode(bytes: ByteArray, maxSide: Int, sample: Int): Bitmap? {
        val opts = BitmapFactory.Options()
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888
        if (sample > 1) opts.inSampleSize = sample
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val orientation = try {
            ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val deg = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (deg != 0f) {
            val m = Matrix()
            m.postRotate(deg)
            val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (r !== bmp) bmp.recycle()
            bmp = r
        }
        val long = max(bmp.width, bmp.height)
        if (maxSide > 0 && long > maxSide) {
            val s = maxSide.toFloat() / long
            val r = Bitmap.createScaledBitmap(
                bmp, max(1, (bmp.width * s).roundToInt()), max(1, (bmp.height * s).roundToInt()), true
            )
            if (r !== bmp) bmp.recycle()
            bmp = r
        }
        return bmp
    }
}
