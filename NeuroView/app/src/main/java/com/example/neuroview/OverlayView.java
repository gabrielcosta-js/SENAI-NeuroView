package com.example.neuroview;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/**
 * View transparente desenhada por cima do PreviewView da camera.
 * Responsavel por desenhar os retangulos (bounding boxes) e labels
 * em volta dos objetos detectados.
 *
 * Cores por nivel de confianca:
 *   Verde  = alta confianca (>= 70%)
 *   Laranja = media confianca (>= 50%)
 *   Vermelho = baixa confianca (< 50%)
 */
public class OverlayView extends View {

    private List<Detection> deteccoes = new ArrayList<>();

    private final Paint paintBorda = new Paint();
    private final Paint paintTexto = new Paint();
    private final Paint paintFundoTexto = new Paint();

    private static final int COR_ALTA    = Color.parseColor("#00FF44");
    private static final int COR_MEDIA   = Color.parseColor("#FFAA00");
    private static final int COR_BAIXA   = Color.parseColor("#FF3300");

    public OverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        inicializar();
    }

    public OverlayView(Context context) {
        super(context);
        inicializar();
    }

    private void inicializar() {
        paintBorda.setStyle(Paint.Style.STROKE);
        paintBorda.setStrokeWidth(5f);
        paintBorda.setAntiAlias(true);

        paintTexto.setColor(Color.WHITE);
        paintTexto.setTextSize(40f);
        paintTexto.setAntiAlias(true);
        paintTexto.setStyle(Paint.Style.FILL);
        paintTexto.setFakeBoldText(true);

        paintFundoTexto.setStyle(Paint.Style.FILL);
        paintFundoTexto.setAntiAlias(true);
    }

    /** Chamado pela MainActivity quando ha novas deteccoes */
    public void setDeteccoes(List<Detection> deteccoes) {
        this.deteccoes = (deteccoes != null) ? deteccoes : new ArrayList<>();
        invalidate(); // dispara onDraw
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        for (Detection d : deteccoes) {
            float conf = d.getConfidence();

            // Seleciona cor baseada na confianca
            int cor;
            if (conf >= 0.70f)      cor = COR_ALTA;
            else if (conf >= 0.50f) cor = COR_MEDIA;
            else                    cor = COR_BAIXA;

            paintBorda.setColor(cor);

            // Desenha o retangulo ao redor do objeto
            RectF box = d.getBoundingBox();
            canvas.drawRoundRect(box, 10f, 10f, paintBorda);

            // Monta o texto do label: "pessoa 87%"
            String labelTexto = d.getLabel() + "  " + String.format("%.0f%%", conf * 100);
            float larguraTexto = paintTexto.measureText(labelTexto);

            // Posicao do label (acima do bounding box)
            float topoLabel = Math.max(box.top - 52f, 0f);

            // Fundo do label (colorido)
            paintFundoTexto.setColor(cor);
            canvas.drawRect(
                box.left,
                topoLabel,
                box.left + larguraTexto + 20f,
                topoLabel + 52f,
                paintFundoTexto
            );

            // Texto branco sobre o fundo
            canvas.drawText(labelTexto, box.left + 10f, topoLabel + 40f, paintTexto);
        }
    }
}
