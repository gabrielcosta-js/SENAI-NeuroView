package com.neuroview.app.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.neuroview.app.model.RecognitionResult
import kotlin.math.max
import kotlin.math.min

class DetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var detections: List<RecognitionResult> = emptyList()

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        strokeCap = Paint.Cap.ROUND
    }
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val textShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAA000000.toInt()
        textSize = 30f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val barBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x33FFFFFF
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = 0x22FFFFFF
        pathEffect = DashPathEffect(floatArrayOf(6f, 8f), 0f)
    }
    private val scanPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var scanY = 0f
    private var pulseAlpha = 1f

    private var scanAnimator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        startAnimations()
    }

    fun setDetections(newDetections: List<RecognitionResult>) {
        detections = newDetections
        invalidate()
    }

    fun clearDetections() {
        detections = emptyList()
        invalidate()
    }

    private fun startAnimations() {
        scanAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                scanY = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        pulseAnimator = ValueAnimator.ofFloat(1f, 0.25f, 1f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                pulseAlpha = it.animatedValue as Float
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f) return

        drawGrid(canvas, vw, vh)
        drawScan(canvas, vw, vh)

        detections.forEach { detection ->
            drawDetection(canvas, detection, vw, vh)
        }
    }

    private fun drawGrid(canvas: Canvas, vw: Float, vh: Float) {
        canvas.drawLine(vw / 3f, 0f, vw / 3f, vh, gridPaint)
        canvas.drawLine(vw * 2f / 3f, 0f, vw * 2f / 3f, vh, gridPaint)
        canvas.drawLine(0f, vh / 3f, vw, vh / 3f, gridPaint)
        canvas.drawLine(0f, vh * 2f / 3f, vw, vh * 2f / 3f, gridPaint)
    }

    private fun drawScan(canvas: Canvas, vw: Float, vh: Float) {
        val y = scanY * vh
        scanPaint.shader = LinearGradient(
            0f, y - 70f, 0f, y + 28f,
            intArrayOf(0x00007AFF, 0x2200D4FF, 0x5500D4FF, 0x0000D4FF),
            floatArrayOf(0f, 0.55f, 0.85f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, y - 70f, vw, y + 28f, scanPaint)
        scanPaint.shader = null
    }

    private fun drawDetection(canvas: Canvas, detection: RecognitionResult, vw: Float, vh: Float) {
        val box = detection.boundingBox ?: return
        val mapped = mapBoxToCenterCrop(box, detection.frameWidth, detection.frameHeight, vw, vh)
        val x1 = mapped.left
        val y1 = mapped.top
        val x2 = mapped.right
        val y2 = mapped.bottom

        if (x2 <= x1 || y2 <= y1) return

        val color = detectionColor(detection)
        cornerPaint.color = color

        drawCorners(canvas, x1, y1, x2, y2, cornerPaint)

        if (isRiskObject(detection.label)) {
            pulsePaint.color = (color and 0x00FFFFFF) or ((pulseAlpha * 120).toInt() shl 24)
            drawCorners(canvas, x1 - 4f, y1 - 4f, x2 + 4f, y2 + 4f, pulsePaint)
        }

        drawLabel(canvas, detection, x1, y1, x2, y2, vw, vh, color)
    }

    private fun mapBoxToCenterCrop(box: RectF, frameW: Int, frameH: Int, vw: Float, vh: Float): RectF {
        if (frameW <= 0 || frameH <= 0) {
            return RectF(box.left * vw, box.top * vh, box.right * vw, box.bottom * vh)
        }

        val scale = max(vw / frameW.toFloat(), vh / frameH.toFloat())
        val displayedW = frameW * scale
        val displayedH = frameH * scale
        val dx = (vw - displayedW) / 2f
        val dy = (vh - displayedH) / 2f

        return RectF(
            dx + box.left * frameW * scale,
            dy + box.top * frameH * scale,
            dx + box.right * frameW * scale,
            dy + box.bottom * frameH * scale
        )
    }

    private fun drawLabel(
        canvas: Canvas,
        detection: RecognitionResult,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        vw: Float,
        vh: Float,
        color: Int
    ) {
        val text = "${detection.label}  ${detection.confidence}%"
        val padH = 12f
        val padV = 8f
        val textW = textPaint.measureText(text)
        val labelW = textW + padH * 2
        val labelH = 44f
        var lx = x1.coerceIn(0f, max(0f, vw - labelW))
        var ly = if (y1 - labelH - 7f > 0f) y1 - labelH - 7f else y2 + 7f
        if (ly + labelH > vh) ly = max(0f, y1 - labelH - 7f)

        labelBgPaint.color = (color and 0x00FFFFFF) or 0xCC000000.toInt()
        canvas.drawRoundRect(lx, ly, lx + labelW, ly + labelH, 7f, 7f, labelBgPaint)

        barPaint.color = color
        canvas.drawRoundRect(lx, ly, lx + labelW, ly + 3f, 2f, 2f, barPaint)

        canvas.drawText(text, lx + padH + 1f, ly + 30f + 1f, textShadowPaint)
        canvas.drawText(text, lx + padH, ly + 30f, textPaint)

        val barY = ly + labelH - 7f
        canvas.drawRoundRect(lx + padH, barY, lx + labelW - padH, barY + 3f, 2f, 2f, barBgPaint)
        val fillW = (labelW - padH * 2) * (detection.confidence / 100f).coerceIn(0f, 1f)
        canvas.drawRoundRect(lx + padH, barY, lx + padH + fillW, barY + 3f, 2f, 2f, barPaint)
    }

    private fun drawCorners(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, paint: Paint) {
        val corner = min(30f, min((x2 - x1) * 0.42f, (y2 - y1) * 0.42f))
        canvas.drawLine(x1, y1, x1 + corner, y1, paint)
        canvas.drawLine(x1, y1, x1, y1 + corner, paint)

        canvas.drawLine(x2, y1, x2 - corner, y1, paint)
        canvas.drawLine(x2, y1, x2, y1 + corner, paint)

        canvas.drawLine(x1, y2, x1 + corner, y2, paint)
        canvas.drawLine(x1, y2, x1, y2 - corner, paint)

        canvas.drawLine(x2, y2, x2 - corner, y2, paint)
        canvas.drawLine(x2, y2, x2, y2 - corner, paint)
    }

    private fun detectionColor(detection: RecognitionResult): Int {
        val confidence = detection.confidence
        return when {
            isRiskObject(detection.label) -> Color.parseColor("#FF3D00")
            confidence >= 70 -> Color.parseColor("#00E676")
            confidence >= 45 -> Color.parseColor("#FFB300")
            else -> Color.parseColor("#00D4FF")
        }
    }

    private fun isRiskObject(label: String): Boolean {
        val l = label.lowercase()
        return l in setOf("pessoa", "carro", "moto", "ônibus", "caminhão", "bicicleta", "trem", "cachorro", "barco")
    }

    override fun onDetachedFromWindow() {
        scanAnimator?.cancel()
        pulseAnimator?.cancel()
        super.onDetachedFromWindow()
    }
}
