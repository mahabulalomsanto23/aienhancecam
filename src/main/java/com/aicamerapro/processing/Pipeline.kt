package com.aicamerapro.processing

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Denoise → White balance/Tone/Color → Sharpen। শুধু রঙ, টোন ও ডিটেইল বদলায়; মুখের গঠন/জ্যামিতি কখনো নয়। */
object Pipeline {

    fun run(src: Bitmap, p: Params, subject: Mask?): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        if (p.denoise > 0.03f) lumaDenoise(px, w, h, p)
        if (p.chromaDenoise > 0.15f) chromaDenoise(px, w, h, p)
        tone(px, w, h, p, subject)
        if (p.sharpen > 0.03f) sharpen(px, w, h, p)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    // ---------------- Luma denoise (edge-preserving bilateral) ----------------
    private fun lumaDenoise(px: IntArray, w: Int, h: Int, p: Params) {
        val luma = ByteArray(w * h)
        Par.rows(h) { y ->
            for (x in 0 until w) {
                val c = px[y * w + x]
                luma[y * w + x] = ((((c shr 16) and 255) * 77 + ((c shr 8) and 255) * 150 + (c and 255) * 29) shr 8).toByte()
            }
        }
        val sr = 8f + 16f * p.denoise
        val range = FloatArray(256) { exp(-(it * it) / (2f * sr * sr)) }
        val sp = FloatArray(25)
        for (dy in -2..2) for (dx in -2..2) {
            sp[(dy + 2) * 5 + (dx + 2)] = exp(-(dx * dx + dy * dy) / (2f * 1.5f * 1.5f))
        }
        val strength = min(1f, p.denoise * 1.6f)
        val guard = p.skinGuard
        Par.rows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val lc = luma[i].toInt() and 255
                var sw = 0f
                var sl = 0f
                for (dy in -2..2) {
                    val yy = min(max(y + dy, 0), h - 1)
                    for (dx in -2..2) {
                        val xx = min(max(x + dx, 0), w - 1)
                        val ln = luma[yy * w + xx].toInt() and 255
                        val wt = sp[(dy + 2) * 5 + dx + 2] * range[abs(ln - lc)]
                        sw += wt
                        sl += wt * ln
                    }
                }
                val lf = sl / sw
                val c = px[i]
                val r = ((c shr 16) and 255).toFloat()
                val g = ((c shr 8) and 255).toFloat()
                val b = (c and 255).toFloat()
                var k = strength
                if (Analyzer.isSkin(r, g, b)) k *= (1f - guard)
                val d = (lf - lc) * k
                px[i] = packRgb(r + d, g + d, b + d)
            }
        }
    }

    // ---------------- Chroma denoise (অর্ধ রেজোলিউশনে) ----------------
    private fun chromaDenoise(px: IntArray, w: Int, h: Int, p: Params) {
        val hw = (w + 1) / 2
        val hh = (h + 1) / 2
        val cr = FloatArray(hw * hh)
        val cb = FloatArray(hw * hh)
        Par.rows(hh) { qy ->
            for (qx in 0 until hw) {
                var sr = 0f
                var sb = 0f
                var n = 0
                for (dy in 0..1) for (dx in 0..1) {
                    val x = qx * 2 + dx
                    val y = qy * 2 + dy
                    if (x < w && y < h) {
                        val c = px[y * w + x]
                        val r = ((c shr 16) and 255).toFloat()
                        val g = ((c shr 8) and 255).toFloat()
                        val b = (c and 255).toFloat()
                        val l = 0.299f * r + 0.587f * g + 0.114f * b
                        sr += r - l
                        sb += b - l
                        n++
                    }
                }
                cr[qy * hw + qx] = sr / n
                cb[qy * hw + qx] = sb / n
            }
        }
        val rad = if (p.chromaDenoise > 0.6f) 3 else 2
        Blur.box(cr, hw, hh, rad, 2)
        Blur.box(cb, hw, hh, rad, 2)
        val k = p.chromaDenoise
        Par.rows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val c = px[i]
                val r = ((c shr 16) and 255).toFloat()
                val g = ((c shr 8) and 255).toFloat()
                val b = (c and 255).toFloat()
                val l = 0.299f * r + 0.587f * g + 0.114f * b
                val q = (y / 2) * hw + (x / 2)
                val r0 = r - l
                val b0 = b - l
                val nr = l + r0 + (cr[q] - r0) * k
                val nb = l + b0 + (cb[q] - b0) * k
                val ng = (l - 0.299f * nr - 0.114f * nb) / 0.587f
                px[i] = packRgb(nr, ng, nb)
            }
        }
    }

    // ---------------- White balance + exposure + local tone + vibrance ----------------
    private fun tone(px: IntArray, w: Int, h: Int, p: Params, subject: Mask?) {
        val bw = max(0.05f, p.whitePoint - p.blackPoint)
        val knee = 0.78f
        val kk = 1f - knee
        val hc = (p.highlightComp * 0.8f).coerceIn(0f, 0.6f)
        val mix = (p.contrast * 2.5f).coerceIn(0f, 0.5f)
        val lut = FloatArray(1024)
        for (i in 0 until 1024) {
            var v = ((i / 1023f - p.blackPoint) / bw).coerceIn(0f, 1f)
            v = v.pow(p.gamma)
            v += p.shadowLift * 1.6f * v * (1f - v) * (1f - v)
            val s = v * v * (3f - 2f * v)
            v += mix * (s - v)
            if (v > knee) {
                val d = v - knee
                v = knee + d * (1f - hc * d / kk) / (1f - hc)
            }
            lut[i] = v.coerceIn(0f, 1f)
        }

        // স্থানীয় আলোর মানচিত্র (16px ব্লক)
        val bs = 16
        val gw = (w + bs - 1) / bs
        val gh = (h + bs - 1) / bs
        val grid = FloatArray(gw * gh)
        Par.rows(gh) { gy ->
            for (gx in 0 until gw) {
                var s = 0f
                var n = 0
                var yy = gy * bs
                while (yy < min(h, gy * bs + bs)) {
                    var xx = gx * bs
                    while (xx < min(w, gx * bs + bs)) {
                        val c = px[yy * w + xx]
                        s += (77 * ((c shr 16) and 255) + 150 * ((c shr 8) and 255) + 29 * (c and 255))
                        n++
                        xx += 4
                    }
                    yy += 4
                }
                grid[gy * gw + gx] = if (n > 0) s / (n * 256f * 255f) else 0.5f
            }
        }
        Blur.box(grid, gw, gh, 1, 2)

        // Subject-aware brightness: বিষয় পটভূমির চেয়ে অন্ধকার হলে সামান্য উজ্জ্বল করা
        var subjLift = 0f
        if (subject != null) {
            var sS = 0.0
            var sN = 0
            var bS = 0.0
            var bN = 0
            var yy = 0
            while (yy < h) {
                var xx = 0
                while (xx < w) {
                    val m = subject.sample((xx + 0.5f) / w, (yy + 0.5f) / h)
                    val c = px[yy * w + xx]
                    val l = (77 * ((c shr 16) and 255) + 150 * ((c shr 8) and 255) + 29 * (c and 255)) / (256f * 255f)
                    if (m > 0.6f) { sS += l; sN++ } else if (m < 0.2f) { bS += l; bN++ }
                    xx += 16
                }
                yy += 16
            }
            if (sN > 30 && bN > 30) {
                val sm = (sS / sN).toFloat()
                val bm = (bS / bN).toFloat()
                if (sm < bm - 0.10f && sm < 0.5f) {
                    subjLift = ((bm - sm) * 0.5f).coerceIn(0f, 0.3f) * p.subjectLiftScale
                }
            }
        }

        val gr = p.gainR
        val gg = p.gainG
        val gb = p.gainB
        val locAmt = p.localExposure
        val sl = subjLift
        val vib = p.vibrance
        val sprot = p.skinProtect

        Par.rows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val col = px[i]
                var r = min(255f, ((col shr 16) and 255) * gr)
                var g = min(255f, ((col shr 8) and 255) * gg)
                var b = min(255f, (col and 255) * gb)
                val l = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                var lp = lut[min(1023, (l * 1023f).toInt())]

                var gain = 1f
                if (locAmt > 0.001f) {
                    val ll = bilinearAt(grid, gw, gh, (x + 0.5f) / bs - 0.5f, (y + 0.5f) / bs - 0.5f)
                    gain = (1f + locAmt * (0.45f - ll)).coerceIn(0.75f, 1.3f)
                }
                if (subject != null && sl > 0.001f) {
                    gain *= 1f + sl * subject.sample((x + 0.5f) / w, (y + 0.5f) / h)
                }
                lp = min(1f, lp * gain)

                var ratio = if (l > 0.003f) lp / l else 1f
                ratio = ratio.coerceIn(0f, 3.5f)
                val mx = max(r, max(g, b))
                if (mx > 0f && mx * ratio > 255f) ratio = 255f / mx
                r *= ratio
                g *= ratio
                b *= ratio

                if (vib > 0.001f) {
                    val mx2 = max(r, max(g, b))
                    val mn2 = min(r, min(g, b))
                    val sat = if (mx2 > 1f) (mx2 - mn2) / mx2 else 0f
                    var boost = vib * (1f - sat)
                    if (sprot > 0f && Analyzer.isSkin(r, g, b)) boost *= (1f - sprot)
                    val ln2 = 0.299f * r + 0.587f * g + 0.114f * b
                    r = ln2 + (r - ln2) * (1f + boost)
                    g = ln2 + (g - ln2) * (1f + boost)
                    b = ln2 + (b - ln2) * (1f + boost)
                }
                px[i] = packRgb(r, g, b)
            }
        }
    }

    // ---------------- Edge-aware unsharp mask (halo সীমিত) ----------------
    private fun sharpen(px: IntArray, w: Int, h: Int, p: Params) {
        val bl = FloatArray(w * h)
        Par.rows(h) { y ->
            for (x in 0 until w) {
                val c = px[y * w + x]
                bl[y * w + x] = 0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255)
            }
        }
        Blur.box(bl, w, h, 2, 2)
        val amount = p.sharpen
        val guard = p.skinGuard
        val thr = 2f
        val lim = 24f
        Par.rows(h) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val c = px[i]
                val r = ((c shr 16) and 255).toFloat()
                val g = ((c shr 8) and 255).toFloat()
                val b = (c and 255).toFloat()
                val l = 0.299f * r + 0.587f * g + 0.114f * b
                val d = l - bl[i]
                val ad = abs(d)
                var dd = if (ad < thr) 0f else (ad - thr) * (if (d > 0f) 1f else -1f)
                dd = dd.coerceIn(-lim, lim)
                var a = amount
                if (Analyzer.isSkin(r, g, b)) a *= (1f - 0.6f * guard)
                val add = a * dd
                px[i] = packRgb(r + add, g + add, b + add)
            }
        }
    }
}
