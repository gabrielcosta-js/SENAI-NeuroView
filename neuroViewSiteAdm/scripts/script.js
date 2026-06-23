import { iniciarTema } from "./tema.js";
import { entrarUsuario, criarUsuario, verificarUsuario } from "./apiAutenticacao.js";

const btnCriarConta = document.getElementById("btnCriarConta");
const btnEntrar = document.getElementById("btnEntrar");
const nomeFormOculto = document.getElementById("nomeFormOculto");
const btnLogar = document.getElementById("btnLogar");
const formAutenticacao = document.getElementById("formAutenticacao");
const mensagemForm = document.getElementById("mensagemForm");
const tituloFormulario = document.getElementById("tituloFormulario");
const textoFormulario = document.getElementById("textoFormulario");

const inputNome = document.getElementById("iNomeForm");
const inputEmail = document.getElementById("iEmailForm");
const inputSenha = document.getElementById("iSenhaForm");

let modoAtual = "entrar";

iniciarTema();

verificarUsuario((usuario) => {
  if (usuario) {
    window.location.href = "painelAdm.html";
  }
});

function modoEntrar() {
  modoAtual = "entrar";

  btnEntrar.classList.add("botaoDestaque");
  btnCriarConta.classList.remove("botaoDestaque");

  nomeFormOculto.classList.add("oculto");

  btnEntrar.disabled = true;
  btnCriarConta.disabled = false;

  tituloFormulario.textContent = "Bem-vindo de volta";
  textoFormulario.textContent = "Entre para acessar o painel do NeuroView.";
  btnLogar.textContent = "Entrar";
  inputSenha.setAttribute("autocomplete", "current-password");

  limparMensagem();
}

function modoCriarConta() {
  modoAtual = "criar";

  btnCriarConta.classList.add("botaoDestaque");
  btnEntrar.classList.remove("botaoDestaque");

  nomeFormOculto.classList.remove("oculto");

  btnCriarConta.disabled = true;
  btnEntrar.disabled = false;

  tituloFormulario.textContent = "Criar sua conta";
  textoFormulario.textContent = "Cadastre-se para acessar o painel.";
  btnLogar.textContent = "Criar conta";
  inputSenha.setAttribute("autocomplete", "new-password");

  limparMensagem();
}

function mudarBtnDestaque() {
  btnCriarConta.addEventListener("click", () => {
    modoCriarConta();
  });

  btnEntrar.addEventListener("click", () => {
    modoEntrar();
  });
}

function mostrarMensagem(texto, tipo) {
  mensagemForm.textContent = texto;
  mensagemForm.className = "mensagem-form " + tipo;
}

function limparMensagem() {
  mensagemForm.textContent = "";
  mensagemForm.className = "mensagem-form oculto";

  inputNome.classList.remove("erro-input");
  inputEmail.classList.remove("erro-input");
  inputSenha.classList.remove("erro-input");
}

function mudarCarregamento(carregando) {
  btnLogar.disabled = carregando;

  if (carregando) {
    btnLogar.textContent = "Carregando...";
    return;
  }

  btnLogar.textContent = modoAtual === "entrar" ? "Entrar" : "Criar conta";
}

function traduzirErro(codigo) {
  const erros = {
    "auth/invalid-email": "E-mail inválido.",
    "auth/missing-password": "Digite uma senha.",
    "auth/weak-password": "A senha precisa ter pelo menos 6 caracteres.",
    "auth/email-already-in-use": "Esse e-mail já tem uma conta.",
    "auth/invalid-credential": "E-mail ou senha incorretos.",
    "auth/wrong-password": "E-mail ou senha incorretos.",
    "auth/user-not-found": "Não encontrei uma conta com esse e-mail.",
    "auth/network-request-failed": "Verifique sua internet e tente novamente."
  };

  return erros[codigo] || "Algo deu errado. Tente novamente.";
}

async function enviarFormulario(evento) {
  evento.preventDefault();
  limparMensagem();

  const nome = inputNome.value.trim();
  const email = inputEmail.value.trim();
  const senha = inputSenha.value;

  let temErro = false;

  if (modoAtual === "criar" && !nome) {
    inputNome.classList.add("erro-input");
    temErro = true;
  }

  if (!email) {
    inputEmail.classList.add("erro-input");
    temErro = true;
  }

  if (!senha) {
    inputSenha.classList.add("erro-input");
    temErro = true;
  }

  if (senha && senha.length < 6) {
    inputSenha.classList.add("erro-input");
    mostrarMensagem("A senha precisa ter pelo menos 6 caracteres.", "erro");
    return;
  }

  if (temErro) {
    mostrarMensagem("Preencha os campos necessários.", "erro");
    return;
  }

  mudarCarregamento(true);

  try {
    if (modoAtual === "entrar") {
      await entrarUsuario(email, senha);
    } else {
      await criarUsuario(nome, email, senha);
    }

    mostrarMensagem("Tudo certo! Entrando...", "sucesso");

    setTimeout(() => {
      window.location.href = "painelAdm.html";
    }, 600);
  } catch (erro) {
    mostrarMensagem(traduzirErro(erro.code), "erro");
    mudarCarregamento(false);
  }
}

modoEntrar();
mudarBtnDestaque();
formAutenticacao.addEventListener("submit", enviarFormulario);
