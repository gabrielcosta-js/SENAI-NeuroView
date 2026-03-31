package com.example.neuroview;

import android.graphics.RectF;

/**
 * Representa um objeto detectado pelo YOLO.
 * Carrega: nome do objeto, nivel de confianca, posicao na tela, distancia estimada.
 */
public class Detection {

    private final String label;           // nome em portugues (ex: "pessoa")
    private final float confidence;       // 0.0 a 1.0
    private final RectF boundingBox;      // posicao e tamanho na tela (pixels)
    private final String distanceEstimate;// "muito perto", "perto", etc.

    public Detection(String label, float confidence, RectF boundingBox, String distanceEstimate) {
        this.label = label;
        this.confidence = confidence;
        this.boundingBox = boundingBox;
        this.distanceEstimate = distanceEstimate;
    }

    public String getLabel()            { return label; }
    public float getConfidence()        { return confidence; }
    public RectF getBoundingBox()       { return boundingBox; }
    public String getDistanceEstimate() { return distanceEstimate; }

    /** Texto que sera falado pelo TTS */
    public String getSpeakText() {
        return label + " a frente, " + distanceEstimate;
    }
}
