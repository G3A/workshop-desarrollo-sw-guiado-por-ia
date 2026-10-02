// Hook de captura del acta (#216). Lo llaman SessionStart, PreToolUse, PostToolUse,
// PostToolUseFailure y SessionEnd desde .claude/settings.json:
//
//   node "$CLAUDE_PROJECT_DIR/scripts/acta/capturar.mjs"
//
// Agrega una linea a .ia/captura/<sesion>.jsonl con lo que el transcript no guarda: el HEAD y el
// hash del arbol, antes y despues de cada accion. En SessionEnd compila el acta cruda, la cura y
// reescribe el indice de cada tarea que toco.
//
// Cuatro decisiones que no son de estilo:
//
// 1. NUNCA bloquea la sesion. Todo error termina en exit 0 con un aviso en stderr: un registro
//    que rompe el trabajo que registra termina desinstalado.
// 2. Las lecturas (Read, Grep, Glob, WebSearch...) no capturan el arbol: no lo cambian, y cada
//    hash cuesta unos 70 ms en este repo. Su accion queda con el arbol en null.
// 3. Antes Y despues de cada accion, no solo despues. Los hooks de un mismo evento corren en
//    paralelo, asi que el `despues` puede tomarse antes de que format-on-edit termine; el
//    `antes` de la accion siguiente si lo incluye. La diferencia entre los dos es justo lo que la
//    curacion necesita para separar el efecto de un hook.
// 4. En cada evento solo carga nucleo-captura.mjs, que no importa nada del repo (#222). El
//    compilador, la curacion y el indice se cargan al cerrar, dentro del try: una sesion que deja
//    uno de ellos con un error de sintaxis pierde el acta de ese cierre, no la captura.
import fs from 'node:fs';
import path from 'node:path';
import { SIN_ARBOL, arbolActual, head, raizDelRepo } from './nucleo-captura.mjs';

function leerEntrada() {
  try {
    return JSON.parse(fs.readFileSync(0, 'utf8'));
  } catch {
    return null;
  }
}

async function principal() {
  const e = leerEntrada();
  if (!e || !e.session_id || !e.hook_event_name) return;
  const raiz = raizDelRepo(e.cwd || process.cwd());
  if (!raiz) return;
  const carpeta = path.join(raiz, '.ia', 'captura');
  fs.mkdirSync(carpeta, { recursive: true });
  const archivo = path.join(carpeta, `${e.session_id}.jsonl`);
  const evento = e.hook_event_name;
  const momento = new Date().toISOString();
  let registro = { evento, momento };

  if (evento === 'SessionStart') {
    registro = {
      ...registro,
      fuente: e.source || null,
      head: head(raiz),
      arbol: arbolActual(raiz),
    };
  } else if (['PreToolUse', 'PostToolUse', 'PostToolUseFailure'].includes(evento)) {
    const herramienta = e.tool_name || null;
    registro = {
      ...registro,
      toolUseId: e.tool_use_id || null,
      herramienta,
      agenteId: e.agent_id || null,
      arbol: SIN_ARBOL.has(herramienta) ? null : arbolActual(raiz),
    };
  } else if (evento === 'SessionEnd') {
    // El arbol al cerrar deja ver lo que un hook cambio despues de la ultima accion (#218).
    registro = { ...registro, motivo: e.reason || null, arbol: arbolActual(raiz) };
  } else {
    return;
  }
  fs.appendFileSync(archivo, JSON.stringify(registro) + '\n');

  if (evento === 'SessionEnd' && e.transcript_path && fs.existsSync(e.transcript_path)) {
    try {
      await cerrar({ raiz, captura: archivo, transcript: e.transcript_path });
    } catch (err) {
      process.stderr.write(`acta: el cierre quedo capturado y el acta fallo (${err.message})\n`);
    }
  }
}

async function cerrar({ raiz, captura, transcript }) {
  let modulos;
  try {
    modulos = await Promise.all([
      import('./compilar-acta.mjs'), import('./curar-acta.mjs'), import('./indexar-tarea.mjs'),
    ]);
  } catch (err) {
    process.stderr.write('acta: la captura quedo escrita, pero el compilador no carga y el ' +
      `acta de este cierre no sale (${err.message})\n`);
    return;
  }
  const [{ compilarYEscribir }, { curarYEscribir }, { indexarYEscribir }] = modulos;
  const log = { log: () => {}, error: (m) => process.stderr.write(`acta: ${m}\n`) };
  const escritas = [];
  const codigo = compilarYEscribir({
    transcript,
    captura,
    salida: path.join(raiz, '.ia', 'registros'),
    raizRepo: raiz,
    log,
    escritas,
  });
  if (codigo !== 0) process.stderr.write(`acta: la compilacion termino con codigo ${codigo}\n`);
  // La curada de cada acta escrita (#218). Una curada de un cierre anterior de la misma sesion
  // (una sesion reanudada) se borra antes: si esta curacion no sale, no puede quedar una curada
  // vieja junto a una cruda nueva.
  for (const cruda of escritas) {
    fs.rmSync(cruda.replace(/\.acta\.cruda\.jsonl$/, '.acta.curada.jsonl'), { force: true });
    const curacion = curarYEscribir({ cruda, raizRepo: raiz, log });
    if (curacion !== 0) {
      const tarea = path.basename(path.dirname(cruda));
      process.stderr.write(`acta: la curacion de la tarea ${tarea} termino con codigo ` +
        `${curacion}; la cruda quedo escrita\n`);
    }
  }
  // El indice de cada tarea, despues de curar: dice si cada acta tiene curada (#222).
  for (const tarea of new Set(escritas.map((c) => path.dirname(c)))) {
    indexarYEscribir(tarea, { log });
  }
}

try {
  await principal();
} catch (err) {
  process.stderr.write(`acta: la captura fallo y no se registro este evento (${err.message})\n`);
}
process.exitCode = 0;
