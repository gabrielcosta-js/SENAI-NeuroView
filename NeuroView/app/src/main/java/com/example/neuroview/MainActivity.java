package com.example.neuroview;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Activity principal — orquestra todos os módulos do app.
 *
 * FIX: YoloDetector agora é inicializado em background thread para não
 * travar (e crashar) a main thread durante o carregamento do modelo TFLite.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int PERM_CAMERA = 100;

    private PreviewView previewView;
    private OverlayView overlayView;
    private TextView tvModelStatus;
    private TextView tvDetectedObject;
    private TextView tvDistance;

    private CameraManager cameraManager;
    private YoloDetector yoloDetector;
    private AudioFeedback audioFeedback;

    private final Handler handlerUI = new Handler(Looper.getMainLooper());

    // FIX: executor dedicado para inicializar o modelo em background
    private final ExecutorService executorInit = Executors.newSingleThreadExecutor();

    private volatile boolean processando = false;
    // FIX: flag para saber se o modelo terminou de carregar antes de iniciar câmera
    private volatile boolean modeloCarregandoBackground = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        previewView      = findViewById(R.id.previewView);
        overlayView      = findViewById(R.id.overlayView);
        tvModelStatus    = findViewById(R.id.tvModelStatus);
        tvDetectedObject = findViewById(R.id.tvDetectedObject);
        tvDistance       = findViewById(R.id.tvDistance);

        // AudioFeedback pode ser iniciado na main thread (é async internamente)
        audioFeedback = new AudioFeedback(this);

        // FIX: YoloDetector carregado em background — evita travar a UI e crash por ANR
        tvModelStatus.setText("Carregando modelo YOLO...");
        tvDetectedObject.setText("Aguarde, inicializando IA...");

        executorInit.execute(() -> {
            // Roda em background thread
            YoloDetector detector = new YoloDetector(MainActivity.this);

            // Volta pra UI thread para atualizar estado
            handlerUI.post(() -> {
                yoloDetector = detector;
                modeloCarregandoBackground = false;

                if (yoloDetector.isModeloCarregado()) {
                    tvModelStatus.setText("Modelo YOLO: OK ✓");
                    tvDetectedObject.setText("Aguardando deteccao...");
                    Log.i(TAG, "Modelo carregado com sucesso");
                } else {
                    tvModelStatus.setText("ERRO: adicione yolov8n_float32.tflite em assets/");
                    tvDetectedObject.setText("Sem modelo — camera ativa mas sem deteccao");
                    Toast.makeText(MainActivity.this,
                        "Modelo nao encontrado! Adicione yolov8n_float32.tflite em assets/.",
                        Toast.LENGTH_LONG).show();
                }

                // Só inicia a câmera APÓS o modelo estar pronto (ou confirmado ausente)
                if (temPermissaoCamera()) {
                    iniciarCamera();
                } else {
                    ActivityCompat.requestPermissions(
                        MainActivity.this,
                        new String[]{Manifest.permission.CAMERA},
                        PERM_CAMERA
                    );
                }
            });
        });

        // FIX: solicita permissão logo no início (enquanto modelo carrega em background)
        // A câmera só é iniciada de fato quando o modelo terminar de carregar
        if (!temPermissaoCamera()) {
            ActivityCompat.requestPermissions(
                this,
                new String[]{Manifest.permission.CAMERA},
                PERM_CAMERA
            );
        }
    }

    private boolean temPermissaoCamera() {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void iniciarCamera() {
        if (cameraManager != null) return; // evita iniciar duas vezes
        cameraManager = new CameraManager(
            this, this, previewView,
            bitmap -> {
                // Só processa se modelo carregou e não está processando outro frame
                if (!processando && yoloDetector != null
                        && yoloDetector.isModeloCarregado() && !modeloCarregandoBackground) {
                    processando = true;
                    processarFrame(bitmap);
                }
            }
        );
        cameraManager.iniciarCamera();
        Log.i(TAG, "Camera iniciada");
    }

    private void processarFrame(Bitmap bitmap) {
        List<Detection> deteccoes = yoloDetector.detectar(bitmap);
        handlerUI.post(() -> {
            atualizarUI(deteccoes);
            processando = false;
        });
    }

    private void atualizarUI(List<Detection> deteccoes) {
        overlayView.setDeteccoes(deteccoes);
        if (!deteccoes.isEmpty()) {
            Detection melhor = deteccoes.get(0);
            tvDetectedObject.setText(melhor.getLabel().toUpperCase());
            tvDistance.setText(melhor.getDistanceEstimate()
                + "  |  " + String.format("%.0f%%", melhor.getConfidence() * 100) + " confianca");
            audioFeedback.anunciarDeteccoes(deteccoes);
        } else {
            tvDetectedObject.setText("Nenhum objeto detectado");
            tvDistance.setText("");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERM_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Só inicia câmera se o modelo já terminou de carregar
                if (!modeloCarregandoBackground) {
                    iniciarCamera();
                }
                // Caso contrário, o callback do executorInit vai chamar iniciarCamera()
            } else {
                tvDetectedObject.setText("Permissao de camera negada!");
                Toast.makeText(this,
                    "O app precisa da camera para funcionar.",
                    Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executorInit.shutdown();
        if (cameraManager != null) cameraManager.encerrar();
        if (yoloDetector  != null) yoloDetector.fechar();
        if (audioFeedback != null) audioFeedback.encerrar();
    }
}
