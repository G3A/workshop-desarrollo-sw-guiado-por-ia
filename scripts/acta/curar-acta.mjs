// Curacion del acta (#218, ADR-0005 punto 3), desde la raiz del repo:
//
//   node scripts/acta/curar-acta.mjs <acta.cruda.jsonl> [--salida <acta.curada.jsonl>]
//
// Deriva el acta curada de la cruda y la escribe junto a ella, como <sesion>.acta.curada.jsonl.
// La curada es lo que re-ejecuta el motor: solo lo que salio bien.
//
// Lo que entra (primer comentario del #218):
//
// 1. Las acciones con `exito = true`, de todos los agentes: orquestador, subagentes y persona.
//    Una accion sin resultado (exito null) no entra: no se sabe que salio bien.
// 2. Cada accion conserva su id de la cruda, y lo mismo pasos, turnos y decisiones: el vinculo
//    entre las dos actas es directo, sin tabla de traduccion.
// 3. Un paso entra solo si conserva al menos una accion, y un turno solo si conserva al menos un
//    paso (invariante I6). Los turnos de solo texto quedan en la cruda.
// 4. Agentes, decisiones e intervenciones se filtran a los que apuntan a lo que quedo. Una
//    decision pierde de `motiva` las acciones que salieron; una intervencion sobre una accion que
//    salio, sale con ella.
//
// DETERMINISTA (invariante I8): es un filtro que conserva el orden de la cruda, y la cabecera
// guarda el sha256 de los bytes de la cruda en `derivadaDe`. No lleva la hora de la curacion.
//
// No pasa por gitleaks: la curada es un subconjunto de la cruda, que ya paso antes de escribirse.
//
// Codigos de salida: 0 escrita, 1 la curada rompe una invariante y no se escribio, 2 uso
// incorrecto.
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { validarActa } from './validar-acta.mjs';
import { serializar } from './compilar-acta.mjs';

const SUFIJO_CRUDA = '.acta.cruda.jsonl';

// La curacion como funcion pura sobre el texto de la cruda: deriva de esos bytes y no de otros.
export function curar(textoCruda) {
  const registros = textoCruda.split('\n').filter(Boolean).map((l) => JSON.parse(l));
  const de = (elemento) => registros.filter((r) => r.elemento === elemento);
  const cabecera = registros.find((r) => r.elemento === 'acta');

  const acciones = de('accion').filter((a) => a.exito === true);
  const idsAccion = new Set(acciones.map((a) => a.id));
  const idsPaso = new Set(acciones.map((a) => a.paso));
  const pasos = de('paso').filter((p) => idsPaso.has(p.id));
  const idsTurno = new Set(pasos.map((p) => p.turno));
  const turnos = de('turno').filter((t) => idsTurno.has(t.id));

  const decisiones = de('decision')
    .map((d) => ({ ...d, motiva: d.motiva.filter((m) => idsAccion.has(m)) }))
    .filter((d) => d.motiva.length > 0 && idsTurno.has(d.turno));
  const intervenciones = de('intervencion').filter((i) => idsPaso.has(i.paso) &&
    (i.accion === null || idsAccion.has(i.accion)));

  // Un subagente que quedo arrastra a quien lo lanzo, aunque esa cadena no conserve acciones.
  const todos = new Map(de('agente').map((a) => [a.id, a]));
  const usados = new Set(['orquestador', ...acciones.map((a) => a.agente),
    ...intervenciones.map((i) => i.agente)]);
  for (const id of [...usados]) {
    for (let a = todos.get(id); a?.actuoEnNombreDe; a = todos.get(a.actuoEnNombreDe)) {
      usados.add(a.actuoEnNombreDe);
    }
  }
  const agentes = de('agente').filter((a) => usados.has(a.id));

  const derivadaDe = {
    acta: `${cabecera.sesion}${SUFIJO_CRUDA}`,
    sha256: crypto.createHash('sha256').update(textoCruda).digest('hex'),
  };
  return [{ ...cabecera, derivadaDe }, ...agentes, ...turnos, ...pasos, ...decisiones,
    ...acciones, ...intervenciones];
}

// Cura y escribe. Devuelve el codigo de salida.
export function curarYEscribir({ cruda, salida = null, log = console }) {
  const registros = curar(fs.readFileSync(cruda, 'utf8'));
  const errores = validarActa(registros);
  if (errores.length) {
    for (const e of errores) log.error(`${e.invariante}: ${e.mensaje}`);
    log.error(`La curada de ${cruda} rompe el modelo y no se escribio.`);
    return 1;
  }
  const destino = salida || path.join(path.dirname(cruda),
    path.basename(cruda, SUFIJO_CRUDA) + '.acta.curada.jsonl');
  fs.writeFileSync(destino, serializar(registros));
  log.log(`Acta curada: ${destino}`);
  return 0;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const [cruda, opcion, salida, ...resto] = process.argv.slice(2);
  const usoValido = cruda && cruda.endsWith(SUFIJO_CRUDA) && fs.existsSync(cruda) &&
    resto.length === 0 && (opcion === undefined || (opcion === '--salida' && salida));
  if (!usoValido) {
    console.error('Uso: node scripts/acta/curar-acta.mjs <acta.cruda.jsonl> ' +
      '[--salida <acta.curada.jsonl>]');
    process.exitCode = 2;
  } else {
    process.exitCode = curarYEscribir({ cruda, salida });
  }
}
