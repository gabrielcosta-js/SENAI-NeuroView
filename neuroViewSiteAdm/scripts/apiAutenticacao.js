import { auth, db } from "./firebaseConfig.js";

import {
  createUserWithEmailAndPassword,
  signInWithEmailAndPassword,
  updateProfile,
  onAuthStateChanged,
  signOut
} from "https://www.gstatic.com/firebasejs/12.14.0/firebase-auth.js";

import {
  ref,
  set,
  get
} from "https://www.gstatic.com/firebasejs/12.14.0/firebase-database.js";

export function verificarUsuario(callback) {
  return onAuthStateChanged(auth, callback);
}

export async function entrarUsuario(email, senha) {
  const resposta = await signInWithEmailAndPassword(auth, email, senha);
  return resposta.user;
}

export async function criarUsuario(nome, email, senha) {
  const resposta = await createUserWithEmailAndPassword(auth, email, senha);
  const usuario = resposta.user;

  if (nome) {
    await updateProfile(usuario, {
      displayName: nome
    });
  }

  await set(ref(db, "administradores/" + usuario.uid), {
    nome: nome || "",
    email: email,
    criadoEm: Date.now()
  });

  return usuario;
}

export async function buscarUsuarioBanco(uid) {
  const resposta = await get(ref(db, "administradores/" + uid));
  return resposta.val();
}

export async function sairUsuario() {
  await signOut(auth);
}
