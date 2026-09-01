// Corre /api/search (sin LLM) contra las 32 preguntas rechazadas de mas de la
// sesion 16, para comparar mejorRerank entre distintos valores de
// tope-por-documento (llamar una vez por cada valor de tope, con el
// contenedor ya recreado para ese valor).
import { readFileSync, writeFileSync } from "node:fs";

const BASE_URL = "http://localhost:8080";
const ETIQUETA = process.argv[2];
if (!ETIQUETA) {
  console.error("uso: node chequeo-32-fallidas.mjs <etiqueta-salida>");
  process.exit(1);
}

const EVAL_DIR = "D:/GitHub_public/workshop-desarrollo-sw-guiado-por-ia/eval-100-preguntas";
const preguntas = JSON.parse(readFileSync(`${EVAL_DIR}/preguntas.json`, "utf8"));
const sesion16 = JSON.parse(
  readFileSync(`${EVAL_DIR}/resultados-completos.ministral-3-3b-sesion16-verificacion.json`, "utf8")
);

const fallidasIds = new Set(
  sesion16.filter((d) => d.comportamiento === "rechaza" && d.esperado === "responde").map((d) => d.id)
);
const baselineMejorRerank = new Map(sesion16.map((d) => [d.id, d.mejorRerank]));

const objetivo = preguntas.filter((p) => fallidasIds.has(p.id));
console.log(`preguntas objetivo: ${objetivo.length} (esperado 32)`);

const salida = [];
for (const p of objetivo) {
  const res = await fetch(`${BASE_URL}/api/search`, {
    method: "POST",
    headers: { "Content-Type": "application/json; charset=utf-8" },
    body: JSON.stringify({ q: p.pregunta }),
  });
  if (!res.ok) {
    console.error(`id=${p.id} HTTP ${res.status}`);
    salida.push({ id: p.id, error: `HTTP ${res.status}` });
    continue;
  }
  const resultados = await res.json();
  const mejorRerank = resultados.length ? Math.max(...resultados.map((r) => r.fragmento.rerank)) : 0;
  salida.push({
    id: p.id,
    pregunta: p.pregunta,
    mejorRerankBaselineSesion16: baselineMejorRerank.get(p.id),
    mejorRerank,
  });
  process.stdout.write(".");
}
console.log("");

writeFileSync(`${EVAL_DIR}/chequeo-32-fallidas.${ETIQUETA}.json`, JSON.stringify(salida, null, 2), "utf8");
console.log(`guardado en chequeo-32-fallidas.${ETIQUETA}.json`);
