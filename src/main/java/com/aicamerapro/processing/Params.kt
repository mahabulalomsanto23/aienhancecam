package com.aicamerapro.processing

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

data class Params(
    val gainR: Float = 1f, val gainG: Float = 1f, val gainB: Float = 1f,
    val blackPoint: Float = 0f, val whitePoint: Float = 1f, val gamma: Float = 1f,
    val shadowLift: Float = 0f, val highlightComp: Float = 0f, val contrast: Float = 0f,
    val localExposure: Float = 0f, val subjectLiftScale: Float = 1f,
    val vibrance: Float = 0f, val skinProtect: Float = 0.7f,
    val denoise: Float = 0f, val chromaDenoise: Float = 0f, val sharpen: Float = 0f,
    val skinGuard: Float = 0.5f
)

object ParamsFactory {

    private fun softDead(x: Float, d: Float): Float = if (x > d) x - d else if (x < -d) x + d else 0f

    private fun gammaFor(p50: Float, target: Float): Float {
        val m = p50.coerceIn(0.03f, 0.97f)
        val t = target.coerceIn(0.03f, 0.97f)
        return ln(t) / ln(m)
    }

    /** প্রতিটি ছবির বিশ্লেষণ থেকে নিজস্ব সংশোধনের মান তৈরি করে। */
    fun forAi(a: Analysis, strength: Float): Params {
        val night = a.scene == Scene.NIGHT

        // ---- White balance (অল্প ও আংশিক; warm দৃশ্যের স্বাভাবিক উষ্ণতা বাঁচানো হয়) ----
        var gR = 1f
        var gG = 1f
        var gB = 1f
        val avg = (a.meanR + a.meanG + a.meanB) / 3f
        if (avg > 0.04f && a.meanR > 0.01f && a.meanG > 0.01f && a.meanB > 0.01f) {
            val devRB = ln(a.meanR / a.meanB)
            val devGM = ln(a.meanG / ((a.meanR + a.meanB) / 2f))
            val dead = if (a.scene == Scene.INDOOR) 0.10f else 0.15f
            val k = (if (night) 0.35f else 0.55f) * strength
            val cRB = softDead(devRB, dead) * k
            val cGM = softDead(devGM, 0.06f) * 0.6f * strength
            gR = exp(-cRB / 2f).coerceIn(0.88f, 1.14f)
            gB = exp(cRB / 2f).coerceIn(0.88f, 1.14f)
            gG = exp(-cGM).coerceIn(0.9f, 1.1f)
        }

        // ---- Exposure (gamma) ----
        var gamma = 1f
        if (night) {
            if (a.p50 < 0.22f && a.p50 > 0.04f) {
                gamma = gammaFor(a.p50, a.p50 + (0.24f - a.p50) * 0.25f * strength).coerceIn(0.88f, 1f)
            }
        } else if (a.p50 < 0.34f) {
            gamma = gammaFor(a.p50, a.p50 + (0.40f - a.p50) * 0.7f * strength).coerceIn(0.70f, 1f)
        } else if (a.p50 > 0.62f) {
            gamma = gammaFor(a.p50, a.p50 - (a.p50 - 0.55f) * 0.5f * strength).coerceIn(1f, 1.25f)
        }

        // ---- Black / white point ----
        val bp = if (a.p1 > 0.03f) minOf(a.p1 * 0.8f, 0.08f) else 0f
        val wp = if (a.p99 < 0.93f && !night) maxOf(a.p99 * 1.02f, 0.88f) else 1f

        // ---- Shadows / highlights / local exposure ----
        val nightMul = if (night) 0.4f else 1f
        val shadow = ((a.lowCrush * 1.8f + (if (a.p10 < 0.08f) 0.1f else 0f)).coerceIn(0f, 0.55f)) * strength * nightMul
        val high = ((a.highClip * 8f + (if (a.p90 > 0.85f) 0.1f else 0f)).coerceIn(0f, 0.6f)) * strength
        val range = a.p99 - a.p1
        val contrast = (if (night) 0.05f else if (range > 0.9f) 0.04f else if (range < 0.6f) 0.16f else 0.10f) * strength
        val local = ((a.p90 - a.p10 - 0.45f) * 1.2f).coerceIn(0f, 0.6f) * strength * (if (night) 0.3f else 1f)

        // ---- Vibrance (আগে থেকেই রঙিন হলে প্রায় কিছুই বাড়ায় না) ----
        val vib = if (a.meanSat > 0.45f) 0.03f else when (a.scene) {
            Scene.LANDSCAPE -> 0.20f
            Scene.PORTRAIT -> 0.08f
            Scene.NIGHT -> 0.06f
            Scene.INDOOR -> 0.10f
            Scene.GENERAL -> 0.14f
        } * strength

        // ---- Denoise / sharpen ----
        val ds = ((a.noise - 2.5f) / 10f).coerceIn(0f, 1f)
        val denoise = ds * 0.6f * (if (night) 1.15f else 1f) * (0.5f + 0.5f * strength)
        val chroma = ((a.noise - 1.5f) / 6f).coerceIn(0f, 1f) * 0.9f
        val sharpBase = if (a.edgeEnergy < 2.5f) 0.6f else if (a.edgeEnergy < 5f) 0.45f else 0.3f
        val sharpen = sharpBase * (1f - 0.6f * ds) * strength

        return Params(
            gainR = gR, gainG = gG, gainB = gB,
            blackPoint = bp, whitePoint = wp, gamma = gamma,
            shadowLift = shadow, highlightComp = high, contrast = contrast,
            localExposure = local, subjectLiftScale = strength,
            vibrance = vib, skinProtect = 0.7f,
            denoise = denoise, chromaDenoise = chroma, sharpen = sharpen,
            skinGuard = 0.5f
        )
    }

    /** Vivid: রঙ ও টোন-কেন্দ্রিক; denoise নেই, sharpening অতি হালকা। */
    fun forVivid(a: Analysis, strength: Float): Params {
        val base = forAi(a, strength * 0.6f)
        return base.copy(
            vibrance = (if (a.meanSat > 0.5f) 0.10f else 0.26f) * strength,
            contrast = 0.14f * strength,
            skinProtect = 0.85f,
            denoise = 0f, chromaDenoise = 0f, sharpen = 0.18f * strength
        )
    }
}

/** Original ও Enhanced তুলনা করে খারাপ ফল বাতিল করার জন্য। */
object Validator {
    fun ok(before: Analysis, after: Analysis, satMul: Float): Boolean =
        after.highClip <= before.highClip + 0.03f &&
            after.meanSat <= before.meanSat * satMul + 0.06f &&
            after.noise <= before.noise * 1.35f + 0.5f &&
            abs(after.meanL - before.meanL) <= 0.25f
}
