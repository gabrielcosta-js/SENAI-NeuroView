const chaveTema = "tema-neuroview";

export function iniciarTema() {
  const btnTema = document.getElementById("btn-tema");

  if (!btnTema) {
    return;
  }

  const temaSalvo = localStorage.getItem(chaveTema) || document.documentElement.getAttribute("data-theme") || "dark";

  aplicarTema(temaSalvo);

  btnTema.addEventListener("click", () => {
    const temaAtual = document.documentElement.getAttribute("data-theme");

    if (temaAtual === "dark") {
      aplicarTema("light");
    } else {
      aplicarTema("dark");
    }
  });
}

function aplicarTema(tema) {
  document.documentElement.setAttribute("data-theme", tema);
  localStorage.setItem(chaveTema, tema);
  mudarIconeTema(tema);
}

function mudarIconeTema(tema) {
  const btnTema = document.getElementById("btn-tema");

  if (!btnTema) {
    return;
  }

  const iconeSol = btnTema.querySelector(".fa-sun");
  const iconeLua = btnTema.querySelector(".fa-moon");

  if (!iconeSol || !iconeLua) {
    return;
  }

  if (tema === "dark") {
    iconeLua.classList.remove("oculto");
    iconeSol.classList.add("oculto");
  } else {
    iconeSol.classList.remove("oculto");
    iconeLua.classList.add("oculto");
  }
}
