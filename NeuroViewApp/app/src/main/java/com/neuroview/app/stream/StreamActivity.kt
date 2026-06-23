package com.neuroview.app.stream

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.neuroview.app.R
import com.neuroview.app.databinding.ActivityStreamBinding
import com.neuroview.app.model.Glasses
import com.neuroview.app.model.RecognitionResult
import com.neuroview.app.utils.hide
import com.neuroview.app.utils.show
import com.neuroview.app.vision.YoloDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

class StreamActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    companion object {
        const val EXTRA_GLASSES = "extra_glasses"
        private const val TAG = "StreamActivity"
        private const val TTS_COOLDOWN_MS = 2200L
        private const val FRAME_INTERVAL_MS = 550L
        private const val MAX_JPEG_SIZE = 2_000_000
        private const val MIN_CONFIDENCE_TO_SPEAK = 50
        private const val MIN_CONFIDENCE_TO_SHOW = 21
    }

    private lateinit var binding: ActivityStreamBinding
    private lateinit var tts: TextToSpeech
    private lateinit var mlKitDetector: ObjectDetector

    private var yoloDetector: YoloDetector? = null
    private var streamJob: Job? = null
    private var analysisJob: Job? = null
    private var connection: HttpURLConnection? = null

    private var mlKitProcessing = false
    private var ttsReady = false
    private var ttsEnabled = true
    private var lastSpokenTime = 0L
    private var lastSpokenKey = ""

    // Controle de foco do áudio:
    // não interrompe uma fala no meio. Enquanto estiver falando, guarda só o próximo aviso.
    @Volatile private var ttsSpeaking = false
    @Volatile private var pendingSpeechText: String? = null
    @Volatile private var pendingSpeechKey: String? = null

    private var lastAnalysisTime = 0L
    private var frameCount = 0
    private var fpsTimer = System.currentTimeMillis()

    private val glasses: Glasses by lazy {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(EXTRA_GLASSES)!!
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStreamBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tts = TextToSpeech(this, this)
        setupObjectDetectors()
        setupUI()
        startStream()
    }

    private fun setupObjectDetectors() {
        val options = ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()

        mlKitDetector = ObjectDetection.getClient(options)

        yoloDetector = try {
            YoloDetector(this).also {
                Log.i(TAG, "YOLO TFLite carregado com sucesso.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "YOLO não carregado. Fallback ML Kit ativado: ${e.message}")
            null
        }
    }

    private fun setupUI() {
        binding.tvDeviceName.text = glasses.name.ifBlank { "ESP" }
        binding.tvModelStatus.text = if (yoloDetector != null) "YOLO" else "ML KIT"
        binding.tvModelStatus.setTextColor(
            if (yoloDetector != null) Color.parseColor("#00E676") else Color.parseColor("#FFB300")
        )

        binding.tvDetection.text = "Aguardando detecção..."
        binding.tvConfidence.text = ""
        binding.tvConfidence.hide()
        binding.tvDistance.text = "iniciando IA"
        binding.tvCount.text = "0 objetos"
        binding.viewConfBar.scaleX = 0f
        updatePositionIndicator(null, Color.parseColor("#00D4FF"))

        binding.btnBack.setOnClickListener { finish() }

        binding.btnDisconnect.setOnClickListener {
            stopStream()
            updateStatus(false)
            finish()
        }

        binding.btnTts.setOnClickListener {
            ttsEnabled = !ttsEnabled
            binding.btnTts.setColorFilter(
                if (ttsEnabled) getColor(R.color.nv_green) else Color.parseColor("#6D7788")
            )
            binding.btnTts.contentDescription = if (ttsEnabled) {
                "Áudio ativado. Toque para desativar."
            } else {
                "Áudio desativado. Toque para ativar."
            }
            binding.root.announceForAccessibility(binding.btnTts.contentDescription)
            if (!ttsEnabled) {
                pendingSpeechText = null
                pendingSpeechKey = null
                ttsSpeaking = false
                tts.stop()
            }
        }
    }

    private fun startStream() {
        stopStream()

        binding.connectingOverlay.show()
        binding.tvStatus.text = "Conectando..."
        binding.tvStatus.setTextColor(Color.parseColor("#5E6B7D"))
        binding.statusDot.setBackgroundResource(R.drawable.bg_dot_gray)
        findViewById<android.view.View>(R.id.status_chip)?.setBackgroundResource(R.drawable.bg_status_pill_offline)
        binding.overlayView.clearDetections()

        streamJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = URL(glasses.streamUrl)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 12000
                    requestMethod = "GET"
                    setRequestProperty("Connection", "keep-alive")
                    setRequestProperty("Cache-Control", "no-cache")
                    setRequestProperty("Accept", "multipart/x-mixed-replace,image/jpeg,*/*")
                    doInput = true
                    connect()
                }

                val activeConnection = connection ?: error("Conexão HTTP não criada")
                if (activeConnection.responseCode !in 200..299) {
                    throw IllegalStateException("HTTP ${activeConnection.responseCode}")
                }

                val input = BufferedInputStream(activeConnection.inputStream)
                while (isActive) {
                    val jpegBytes = readNextJpeg(input) ?: break
                    val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: continue
                    withContext(Dispatchers.Main) {
                        onFrameReceived(bitmap)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Stream error: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    showStreamError()
                }
            } finally {
                try { connection?.disconnect() } catch (_: Exception) {}
                connection = null
            }
        }
    }

    /**
     * O endpoint /stream do ESP32-CAM envia MJPEG multipart.
     * Aqui buscamos diretamente os marcadores de cada JPEG:
     * FF D8 = início; FF D9 = fim.
     */
    private fun readNextJpeg(input: BufferedInputStream): ByteArray? {
        val buffer = ArrayList<Byte>(64 * 1024)
        var previous = -1
        var insideJpeg = false

        while (streamJob?.isActive == true) {
            val current = input.read()
            if (current == -1) return null

            if (!insideJpeg) {
                if (previous == 0xFF && current == 0xD8) {
                    insideJpeg = true
                    buffer.add(0xFF.toByte())
                    buffer.add(0xD8.toByte())
                }
            } else {
                buffer.add(current.toByte())
                if (buffer.size > MAX_JPEG_SIZE) return null
                if (previous == 0xFF && current == 0xD9) {
                    return buffer.toByteArray()
                }
            }
            previous = current
        }

        return null
    }

    private fun onFrameReceived(bitmap: Bitmap) {
        binding.imageStream.setImageBitmap(bitmap)
        binding.connectingOverlay.hide()
        updateStatus(true)
        updateFps()

        val now = System.currentTimeMillis()
        val busy = analysisJob?.isActive == true || mlKitProcessing
        if (now - lastAnalysisTime >= FRAME_INTERVAL_MS && !busy) {
            lastAnalysisTime = now
            // A IA trabalha em uma cópia do frame para nunca reciclar/alterar
            // o mesmo Bitmap que está sendo desenhado no ImageView.
            val analysisBitmap = try {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
            } catch (_: Exception) {
                bitmap
            }
            analyzeFrame(analysisBitmap)
        }
    }

    private fun updateFps() {
        frameCount++
        val now = System.currentTimeMillis()
        if (now - fpsTimer >= 1500) {
            val fps = frameCount * 1000f / (now - fpsTimer).toFloat()
            binding.tvFps.text = String.format(Locale.US, "%.1f FPS", fps)
            frameCount = 0
            fpsTimer = now
        }
    }

    private fun analyzeFrame(bitmap: Bitmap) {
        val yolo = yoloDetector
        if (yolo != null) {
            analysisJob = lifecycleScope.launch(Dispatchers.Default) {
                try {
                    val results = yolo.detect(bitmap, minConfidence = 25)
                    withContext(Dispatchers.Main) {
                        if (results.isNotEmpty()) {
                            binding.tvModelStatus.text = "YOLO"
                            binding.tvModelStatus.setTextColor(Color.parseColor("#00E676"))
                            handleRecognitionResults(results)
                        } else {
                            analyzeFrameWithMlKit(bitmap)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "YOLO falhou neste frame. ML Kit fallback: ${e.message}")
                    withContext(Dispatchers.Main) {
                        analyzeFrameWithMlKit(bitmap)
                    }
                }
            }
        } else {
            analyzeFrameWithMlKit(bitmap)
        }
    }

    private fun analyzeFrameWithMlKit(bitmap: Bitmap) {
        if (mlKitProcessing) return
        mlKitProcessing = true

        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            mlKitDetector.process(image)
                .addOnSuccessListener { objects ->
                    val results = objects.map { obj ->
                        val bestLabel = obj.labels.maxByOrNull { it.confidence }
                        val confidence = if (bestLabel != null) {
                            (bestLabel.confidence * 100).toInt().coerceIn(0, 100)
                        } else {
                            20
                        }

                        val rawLabel = bestLabel?.text ?: "object"
                        val box = obj.boundingBox.toNormalizedRect(bitmap.width, bitmap.height)

                        RecognitionResult(
                            label = translateLabel(rawLabel),
                            confidence = confidence,
                            source = if (bestLabel != null) "MLKIT_FALLBACK" else "MLKIT_OBJECT",
                            frameWidth = bitmap.width,
                            frameHeight = bitmap.height,
                            boundingBox = box,
                            distanceLabel = estimateDistance(box),
                            position = estimatePosition(box.centerX())
                        )
                    }.sortedByDescending { it.confidence }

                    binding.tvModelStatus.text = if (yoloDetector != null) "YOLO/ML" else "ML KIT"
                    binding.tvModelStatus.setTextColor(Color.parseColor("#FFB300"))
                    handleRecognitionResults(results)
                }
                .addOnFailureListener {
                    Log.w(TAG, "Detection failed: ${it.message}")
                    handleRecognitionResults(emptyList())
                }
                .addOnCompleteListener {
                    mlKitProcessing = false
                }
        } catch (e: Exception) {
            mlKitProcessing = false
            Log.w(TAG, "Frame analysis error: ${e.message}")
            handleRecognitionResults(emptyList())
        }
    }

    private fun handleRecognitionResults(results: List<RecognitionResult>) {
        val validResults = results.filter {
                it.boundingBox != null && it.confidence >= MIN_CONFIDENCE_TO_SHOW
            }
            .sortedByDescending { it.confidence }
            .take(8)

        binding.overlayView.setDetections(validResults)

        val top = validResults.firstOrNull()
        if (top == null) {
            binding.tvDetection.text = "Aguardando detecção..."
            binding.tvConfidence.text = ""
            binding.tvConfidence.hide()
            binding.tvDistance.text = "analisando cena"
            binding.tvCount.text = "0 objetos"
            binding.viewConfBar.animate().scaleX(0f).setDuration(120).start()
            updatePositionIndicator(null, Color.parseColor("#00D4FF"))
            binding.viewDivider.setBackgroundColor(Color.parseColor("#00D4FF"))
            return
        }

        val color = detectionColor(top)
        binding.tvDetection.text = top.label.uppercase(Locale("pt", "BR"))
        binding.tvConfidence.show()
        binding.tvConfidence.text = "${top.confidence}%"
        binding.tvConfidence.setTextColor(color)
        binding.tvDistance.text = top.distanceLabel.ifBlank { "posição detectada" }
        binding.tvCount.text = "${validResults.size} ${if (validResults.size == 1) "objeto" else "objetos"}"
        binding.viewDivider.setBackgroundColor(color)
        binding.viewConfBar.backgroundTintList = ColorStateList.valueOf(color)
        binding.viewConfBar.animate()
            .scaleX((top.confidence / 100f).coerceIn(0f, 1f))
            .setDuration(140)
            .start()

        updatePositionIndicator(top.position, color)

        val now = System.currentTimeMillis()

        val audioText: String
        val speakKey: String

        if (top.confidence >= MIN_CONFIDENCE_TO_SPEAK) {
            val prefix = if (isRiskObject(top.label)) "Atenção! " else ""
            audioText = prefix + top.speakText()
            speakKey = top.label + "_" + top.position.name
        } else {
            audioText = "Objeto à frente detectado"
            speakKey = "objeto_a_frente_" + top.position.name
        }

        val shouldSpeak = ttsEnabled && ttsReady &&
                (speakKey != lastSpokenKey || now - lastSpokenTime > TTS_COOLDOWN_MS)

        if (shouldSpeak) {
            requestSpeak(audioText, speakKey)
        }
    }

    private fun updatePositionIndicator(position: RecognitionResult.Position?, color: Int) {
        val inactiveColor = Color.parseColor("#6D7788")
        val activeTextColor = color

        binding.tvPosL.setBackgroundResource(R.drawable.bg_pos_inactive)
        binding.tvPosC.setBackgroundResource(R.drawable.bg_pos_inactive)
        binding.tvPosR.setBackgroundResource(R.drawable.bg_pos_inactive)
        binding.tvPosL.setTextColor(inactiveColor)
        binding.tvPosC.setTextColor(inactiveColor)
        binding.tvPosR.setTextColor(inactiveColor)

        val bg = when {
            color == Color.parseColor("#FFB300") -> R.drawable.bg_pos_active_amber
            color == Color.parseColor("#00E676") -> R.drawable.bg_pos_active_green
            else -> R.drawable.bg_pos_active_cyan
        }

        when (position) {
            RecognitionResult.Position.LEFT -> {
                binding.tvPosL.setBackgroundResource(bg)
                binding.tvPosL.setTextColor(activeTextColor)
            }
            RecognitionResult.Position.CENTER -> {
                binding.tvPosC.setBackgroundResource(bg)
                binding.tvPosC.setTextColor(activeTextColor)
            }
            RecognitionResult.Position.RIGHT -> {
                binding.tvPosR.setBackgroundResource(bg)
                binding.tvPosR.setTextColor(activeTextColor)
            }
            null -> Unit
        }
    }

    private fun detectionColor(result: RecognitionResult): Int {
        return when {
            isRiskObject(result.label) -> Color.parseColor("#FF3D00")
            result.confidence >= 70 -> Color.parseColor("#00E676")
            result.confidence >= 45 -> Color.parseColor("#FFB300")
            else -> Color.parseColor("#00D4FF")
        }
    }

    private fun isRiskObject(label: String): Boolean {
        return label.lowercase(Locale("pt", "BR")) in setOf(
            "pessoa", "carro", "moto", "ônibus", "caminhão", "bicicleta", "trem", "cachorro", "barco"
        )
    }

    private fun android.graphics.Rect.toNormalizedRect(frameWidth: Int, frameHeight: Int): RectF {
        val fw = max(frameWidth, 1).toFloat()
        val fh = max(frameHeight, 1).toFloat()
        return RectF(
            (left / fw).coerceIn(0f, 1f),
            (top / fh).coerceIn(0f, 1f),
            (right / fw).coerceIn(0f, 1f),
            (bottom / fh).coerceIn(0f, 1f)
        )
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

    private fun translateLabel(label: String): String {
        return when (label.lowercase(Locale("pt", "BR")).trim()) {
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
            else -> label.lowercase(Locale("pt", "BR")).trim().ifBlank { "objeto" }
        }
    }

    private fun requestSpeak(text: String, key: String) {
        if (!ttsReady || !ttsEnabled) return

        val now = System.currentTimeMillis()
        if (key == lastSpokenKey && now - lastSpokenTime < TTS_COOLDOWN_MS) return

        // Se já está falando, não interrompe. Guarda apenas o próximo aviso mais recente.
        if (ttsSpeaking || tts.isSpeaking) {
            pendingSpeechText = text
            pendingSpeechKey = key
            return
        }

        speakNow(text, key)
    }

    private fun speakNow(text: String, key: String) {
        if (!ttsReady || !ttsEnabled) return

        ttsSpeaking = true
        lastSpokenKey = key
        lastSpokenTime = System.currentTimeMillis()

        val utteranceId = "neuroview_tts_" + lastSpokenTime
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
    }

    private fun speak(text: String) {
        // Usado apenas para avisos simples, sem interromper detecções.
        requestSpeak(text, "system_" + text.hashCode())
    }

    private fun onTtsFinished() {
        val nextText = pendingSpeechText
        val nextKey = pendingSpeechKey

        pendingSpeechText = null
        pendingSpeechKey = null
        ttsSpeaking = false

        if (ttsEnabled && ttsReady && nextText != null && nextKey != null) {
            runOnUiThread {
                speakNow(nextText, nextKey)
            }
        }
    }

    private fun updateStatus(connected: Boolean) {
        if (connected) {
            binding.tvStatus.text = "Transmissão ativa"
            binding.tvStatus.setTextColor(getColor(R.color.nv_green_dark))
            binding.statusDot.setBackgroundResource(R.drawable.bg_dot_green)
            findViewById<android.view.View>(R.id.status_chip)?.setBackgroundResource(R.drawable.bg_status_pill_online)
        } else {
            binding.tvStatus.text = "Desconectado"
            binding.tvStatus.setTextColor(getColor(R.color.nv_error))
            binding.statusDot.setBackgroundResource(R.drawable.bg_dot_red)
            findViewById<android.view.View>(R.id.status_chip)?.setBackgroundResource(R.drawable.bg_status_pill_offline)
        }
    }

    private fun showStreamError() {
        updateStatus(false)
        binding.connectingOverlay.show()
        binding.overlayView.clearDetections()
        binding.tvDetection.text = "Sem imagem do ESP"
        binding.tvConfidence.text = ""
        binding.tvConfidence.hide()
        binding.tvDistance.text = "verifique o IP e o Wi‑Fi do ESP"
        binding.tvCount.text = ""
        binding.viewConfBar.animate().scaleX(0f).setDuration(120).start()
    }

    private fun stopStream() {
        streamJob?.cancel()
        streamJob = null
        analysisJob?.cancel()
        analysisJob = null
        mlKitProcessing = false
        try { connection?.disconnect() } catch (_: Exception) {}
        connection = null
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(Locale("pt", "BR"))
            ttsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED

            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    ttsSpeaking = true
                }

                override fun onDone(utteranceId: String?) {
                    onTtsFinished()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    onTtsFinished()
                }
            })

            if (ttsReady) {
                tts.setSpeechRate(1.0f)
                tts.setPitch(1.0f)
                speak("NeuroView conectado")
            }
        }
    }

    override fun onPause() {
        super.onPause()
        stopStream()
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized && streamJob == null) startStream()
    }

    override fun onDestroy() {
        stopStream()
        try { yoloDetector?.close() } catch (_: Exception) {}
        if (::mlKitDetector.isInitialized) {
            try { mlKitDetector.close() } catch (_: Exception) {}
        }
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        super.onDestroy()
    }
}
