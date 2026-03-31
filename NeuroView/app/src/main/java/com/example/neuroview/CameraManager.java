package com.example.neuroview;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.util.Log;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Encapsula toda a lógica do CameraX.
 *
 * FIX: Removida a dupla chamada a ProcessCameraProvider.getInstance().
 * O original chamava getInstance() duas vezes — a segunda com .get() bloqueante
 * dentro do listener, o que causava deadlock em alguns dispositivos.
 */
public class CameraManager {

    private static final String TAG = "CameraManager";

    public interface CallbackFrame {
        void onFrame(Bitmap bitmap);
    }

    private final Context context;
    private final LifecycleOwner lifecycleOwner;
    private final PreviewView previewView;
    private final CallbackFrame callback;
    private ExecutorService executorCamera;

    public CameraManager(Context context, LifecycleOwner lifecycleOwner,
                         PreviewView previewView, CallbackFrame callback) {
        this.context = context;
        this.lifecycleOwner = lifecycleOwner;
        this.previewView = previewView;
        this.callback = callback;
        this.executorCamera = Executors.newSingleThreadExecutor();
    }

    public void iniciarCamera() {
        // FIX: Guardamos a future em variável e usamos ela dentro do listener,
        // evitando chamar getInstance() duas vezes (deadlock no original).
        ListenableFuture<ProcessCameraProvider> future =
            ProcessCameraProvider.getInstance(context);

        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get(); // usa a mesma future
                vincularCamera(provider);
            } catch (Exception e) {
                Log.e(TAG, "Erro ao obter CameraProvider: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(context));
    }

    private void vincularCamera(ProcessCameraProvider provider) {
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis analise = new ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build();

        analise.setAnalyzer(executorCamera, imageProxy -> {
            Bitmap bitmap = converterParaBitmap(imageProxy);
            imageProxy.close();
            if (bitmap != null) {
                callback.onFrame(bitmap);
            }
        });

        try {
            provider.unbindAll();
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analise
            );
            Log.i(TAG, "Camera vinculada com sucesso");
        } catch (Exception e) {
            Log.e(TAG, "Falha ao vincular camera: " + e.getMessage());
        }
    }

    private Bitmap converterParaBitmap(ImageProxy imageProxy) {
        try {
            Image img = imageProxy.getImage();
            if (img == null) return null;

            Image.Plane[] planos = img.getPlanes();
            ByteBuffer bufY = planos[0].getBuffer();
            ByteBuffer bufU = planos[1].getBuffer();
            ByteBuffer bufV = planos[2].getBuffer();

            int tamY = bufY.remaining();
            int tamU = bufU.remaining();
            int tamV = bufV.remaining();

            byte[] nv21 = new byte[tamY + tamU + tamV];
            bufY.get(nv21, 0, tamY);
            bufV.get(nv21, tamY, tamV);
            bufU.get(nv21, tamY + tamV, tamU);

            YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21,
                img.getWidth(), img.getHeight(), null);
            ByteArrayOutputStream saida = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(
                new Rect(0, 0, img.getWidth(), img.getHeight()), 85, saida);
            byte[] bytesJpeg = saida.toByteArray();
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytesJpeg, 0, bytesJpeg.length);

            int rotacao = imageProxy.getImageInfo().getRotationDegrees();
            if (rotacao != 0) {
                Matrix matriz = new Matrix();
                matriz.postRotate(rotacao);
                bitmap = Bitmap.createBitmap(
                    bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matriz, true);
            }
            return bitmap;

        } catch (Exception e) {
            Log.e(TAG, "Erro ao converter frame: " + e.getMessage());
            return null;
        }
    }

    public void encerrar() {
        if (executorCamera != null && !executorCamera.isShutdown()) {
            executorCamera.shutdown();
        }
    }
}
