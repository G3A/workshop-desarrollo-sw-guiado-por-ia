// Conformidad de un acta con sus instructivos (#222, fase 4; PC-04, PC-05 y PC-06), desde la raiz
// del repo:
//
//   node scripts/acta/conformidad.mjs <acta.cruda.jsonl | acta.curada.jsonl>
//
// Compara la secuencia de pasos que las skills marcaron (docs/protocolo-de-marcadores-de-paso.md)
// con la que prescribe cada instructivo, en la version que la huella del acta registro, y escribe
// el resultado en stdout como JSON: pasos omitidos, repetidos, fuera de orden y no prescritos, y
// las acciones que no quedaron en ningun paso.
//
// Tres decisiones que no son de estilo:
//
// 1. Sin marcadores no hay conformidad: el estado es «sin datos» y no se lista nada. Las acciones
//    de un acta sin marcar no estan «sin paso» por descuido de nadie; inferir sus pasos meteria
//    una heuristica en el registro (PC-04).
// 2. Los pasos prescritos son los encabezados `Phase N` y `Step X` del SKILL.md, en el orden en
//    que aparecen, leidos del blob que la huella registro, no del archivo de hoy. Sin ese
//    documento en la huella, la actividad dice que ejecuto pero no contra que se compara.
// 3. Marcar el mismo paso dos veces seguidas, por ejemplo al retomar despues de una respuesta de
//    la persona, es UNA ejecucion. Repetido es volver a un paso despues de pasar por otro.
//
// Los hechos se reportan, no se juzgan: volver de la Phase 5 a la 3 es lo que debt-triage manda
// cuando falla una fila de su gate, y una Phase 1 que no encuentra analizador termina ahi. La
// lectura queda para quien mira el reporte.
//
// Codigos de salida: 0 reporte escrito, 2 uso incorrecto.
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { git, raizDelRepo } from './git.mjs';
import { leerActa } from './validar-acta.mjs';
import { BLOQUE_DE_CODIGO, PLAN_DE_ISSUE } from './compilar-acta.mjs';

const RUTA_SKILL = /(^|\/)skills\/([^/]+)\/SKILL\.md$/;
const RUTA_REFERENCIA = /(^|\/)skills\/([^/]+)\/references\/([^/]+\.md)$/;
// Un numero o UNA mayuscula: «## Step markers» es un encabezado sobre los marcadores, no un paso.
const ENCABEZADO = /^#{2,4}\s*`?(Phase|Step)\s+([0-9]+|[A-Z])\b`?\s*(?:[—:-]+\s*)?(.*)$/gm;

// Una fila de la tabla de fases, como en requirement-to-spec-java: «| 1 — Discover (silent) | …».
const FILA_DE_FASE = /^\|\s*([0-9]+|[A-Z])\s*[—:-]+\s*([^|]+?)\s*\|/;

// Los pasos prescritos de un instructivo, en orden: sus encabezados Phase/Step o, si no tiene,
// las filas de la tabla cuya primera columna es «Phase» o «Step» (#230).
export function pasosPrescritos(texto) {
  const encabezados = [...String(texto).matchAll(ENCABEZADO)]
    .map((m, i) => ({ letra: m[2], titulo: m[3].trim(), orden: i + 1 }));
  if (encabezados.length) return encabezados;
  const lineas = String(texto).split(/\r?\n/);
  const inicio = lineas.findIndex((l) => /^\|\s*(Phase|Step)\s*\|/.test(l));
  if (inicio < 0) return [];
  const pasos = [];
  for (const l of lineas.slice(inicio + 2)) {
    if (!l.startsWith('|')) break;
    const m = FILA_DE_FASE.exec(l);
    if (m) pasos.push({ letra: m[1], titulo: m[2], orden: pasos.length + 1 });
  }
  return pasos;
}

// Los pasos del plan de un issue (#230): sus encabezados numerados, con o sin la palabra
// «Fase», como «## Fase 2 · Motor de re-ejecución» o «## 5 · Actividad declarada».
const PASO_DEL_PLAN =
  /^#{2,3}\s*(?:(?:Fase|Paso|Phase|Step)\s+)?([0-9]+|[A-Z])\s*[·:—.-]\s*(.+)$/gm;

export function pasosDelPlan(texto) {
  return [...String(texto).replace(BLOQUE_DE_CODIGO, '').matchAll(PASO_DEL_PLAN)]
    .map((m, i) => ({ letra: m[1], titulo: m[2].trim(), orden: i + 1 }));
}

// Los pasos que el SKILL.md delega en sus references/ (#230). Solo cuentan los `Step <LETRA>`: un
// `Phase N` de un reference repite el mapa del SKILL.md, y un `Step 1` chocaria con la Phase 1. Van
// anidados bajo la Phase cuya seccion nombra el archivo, en el orden en que los nombra.
const STEP_CON_LETRA = /^#{2,4}\s*`?Step\s+([A-Z])\b`?\s*(?:[—:-]+\s*)?(.*)$/gm;
const SECCION_DE_FASE = /^## `?Phase\s+([0-9]+)\b[^\n]*\n([\s\S]*?)(?=^## |(?![\s\S]))/gm;

// Un regreso que el instructivo manda, declarado en el SKILL.md: «**Loop:** from Step I back to
// Step G». Volver asi no es repetir ni salir de orden: es una iteracion.
const PASO = '(?:Step|Phase) ([0-9]+|[A-Z])';
const BUCLE = new RegExp(`\\*\\*Loop:\\*\\* from ${PASO} back to ${PASO}\\b`, 'g');

export function bucles(textoSkill) {
  return [...String(textoSkill).matchAll(BUCLE)].map((m) => ({ desde: m[1], hacia: m[2] }));
}

export function pasosDeLaSkill(textoSkill, leerReferencia = () => null) {
  const fases = pasosPrescritos(textoSkill);
  const anidados = new Map();
  const faltan = [];
  for (const m of String(textoSkill).matchAll(SECCION_DE_FASE)) {
    const nombrados = [...m[2].matchAll(/references\/([\w.-]+\.md)/g)].map((x) => x[1]);
    const archivos = [...new Set(nombrados)];
    const pasos = [];
    for (const archivo of archivos) {
      const texto = leerReferencia(archivo);
      if (texto === null) {
        faltan.push(archivo);
        continue;
      }
      for (const s of String(texto).matchAll(STEP_CON_LETRA)) {
        pasos.push({ letra: s[1], titulo: s[2].trim(), dentroDe: m[1], archivo });
      }
    }
    if (pasos.length) anidados.set(m[1], pasos);
  }
  const todos = [];
  for (const f of fases) {
    todos.push(f);
    for (const p of anidados.get(f.letra) || []) todos.push(p);
  }
  return { pasos: todos.map((p, i) => ({ ...p, orden: i + 1 })), faltan };
}

function textoDelBlob(raizRepo, hash) {
  if (!raizRepo || !hash) return null;
  return git(['cat-file', 'blob', hash], { cwd: raizRepo, permitirFallo: true });
}

export function conformidad(registros, { raizRepo = null, planDelIssue = null } = {}) {
  const cabecera = registros.find((r) => r.elemento === 'acta');
  const pasos = registros.filter((r) => r.elemento === 'paso');
  const marcados = pasos.filter((p) => p.procedencia === 'marcado');
  const base = { sesion: cabecera.sesion, tarea: cabecera.tarea, fase: cabecera.fase };
  if (!marcados.length) {
    return { ...base, estado: 'sin datos',
      motivo: 'ninguna skill marco sus pasos en esta acta', actividades: [],
      accionesSinPaso: null };
  }

  const ausentes = new Set(pasos.filter((p) => p.procedencia === 'ausente').map((p) => p.id));
  const accionesSinPaso = registros
    .filter((r) => r.elemento === 'accion' && ausentes.has(r.paso)).map((a) => a.id);

  const instructivos = new Map();
  const referencias = new Map();
  for (const d of cabecera.huella?.documentos || []) {
    const m = RUTA_SKILL.exec(d.ruta);
    if (m) instructivos.set(m[2], d);
    const r = RUTA_REFERENCIA.exec(d.ruta);
    if (r) referencias.set(`${r[2]}/${r[3]}`, d);
  }

  const skills = [...new Set(marcados.map((p) => p.pasoPrescrito.skill))].sort();
  const actividades = skills.map((skill) => {
    // La secuencia ejecutada, sin las marcas seguidas del mismo paso (decision 3).
    const ejecutados = [];
    for (const p of marcados.filter((x) => x.pasoPrescrito.skill === skill)) {
      if (ejecutados[ejecutados.length - 1] !== p.pasoPrescrito.letra) {
        ejecutados.push(p.pasoPrescrito.letra);
      }
    }
    // El plan de un issue no esta en el repo: su texto lo guardo registrar-sesion.mjs, con la
    // fecha en que se leyo y su sha256 (#230).
    const esPlan = skill === PLAN_DE_ISSUE;
    const doc = esPlan ? null : instructivos.get(skill) || null;
    const texto = esPlan ? planDelIssue?.cuerpo ?? null
      : doc ? textoDelBlob(raizRepo, doc.hash) : null;
    const instructivo = esPlan && planDelIssue
      ? { ruta: `issue #${planDelIssue.numero}`, actualizado: planDelIssue.actualizado,
        sha256: planDelIssue.sha256 }
      : doc ? { ruta: doc.ruta, commit: doc.commit, hash: doc.hash } : null;
    if (texto === null) {
      return { skill, instructivo, ejecutados, prescritos: null, omitidos: null,
        repetidos: null, fueraDeOrden: null, noPrescritos: null,
        motivo: esPlan ? 'no se guardo el plan del issue al registrar la sesion'
          : doc ? 'el blob del instructivo no esta en el repo'
            : 'la huella del acta no registra su instructivo' };
    }
    const leerReferencia = (archivo) => {
      const d = referencias.get(`${skill}/${archivo}`);
      return d ? textoDelBlob(raizRepo, d.hash) : null;
    };
    const deLaSkill = esPlan ? { pasos: pasosDelPlan(texto), faltan: [] }
      : pasosDeLaSkill(texto, leerReferencia);
    const prescritos = deLaSkill.pasos;
    const declarados = esPlan ? [] : bucles(texto);
    const orden = new Map(prescritos.map((p) => [p.letra, p.orden]));
    const vistos = new Set(ejecutados);
    // Una vuelta por un bucle declarado: de un paso entre «hacia» y «desde» se vuelve a «hacia».
    // Mientras dure la vuelta, pasar otra vez por esos pasos no es repetirlos.
    const repetidos = new Set();
    const fueraDeOrden = [];
    const iteraciones = new Map();
    const yaVistos = new Set();
    let maximo = 0;
    let vuelta = null;
    ejecutados.forEach((letra, i) => {
      const n = orden.get(letra);
      if (n === undefined) return;
      if (vuelta && (n < vuelta.de || n > vuelta.a)) vuelta = null;
      const bucle = n < maximo && declarados.find((b) => b.hacia === letra &&
        orden.get(ejecutados[i - 1]) <= orden.get(b.desde) &&
        orden.get(ejecutados[i - 1]) >= n);
      if (bucle) {
        const clave = `${bucle.desde}>${bucle.hacia}`;
        iteraciones.set(clave, (iteraciones.get(clave) || 0) + 1);
        vuelta = { de: n, a: orden.get(bucle.desde) };
        maximo = n;
        return;
      }
      if (yaVistos.has(letra) && !vuelta) repetidos.add(letra);
      if (n < maximo) fueraDeOrden.push({ letra, despuesDe: ejecutados[i - 1] });
      yaVistos.add(letra);
      maximo = Math.max(maximo, n);
    });
    return {
      skill,
      instructivo,
      ejecutados,
      prescritos,
      omitidos: prescritos.map((p) => p.letra).filter((l) => !vistos.has(l)),
      repetidos: [...repetidos],
      fueraDeOrden,
      iteraciones: [...iteraciones].map(([k, veces]) => {
        const [desde, hacia] = k.split('>');
        return { desde, hacia, veces };
      }),
      referenciasFaltantes: deLaSkill.faltan,
      noPrescritos: [...vistos].filter((l) => !orden.has(l)),
      motivo: null,
    };
  });
  return { ...base, estado: 'con datos', motivo: null, actividades, accionesSinPaso };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const [acta, ...resto] = process.argv.slice(2);
  if (!acta || resto.length || !fs.existsSync(acta)) {
    console.error('Uso: node scripts/acta/conformidad.mjs <acta.cruda.jsonl | acta.curada.jsonl>');
    process.exitCode = 2;
  } else {
    const reporte = conformidad(leerActa(acta), { raizRepo: raizDelRepo(process.cwd()) });
    process.stdout.write(JSON.stringify(reporte, null, 2) + '\n');
  }
}
