package com.neuroview.app.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.util.Log
import com.neuroview.app.model.RecognitionResult
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

/**
 * Detector YOLO TFLite robusto.
 *
 * Compatível com:
 * - yolo_model.tflite + coco_labels.txt
 * - yolov8n_float32.tflite + labels.txt
 *
 * Corrige o problema comum de modelos YOLOv8/YOLO11:
 * as coordenadas podem sair em pixels da entrada (ex: 640) e não em [0,1].
 */
class YoloDetector(private val context: Context) : AutoCloseable {

    companion object {
        private const val TAG = "YoloDetector"
        const val SOURCE = "YOLO_TFLITE"

        private val MODEL_CANDIDATES = listOf("yolo_model.tflite", "yolov8n_float32.tflite")
        private val LABEL_CANDIDATES = listOf("coco_labels.txt", "labels.txt")

        private const val IOU_THRESHOLD = 0.45f
        private const val MAX_DETECTIONS = 20
    }

    private val interpreter: Interpreter
    private val labels: List<String>
    private val inputWidth: Int
    private val inputHeight: Int
    private val inputChannels: Int
    private val isFloatInput: Boolean
    private val modelName: String
    private val labelName: String

    init {
        modelName = findAsset(MODEL_CANDIDATES)
            ?: error("Modelo YOLO não encontrado. Coloque yolo_model.tflite em app/src/main/assets.")
        labelName = findAsset(LABEL_CANDIDATES)
            ?: error("Labels não encontradas. Coloque coco_labels.txt ou labels.txt em assets.")

        interpreter = Interpreter(loadModelFile(modelName), Interpreter.Options().apply {
            setNumThreads(max(2, Runtime.getRuntime().availableProcessors() / 2))
        })

        labels = context.assets.open(labelName).bufferedReader().useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
        }

        val inputShape = interpreter.getInputTensor(0).shape()
        inputHeight = inputShape.getOrNull(1) ?: 640
        inputWidth = inputShape.getOrNull(2) ?: 640
        inputChannels = inputShape.getOrNull(3) ?: 3
        isFloatInput = interpreter.getInputTensor(0).dataType().name == "FLOAT32"

        require(inputChannels == 3) { "Modelo YOLO precisa usar entrada RGB com 3 canais." }

        Log.i(TAG, "YOLO carregado: model=$modelName labels=$labelName input=${inputWidth}x${inputHeight} float=$isFloatInput")
    }

    fun detect(bitmap: Bitmap, minConfidence: Int = 21): List<RecognitionResult> {
        val letterbox = letterbox(bitmap)
        val input = bitmapToInputBuffer(letterbox.bitmap)

        val outputShape = interpreter.getOutputTensor(0).shape()
        if (outputShape.size != 3 || outputShape[0] != 1) {
            Log.w(TAG, "Formato de saída não esperado: ${outputShape.joinToString()}")
            return emptyList()
        }

        val dim1 = outputShape[1]
        val dim2 = outputShape[2]
        val output = Array(1) { Array(dim1) { FloatArray(dim2) } }

        interpreter.run(input, output)

        if (letterbox.bitmap !== bitmap && !letterbox.bitmap.isRecycled) {
            letterbox.bitmap.recycle()
        }

        val raw = parseOutput(output[0], dim1, dim2, bitmap.width, bitmap.height, letterbox, minConfidence)
        return nms(raw).sortedByDescending { it.confidence }.take(MAX_DETECTIONS)
    }

    private data class LetterboxResult(
        val bitmap: Bitmap,
        val padLeft: Float,
        val padTop: Float,
        val usedWidth: Float,
        val usedHeight: Float
    )

    private fun letterbox(src: Bitmap): LetterboxResult {
        val sw = src.width
        val sh = src.height

        val scale = min(inputWidth / sw.toFloat(), inputHeight / sh.toFloat())
        val newW = (sw * scale).toInt().coerceAtLeast(1)
        val newH = (sh * scale).toInt().coerceAtLeast(1)

        val padLeftPx = (inputWidth - newW) / 2f
        val padTopPx = (inputHeight - newH) / 2f

        val dest = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(dest)
        canvas.drawColor(Color.rgb(114, 114, 114))

        val resized = Bitmap.createScaledBitmap(src, newW, newH, true)
        canvas.drawBitmap(resized, padLeftPx, padTopPx, null)
        if (resized !== src && !resized.isRecycled) resized.recycle()

        return LetterboxResult(
            bitmap = dest,
            padLeft = padLeftPx / inputWidth,
            padTop = padTopPx / inputHeight,
            usedWidth = newW / inputWidth.toFloat(),
            usedHeight = newH / inputHeight.toFloat()
        )
    }

    private fun parseOutput(
        raw: Array<FloatArray>,
        dim1: Int,
        dim2: Int,
        frameWidth: Int,
        frameHeight: Int,
        letterbox: LetterboxResult,
        minConfidence: Int
    ): List<RecognitionResult> {
        val channelFirst = dim1 < dim2
        val boxes = if (channelFirst) dim2 else dim1
        val attrs = if (channelFirst) dim1 else dim2

        val results = mutableListOf<RecognitionResult>()

        // Formato pós-processado comum: [1, N, 6] ou [1, 6, N]
        if (attrs in 6..7) {
            for (i in 0 until boxes) {
                val a0 = value(raw, channelFirst, 0, i)
                val a1 = value(raw, channelFirst, 1, i)
                val a2 = value(raw, channelFirst, 2, i)
                val a3 = value(raw, channelFirst, 3, i)
                val score = value(raw, channelFirst, 4, i).coerceIn(0f, 1f)
                val cls = value(raw, channelFirst, 5, i).toInt()
                val confidence = (score * 100f).toInt().coerceIn(0, 100)
                if (confidence < minConfidence) continue

                val box = buildBoxFromEitherXYXYOrCXCYWH(a0, a1, a2, a3, letterbox) ?: continue
                val label = translateLabel(labels.getOrNull(cls) ?: "object")
                results.add(makeResult(label, confidence, box, frameWidth, frameHeight))
            }
            return results
        }

        if (attrs <= 5) return emptyList()

        val hasObjectness = attrs >= labels.size + 5
        val classStart = if (hasObjectness) 5 else 4
        if (attrs <= classStart) return emptyList()

        for (i in 0 until boxes) {
            val cx = value(raw, channelFirst, 0, i)
            val cy = value(raw, channelFirst, 1, i)
            val bw = value(raw, channelFirst, 2, i)
            val bh = value(raw, channelFirst, 3, i)

            val objectness = if (hasObjectness) {
                value(raw, channelFirst, 4, i).coerceIn(0f, 1f)
            } else {
                1f
            }

            var bestClass = -1
            var bestScore = 0f
            for (classAttr in classStart until attrs) {
                val classIndex = classAttr - classStart
                if (classIndex >= labels.size) break
                val rawScore = value(raw, channelFirst, classAttr, i)
                val score = objectness * rawScore
                if (score > bestScore) {
                    bestScore = score
                    bestClass = classIndex
                }
            }

            val confidence = (bestScore * 100f).toInt().coerceIn(0, 100)
            if (confidence < minConfidence) continue

            val box = buildBoxFromCXCYWH(cx, cy, bw, bh, letterbox) ?: continue
            val label = translateLabel(labels.getOrNull(bestClass) ?: "object")
            results.add(makeResult(label, confidence, box, frameWidth, frameHeight))
        }

        return results
    }

    private fun value(raw: Array<FloatArray>, channelFirst: Boolean, attr: Int, box: Int): Float {
        return if (channelFirst) raw[attr][box] else raw[box][attr]
    }

    private fun buildBoxFromEitherXYXYOrCXCYWH(
        a0: Float,
        a1: Float,
        a2: Float,
        a3: Float,
        letterbox: LetterboxResult
    ): RectF? {
        val maxCoord = max(max(a0, a1), max(a2, a3))
        val x0 = if (maxCoord > 2f) a0 / inputWidth else a0
        val y0 = if (maxCoord > 2f) a1 / inputHeight else a1
        val x2 = if (maxCoord > 2f) a2 / inputWidth else a2
        val y2 = if (maxCoord > 2f) a3 / inputHeight else a3

        // Se parece xyxy, usa xyxy; se não, cai para cxcywh.
        return if (x2 > x0 && y2 > y0 && (x2 - x0) < 1.2f && (y2 - y0) < 1.2f) {
            removeLetterbox(RectF(x0, y0, x2, y2), letterbox)
        } else {
            buildBoxFromCXCYWH(a0, a1, a2, a3, letterbox)
        }
    }

    private fun buildBoxFromCXCYWH(
        cxRaw: Float,
        cyRaw: Float,
        wRaw: Float,
        hRaw: Float,
        letterbox: LetterboxResult
    ): RectF? {
        val maxCoord = max(max(cxRaw, cyRaw), max(wRaw, hRaw))

        val cx = if (maxCoord > 2f) cxRaw / inputWidth else cxRaw
        val cy = if (maxCoord > 2f) cyRaw / inputHeight else cyRaw
        val bw = if (maxCoord > 2f) wRaw / inputWidth else wRaw
        val bh = if (maxCoord > 2f) hRaw / inputHeight else hRaw

        val boxInInput = RectF(
            cx - bw / 2f,
            cy - bh / 2f,
            cx + bw / 2f,
            cy + bh / 2f
        )

        return removeLetterbox(boxInInput, letterbox)
    }

    private fun removeLetterbox(box: RectF, letterbox: LetterboxResult): RectF? {
        if (letterbox.usedWidth <= 0f || letterbox.usedHeight <= 0f) return null

        val x1 = ((box.left - letterbox.padLeft) / letterbox.usedWidth).coerceIn(0f, 1f)
        val y1 = ((box.top - letterbox.padTop) / letterbox.usedHeight).coerceIn(0f, 1f)
        val x2 = ((box.right - letterbox.padLeft) / letterbox.usedWidth).coerceIn(0f, 1f)
        val y2 = ((box.bottom - letterbox.padTop) / letterbox.usedHeight).coerceIn(0f, 1f)

        if (x2 <= x1 || y2 <= y1) return null
        if ((x2 - x1) < 0.01f || (y2 - y1) < 0.01f) return null

        return RectF(x1, y1, x2, y2)
    }

    private fun makeResult(
        label: String,
        confidence: Int,
        box: RectF,
        frameWidth: Int,
        frameHeight: Int
    ): RecognitionResult {
        return RecognitionResult(
            label = label,
            confidence = confidence,
            source = SOURCE,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            boundingBox = box,
            distanceLabel = estimateDistance(box),
            position = estimatePosition(box.centerX())
        )
    }

    private fun nms(detections: List<RecognitionResult>): List<RecognitionResult> {
        val grouped = detections.groupBy { it.label }
        val output = mutableListOf<RecognitionResult>()

        for ((_, group) in grouped) {
            val sorted = group.sortedByDescending { it.confidence }.toMutableList()
            while (sorted.isNotEmpty()) {
                val best = sorted.removeAt(0)
                output.add(best)

                val bestBox = best.boundingBox
                if (bestBox != null) {
                    sorted.removeAll { other ->
                        val otherBox = other.boundingBox
                        otherBox != null && iou(bestBox, otherBox) > IOU_THRESHOLD
                    }
                }
            }
        }

        return output
    }

    private fun iou(a: RectF, b: RectF): Float {
        val ix1 = max(a.left, b.left)
        val iy1 = max(a.top, b.top)
        val ix2 = min(a.right, b.right)
        val iy2 = min(a.bottom, b.bottom)
        if (ix2 <= ix1 || iy2 <= iy1) return 0f

        val intersection = (ix2 - ix1) * (iy2 - iy1)
        val union = a.width() * a.height() + b.width() * b.height() - intersection
        return if (union > 0f) intersection / union else 0f
    }

    private fun estimateDistance(box: RectF): String {
        val area = box.width() * box.height()
        return when {
            area > 0.30f -> "muito perto"
            area > 0.08f -> "perto"
            area > 0.015f -> "distância média"
            else -> "longe"
        }
    }

    private fun estimatePosition(cx: Float): RecognitionResult.Position {
        return when {
            cx < 0.35f -> RecognitionResult.Position.LEFT
            cx > 0.65f -> RecognitionResult.Position.RIGHT
            else -> RecognitionResult.Position.CENTER
        }
    }

    private fun bitmapToInputBuffer(bitmap: Bitmap): ByteBuffer {
        val bytesPerChannel = if (isFloatInput) 4 else 1
        val buffer = ByteBuffer.allocateDirect(1 * inputWidth * inputHeight * 3 * bytesPerChannel)
        buffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(inputWidth * inputHeight)
        bitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)

        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF

            if (isFloatInput) {
                buffer.putFloat(r / 255f)
                buffer.putFloat(g / 255f)
                buffer.putFloat(b / 255f)
            } else {
                buffer.put(r.toByte())
                buffer.put(g.toByte())
                buffer.put(b.toByte())
            }
        }

        buffer.rewind()
        return buffer
    }

    private fun findAsset(candidates: List<String>): String? {
        val available = context.assets.list("")?.toSet().orEmpty()
        val direct = candidates.firstOrNull { it in available }
        if (direct != null) return direct

        // Fallback: se o usuário colocou o modelo com outro nome
        // exemplo: YOLOv11-Detection.tflite, usa o primeiro .tflite encontrado.
        if (candidates.any { it.endsWith(".tflite") }) {
            return available.firstOrNull { it.endsWith(".tflite", ignoreCase = true) }
        }

        return null
    }

    private fun loadModelFile(fileName: String): MappedByteBuffer {
        val fd = context.assets.openFd(fileName)
        FileInputStream(fd.fileDescriptor).channel.use { channel ->
            return channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }

    private fun translateLabel(label: String): String {
        return when (label.lowercase().trim()) {
            "person" -> "pessoa"
            "bicycle" -> "bicicleta"
            "car", "vehicle" -> "carro"
            "motorcycle" -> "moto"
            "airplane" -> "avião"
            "bus" -> "ônibus"
            "train" -> "trem"
            "truck" -> "caminhão"
            "boat" -> "barco"
            "traffic light" -> "semáforo"
            "fire hydrant" -> "hidrante"
            "stop sign" -> "placa de pare"
            "parking meter" -> "parquímetro"
            "bench" -> "banco"
            "bird" -> "pássaro"
            "cat" -> "gato"
            "dog" -> "cachorro"
            "horse" -> "cavalo"
            "sheep" -> "ovelha"
            "cow" -> "vaca"
            "elephant" -> "elefante"
            "bear" -> "urso"
            "zebra" -> "zebra"
            "giraffe" -> "girafa"
            "backpack" -> "mochila"
            "umbrella" -> "guarda-chuva"
            "handbag", "bag" -> "bolsa"
            "tie" -> "gravata"
            "suitcase" -> "mala"
            "sports ball" -> "bola"
            "bottle" -> "garrafa"
            "wine glass" -> "taça"
            "cup" -> "copo"
            "fork" -> "garfo"
            "knife" -> "faca"
            "spoon" -> "colher"
            "bowl" -> "tigela"
            "banana" -> "banana"
            "apple" -> "maçã"
            "sandwich" -> "sanduíche"
            "orange" -> "laranja"
            "broccoli" -> "brócolis"
            "carrot" -> "cenoura"
            "hot dog" -> "cachorro-quente"
            "pizza" -> "pizza"
            "donut" -> "rosquinha"
            "cake" -> "bolo"
            "chair" -> "cadeira"
            "couch", "sofa" -> "sofá"
            "potted plant", "plant" -> "planta"
            "bed" -> "cama"
            "dining table", "table" -> "mesa"
            "toilet" -> "vaso sanitário"
            "tv", "television" -> "televisão"
            "laptop" -> "computador"
            "mouse" -> "mouse"
            "remote" -> "controle remoto"
            "keyboard" -> "teclado"
            "cell phone", "phone" -> "celular"
            "microwave" -> "micro-ondas"
            "oven" -> "forno"
            "toaster" -> "torradeira"
            "sink" -> "pia"
            "refrigerator" -> "geladeira"
            "book" -> "livro"
            "clock" -> "relógio"
            "vase" -> "vaso"
            "scissors" -> "tesoura"
            "teddy bear" -> "urso de pelúcia"
            "hair drier" -> "secador"
            "toothbrush" -> "escova de dentes"
            "object" -> "objeto"
            else -> label.lowercase().trim()
        }
    }

    override fun close() {
        interpreter.close()
    }
}
