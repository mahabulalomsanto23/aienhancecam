package com.aicamerapro.processing

import java.util.stream.IntStream
import kotlin.math.max
import kotlin.math.min

object Par {
    fun rows(n: Int, body: (Int) -> Unit) {
        IntStream.range(0, n).parallel().forEach { body(it) }
    }
}

fun packRgb(r: Float, g: Float, b: Float): Int {
    val ri = (r + 0.5f).toInt().coerceIn(0, 255)
    val gi = (g + 0.5f).toInt().coerceIn(0, 255)
    val bi = (b + 0.5f).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
}

fun bilinearAt(a: FloatArray, w: Int, h: Int, x: Float, y: Float): Float {
    val xc = if (x < 0f) 0f else if (x > w - 1f) w - 1f else x
    val yc = if (y < 0f) 0f else if (y > h - 1f) h - 1f else y
    val x0 = xc.toInt()
    val y0 = yc.toInt()
    val x1 = if (x0 + 1 < w) x0 + 1 else x0
    val y1 = if (y0 + 1 < h) y0 + 1 else y0
    val fx = xc - x0
    val fy = yc - y0
    val top = a[y0 * w + x0] * (1f - fx) + a[y0 * w + x1] * fx
    val bot = a[y1 * w + x0] * (1f - fx) + a[y1 * w + x1] * fx
    return top * (1f - fy) + bot * fy
}

/** দ্রুত separable box blur (একাধিক pass = প্রায় Gaussian)। array in-place বদলায়। */
object Blur {
    fun box(a: FloatArray, w: Int, h: Int, r: Int, passes: Int) {
        if (r < 1 || passes < 1) return
        val tmp = FloatArray(a.size)
        for (i in 0 until passes) {
            horizontal(a, tmp, w, h, r)
            vertical(tmp, a, w, h, r)
        }
    }

    private fun horizontal(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
        val inv = 1f / (2 * r + 1)
        Par.rows(h) { y ->
            val o = y * w
            var sum = 0f
            for (i in -r..r) sum += src[o + min(max(i, 0), w - 1)]
            for (x in 0 until w) {
                dst[o + x] = sum * inv
                sum += src[o + min(x + r + 1, w - 1)] - src[o + max(x - r, 0)]
            }
        }
    }

    private fun vertical(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int) {
        val inv = 1f / (2 * r + 1)
        Par.rows(w) { x ->
            var sum = 0f
            for (i in -r..r) sum += src[min(max(i, 0), h - 1) * w + x]
            for (y in 0 until h) {
                dst[y * w + x] = sum * inv
                sum += src[min(y + r + 1, h - 1) * w + x] - src[max(y - r, 0) * w + x]
            }
        }
    }
}

/** Subject confidence mask (0..1)। */
class Mask(val w: Int, val h: Int, val data: FloatArray) {
    fun sample(u: Float, v: Float): Float = bilinearAt(data, w, h, u * w - 0.5f, v * h - 0.5f)

    fun coverage(): Float {
        var c = 0
        for (v in data) if (v > 0.5f) c++
        return c.toFloat() / data.size
    }

    /** কিনারা নরম করা: threshold বদলে হালকা feather। */
    fun refined(): Mask {
        val d = FloatArray(data.size) { ((data[it] - 0.25f) / 0.5f).coerceIn(0f, 1f) }
        Blur.box(d, w, h, 1, 2)
        return Mask(w, h, d)
    }
}
