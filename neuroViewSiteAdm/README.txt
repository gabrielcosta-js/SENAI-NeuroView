Arquivos principais:

index.html
- Tela de login/cadastro.
- Ao entrar ou criar conta, manda para painelAdm.html.

painelAdm.html
- Tela do painel administrativo.
- Só abre se o usuário estiver logado.

scripts/firebaseConfig.js
- Configuração do Firebase.

scripts/apiAutenticacao.js
- Camada responsável por login, cadastro, logout e dados do administrador.

scripts/apiPainel.js
- Camada responsável por buscar os pré-cadastros no Realtime Database.

scripts/script.js
- Código da tela de login/cadastro.

scripts/painelAdm.js
- Código da tela do painel administrativo.

scripts/tema.js
- Código de mudar tema.

Banco usado:
- administradores/{uid}
- leads

Observação:
- O site de vendas já grava em leads.
- O painel lê leads em tempo real e atualiza tabela e cards.
