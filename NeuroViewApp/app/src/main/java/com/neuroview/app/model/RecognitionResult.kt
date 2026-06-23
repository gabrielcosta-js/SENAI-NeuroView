package com.neuroview.app.model

import android.graphics.RectF

data class RecognitionResult(
    val label: String,
    val confidence: Int,
    val source: String,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val boundingBox: RectF? = null, // normalizado [0,1] em relação ao frame original
    val distanceLabel: String = "",
    val position: Position = Position.CENTER
) {
    enum class Position { LEFT, CENTER, RIGHT }

    fun speakText(): String {
        val pos = when (position) {
            Position.LEFT -> "à esquerda"
            Position.RIGHT -> "à direita"
            Position.CENTER -> "à frente"
        }
        val distance = if (distanceLabel.isNotBlank()) ", $distanceLabel" else ""
        return "$label $pos$distance"
    }
}
