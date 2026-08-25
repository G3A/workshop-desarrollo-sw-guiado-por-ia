// Automatiza las 100 preguntas de preguntas.json contra la UI real (index.html)
// con Playwright, en serie (una a la vez: llama-server/Bonsai sirve un solo
// modelo en una sola GPU, correr en paralelo no acelera nada y solo arriesga
// timeouts cruzados). Vuelca la respuesta cruda de cada turno a
// resultados-brutos.json -- reporte.js hace despues el cruce con query_log y
// arma el HTML.
//
// Resumible: si resultados-brutos.json ya existe, las preguntas cuyo id ya
// este ahi se saltan -- permite reanudar tras una interrupcion sin repetir
// las que ya corrieron.

import { chromium } from "playwright";
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const DIR = path.dirname(fileURLToPath(import.meta.url));

const BASE_URL = process.env.EVAL_BASE_URL || "http://localhost:8080";
const PROYECTO = process.env.EVAL_PROYECTO || "default";
const LIMITE = process.env.EVAL_LIMITE ? Number(process.env.EVAL_LIMITE) : Infinity;
// KB_LLM_TIMEOUT del perfil Bonsai (450s, ver compose.bonsai.yml) es el tope
// de UNA sola llamada al LLM -- pero una pregunta puede disparar VARIAS
// llamadas secuenciales (planificador + verificador de grounding + sintesis).
// Medido en vivo con 480_000 (8 min): ya se vio un timeout real en una
// pregunta de la zona AMBIGUO antes de completar. 900_000 (15 min) da margen
// para el peor caso de las tres llamadas encadenadas sin abortar de más.
const TIMEOUT_MS = Number(process.env.EVAL_TIMEOUT_MS || 900_000);
// Lista de ids separados por coma (p. ej. "1,6,12,23,31,64") para correr solo
// una muestra puntual -- confirmar en el pipeline real un puñado de preguntas
// tras un cambio de configuración, sin repetir las 100 completas.
const IDS = process.env.EVAL_IDS
  ? new Set(process.env.EVAL_IDS.split(",").map((s) => Number(s.trim())))
  : null;
// Sufijo para no pisar resultados-brutos.json de una corrida completa al
// correr una muestra puntual (EVAL_IDS) contra la misma carpeta.
const SUFIJO = process.env.EVAL_SUFIJO ? `.${process.env.EVAL_SUFIJO}` : "";

const ARCHIVO_PREGUNTAS = path.join(DIR, "preguntas.json");
const ARCHIVO_RESULTADOS = path.join(DIR, `resultados-brutos${SUFIJO}.json`);

const todas = JSON.parse(readFileSync(ARCHIVO_PREGUNTAS, "utf8"));
const limitadas = todas.slice(0, Number.isFinite(LIMITE) ? LIMITE : todas.length);
const preguntas = IDS ? limitadas.filter((p) => IDS.has(p.id)) : limitadas;

const resultados = existsSync(ARCHIVO_RESULTADOS)
  ? JSON.parse(readFileSync(ARCHIVO_RESULTADOS, "utf8"))
  : [];
const yaHechas = new Set(resultados.map((r) => r.id));

function guardar() {
  writeFileSync(ARCHIVO_RESULTADOS, JSON.stringify(resultados, null, 2), "utf8");
}

// Una pestaña nueva por pregunta, no una sola reusada 100 veces: con una sola
// pestaña, el #historial de index.html acumula un <div class="turno"> por
// respuesta (texto completo, citas, etc.) durante toda la corrida -- medido
// en vivo, esto hizo que Chromium headless muriera con "Page crashed" a la
// octava pregunta, y como la pestaña ya estaba muerta, TODAS las preguntas
// siguientes fallaban igual sin que el mensaje de arranque lo reflejara
// (arranca de nuevo, cuenta solo lo que de verdad esta en el JSON). Abrir una
// pestaña limpia por pregunta cuesta segundos, insignificante frente a los
// minutos que tarda cada respuesta de Bonsai.
async function abrirPagina(browser) {
  const page = await browser.newPage();
  await page.goto(BASE_URL + "/");
  await page.waitForSelector("#proyecto");
  await page.selectOption("#proyecto", PROYECTO).catch(() => {
    console.warn(`Aviso: no se pudo seleccionar el proyecto "${PROYECTO}" (¿no existe esa opción?), sigo con el valor por defecto.`);
  });
  return page;
}

async function main() {
  const pendientes = preguntas.filter((p) => !yaHechas.has(p.id));
  console.log(`Total: ${preguntas.length} | ya hechas: ${yaHechas.size} | pendientes: ${pendientes.length}`);
  if (pendientes.length === 0) {
    console.log("Nada que correr -- borra resultados-brutos.json para repetir desde cero.");
    return;
  }

  let browser = await chromium.launch();

  for (const item of pendientes) {
    const inicio = new Date();
    console.log(`[${item.id}/${preguntas.length}] ${item.pregunta}`);

    let respuesta = "";
    let estadoFinal = "";
    let error = null;
    let page = null;
    try {
      if (!browser.isConnected()) {
        console.warn("  -> el navegador se había desconectado, relanzando...");
        browser = await chromium.launch();
      }
      page = await abrirPagina(browser);

      const antes = await page.locator(".turno").count();
      await page.fill("#pregunta", item.pregunta);
      await page.click("#boton-preguntar");
      await page.waitForFunction((n) => document.querySelectorAll(".turno").length > n, antes, { timeout: 10_000 });
      await page.waitForSelector("#boton-preguntar:not([disabled])", { timeout: TIMEOUT_MS });

      const ultimo = page.locator(".turno").last();
      respuesta = (await ultimo.locator(".turno-respuesta").innerText()).trim();
      estadoFinal = (await ultimo.locator(".turno-estado").innerText()).trim();
      const estadoEsError = (await ultimo.locator(".turno-estado.error").count()) > 0;
      if (estadoEsError) {
        // app.js marca fuente.onerror con la clase "error" en turno-estado
        // (p. ej. "se perdió la conexión con el servidor") -- eso no es un
        // rechazo por umbral, es una falla de infraestructura (Ollama/llama-
        // server caido o timeout de red): no se debe calificar como
        // correcta/incorrecta contra "esperado", se marca como error.
        error = estadoFinal || "conexión SSE interrumpida (turno-estado.error)";
      }
    } catch (e) {
      error = e.message;
      console.warn(`  -> error/timeout: ${error}`);
      // browser.isConnected() no detecta este caso: el proceso principal de
      // Chromium sigue vivo (isConnected() = true) pero queda corrupto y
      // browser.newPage() falla siempre igual -- medido en vivo (sesion de
      // Ministral), 62 preguntas seguidas cayeron en este loop sin que el
      // chequeo de isConnected() al inicio del siguiente ciclo lo detectara.
      // Cerrar y relanzar apenas se ve un error de este tipo corta el loop.
      // "closed" (no solo "Target closed") agregado en la sesion 16: el
      // mensaje real que devuelve chromium.launch() cuando el proceso muere
      // durante el arranque es "Target page, context or browser has been
      // closed" -- no matcheaba ninguna de las tres alternativas de arriba,
      // asi que el catch caia aca sin relanzar nada, dejaba `browser` en su
      // referencia vieja ya muerta, y CADA pregunta siguiente repetia el
      // mismo fallo instantaneo sin esperar ni reintentar -- medido en vivo,
      // asi se perdieron 50 preguntas seguidas (51-100) en ~14 minutos en vez
      // de las ~3 horas que hubieran tomado de verdad.
      if (/crashed|closed|disconnected|Protocol error/i.test(error)) {
        console.warn("  -> parece un browser corrupto, cerrando y relanzando...");
        await browser.close().catch(() => {});
        // El relanzamiento mismo puede fallar -- medido en vivo dos causas
        // distintas: (a) una interrupcion de la sesion de terminal a mitad
        // de una corrida mato procesos huerfanos de Chromium y dejo el
        // entorno inconsistente para el siguiente launch(); (b) presion real
        // de memoria del sistema (vmmemWSL + Docker + el resto de apps
        // abiertas dejando <4 GB libres de 33 GB) hizo que
        // chrome-headless-shell.exe muriera con exitCode=3221225794
        // (0xC0000005, access violation) incluso en el reintento. Sin este
        // try/catch, esa excepcion escapaba del catch de arriba y tumbaba
        // TODO el proceso -- ya paso dos veces, una con 47 preguntas
        // pendientes y otra con 25. Varios intentos con espera creciente
        // (5s, 15s, 30s) le da tiempo a que la presion de memoria baje en
        // vez de asumir que un solo reintento alcanza.
        let relanzado = false;
        for (const esperaMs of [5_000, 15_000, 30_000]) {
          await new Promise((r) => setTimeout(r, esperaMs));
          try {
            browser = await chromium.launch();
            relanzado = true;
            break;
          } catch (e2) {
            console.warn(`  -> el relanzamiento tambien fallo (${e2.message}), reintentando en ${esperaMs / 1000}s...`);
          }
        }
        if (!relanzado) {
          throw new Error("no se pudo relanzar el navegador tras varios intentos, abortando la corrida");
        }
      }
    } finally {
      if (page) {
        await page.close().catch(() => {});
      }
    }

    const fin = new Date();
    resultados.push({
      id: item.id,
      categoria: item.categoria,
      pregunta: item.pregunta,
      esperado: item.esperado,
      respuesta,
      estadoFinal,
      error,
      inicio: inicio.toISOString(),
      fin: fin.toISOString(),
      elapsedMs: fin - inicio,
    });
    guardar(); // progreso incremental: una corrida larga no pierde todo si se corta a la mitad

    console.log(`  -> ${fin - inicio} ms | ${respuesta ? respuesta.replace(/\n/g, " ") : "(sin respuesta)"}`);
  }

  await browser.close().catch(() => {});
  console.log(`Listo. ${resultados.length} resultados en ${ARCHIVO_RESULTADOS}.`);
  console.log("Corre 'npm run reporte' para cruzar con query_log y generar reporte.html.");
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
