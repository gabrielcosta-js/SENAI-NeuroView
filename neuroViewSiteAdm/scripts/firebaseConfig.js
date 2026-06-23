import { initializeApp } from "https://www.gstatic.com/firebasejs/12.14.0/firebase-app.js";
import { getAuth } from "https://www.gstatic.com/firebasejs/12.14.0/firebase-auth.js";
import { getDatabase } from "https://www.gstatic.com/firebasejs/12.14.0/firebase-database.js";

const firebaseConfig = {
  apiKey: "AIzaSyAqqDcFiE7VfOiDKZsG2ijIF-ytV_8bZH8",
  authDomain: "neuroview-7e52a.firebaseapp.com",
  databaseURL: "https://neuroview-7e52a-default-rtdb.firebaseio.com",
  projectId: "neuroview-7e52a",
  storageBucket: "neuroview-7e52a.firebasestorage.app",
  messagingSenderId: "598265621179",
  appId: "1:598265621179:web:9f009d94ec50592108179e"
};

const app = initializeApp(firebaseConfig);

export const auth = getAuth(app);
export const db = getDatabase(app);
