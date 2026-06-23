Correção NeuroView — compatibilidade App + ESP32-CAM
====================================================

Problema encontrado
-------------------
O firmware do ESP32-CAM enviado estava em modo Access Point:
- Rede criada pelo ESP: NeuroView-AP
- IP fixo: 192.168.4.1
- Stream: http://192.168.4.1:81/stream

Esse fluxo obriga o celular a conectar no Wi-Fi do ESP, deixando o celular sem internet.
O app corrigido anteriormente esperava o fluxo melhor: ESP e celular na mesma rede Wi-Fi do roteador/hotspot.
Por isso a mensagem "Sem imagem do ESP" aparece quando o celular está em uma rede e o ESP está em outra.

Correção aplicada
-----------------
1. Foi adicionado o firmware corrigido:
   ESP32_CAM_NeuroView_STA_CORRIGIDO.ino

2. Esse firmware tenta conectar o ESP32-CAM ao Wi-Fi principal usando:
   WIFI_SSID
   WIFI_PASSWORD

3. Após gravar o firmware, abra o Monitor Serial em 115200 baud.
   O ESP mostrará algo como:
   Cadastre no app o IP: 192.168.1.50
   Stream: http://192.168.1.50:81/stream

4. No app, cadastre somente o IP mostrado, por exemplo:
   192.168.1.50

5. O app montará automaticamente a URL:
   http://192.168.1.50:81/stream

6. O celular deve estar no mesmo Wi-Fi do ESP.
   Assim o celular pode continuar com internet normalmente.

Modo fallback
-------------
Se o ESP não conseguir conectar ao Wi-Fi configurado, ele cria a rede fallback NeuroView-AP.
Nesse caso, o celular precisa conectar nessa rede e o IP a cadastrar será 192.168.4.1.
Esse é o modo antigo, sem internet no celular, usado apenas para emergência/teste.

Correções extras
----------------
- Corrigida a orientação exibida na tela de transmissão do app.
- A mensagem de erro agora mostra a URL usada pelo app.
- O app continua aceitando tanto IP simples quanto URL completa.
- No firmware, o buffer do cabeçalho MJPEG foi corrigido de char* part_buf[128] para char part_buf[128].

Checklist para testar
---------------------
1. Edite o firmware ESP32_CAM_NeuroView_STA_CORRIGIDO.ino.
2. Troque:
   COLOQUE_AQUI_O_NOME_DO_WIFI
   COLOQUE_AQUI_A_SENHA_DO_WIFI
3. Grave no ESP32-CAM.
4. Abra o Monitor Serial em 115200.
5. Copie o IP mostrado.
6. No navegador do celular, teste:
   http://IP_DO_ESP:81/stream
7. Se abrir imagem no navegador, cadastre o mesmo IP no app.
