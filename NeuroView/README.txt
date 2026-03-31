===========================================================
  NEUROVIEW - Óculos Sensorial para Deficientes Visuais
  VERSÃO CORRIGIDA - Compatibilidade Gradle/AGP resolvida
===========================================================

CORREÇÕES APLICADAS NESTA VERSÃO:
----------------------------------
1. [CRÍTICO] Adicionado gradle/wrapper/gradle-wrapper.properties
   → O ZIP original não tinha esse arquivo, causando o erro de versão do Gradle
   → Agora aponta para Gradle 8.2.1 (compatível com AGP 8.2.2)

2. AGP atualizado de 8.2.0 → 8.2.2 (versão estável com patches)

3. Java VERSION_1_8 → VERSION_17
   → AGP 8+ exige Java 17 para compilar corretamente

4. aaptOptions { noCompress } → androidResources { noCompress }
   → Sintaxe correta para AGP 8.x (a antiga causava warnings de depreciação)

5. Adicionado bloco packaging {} com pickFirsts para .so do TFLite GPU
   → Evita erro "More than one file was found" ao usar GPU Delegate

6. TensorFlow Lite atualizado: 2.14.0 → 2.16.1
   → Versões mistas de tensorflow-lite + tensorflow-lite-gpu causavam UnsatisfiedLinkError

7. CameraX atualizado: 1.3.1 → 1.3.4
   → Corrige bugs de conversão YUV no Android 14

8. Adicionado tensorflow-lite-gpu-delegate-plugin:0.4.4
   → Necessário para inicialização correta do GPU Delegate

COMO USAR:
----------
1. Abra o Android Studio
2. File → Open → selecione a pasta NeuroView_corrigido
3. Aguarde o Gradle sync (vai baixar Gradle 8.2.1 na primeira vez)
4. Adicione o modelo YOLO em: app/src/main/assets/yolov8n_float32.tflite
   Download: https://huggingface.co/Ultralytics/YOLOv8/resolve/main/yolov8n_float32.tflite
5. Conecte um celular Android (API 24+) ou use o emulador
6. Run → Run 'app'

REQUISITOS:
-----------
- Android Studio Hedgehog (2023.1.1) ou mais recente
- JDK 17 (File → Project Structure → SDK Location → JDK Location)
- Android SDK API 34
- Celular com Android 7.0+ (API 24)

SE O SYNC AINDA FALHAR:
------------------------
- File → Invalidate Caches and Restart
- Verifique: File → Project Structure → JDK version deve ser 17
- Verifique conexão com internet (Gradle precisa baixar dependências)
