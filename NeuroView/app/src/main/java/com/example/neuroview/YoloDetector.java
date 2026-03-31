package com.example.neuroview;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.util.Log;
import org.tensorflow.lite.Interpreter;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class YoloDetector {

    private static final String TAG = "YoloDetector";
    private static final String ARQUIVO_MODELO   = "yolov8n_float32.tflite";
    private static final String ARQUIVO_LABELS   = "labels.txt";
    private static final int    TAMANHO_ENTRADA  = 640;
    private static final float  LIMIAR_CONFIANCA = 0.45f;
    private static final float  LIMIAR_IOU       = 0.50f;
    private static final int    NUM_CLASSES      = 80;
    private static final int    MAX_DETECCOES    = 10;

    private Interpreter interpreter;
    private List<String> labels;
    private boolean modeloCarregado = false;
    private boolean saidaTransposta = false;

    public YoloDetector(Context context) {
        try {
            Interpreter.Options opcoes = new Interpreter.Options();
            opcoes.setNumThreads(4);

            // FIX CRITICO: GpuDelegate lanca UnsatisfiedLinkError (Error, nao Exception).
            // catch(Exception) nao captura Error — o app crashava aqui silenciosamente.
            // Solucao: usar catch(Throwable) para capturar qualquer tipo de falha.
            try {
                org.tensorflow.lite.gpu.GpuDelegate gpu =
                    new org.tensorflow.lite.gpu.GpuDelegate();
                opcoes.addDelegate(gpu);
                Log.i(TAG, "GPU Delegate ativado");
            } catch (Throwable t) {
                Log.w(TAG, "GPU indisponivel, usando CPU: " + t.getMessage());
            }

            MappedByteBuffer modeloBuffer = carregarModelo(context);
            interpreter = new Interpreter(modeloBuffer, opcoes);
            labels = carregarLabels(context);

            int[] formatoSaida = interpreter.getOutputTensor(0).shape();
            Log.i(TAG, "Formato de saida: " + Arrays.toString(formatoSaida));
            saidaTransposta = (formatoSaida.length >= 3 && formatoSaida[1] == NUM_CLASSES + 4);

            modeloCarregado = true;
            Log.i(TAG, "YoloDetector pronto! Labels: " + labels.size());

        } catch (IOException e) {
            Log.e(TAG, "Modelo nao encontrado em assets/: " + e.getMessage());
            modeloCarregado = false;
        } catch (Throwable t) {
            Log.e(TAG, "Erro inesperado ao inicializar: " + t.getMessage());
            modeloCarregado = false;
        }
    }

    private MappedByteBuffer carregarModelo(Context context) throws IOException {
        AssetFileDescriptor fd = context.getAssets().openFd(ARQUIVO_MODELO);
        FileInputStream fis = new FileInputStream(fd.getFileDescriptor());
        FileChannel canal = fis.getChannel();
        return canal.map(FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getDeclaredLength());
    }

    private List<String> carregarLabels(Context context) throws IOException {
        List<String> lista = new ArrayList<>();
        BufferedReader br = new BufferedReader(
            new InputStreamReader(context.getAssets().open(ARQUIVO_LABELS)));
        String linha;
        while ((linha = br.readLine()) != null) {
            String s = linha.trim();
            if (!s.isEmpty()) lista.add(s);
        }
        br.close();
        return lista;
    }

    public List<Detection> detectar(Bitmap bitmap) {
        if (!modeloCarregado || interpreter == null) return new ArrayList<>();

        Bitmap redimensionado = Bitmap.createScaledBitmap(
            bitmap, TAMANHO_ENTRADA, TAMANHO_ENTRADA, true);

        ByteBuffer entrada = ByteBuffer.allocateDirect(
            1 * TAMANHO_ENTRADA * TAMANHO_ENTRADA * 3 * Float.BYTES);
        entrada.order(ByteOrder.nativeOrder());

        int[] pixels = new int[TAMANHO_ENTRADA * TAMANHO_ENTRADA];
        redimensionado.getPixels(pixels, 0, TAMANHO_ENTRADA, 0, 0, TAMANHO_ENTRADA, TAMANHO_ENTRADA);
        for (int pixel : pixels) {
            entrada.putFloat(((pixel >> 16) & 0xFF) / 255.0f);
            entrada.putFloat(((pixel >>  8) & 0xFF) / 255.0f);
            entrada.putFloat(( pixel        & 0xFF) / 255.0f);
        }

        List<Detection> deteccoes;
        try {
            if (saidaTransposta) {
                float[][][] saida = new float[1][84][8400];
                interpreter.run(entrada, saida);
                deteccoes = interpretarSaidaTransposta(saida, bitmap.getWidth(), bitmap.getHeight());
            } else {
                float[][][] saida = new float[1][8400][84];
                interpreter.run(entrada, saida);
                deteccoes = interpretarSaidaNormal(saida, bitmap.getWidth(), bitmap.getHeight());
            }
        } catch (Throwable t) {
            Log.e(TAG, "Erro durante inferencia: " + t.getMessage());
            deteccoes = new ArrayList<>();
        }

        if (redimensionado != bitmap && !redimensionado.isRecycled()) {
            redimensionado.recycle();
        }
        return aplicarNMS(deteccoes);
    }

    private List<Detection> interpretarSaidaTransposta(float[][][] saida, int larg, int alt) {
        List<Detection> r = new ArrayList<>();
        for (int i = 0; i < saida[0][0].length; i++) {
            float cx = saida[0][0][i], cy = saida[0][1][i];
            float w  = saida[0][2][i], h  = saida[0][3][i];
            float max = 0; int cls = 0;
            for (int c = 0; c < NUM_CLASSES; c++) {
                if (saida[0][4+c][i] > max) { max = saida[0][4+c][i]; cls = c; }
            }
            if (max >= LIMIAR_CONFIANCA) r.add(construirDeteccao(cx,cy,w,h,max,cls,larg,alt));
        }
        return r;
    }

    private List<Detection> interpretarSaidaNormal(float[][][] saida, int larg, int alt) {
        List<Detection> r = new ArrayList<>();
        for (int i = 0; i < saida[0].length; i++) {
            float cx = saida[0][i][0], cy = saida[0][i][1];
            float w  = saida[0][i][2], h  = saida[0][i][3];
            float max = 0; int cls = 0;
            for (int c = 0; c < NUM_CLASSES; c++) {
                if (saida[0][i][4+c] > max) { max = saida[0][i][4+c]; cls = c; }
            }
            if (max >= LIMIAR_CONFIANCA) r.add(construirDeteccao(cx,cy,w,h,max,cls,larg,alt));
        }
        return r;
    }

    private Detection construirDeteccao(float cx, float cy, float w, float h,
                                         float score, int cls, int larg, int alt) {
        float x1 = Math.max(0f, (cx - w/2f) * larg);
        float y1 = Math.max(0f, (cy - h/2f) * alt);
        float x2 = Math.min(larg, (cx + w/2f) * larg);
        float y2 = Math.min(alt,  (cy + h/2f) * alt);
        String nome = (cls < labels.size()) ? labels.get(cls) : "objeto";
        return new Detection(traduzirLabel(nome), score, new RectF(x1,y1,x2,y2), estimarDistancia(w));
    }

    private String estimarDistancia(float w) {
        if (w > 0.55f) return "muito perto";
        if (w > 0.28f) return "perto";
        if (w > 0.10f) return "distancia media";
        return "longe";
    }

    private List<Detection> aplicarNMS(List<Detection> d) {
        if (d.isEmpty()) return d;
        d.sort((a, b) -> Float.compare(b.getConfidence(), a.getConfidence()));
        List<Detection> res = new ArrayList<>();
        boolean[] sup = new boolean[d.size()];
        for (int i = 0; i < d.size(); i++) {
            if (sup[i]) continue;
            res.add(d.get(i));
            if (res.size() >= MAX_DETECCOES) break;
            for (int j = i+1; j < d.size(); j++) {
                if (!sup[j] && calcularIOU(d.get(i).getBoundingBox(), d.get(j).getBoundingBox()) > LIMIAR_IOU)
                    sup[j] = true;
            }
        }
        return res;
    }

    private float calcularIOU(RectF a, RectF b) {
        float esq = Math.max(a.left,b.left), top = Math.max(a.top,b.top);
        float dir = Math.min(a.right,b.right), bot = Math.min(a.bottom,b.bottom);
        if (dir<=esq||bot<=top) return 0f;
        float inter = (dir-esq)*(bot-top);
        float uniao = a.width()*a.height()+b.width()*b.height()-inter;
        return (uniao>0f)?inter/uniao:0f;
    }

    private String traduzirLabel(String label) {
        switch (label.toLowerCase().trim()) {
            case "person": return "pessoa";
            case "bicycle": return "bicicleta";
            case "car": return "carro";
            case "motorcycle": return "moto";
            case "airplane": return "aviao";
            case "bus": return "onibus";
            case "train": return "trem";
            case "truck": return "caminhao";
            case "boat": return "barco";
            case "traffic light": return "semaforo";
            case "fire hydrant": return "hidrante";
            case "stop sign": return "placa de pare";
            case "bench": return "banco";
            case "bird": return "passaro";
            case "cat": return "gato";
            case "dog": return "cachorro";
            case "horse": return "cavalo";
            case "sheep": return "ovelha";
            case "cow": return "vaca";
            case "elephant": return "elefante";
            case "bear": return "urso";
            case "zebra": return "zebra";
            case "giraffe": return "girafa";
            case "backpack": return "mochila";
            case "umbrella": return "guarda chuva";
            case "handbag": return "bolsa";
            case "suitcase": return "mala";
            case "sports ball": return "bola";
            case "skateboard": return "skate";
            case "surfboard": return "prancha";
            case "tennis racket": return "raquete";
            case "bottle": return "garrafa";
            case "wine glass": return "taca";
            case "cup": return "copo";
            case "fork": return "garfo";
            case "knife": return "faca";
            case "spoon": return "colher";
            case "bowl": return "tigela";
            case "banana": return "banana";
            case "apple": return "maca";
            case "sandwich": return "sanduiche";
            case "orange": return "laranja";
            case "broccoli": return "brocolis";
            case "carrot": return "cenoura";
            case "hot dog": return "cachorro quente";
            case "pizza": return "pizza";
            case "donut": return "rosquinha";
            case "cake": return "bolo";
            case "chair": return "cadeira";
            case "couch": return "sofa";
            case "potted plant": return "planta";
            case "bed": return "cama";
            case "dining table": return "mesa";
            case "toilet": return "vaso sanitario";
            case "tv": return "televisao";
            case "laptop": return "computador";
            case "mouse": return "mouse";
            case "remote": return "controle remoto";
            case "keyboard": return "teclado";
            case "cell phone": return "celular";
            case "microwave": return "micro ondas";
            case "oven": return "forno";
            case "toaster": return "torradeira";
            case "sink": return "pia";
            case "refrigerator": return "geladeira";
            case "book": return "livro";
            case "clock": return "relogio";
            case "vase": return "vaso";
            case "scissors": return "tesoura";
            case "teddy bear": return "ursinho";
            case "hair drier": return "secador de cabelo";
            case "toothbrush": return "escova de dente";
            default: return label;
        }
    }

    public boolean isModeloCarregado() { return modeloCarregado; }

    public void fechar() {
        if (interpreter != null) { interpreter.close(); interpreter = null; }
    }
}
