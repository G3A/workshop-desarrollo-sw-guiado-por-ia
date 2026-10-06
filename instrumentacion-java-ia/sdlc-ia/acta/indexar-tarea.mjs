// Indice de una tarea (#222; PC-02 y PC-03), desde la raiz del repo:
//
//   node $ACTA/indexar-tarea.mjs <.ia/registros/<tarea>>
//
// Escribe <carpeta>/indice.json con las actas crudas de la tarea, una por sesion: su inicio, su
// fin, sus ramas, el sha256 de sus bytes y si tiene curada. Responde PC-02 sin abrir ninguna acta,
// y lleva ademas el indice inverso de PC-03: por cada archivo que la tarea cambio, las acciones
// que lo cambiaron, de que sesion y de que agente. El hook lo reescribe en cada SessionEnd.
//
// Tres decisiones que no son de estilo:
//
// 1. DETERMINISTA, como el acta: las mismas actas producen el mismo indice, byte a byte. Sin la
//    hora en que se indexo; actas en orden de inicio y de sesion, archivos en orden de ruta y
//    acciones en el orden de su acta.
// 2. El indice inverso sale de la CRUDA: lista tambien los fallos que dejaron residuo, los
//    efectos de hook y los subagentes descartados. Un archivo que cambio y volvio a quedar igual
//    figura igual, porque la pregunta es que acciones lo tocaron, no como termino.
// 3. La fase de cada acta es la que declararon sus pasos marcados, o null; la tarea lista en
//    `fases` las de todas sus actas (#222, fase 4). Una tarea puede pasar por varias.
//
// Codigos de salida: 0 escrito, 2 uso incorrecto. Un acta que no se puede leer no detiene el
// indice: sale de el con un aviso en stderr.
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const SUFIJO_CRUDA = '.acta.cruda.jsonl';
const SUFIJO_CURADA = '.acta.curada.jsonl';

// Por cada archivo cambiado, las acciones que lo cambiaron, en el orden del acta.
export function indiceInverso(registros) {
  const archivos = new Map();
  for (const a of registros) {
    if (a.elemento !== 'accion') continue;
    for (const c of a.cambios || []) {
      if (!archivos.has(c.archivo)) archivos.set(c.archivo, []);
      archivos.get(c.archivo).push({ accion: a.id, agente: a.agente });
    }
  }
  return new Map([...archivos].sort(([x], [y]) => (x < y ? -1 : x > y ? 1 : 0)));
}

export function indexarTarea(carpeta, { log = console } = {}) {
  const actas = [];
  const archivos = new Map();
  for (const nombre of fs.readdirSync(carpeta).filter((n) => n.endsWith(SUFIJO_CRUDA)).sort()) {
    const texto = fs.readFileSync(path.join(carpeta, nombre), 'utf8');
    let registros;
    try {
      registros = texto.split('\n').filter(Boolean).map((l) => JSON.parse(l));
    } catch {
      log.error(`aviso: ${nombre} no es JSONL y queda fuera del indice`);
      continue;
    }
    const cabecera = registros[0];
    if (cabecera?.elemento !== 'acta') {
      log.error(`aviso: ${nombre} no empieza con la cabecera del acta y queda fuera del indice`);
      continue;
    }
    const curada = nombre.slice(0, -SUFIJO_CRUDA.length) + SUFIJO_CURADA;
    actas.push({
      sesion: cabecera.sesion,
      cruda: nombre,
      sha256: crypto.createHash('sha256').update(texto).digest('hex'),
      curada: fs.existsSync(path.join(carpeta, curada)) ? curada : null,
      ramas: cabecera.ramas,
      fase: cabecera.fase,
      inicio: cabecera.inicio,
      fin: cabecera.fin,
      inverso: indiceInverso(registros),
    });
  }
  actas.sort((p, q) => ((p.inicio || '') < (q.inicio || '') ? -1
    : (p.inicio || '') > (q.inicio || '') ? 1 : p.sesion < q.sesion ? -1 : 1));
  for (const a of actas) {
    for (const [archivo, acciones] of a.inverso) {
      if (!archivos.has(archivo)) archivos.set(archivo, []);
      archivos.get(archivo).push(...acciones.map((x) => ({ sesion: a.sesion, ...x })));
    }
    delete a.inverso;
  }
  const momentos = actas.flatMap((a) => [a.inicio, a.fin]).filter(Boolean).sort();
  return {
    tarea: path.basename(carpeta),
    fases: [...new Set(actas.map((a) => a.fase).filter((x) => x !== null))].sort(),
    inicio: momentos[0] || null,
    fin: momentos[momentos.length - 1] || null,
    ramas: [...new Set(actas.flatMap((a) => a.ramas || []))].sort(),
    actas,
    archivos: Object.fromEntries([...archivos].sort(([x], [y]) => (x < y ? -1 : x > y ? 1 : 0))),
  };
}

export function indexarYEscribir(carpeta, { log = console } = {}) {
  const destino = path.join(carpeta, 'indice.json');
  fs.writeFileSync(destino, JSON.stringify(indexarTarea(carpeta, { log }), null, 2) + '\n');
  return destino;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const [carpeta, ...resto] = process.argv.slice(2);
  if (!carpeta || resto.length || !fs.existsSync(carpeta) || !fs.statSync(carpeta).isDirectory()) {
    console.error('Uso: node $ACTA/indexar-tarea.mjs <.ia/registros/<tarea>>');
    process.exitCode = 2;
  } else {
    console.log(`Indice de la tarea: ${indexarYEscribir(carpeta)}`);
  }
}
