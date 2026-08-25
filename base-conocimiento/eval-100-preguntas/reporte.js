// Cruza resultados-brutos.json (lo que produjo ejecutar.js) con query_log en
// Postgres para recuperar, por cada pregunta, el mejor puntaje de rerank que
// vio UmbralRelevancia (ADR-0008) -- la UI nunca expone ese numero al
// navegador, solo vive en la tabla de auditoria. Con eso recalcula la misma
// decision INSUFICIENTE/AMBIGUO/SUFICIENTE que tomo Orquestador (mismo umbral
// dinamico, ver UmbralRelevancia.java) y arma reporte.html.
//
// No corre nada contra el LLM: es puro calculo sobre datos ya generados, por
// eso esta separado de ejecutar.js -- se puede re-generar el reporte sin
// repetir las 100 preguntas si cambia solo la logica de calificacion.

import { execFileSync } from "node:child_process";
import { readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const DIR = path.dirname(fileURLToPath(import.meta.url));

const DB_CONTAINER = process.env.EVAL_DB_CONTAINER || "kb-db";
const DB_USER = process.env.EVAL_DB_USER || "kb";
const DB_NAME = process.env.EVAL_DB_NAME || "baseconocimiento";
const API_CONTAINER = process.env.EVAL_API_CONTAINER || "kb-api";
const PROYECTO = process.env.EVAL_PROYECTO || "default";
// Mismo sufijo que ejecutar.js (EVAL_SUFIJO) para cruzar la muestra puntual
// correcta en vez de resultados-brutos.json de la corrida completa.
const SUFIJO = process.env.EVAL_SUFIJO ? `.${process.env.EVAL_SUFIJO}` : "";

// Orquestador.MENSAJE_SIN_INFORMACION -- si cambia ese texto en el codigo,
// actualizar tambien aca.
const MENSAJE_SIN_INFORMACION =
  "No encontré información suficientemente relevante en la base de conocimiento " +
  "para responder esto. Prueba con otra formulación o verifica que el tema " +
  "esté cubierto por las fuentes ingeridas.";

const ARCHIVO_RESULTADOS = path.join(DIR, `resultados-brutos${SUFIJO}.json`);
const ARCHIVO_SALIDA_JSON = path.join(DIR, `resultados-completos${SUFIJO}.json`);
const ARCHIVO_SALIDA_HTML = path.join(DIR, `reporte${SUFIJO}.html`);

function psqlJson(sql) {
  const salida = execFileSync(
    "docker",
    ["exec", DB_CONTAINER, "psql", "-U", DB_USER, "-d", DB_NAME, "-t", "-A", "-c", sql],
    { encoding: "utf8", maxBuffer: 64 * 1024 * 1024 },
  ).trim();
  return salida ? JSON.parse(salida) : null;
}

function leerPropiedadesUmbral() {
  // Defaults de application.yml -- se pisan si el contenedor tiene las
  // variables de entorno seteadas (p. ej. compose.bonsai.yml sube
  // techo-confianza a 6.0).
  const props = { piso: 0.003, techo: 0.05, chunksReferencia: 500, techoConfianza: 8.0 };
  let env = "";
  try {
    env = execFileSync("docker", ["exec", API_CONTAINER, "env"], { encoding: "utf8" });
  } catch {
    console.warn(`Aviso: no se pudo leer el entorno de ${API_CONTAINER}, uso los defaults de application.yml.`);
    return props;
  }
  const buscar = (nombre) => {
    const linea = env.split("\n").find((l) => l.startsWith(nombre + "="));
    return linea ? Number(linea.slice(nombre.length + 1)) : undefined;
  };
  props.piso = buscar("KB_UMBRAL_RELEVANCIA_PISO") ?? props.piso;
  props.techo = buscar("KB_UMBRAL_RELEVANCIA_TECHO") ?? props.techo;
  props.chunksReferencia = buscar("KB_UMBRAL_RELEVANCIA_CHUNKS_REFERENCIA") ?? props.chunksReferencia;
  props.techoConfianza = buscar("KB_UMBRAL_RELEVANCIA_TECHO_CONFIANZA") ?? props.techoConfianza;
  return props;
}

function umbralEfectivo(chunksProyecto, props) {
  const proporcion = Math.min(1, chunksProyecto / Math.max(1, props.chunksReferencia));
  return props.piso + (props.techo - props.piso) * proporcion;
}

// Replica UmbralRelevancia.evaluar(...) en JS, a partir de lo que quedo en
// query_log.candidates (mismo shape que arma QueryLogRepositorio: rerank=-1.0
// es el centinela para "sin puntaje de reranker", no null real en jsonb).
function decidir(candidates, chunksProyecto, props) {
  if (!candidates || candidates.length === 0) {
    return { decision: "INSUFICIENTE", umbral: umbralEfectivo(chunksProyecto, props), mejorRerank: null };
  }
  const hayFragmentosSinRankear = candidates.some((c) => c.rerank === -1 || c.rerank === -1.0);
  if (hayFragmentosSinRankear) {
    // Herramientas de listado (recent_commits/subsystem_index/who_knows): la
    // puerta no aplica, se deja pasar sin mas -- ver UmbralRelevancia.java.
    return { decision: "SUFICIENTE", umbral: 0, mejorRerank: null, sinPuerta: true };
  }
  const umbral = umbralEfectivo(chunksProyecto, props);
  const mejor = Math.max(...candidates.map((c) => c.rerank));
  if (mejor < umbral) return { decision: "INSUFICIENTE", umbral, mejorRerank: mejor };
  if (mejor >= props.techoConfianza) return { decision: "SUFICIENTE", umbral, mejorRerank: mejor };
  return { decision: "AMBIGUO", umbral, mejorRerank: mejor };
}

function comportamientoDe(respuesta) {
  return respuesta.trim() === MENSAJE_SIN_INFORMACION ? "rechaza" : "responde";
}

function escapeHtml(s) {
  return String(s ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;");
}

function main() {
  const brutos = JSON.parse(readFileSync(ARCHIVO_RESULTADOS, "utf8"));
  if (brutos.length === 0) {
    console.error("resultados-brutos.json está vacío -- corre 'npm run eval' primero.");
    process.exit(1);
  }

  const props = leerPropiedadesUmbral();
  const chunksProyecto = Number(psqlJson(
    `SELECT count(*) FROM chunks WHERE project_id = '${PROYECTO}'`,
  ) ?? 0);

  const inicioLote = brutos.map((r) => r.inicio).sort()[0];
  const filas = psqlJson(`
    SELECT json_agg(row_to_json(t)) FROM (
      SELECT id, question, answer, candidates, latency_ms, created_at
      FROM query_log
      WHERE project_id = '${PROYECTO}' AND adapter = 'web' AND created_at >= '${inicioLote}'::timestamptz - interval '5 seconds'
      ORDER BY created_at ASC
    ) t
  `) || [];

  console.log(`Config umbral: piso=${props.piso} techo=${props.techo} chunksReferencia=${props.chunksReferencia} techoConfianza=${props.techoConfianza}`);
  console.log(`Chunks del proyecto "${PROYECTO}": ${chunksProyecto}`);
  console.log(`Filas de query_log candidatas: ${filas.length} (preguntas corridas: ${brutos.length})`);

  // Empareja por texto exacto de pregunta + orden cronologico -- ejecutar.js
  // corre en serie y espera cada respuesta antes de la siguiente, asi que no
  // hay ambiguedad entre filas con la misma pregunta.
  const usadas = new Set();
  function matchQueryLog(pregunta) {
    for (const f of filas) {
      const clave = `${f.id}`;
      if (f.question === pregunta && !usadas.has(clave)) {
        usadas.add(clave);
        return f;
      }
    }
    return null;
  }

  const completos = brutos.map((r) => {
    const fila = matchQueryLog(r.pregunta);
    const candidates = fila?.candidates ?? null;
    const { decision, umbral, mejorRerank, sinPuerta } = decidir(candidates, chunksProyecto, props);
    const comportamiento = r.error ? "error" : comportamientoDe(r.respuesta);
    const correcta = !r.error && comportamiento === r.esperado;
    return {
      ...r,
      queryLogId: fila?.id ?? null,
      latencyMsServidor: fila?.latency_ms ?? null,
      mejorRerank,
      umbralUsado: umbral,
      techoConfianza: props.techoConfianza,
      decision,
      sinPuerta: Boolean(sinPuerta),
      comportamiento,
      correcta,
    };
  });

  const sinMatch = completos.filter((r) => r.queryLogId === null && !r.error).length;
  if (sinMatch > 0) {
    console.warn(`Aviso: ${sinMatch} preguntas no encontraron fila en query_log (¿corriste ejecutar.js contra otra base o proyecto?).`);
  }

  writeFileSync(ARCHIVO_SALIDA_JSON, JSON.stringify(completos, null, 2), "utf8");
  writeFileSync(ARCHIVO_SALIDA_HTML, generarHtml(completos, props, chunksProyecto), "utf8");

  const calificadas = completos.filter((r) => !r.error);
  const aciertos = calificadas.filter((r) => r.correcta).length;
  console.log(`\nPrecisión (sobre ${calificadas.length} calificadas, ${completos.length - calificadas.length} con error): ${aciertos}/${calificadas.length} (${((aciertos / calificadas.length) * 100).toFixed(1)}%)`);
  console.log(`Reporte: ${ARCHIVO_SALIDA_HTML}`);
}

function generarHtml(filas, props, chunksProyecto) {
  const total = filas.length;
  const errores = filas.filter((r) => r.error).length;
  const calificadas = filas.filter((r) => !r.error);
  const aciertos = calificadas.filter((r) => r.correcta).length;
  const porDecision = { SUFICIENTE: 0, AMBIGUO: 0, INSUFICIENTE: 0 };
  calificadas.forEach((r) => porDecision[r.decision] = (porDecision[r.decision] ?? 0) + 1);
  const debianResponder = calificadas.filter((r) => r.esperado === "responde");
  const debianRechazar = calificadas.filter((r) => r.esperado === "rechaza");
  const aciertosResponder = debianResponder.filter((r) => r.correcta).length;
  const aciertosRechazar = debianRechazar.filter((r) => r.correcta).length;
  const latenciaProm = Math.round(filas.reduce((s, r) => s + (r.elapsedMs ?? 0), 0) / total / 1000);
  const categorias = [...new Set(filas.map((r) => r.categoria))];

  const filasHtml = filas
    .map((r) => {
      const rerankTxt = r.mejorRerank === null ? (r.sinPuerta ? "— (sin puerta)" : "—") : r.mejorRerank.toFixed(4);
      return `
      <tr class="${r.correcta ? "ok" : r.error ? "err" : "bad"}" data-categoria="${escapeHtml(r.categoria)}" data-correcta="${r.correcta}">
        <td>${r.id}</td>
        <td>${escapeHtml(r.categoria)}</td>
        <td class="pregunta">${escapeHtml(r.pregunta)}</td>
        <td class="respuesta"><details><summary>${escapeHtml((r.respuesta || "(sin respuesta)").slice(0, 90))}${r.respuesta.length > 90 ? "…" : ""}</summary>${escapeHtml(r.respuesta || r.error || "(sin respuesta)")}</details></td>
        <td>${r.esperado}</td>
        <td>${r.comportamiento}</td>
        <td class="centro">${r.error ? "⚠️ error" : r.correcta ? "✅" : "❌"}</td>
        <td class="num">${r.error ? "—" : rerankTxt}</td>
        <td class="num">${r.error ? "—" : (r.umbralUsado?.toFixed(4) ?? "—")}</td>
        <td class="num">${r.error ? "—" : r.techoConfianza}</td>
        <td>${r.error ? "—" : r.decision}</td>
        <td class="num">${(r.elapsedMs / 1000).toFixed(1)} s</td>
      </tr>`;
    })
    .join("");

  return `<!doctype html>
<html lang="es">
<head>
<meta charset="utf-8">
<title>Reporte de evaluación — 100 preguntas</title>
<style>
  :root { color-scheme: light dark; }
  body { font-family: system-ui, "Segoe UI", sans-serif; margin: 2rem; line-height: 1.4; }
  h1 { margin-bottom: 0.2rem; }
  .subtitulo { color: #777; margin-top: 0; }
  .resumen { display: flex; flex-wrap: wrap; gap: 1rem; margin: 1.5rem 0; }
  .tarjeta { border: 1px solid #8883; border-radius: 8px; padding: 0.9rem 1.2rem; min-width: 160px; }
  .tarjeta .valor { font-size: 1.6rem; font-weight: 700; }
  .tarjeta .etiqueta { font-size: 0.8rem; color: #888; text-transform: uppercase; letter-spacing: 0.03em; }
  .controles { margin: 1rem 0; display: flex; gap: 0.75rem; align-items: center; flex-wrap: wrap; }
  table { border-collapse: collapse; width: 100%; font-size: 0.85rem; }
  th, td { border: 1px solid #8884; padding: 0.4rem 0.5rem; vertical-align: top; }
  th { position: sticky; top: 0; background: Canvas; text-align: left; }
  td.pregunta { max-width: 260px; }
  td.respuesta { max-width: 320px; }
  td.respuesta details summary { cursor: pointer; }
  td.num, td.centro { text-align: center; white-space: nowrap; }
  tr.ok { background: color-mix(in srgb, green 12%, Canvas); }
  tr.bad { background: color-mix(in srgb, crimson 12%, Canvas); }
  tr.err { background: color-mix(in srgb, orange 15%, Canvas); }
  .contenedor-tabla { overflow-x: auto; max-height: 75vh; overflow-y: auto; border: 1px solid #8883; border-radius: 8px; }
  code { background: #8882; padding: 0.05rem 0.3rem; border-radius: 4px; }
</style>
</head>
<body>
  <h1>Reporte de evaluación — 100 preguntas (jls25.pdf)</h1>
  <p class="subtitulo">Umbral dinámico de relevancia (ADR-0008) recalculado a partir de <code>query_log</code>:
    piso=${props.piso}, techo=${props.techo}, chunksReferencia=${props.chunksReferencia}, techoConfianza=${props.techoConfianza},
    chunks del proyecto=${chunksProyecto}.</p>

  <div class="resumen">
    <div class="tarjeta"><div class="valor">${aciertos}/${calificadas.length}</div><div class="etiqueta">Precisión global (${((aciertos / calificadas.length) * 100).toFixed(1)}%)</div></div>
    <div class="tarjeta"><div class="valor">${aciertosResponder}/${debianResponder.length}</div><div class="etiqueta">Correctas entre las que debían responder</div></div>
    <div class="tarjeta"><div class="valor">${aciertosRechazar}/${debianRechazar.length}</div><div class="etiqueta">Correctas entre las que debían rechazar</div></div>
    <div class="tarjeta"><div class="valor">${porDecision.SUFICIENTE}</div><div class="etiqueta">Decisión SUFICIENTE</div></div>
    <div class="tarjeta"><div class="valor">${porDecision.AMBIGUO}</div><div class="etiqueta">Decisión AMBIGUO</div></div>
    <div class="tarjeta"><div class="valor">${porDecision.INSUFICIENTE}</div><div class="etiqueta">Decisión INSUFICIENTE</div></div>
    <div class="tarjeta"><div class="valor">${latenciaProm} s</div><div class="etiqueta">Latencia promedio por pregunta</div></div>
    <div class="tarjeta"><div class="valor">${errores}</div><div class="etiqueta">Errores de infraestructura (no calificados)</div></div>
  </div>

  <div class="controles">
    <label>Categoría: <select id="filtroCategoria"><option value="">(todas)</option>${categorias.map((c) => `<option>${escapeHtml(c)}</option>`).join("")}</select></label>
    <label>Solo incorrectas: <input type="checkbox" id="filtroIncorrectas"></label>
  </div>

  <div class="contenedor-tabla">
  <table id="tabla">
    <thead>
      <tr>
        <th>#</th><th>Categoría</th><th>Pregunta</th><th>Respuesta</th><th>Esperado</th>
        <th>Comportamiento real</th><th>¿Correcta?</th><th>Mejor rerank</th><th>Umbral(n)</th>
        <th>Techo confianza</th><th>Decisión</th><th>Latencia</th>
      </tr>
    </thead>
    <tbody>${filasHtml}</tbody>
  </table>
  </div>

  <script>
    const selCategoria = document.getElementById("filtroCategoria");
    const chkIncorrectas = document.getElementById("filtroIncorrectas");
    function aplicarFiltro() {
      const cat = selCategoria.value;
      const soloIncorrectas = chkIncorrectas.checked;
      for (const fila of document.querySelectorAll("#tabla tbody tr")) {
        const pasaCategoria = !cat || fila.dataset.categoria === cat;
        const pasaIncorrectas = !soloIncorrectas || fila.dataset.correcta === "false";
        fila.style.display = (pasaCategoria && pasaIncorrectas) ? "" : "none";
      }
    }
    selCategoria.addEventListener("change", aplicarFiltro);
    chkIncorrectas.addEventListener("change", aplicarFiltro);
  </script>
</body>
</html>`;
}

main();
