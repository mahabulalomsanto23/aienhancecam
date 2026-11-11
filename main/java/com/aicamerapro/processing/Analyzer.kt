package com.aicamerapro.processing

import android.graphics.Bitmap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class Scene { PORTRAIT, LANDSCAPE, NIGHT, INDOOR, GENERAL }

data class Analysis(
    val meanL: Float, val p1: Float, val p10: Float, val p50: Float, val p90: Float, val p99: Float,
    val highClip: Float, val lowCrush: Float,
    val meanR: Float, val meanG: Float, val meanB: Float,
    val meanSat: Float, val skinFrac: Float, val greenFrac: Float, val skyFrac: Float,
    val noise: Float, val edgeEnergy: Float, val scene: Scene
)

object Analyzer {

    fun isSkin(r: Float, g: Float, b: Float): Boolean {
        val y = 0.299f * r + 0.587f * g + 0.114f * b
        val cb = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b
        val cr = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b
        return y > 40f && cb >= 77f && cb <= 127f && cr >= 133f && cr <= 173f
    }

    fun analyze(src: Bitmap): Analysis {
        val long = max(src.width, src.height)
        val scale = min(1f, 400f / long)
        val sw = max(1, (src.width * scale).roundToInt())
        val sh = max(1, (src.height * scale).roundToInt())
        val small = if (scale < 1f) Bitmap.createScaledBitmap(src, sw, sh, true) else src
        val n = sw * sh
        val px = IntArray(n)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        if (small !== src) small.recycle()

        val hist = IntArray(256)
        var sumL = 0.0
        var sumSat = 0.0
        var skin = 0
        var green = 0
        var sky = 0
        var hiClip = 0
        var lowCr = 0
        var nr = 0.0
        var ng = 0.0
        var nb = 0.0
        var nn = 0
        var ar = 0.0
        var ag = 0.0
        var ab = 0.0

        for (c in px) {
            val r = (c shr 16) and 255
            val g = (c shr 8) and 255
            val b = c and 255
            val l = (77 * r + 150 * g + 29 * b) shr 8
            hist[l]++
            sumL += l
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            val sat = if (mx > 0) (mx - mn).toFloat() / mx else 0f
            sumSat += sat
            ar += r; ag += g; ab += b
            if (l > 245) hiClip++
            if (l < 30) lowCr++
            if (isSkin(r.toFloat(), g.toFloat(), b.toFloat())) skin++
            if (g > r * 1.05f && g > b * 1.05f && sat > 0.15f) green++
            if (b > r * 1.1f && b >= g && l > 90) sky++
            if (l in 25..230 && sat < 0.6f) {
                nr += r; ng += g; nb += b; nn++
            }
        }

        fun pct(q: Float): Float {
            val target = (n * q).toInt()
            var acc = 0
            for (i in 0..255) {
                acc += hist[i]
                if (acc > target) return i / 255f
            }
            return 1f
        }

        val useNeutral = nn > n / 20
        val mr = ((if (useNeutral) nr / nn else ar / n) / 255.0).toFloat()
        val mg = ((if (useNeutral) ng / nn else ag / n) / 255.0).toFloat()
        val mb = ((if (useNeutral) nb / nn else ab / n) / 255.0).toFloat()

        // শব্দ (Immerkaer estimator) ও ধারের শক্তি — ছবির মাঝের অংশ থেকে, পূর্ণ রেজোলিউশনে
        val cw = min(512, src.width)
        val ch = min(512, src.height)
        val cx = (src.width - cw) / 2
        val cy = (src.height - ch) / 2
        val cp = IntArray(cw * ch)
        src.getPixels(cp, 0, cw, cx, cy, cw, ch)
        val lum = IntArray(cw * ch) {
            val c = cp[it]
            (((c shr 16) and 255) * 77 + ((c shr 8) and 255) * 150 + (c and 255) * 29) shr 8
        }
        var sumN = 0.0
        var sumE = 0.0
        var cnt = 0
        for (y in 1 until ch - 1) {
            for (x in 1 until cw - 1) {
                val i = y * cw + x
                val c0 = lum[i]
                val lapN = lum[i - cw - 1] - 2 * lum[i - cw] + lum[i - cw + 1] -
                    2 * lum[i - 1] + 4 * c0 - 2 * lum[i + 1] +
                    lum[i + cw - 1] - 2 * lum[i + cw] + lum[i + cw + 1]
                sumN += abs(lapN)
                val lap = lum[i - 1] + lum[i + 1] + lum[i - cw] + lum[i + cw] - 4 * c0
                sumE += abs(lap)
                cnt++
            }
        }
        val noise = if (cnt > 0) (sqrt(PI / 2.0) / 6.0 * sumN / cnt).toFloat() else 0f
        val edge = if (cnt > 0) (sumE / cnt).toFloat() else 0f

        val meanL = (sumL / n / 255.0).toFloat()
        val meanSat = (sumSat / n).toFloat()
        val skinFrac = skin.toFloat() / n
        val greenFrac = green.toFloat() / n
        val skyFrac = sky.toFloat() / n
        val warm = if (mb > 0.01f) mr / mb else 1f

        val scene = when {
            meanL < 0.20f -> Scene.NIGHT
            skinFrac > 0.07f -> Scene.PORTRAIT
            greenFrac + skyFrac > 0.35f -> Scene.LANDSCAPE
            warm > 1.25f && meanSat < 0.35f -> Scene.INDOOR
            else -> Scene.GENERAL
        }

        return Analysis(
            meanL, pct(0.01f), pct(0.10f), pct(0.50f), pct(0.90f), pct(0.99f),
            hiClip.toFloat() / n, lowCr.toFloat() / n,
            mr, mg, mb, meanSat, skinFrac, greenFrac, skyFrac, noise, edge, scene
        )
    }
}
