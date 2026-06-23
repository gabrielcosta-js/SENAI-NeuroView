import { db } from "./firebaseConfig.js";

import {
  ref,
  onValue
} from "https://www.gstatic.com/firebasejs/12.14.0/firebase-database.js";

export function escutarCadastros(callback, callbackErro) {
  const caminho = ref(db, "leads");

  onValue(caminho, (resultado) => {
    const dados = resultado.val() || {};

    const cadastros = Object.entries(dados).map(([id, item]) => {
      return {
        id,
        ...item
      };
    });

    cadastros.sort((a, b) => {
      return (b.criadoEm || 0) - (a.criadoEm || 0);
    });

    callback(cadastros);
  }, callbackErro);
}
