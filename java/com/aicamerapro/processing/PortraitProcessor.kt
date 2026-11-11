package com.aicamerapro.processing

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class BlurStrength(val label: String, val sigmaFrac: Float) {
    NATURAL("Natural", 0.004f),
    MEDIUM("Medium", 0.008f),
    STRONG("Strong", 0.013f),
    MAXIMUM("Maximum", 0.020f)
}

/**
 * Computational background blur (প্রকৃত অপটিক্যাল depth of field নয়)।
 * বিষয়ের রঙ যাতে পটভূমিতে না ছড়ায় (halo) তাই শুধু পটভূমির পিক্সেল ওজন করে blur করা হয়।
 * সাবজেক্ট শনাক্ত না হলে null ফেরত দেয় (আসল ছবি অপরিবর্তিত থাকে)।
 */
object PortraitProcessor {

    fun process(src: Bitmap, strength: BlurStrength): Bitmap? {
        val raw = SubjectSegmenter.segment(src) ?: return null
        val cov = raw.coverage()
        if (cov < 0.02f || cov > 0.92f) return null
        val mask = raw.refined()

        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        val q = 4
        val qw = (w + q - 1) / q
        val qh = (h + q - 1) / q
        val cr = FloatArray(qw * qh)
        val cg = FloatArray(qw * qh)
        val cb = FloatArray(qw * qh)
        val cwt = FloatArray(qw * qh)
        val offs = intArrayOf(1, 3)

        Par.rows(qh) { qy ->
            for (qx in 0 until qw) {
                var r = 0f
                var g = 0f
                var b = 0f
                var wt = 0f
                for (oy in offs) for (ox in offs) {
                    val x = min(qx * q + ox, w - 1)
                    val y = min(qy * q + oy, h - 1)
                    val c = px[y * w + x]
                    val bgw = 1f - mask.sample((x + 0.5f) / w, (y + 0.5f) / h)
                    r += ((c shr 16) and 255) * bgw
                    g += ((c shr 8) and 255) * bgw
                    b += (c and 255) * bgw
                    wt += bgw
                }
                val i = qy * qw + qx
                cr[i] = r / 4f
                cg[i] = g / 4f
                cb[i] = b / 4f
                cwt[i] = wt / 4f
            }
        }

        val sigmaFull = strength.sigmaFrac * max(w, h)
        val rq = max(1, (sigmaFull / q).roundToInt())
        Blur.box(cr, qw, qh, rq, 3)
        Blur.box(cg, qw, qh, rq, 3)
        Blur.box(cb, qw, qh, rq, 3)
        Blur.box(cwt, qw, qh, rq, 3)

        val out = IntArray(w * h)
        Par.rows(h) { y ->
            val fy = (y + 0.5f) / q - 0.5f
            for (x in 0 until w) {
                val i = y * w + x
                val c = px[i]
                val m = mask.sample((x + 0.5f) / w, (y + 0.5f) / h)
                if (m >= 0.999f) {
                    out[i] = c
                    continue
                }
                val fx = (x + 0.5f) / q - 0.5f
                val wv = bilinearAt(cwt, qw, qh, fx, fy)
                val r = ((c shr 16) and 255).toFloat()
                val g = ((c shr 8) and 255).toFloat()
                val b = (c and 255).toFloat()
                var br = r
                var bg = g
                var bb = b
                if (wv > 0.02f) {
                    br = bilinearAt(cr, qw, qh, fx, fy) / wv
                    bg = bilinearAt(cg, qw, qh, fx, fy) / wv
                    bb = bilinearAt(cb, qw, qh, fx, fy) / wv
                }
                val inv = 1f - m
                out[i] = packRgb(m * r + inv * br, m * g + inv * bg, m * b + inv * bb)
            }
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(out, 0, w, 0, 0, w, h)
        return bmp
    }
}
