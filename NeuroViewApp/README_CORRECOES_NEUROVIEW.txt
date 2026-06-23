Correções aplicadas no NeuroView
================================

1. Fluxo de conexão melhorado
- O app não orienta mais o usuário a conectar o celular no Wi-Fi do ESP.
- O fluxo recomendado agora é: ESP32-CAM e celular conectados na mesma rede Wi-Fi.
- Assim o celular mantém internet e o app acessa o ESP pelo IP local.
- Observação importante: para isso funcionar, o firmware do ESP precisa estar em modo STA/client, conectado ao roteador, e não apenas em modo Access Point.

2. Cadastro simplificado
- Removido o campo obrigatório de ID do óculos.
- Agora o cadastro pede somente:
  - Nome do dispositivo
  - IP/host do ESP32-CAM
- O identificador interno passa a ser gerado automaticamente pelo Firestore.

3. Correção da tela preta na transmissão
- Removido o uso de WebView para renderizar o stream MJPEG.
- O app agora abre diretamente o endpoint do ESP, lê os frames JPEG do MJPEG e mostra em um ImageView.
- O status "Transmissão ativa" só aparece depois que o primeiro frame real é recebido.
- Se não receber imagem, o app mostra mensagem orientando a verificar IP e rede Wi-Fi.

4. Interface ajustada
- Tela de cadastro atualizada com texto explicando o fluxo correto.
- Tela Home agora mostra o endereço salvo do ESP em vez de um ID manual.
- Botão alterado para "Abrir transmissão".

Endpoint esperado
=================
Por padrão, ao cadastrar apenas o IP, o app monta:
http://IP_DO_ESP:81/stream

Exemplo:
IP cadastrado: 192.168.1.50
URL usada: http://192.168.1.50:81/stream

Também aceita cadastrar com porta, por exemplo:
192.168.1.50:81

E também aceita URL completa, por exemplo:
http://192.168.1.50:81/stream

Validação
=========
Eu fiz checagem estática dos arquivos e validei que os XMLs estão bem formados.
Não consegui rodar o build completo neste ambiente porque o Gradle tentou baixar a distribuição do Gradle pela internet e o ambiente está sem DNS/acesso externo.
