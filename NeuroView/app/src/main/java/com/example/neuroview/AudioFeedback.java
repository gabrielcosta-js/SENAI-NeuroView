package com.example.neuroview;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import java.util.List;
import java.util.Locale;

/**
 * Gerencia o sistema de audio (Text-to-Speech).
 *
 * Regras inteligentes para evitar spam de audio:
 *  - So repete o mesmo objeto apos 3 segundos
 *  - Objetos diferentes interrompem imediatamente
 *  - Prioriza o objeto com maior confianca
 */
public class AudioFeedback {

    private static final String TAG = "AudioFeedback";
    private static final long INTERVALO_MINIMO_MS = 3000; // 3 segundos entre falas

    private TextToSpeech tts;
    private boolean pronto = false;
    private long ultimaFalaMs = 0;
    private String ultimoObjeto = "";

    public AudioFeedback(Context context) {
        tts = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = tts.setLanguage(new Locale("pt", "BR"));
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "Portugues BR nao disponivel, usando idioma padrao");
                    tts.setLanguage(Locale.getDefault());
                }
                tts.setSpeechRate(0.85f);  // um pouco mais devagar para clareza
                tts.setPitch(1.0f);
                pronto = true;
                Log.i(TAG, "TTS iniciado com sucesso");
            } else {
                Log.e(TAG, "Falha ao iniciar TTS: " + status);
            }
        });
    }

    /**
     * Anuncia o objeto mais importante da lista de deteccoes.
     * Chamado a cada ciclo de deteccao.
     */
    public void anunciarDeteccoes(List<Detection> deteccoes) {
        if (!pronto || deteccoes == null || deteccoes.isEmpty()) return;

        // O primeiro da lista ja e o de maior confianca (ordenado no NMS)
        Detection melhor = deteccoes.get(0);
        String objeto = melhor.getLabel();
        long agora = System.currentTimeMillis();

        boolean objetoDiferente = !objeto.equals(ultimoObjeto);
        boolean tempoSuficiente = (agora - ultimaFalaMs) >= INTERVALO_MINIMO_MS;

        if (objetoDiferente || tempoSuficiente) {
            String texto = "Atencao! " + melhor.getSpeakText();
            tts.speak(texto, TextToSpeech.QUEUE_FLUSH, null, "det_" + agora);
            ultimaFalaMs = agora;
            ultimoObjeto = objeto;
            Log.d(TAG, "Falando: " + texto);
        }
    }

    /** Fala um texto qualquer imediatamente (usado para mensagens do sistema) */
    public void falar(String texto) {
        if (pronto) {
            tts.speak(texto, TextToSpeech.QUEUE_FLUSH, null, null);
        }
    }

    public void encerrar() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }
}
