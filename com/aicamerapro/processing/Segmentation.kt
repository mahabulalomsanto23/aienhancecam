package com.aicamerapro.processing

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** On-device ML Kit Selfie Segmentation (মডেল APK-এর ভেতরে; ইন্টারনেট লাগে না)। ব্যাকগ্রাউন্ড থ্রেড থেকে ডাকতে হবে। */
object SubjectSegmenter {
    private val client by lazy {
        val options = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .enableRawSizeMask()
            .build()
        Segmentation.getClient(options)
    }

    fun segment(src: Bitmap): Mask? {
        return try {
            val scale = min(1f, 512f / max(src.width, src.height))
            val small = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    src,
                    max(1, (src.width * scale).roundToInt()),
                    max(1, (src.height * scale).roundToInt()),
                    true
                )
            } else src
            val res = Tasks.await(client.process(InputImage.fromBitmap(small, 0)))
            val w = res.width
            val h = res.height
            val buf = res.buffer
            buf.rewind()
            val arr = FloatArray(w * h) { buf.getFloat() }
            if (small !== src) small.recycle()
            Mask(w, h, arr)
        } catch (e: Exception) {
            null
        }
    }
}
