import { iniciarTema } from "./tema.js";
import { verificarUsuario, buscarUsuarioBanco, sairUsuario } from "./apiAutenticacao.js";
import { escutarCadastros } from "./apiPainel.js";

const emailUsuario = document.getElementById("emailUsuario");
const btnSair = document.getElementById("btnSair");
const telaCarregando = document.getElementById("telaCarregando");

const totalCadastros = document.getElementById("totalCadastros");
const totalGratuito = document.getElementById("totalGratuito");
const totalPremium = document.getElementById("totalPremium");
const corpoTabela = document.getElementById("corpoTabela");

iniciarTema();

verificarUsuario(async (usuario) => {
  if (!usuario) {
    window.location.replace("index.html");
    return;
  }

  await mostrarUsuarioLogado(usuario);
  telaCarregando.style.display = "none";
  carregarCadastros();
});

btnSair.addEventListener("click", async () => {
  await sairUsuario();
  window.location.replace("index.html");
});

async function mostrarUsuarioLogado(usuario) {
  try {
    const dadosUsuario = await buscarUsuarioBanco(usuario.uid);

    if (dadosUsuario && dadosUsuario.email) {
      emailUsuario.textContent = dadosUsuario.email;
    } else {
      emailUsuario.textContent = usuario.email;
    }
  } catch (erro) {
    emailUsuario.textContent = usuario.email;
  }
}

function carregarCadastros() {
  escutarCadastros((cadastros) => {
    atualizarCards(cadastros);
    montarTabela(cadastros);
  }, () => {
    corpoTabela.innerHTML = `
      <tr>
        <td colspan="5" class="tabela-vazia">Não foi possível carregar os dados do Firebase.</td>
      </tr>
    `;
  });
}

function atualizarCards(cadastros) {
  const gratuitos = cadastros.filter((cadastro) => {
    return cadastro.plano !== "premium";
  });

  const premium = cadastros.filter((cadastro) => {
    return cadastro.plano === "premium";
  });

  totalCadastros.textContent = cadastros.length;
  totalGratuito.textContent = gratuitos.length;
  totalPremium.textContent = premium.length;
}

function montarTabela(cadastros) {
  if (cadastros.length === 0) {
    corpoTabela.innerHTML = `
      <tr>
        <td colspan="5" class="tabela-vazia">Nenhum cadastro encontrado.</td>
      </tr>
    `;
    return;
  }

  corpoTabela.innerHTML = cadastros.map((cadastro) => {
    const plano = cadastro.plano === "premium" ? "Premium" : "Gratuito";
    const classePlano = cadastro.plano === "premium" ? "premium" : "gratuito";

    return `
      <tr>
        <td data-label="Nome">${limparTexto(cadastro.nome) || "—"}</td>
        <td data-label="E-mail" class="email-tabela">${limparTexto(cadastro.email) || "—"}</td>
        <td data-label="Plano">
          <span class="selo-plano ${classePlano}">${plano}</span>
        </td>
        <td data-label="Interesse">${limparTexto(cadastro.escolha) || "—"}</td>
        <td data-label="Quando" class="data-tabela">${formatarData(cadastro.criadoEm)}</td>
      </tr>
    `;
  }).join("");
}

function formatarData(data) {
  if (!data) {
    return "—";
  }

  const dataFormatada = new Date(data);

  const diaMes = dataFormatada.toLocaleDateString("pt-BR", {
    day: "2-digit",
    month: "2-digit"
  });

  const hora = dataFormatada.toLocaleTimeString("pt-BR", {
    hour: "2-digit",
    minute: "2-digit"
  });

  return diaMes + " " + hora;
}

function limparTexto(texto) {
  return String(texto ?? "").replace(/[&<>"']/g, (letra) => {
    const mapa = {
      "&": "&amp;",
      "<": "&lt;",
      ">": "&gt;",
      '"': "&quot;",
      "'": "&#39;"
    };

    return mapa[letra];
  });
}
