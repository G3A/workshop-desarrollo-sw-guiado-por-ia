// Chequeo de regresion del hallazgo 64 (paso 1 del plan): llama /api/search
// (sin LLM) para las 68 preguntas que ya respondian bien en la sesion 16, y
// compara mejorRerank contra la etiqueta pasada por argv (baseline-tope3 o tope20).
import { readFileSync, writeFileSync } from "node:fs";

const BASE_URL = "http://localhost:8080";
const ETIQUETA = process.argv[2];
if (!ETIQUETA) {
  console.error("uso: node chequeo-regresion-tope20.mjs <etiqueta-salida>");
  process.exit(1);
}

const EVAL_DIR = "D:/GitHub_public/workshop-desarrollo-sw-guiado-por-ia/eval-100-preguntas";
const preguntas = JSON.parse(readFileSync(`${EVAL_DIR}/preguntas.json`, "utf8"));
const sesion16 = JSON.parse(
  readFileSync(`${EVAL_DIR}/resultados-completos.ministral-3-3b-sesion16-verificacion.json`, "utf8")
);

const buenasIds = new Set(
  sesion16.filter((d) => !(d.comportamiento === "rechaza" && d.esperado === "responde")).map((d) => d.id)
);
const baselineMejorRerank = new Map(sesion16.map((d) => [d.id, d.mejorRerank]));

const objetivo = preguntas.filter((p) => buenasIds.has(p.id));
console.log(`preguntas objetivo: ${objetivo.length} (esperado 68)`);

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
  const top1ChunkId = resultados.length
    ? resultados.reduce((a, b) => (b.fragmento.rerank > a.fragmento.rerank ? b : a)).fragmento.id
    : null;
  salida.push({
    id: p.id,
    pregunta: p.pregunta,
    mejorRerankBaselineSesion16: baselineMejorRerank.get(p.id),
    mejorRerank,
    top1ChunkId,
  });
  process.stdout.write(".");
}
console.log("");

writeFileSync(`${EVAL_DIR}/chequeo-regresion-tope20.${ETIQUETA}.json`, JSON.stringify(salida, null, 2), "utf8");
console.log(`guardado en chequeo-regresion-tope20.${ETIQUETA}.json`);
