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

const RUTA_SKILL = /(^|\/)skills\/([^/]+)\/SKILL\.md$/;
// Un numero o UNA mayuscula: «## Step markers» es un encabezado sobre los marcadores, no un paso.
const ENCABEZADO = /^#{2,4}\s*`?(Phase|Step)\s+([0-9]+|[A-Z])\b`?\s*(?:[—:-]+\s*)?(.*)$/gm;

// Los pasos prescritos de un instructivo, en orden.
export function pasosPrescritos(texto) {
  return [...String(texto).matchAll(ENCABEZADO)]
    .map((m, i) => ({ letra: m[2], titulo: m[3].trim(), orden: i + 1 }));
}

function textoDelBlob(raizRepo, hash) {
  if (!raizRepo || !hash) return null;
  return git(['cat-file', 'blob', hash], { cwd: raizRepo, permitirFallo: true });
}

export function conformidad(registros, { raizRepo = null } = {}) {
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
  for (const d of cabecera.huella?.documentos || []) {
    const m = RUTA_SKILL.exec(d.ruta);
    if (m) instructivos.set(m[2], d);
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
    const doc = instructivos.get(skill) || null;
    const texto = doc ? textoDelBlob(raizRepo, doc.hash) : null;
    const instructivo = doc ? { ruta: doc.ruta, commit: doc.commit, hash: doc.hash } : null;
    if (texto === null) {
      return { skill, instructivo, ejecutados, prescritos: null, omitidos: null,
        repetidos: null, fueraDeOrden: null, noPrescritos: null,
        motivo: doc ? 'el blob del instructivo no esta en el repo'
          : 'la huella del acta no registra su instructivo' };
    }
    const prescritos = pasosPrescritos(texto);
    const orden = new Map(prescritos.map((p) => [p.letra, p.orden]));
    const vistos = new Set(ejecutados);
    const repetidos = [...vistos].filter((l) => ejecutados.filter((x) => x === l).length > 1);
    const fueraDeOrden = [];
    let maximo = 0;
    ejecutados.forEach((letra, i) => {
      const n = orden.get(letra);
      if (n === undefined) return;
      if (n < maximo) fueraDeOrden.push({ letra, despuesDe: ejecutados[i - 1] });
      maximo = Math.max(maximo, n);
    });
    return {
      skill,
      instructivo,
      ejecutados,
      prescritos,
      omitidos: prescritos.map((p) => p.letra).filter((l) => !vistos.has(l)),
      repetidos,
      fueraDeOrden,
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
