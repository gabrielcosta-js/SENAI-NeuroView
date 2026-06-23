/*
 * NeuroView — Firmware ESP32-CAM
 * ===============================
 * Autor: Equipe NeuroView
 * Versão: 1.0
 *
 * Funcionalidades:
 *  - Conecta no Wi-Fi do roteador/celular como estação (STA)
 *  - Stream MJPEG na porta 81 → http://IP_DO_ESP:81/stream
 *  - Painel de configuração na porta 80 → http://IP_DO_ESP
 *  - Se não conseguir conectar no Wi-Fi, cria fallback AP: NeuroView-AP
 *  - LED de status (GPIO 33 — LED azul interno do AI-Thinker)
 *  - Botão opcional para reset (GPIO 0)
 *
 * Hardware suportado:
 *  - AI-Thinker ESP32-CAM (padrão)
 *  - Outros módulos: ajuste os pinos da câmera abaixo
 *
 * INSTALAÇÃO no Arduino IDE:
 *  1. Adicione a URL de placas ESP32:
 *     https://raw.githubusercontent.com/espressif/arduino-esp32/gh-pages/package_esp32_index.json
 *  2. Ferramentas → Gerenciar Placas → Instale "esp32 by Espressif Systems"
 *  3. Ferramentas → Placa → ESP32 Arduino → AI Thinker ESP32-CAM
 *  4. Ferramentas → Partition Scheme → Huge APP (3MB No OTA/1MB SPIFFS)
 *  5. Ferramentas → Porta → selecione a porta COM do seu módulo
 *  6. Faça upload deste sketch
 */

#include "esp_camera.h"
#include <WiFi.h>
#include "esp_http_server.h"
#include "esp_timer.h"
#include "img_converters.h"
#include "Arduino.h"

// ─────────────────────────────────────────────
//  CONFIGURAÇÃO DA REDE WI-FI DO ROTEADOR / HOTSPOT
// ─────────────────────────────────────────────
// IMPORTANTE:
// Coloque aqui o nome e a senha do Wi-Fi onde o celular também estará conectado.
// Assim o celular continua com internet e ainda consegue acessar o ESP32-CAM.
const char* WIFI_SSID     = "COLOQUE_AQUI_O_NOME_DO_WIFI";
const char* WIFI_PASSWORD = "COLOQUE_AQUI_A_SENHA_DO_WIFI";

// Fallback: se o ESP não conseguir entrar no Wi-Fi acima, ele cria essa rede própria.
// Nesse modo antigo, o celular precisa conectar em NeuroView-AP e usar 192.168.4.1.
const char* FALLBACK_AP_SSID     = "NeuroView-AP";
const char* FALLBACK_AP_PASSWORD = "neuroview123";
IPAddress   FALLBACK_AP_IP(192, 168, 4, 1);
IPAddress   FALLBACK_AP_GATEWAY(192, 168, 4, 1);
IPAddress   FALLBACK_AP_SUBNET(255, 255, 255, 0);

bool fallbackApMode = false;

// ─────────────────────────────────────────────
//  PINOS DA CÂMERA — AI-Thinker ESP32-CAM
// ─────────────────────────────────────────────
#define PWDN_GPIO_NUM     32
#define RESET_GPIO_NUM    -1
#define XCLK_GPIO_NUM      0
#define SIOD_GPIO_NUM     26
#define SIOC_GPIO_NUM     27
#define Y9_GPIO_NUM       35
#define Y8_GPIO_NUM       34
#define Y7_GPIO_NUM       39
#define Y6_GPIO_NUM       36
#define Y5_GPIO_NUM       21
#define Y4_GPIO_NUM       19
#define Y3_GPIO_NUM       18
#define Y2_GPIO_NUM        5
#define VSYNC_GPIO_NUM    25
#define HREF_GPIO_NUM     23
#define PCLK_GPIO_NUM     22

// ─────────────────────────────────────────────
//  CONFIGURAÇÕES DE STREAM
// ─────────────────────────────────────────────
#define STREAM_PORT       81       // Porta do stream MJPEG
#define CONFIG_PORT       80       // Porta do painel web
#define LED_PIN           33       // LED azul interno (ativo em LOW)
#define FLASH_PIN          4       // LED flash branco

// Qualidade da imagem: 0 (melhor) a 63 (pior)
// Para melhor performance no app, use 12-20
#define CAM_QUALITY       12

// Resolução: FRAMESIZE_QVGA(320x240), FRAMESIZE_VGA(640x480),
//            FRAMESIZE_SVGA(800x600), FRAMESIZE_XGA(1024x768)
// Recomendado para mobile: FRAMESIZE_VGA
#define CAM_FRAMESIZE     FRAMESIZE_VGA

// ─────────────────────────────────────────────
//  HANDLES DOS SERVIDORES HTTP
// ─────────────────────────────────────────────
httpd_handle_t stream_httpd = NULL;
httpd_handle_t config_httpd = NULL;

// ─────────────────────────────────────────────
//  HANDLER DO STREAM MJPEG
// ─────────────────────────────────────────────
#define PART_BOUNDARY "123456789000000000000987654321"
static const char* STREAM_CONTENT_TYPE =
    "multipart/x-mixed-replace;boundary=" PART_BOUNDARY;
static const char* STREAM_BOUNDARY =
    "\r\n--" PART_BOUNDARY "\r\n";
static const char* STREAM_PART =
    "Content-Type: image/jpeg\r\nContent-Length: %u\r\n\r\n";

esp_err_t stream_handler(httpd_req_t* req) {
    camera_fb_t* fb = NULL;
    esp_err_t res = ESP_OK;
    size_t _jpg_buf_len = 0;
    uint8_t* _jpg_buf = NULL;
    char part_buf[128];

    res = httpd_resp_set_type(req, STREAM_CONTENT_TYPE);
    if (res != ESP_OK) return res;

    // Permite CORS para o app
    httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");
    httpd_resp_set_hdr(req, "X-Framerate", "60");

    // Pisca LED para indicar stream ativo
    digitalWrite(LED_PIN, LOW);

    while (true) {
        fb = esp_camera_fb_get();
        if (!fb) {
            Serial.println("[ERRO] Falha ao capturar frame");
            res = ESP_FAIL;
            break;
        }

        if (fb->format != PIXFORMAT_JPEG) {
            bool jpeg_converted = frame2jpg(fb, 80, &_jpg_buf, &_jpg_buf_len);
            esp_camera_fb_return(fb);
            fb = NULL;
            if (!jpeg_converted) {
                Serial.println("[ERRO] Conversão JPEG falhou");
                res = ESP_FAIL;
                break;
            }
        } else {
            _jpg_buf_len = fb->len;
            _jpg_buf = fb->buf;
        }

        // Envia boundary
        res = httpd_resp_send_chunk(req, STREAM_BOUNDARY, strlen(STREAM_BOUNDARY));
        if (res != ESP_OK) break;

        // Envia header da parte
        size_t hlen = snprintf((char*)part_buf, 128, STREAM_PART, _jpg_buf_len);
        res = httpd_resp_send_chunk(req, (const char*)part_buf, hlen);
        if (res != ESP_OK) break;

        // Envia dados JPEG
        res = httpd_resp_send_chunk(req, (const char*)_jpg_buf, _jpg_buf_len);

        if (fb) {
            esp_camera_fb_return(fb);
            fb = NULL;
            _jpg_buf = NULL;
        } else if (_jpg_buf) {
            free(_jpg_buf);
            _jpg_buf = NULL;
        }

        if (res != ESP_OK) break;
    }

    digitalWrite(LED_PIN, HIGH); // Apaga LED quando stream para
    return res;
}

// ─────────────────────────────────────────────
//  HANDLER DA PÁGINA WEB (porta 80)
// ─────────────────────────────────────────────
esp_err_t index_handler(httpd_req_t* req) {
    const char* html = R"rawhtml(
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>NeuroView</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body { background: #0a0a0a; color: #fff; font-family: sans-serif; padding: 24px; }
        h1 { color: #4ade80; font-size: 22px; letter-spacing: 4px; margin-bottom: 4px; }
        p { color: #888; font-size: 13px; margin-bottom: 24px; }
        .card { background: #141414; border: 1px solid #2a2a2a; border-radius: 12px; padding: 20px; margin-bottom: 16px; }
        .label { color: #555; font-size: 11px; letter-spacing: 2px; margin-bottom: 6px; }
        .value { color: #fff; font-size: 15px; font-family: monospace; }
        .green { color: #4ade80; }
        img { width: 100%; border-radius: 8px; margin-top: 12px; }
        .dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; background: #4ade80; margin-right: 6px; }
    </style>
</head>
<body>
    <h1>NEUROVIEW</h1>
    <p>&#Óculos Sensorial Inteligente</p>

    <div class="card">
        <div class="label">STATUS</div>
        <div class="value"><span class="dot"></span><span class="green">Online</span></div>
    </div>

    <div class="card">
        <div class="label">STREAM URL</div>
        <div class="value">Use o IP exibido no Monitor Serial: http://IP_DO_ESP:81/stream</div>
    </div>

    <div class="card">
        <div class="label">REDE WI-FI</div>
        <div class="value">Mesmo Wi-Fi do celular/roteador</div>
    </div>

    <div class="card">
        <div class="label">PREVIEW AO VIVO</div>
        <img src="/stream" alt="Stream">
    </div>
</body>
</html>
)rawhtml";

    httpd_resp_set_type(req, "text/html");
    httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");
    return httpd_resp_send(req, html, strlen(html));
}

// ─────────────────────────────────────────────
//  FUNÇÕES AUXILIARES DE REDE
// ─────────────────────────────────────────────
String currentIpString() {
    if (WiFi.status() == WL_CONNECTED) {
        return WiFi.localIP().toString();
    }
    return WiFi.softAPIP().toString();
}

bool connectToRouterWifi() {
    Serial.println("[...] Conectando ao Wi-Fi do roteador/celular...");
    WiFi.mode(WIFI_STA);
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);

    unsigned long startAttempt = millis();
    while (WiFi.status() != WL_CONNECTED && millis() - startAttempt < 20000) {
        Serial.print(".");
        delay(500);
    }
    Serial.println();

    if (WiFi.status() == WL_CONNECTED) {
        fallbackApMode = false;
        Serial.println("[OK] ESP conectado ao Wi-Fi principal");
        Serial.printf("     SSID   : %s\n", WIFI_SSID);
        Serial.printf("     IP     : %s\n", WiFi.localIP().toString().c_str());
        Serial.printf("     Gateway: %s\n", WiFi.gatewayIP().toString().c_str());
        return true;
    }

    Serial.println("[AVL] Não consegui conectar ao Wi-Fi principal");
    return false;
}

void startFallbackAccessPoint() {
    fallbackApMode = true;
    Serial.println("[...] Criando rede fallback NeuroView-AP...");
    WiFi.mode(WIFI_AP);
    WiFi.softAPConfig(FALLBACK_AP_IP, FALLBACK_AP_GATEWAY, FALLBACK_AP_SUBNET);
    WiFi.softAP(FALLBACK_AP_SSID, FALLBACK_AP_PASSWORD);

    Serial.println("[OK] Rede fallback criada:");
    Serial.printf("     SSID   : %s\n", FALLBACK_AP_SSID);
    Serial.printf("     Senha  : %s\n", FALLBACK_AP_PASSWORD);
    Serial.printf("     IP     : %s\n", WiFi.softAPIP().toString().c_str());
}

// ─────────────────────────────────────────────
//  INICIALIZAÇÃO DOS SERVIDORES HTTP
// ─────────────────────────────────────────────
void startStreamServer() {
    httpd_config_t config = HTTPD_DEFAULT_CONFIG();
    config.server_port = STREAM_PORT;
    config.ctrl_port   = 32769;

    httpd_uri_t stream_uri = {
        .uri       = "/stream",
        .method    = HTTP_GET,
        .handler   = stream_handler,
        .user_ctx  = NULL
    };

    if (httpd_start(&stream_httpd, &config) == ESP_OK) {
        httpd_register_uri_handler(stream_httpd, &stream_uri);
        Serial.printf("[OK] Stream disponível em: http://%s:%d/stream\n",
                      currentIpString().c_str(), STREAM_PORT);
    } else {
        Serial.println("[ERRO] Falha ao iniciar servidor de stream");
    }
}

void startConfigServer() {
    httpd_config_t config = HTTPD_DEFAULT_CONFIG();
    config.server_port = CONFIG_PORT;

    httpd_uri_t index_uri = {
        .uri       = "/",
        .method    = HTTP_GET,
        .handler   = index_handler,
        .user_ctx  = NULL
    };

    if (httpd_start(&config_httpd, &config) == ESP_OK) {
        httpd_register_uri_handler(config_httpd, &index_uri);
        Serial.printf("[OK] Painel disponível em: http://%s\n",
                      currentIpString().c_str());
    }
}

// ─────────────────────────────────────────────
//  INICIALIZAÇÃO DA CÂMERA
// ─────────────────────────────────────────────
bool initCamera() {
    camera_config_t config;
    config.ledc_channel = LEDC_CHANNEL_0;
    config.ledc_timer   = LEDC_TIMER_0;
    config.pin_d0       = Y2_GPIO_NUM;
    config.pin_d1       = Y3_GPIO_NUM;
    config.pin_d2       = Y4_GPIO_NUM;
    config.pin_d3       = Y5_GPIO_NUM;
    config.pin_d4       = Y6_GPIO_NUM;
    config.pin_d5       = Y7_GPIO_NUM;
    config.pin_d6       = Y8_GPIO_NUM;
    config.pin_d7       = Y9_GPIO_NUM;
    config.pin_xclk     = XCLK_GPIO_NUM;
    config.pin_pclk     = PCLK_GPIO_NUM;
    config.pin_vsync    = VSYNC_GPIO_NUM;
    config.pin_href     = HREF_GPIO_NUM;
    config.pin_sscb_sda = SIOD_GPIO_NUM;
    config.pin_sscb_scl = SIOC_GPIO_NUM;
    config.pin_pwdn     = PWDN_GPIO_NUM;
    config.pin_reset    = RESET_GPIO_NUM;
    config.xclk_freq_hz = 20000000;
    config.pixel_format = PIXFORMAT_JPEG;

    // Usa PSRAM se disponível para melhor qualidade
    if (psramFound()) {
        config.frame_size   = CAM_FRAMESIZE;
        config.jpeg_quality = CAM_QUALITY;
        config.fb_count     = 2;
        config.fb_location  = CAMERA_FB_IN_PSRAM;
        config.grab_mode    = CAMERA_GRAB_LATEST;
        Serial.println("[OK] PSRAM encontrada — qualidade alta ativada");
    } else {
        // Sem PSRAM: resolução menor para evitar crash
        config.frame_size   = FRAMESIZE_SVGA;
        config.jpeg_quality = 20;
        config.fb_count     = 1;
        config.fb_location  = CAMERA_FB_IN_DRAM;
        Serial.println("[AVL] Sem PSRAM — resolução reduzida");
    }

    esp_err_t err = esp_camera_init(&config);
    if (err != ESP_OK) {
        Serial.printf("[ERRO] Câmera falhou: 0x%x\n", err);
        return false;
    }

    // Ajustes finos do sensor
    sensor_t* s = esp_camera_sensor_get();
    if (s) {
        s->set_brightness(s, 0);      // -2 a 2
        s->set_contrast(s, 0);        // -2 a 2
        s->set_saturation(s, 0);      // -2 a 2
        s->set_sharpness(s, 0);       // -2 a 2
        s->set_whitebal(s, 1);        // Balanço de branco auto
        s->set_awb_gain(s, 1);
        s->set_wb_mode(s, 0);         // 0=auto
        s->set_exposure_ctrl(s, 1);   // Exposição auto
        s->set_aec2(s, 1);
        s->set_ae_level(s, 0);
        s->set_gain_ctrl(s, 1);       // Ganho auto
        s->set_agc_gain(s, 0);
        s->set_gainceiling(s, (gainceiling_t)0);
        s->set_bpc(s, 0);
        s->set_wpc(s, 1);
        s->set_raw_gma(s, 1);
        s->set_lenc(s, 1);
        s->set_hmirror(s, 0);         // Espelhar horizontal: 1 para espelhar
        s->set_vflip(s, 0);           // Virar vertical: 1 para inverter
        s->set_dcw(s, 1);
        s->set_colorbar(s, 0);
        Serial.println("[OK] Sensor da câmera configurado");
    }

    return true;
}

// ─────────────────────────────────────────────
//  SETUP
// ─────────────────────────────────────────────
void setup() {
    Serial.begin(115200);
    Serial.println("\n\n============================");
    Serial.println("   NEUROVIEW — ESP32-CAM");
    Serial.println("============================");

    // Configura LEDs
    pinMode(LED_PIN, OUTPUT);
    digitalWrite(LED_PIN, HIGH); // LED apagado (ativo em LOW)

    pinMode(FLASH_PIN, OUTPUT);
    digitalWrite(FLASH_PIN, LOW); // Flash apagado

    // ── Inicializa câmera ──
    Serial.println("[...] Iniciando câmera...");
    if (!initCamera()) {
        Serial.println("[ERRO] Câmera não inicializou. Reiniciando em 5s...");
        // Pisca LED de erro
        for (int i = 0; i < 10; i++) {
            digitalWrite(LED_PIN, !digitalRead(LED_PIN));
            delay(250);
        }
        ESP.restart();
    }
    Serial.println("[OK] Câmera pronta");

    // ── Conecta no Wi-Fi do roteador/celular ──
    if (!connectToRouterWifi()) {
        startFallbackAccessPoint();
    }

    // ── Inicia servidores HTTP ──
    startStreamServer();
    startConfigServer();

    // ── Sinaliza pronto com LED ──
    // 3 piscadas rápidas = pronto
    for (int i = 0; i < 3; i++) {
        digitalWrite(LED_PIN, LOW);
        delay(100);
        digitalWrite(LED_PIN, HIGH);
        delay(100);
    }

    Serial.println("\n============================");
    Serial.println("  PRONTO PARA CONECTAR");
    Serial.println("============================");
    if (fallbackApMode) {
        Serial.printf("1. Conecte o celular ao Wi-Fi: %s\n", FALLBACK_AP_SSID);
        Serial.printf("2. Cadastre no app o IP: %s\n", currentIpString().c_str());
    } else {
        Serial.println("1. Mantenha o celular no MESMO Wi-Fi do ESP");
        Serial.printf("2. Cadastre no app o IP: %s\n", currentIpString().c_str());
    }
    Serial.printf("3. Stream: http://%s:%d/stream\n", currentIpString().c_str(), STREAM_PORT);
    Serial.printf("4. Painel: http://%s\n", currentIpString().c_str());
    Serial.println("============================\n");
}

// ─────────────────────────────────────────────
//  LOOP PRINCIPAL
// ─────────────────────────────────────────────
void loop() {
    static unsigned long lastReport = 0;
    if (millis() - lastReport > 10000) {
        lastReport = millis();

        if (fallbackApMode) {
            int clients = WiFi.softAPgetStationNum();
            Serial.printf("[INFO] Modo AP fallback | Clientes: %d | IP: %s | Heap livre: %d bytes\n",
                          clients, currentIpString().c_str(), ESP.getFreeHeap());
            digitalWrite(LED_PIN, clients > 0 ? LOW : HIGH);
        } else {
            bool connected = WiFi.status() == WL_CONNECTED;
            Serial.printf("[INFO] Modo Wi-Fi roteador | Conectado: %s | IP: %s | Heap livre: %d bytes\n",
                          connected ? "sim" : "não", currentIpString().c_str(), ESP.getFreeHeap());
            digitalWrite(LED_PIN, connected ? LOW : HIGH);

            if (!connected) {
                Serial.println("[AVL] Wi-Fi caiu. Tentando reconectar...");
                WiFi.reconnect();
            }
        }
    }

    delay(100);
}
