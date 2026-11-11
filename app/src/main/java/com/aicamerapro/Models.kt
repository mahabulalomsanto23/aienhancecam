package com.aicamerapro

import com.aicamerapro.processing.BlurStrength

enum class CameraMode(val label: String, val tag: String) {
    NORMAL("Normal", "normal"),
    VIVID("Vivid", "vivid"),
    AI("AI", "ai"),
    PORTRAIT("Portrait", "portrait")
}

data class AppSettings(
    val saveBoth: Boolean = true,
    val startupMode: Int = CameraMode.AI.ordinal,
    val sizeIdx: Int = 0,
    val aspect169: Boolean = false,
    val flash: Int = 0,
    val blur: Int = BlurStrength.MEDIUM.ordinal,
    val strengthIdx: Int = 1
) {
    val maxSide: Int get() = intArrayOf(0, 4096, 3072)[sizeIdx.coerceIn(0, 2)]
    val strength: Float get() = if (strengthIdx == 0) 0.6f else 1f
}
