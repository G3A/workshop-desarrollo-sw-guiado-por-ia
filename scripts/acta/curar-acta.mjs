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
// Residuos de los fallos (ADR-0005, primera regla). Un fallo, o una accion sin resultado,
// puede haber cambiado el arbol: un Bash interrumpido a mitad de camino, un Write que fallo
// despues de escribir. Si la curada lo ignora, el motor no llega al arbol final.
//
// 5. Si el fallo tiene los dos arboles y son distintos, entra en su lugar una accion derivada,
//    `residuo`, de clase pura, con los cambios del fallo y el id `<id>.r`. Hereda su paso, su
//    agente y la decision que lo motivo.
// 6. Si los dos arboles son iguales, o la herramienta no cambia el arbol (SIN_ARBOL), o escribe
//    fuera de la maquina (efecto_externo), no hay residuo. Lo ultimo cubre los `!git push` de la
//    persona: no tienen arbol, porque los hooks no corren, y git escribe su avance en stderr, asi
//    que el compilador los marca como fallidos.
// 7. En cualquier otro caso la curacion se DETIENE: un fallo sin arbol que pudo escribir, o un
//    residuo sin diff (la cruda se compilo sin repo). Una curada que no sabe si falta un cambio
//    miente sobre lo que el motor va a reproducir.
//
// Intentos previos (invariante I7, PC-08): cada accion curada lista en `intentosPrevios` los
// fallos de la cruda con su misma herramienta y su mismo archivo o comando, posteriores al exito
// anterior con esa misma clave. Una accion sin archivo ni comando, o un residuo, lleva [].
//
// DETERMINISTA (invariante I8): un recorrido que conserva el orden de la cruda, y la cabecera
// guarda el sha256 de los bytes de la cruda en `derivadaDe`. No lleva la hora de la curacion.
//
// No pasa por gitleaks: la curada es un subconjunto de la cruda, que ya paso antes de escribirse.
//
// Codigos de salida: 0 escrita, 1 la curada rompe una invariante, 2 uso incorrecto, 3 la
// curacion se detuvo por un fallo sin residuo verificable. En 1 y 3 no se escribe nada.
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { SIN_ARBOL } from './clasificar.mjs';
import { serializar } from './compilar-acta.mjs';
import { leerActa, objetivoDe, validarActa } from './validar-acta.mjs';

const SUFIJO_CRUDA = '.acta.cruda.jsonl';

export class CuracionDetenida extends Error {
  constructor(motivos) {
    super(`la curacion se detuvo: ${motivos.join('; ')}`);
    this.motivos = motivos;
  }
}

// Que deja un fallo en la curada: un residuo, nada, o un motivo para detenerse.
function residuoDe(a) {
  const sinArbol = !a.arbolAntes || !a.arbolDespues;
  if (!sinArbol && a.arbolAntes === a.arbolDespues) return { nada: true };
  if (sinArbol && (SIN_ARBOL.has(a.herramienta) || a.claseDeterminismo === 'efecto_externo')) {
    return { nada: true };
  }
  if (sinArbol) {
    return { motivo: `la accion ${a.id} (${a.herramienta}) fallo sin arbol y pudo escribir` };
  }
  if (!a.cambios.length) {
    return { motivo: `la accion ${a.id} fallo y cambio el arbol, pero la cruda no trae su diff` };
  }
  return {
    residuo: {
      elemento: 'accion',
      id: `${a.id}.r`,
      paso: a.paso,
      agente: a.agente,
      lanzadaPor: a.lanzadaPor,
      herramienta: 'residuo',
      residuoDe: a.id,
      claseDeterminismo: 'pura',
      momento: a.momento,
      exito: true,
      arbolAntes: a.arbolAntes,
      arbolDespues: a.arbolDespues,
      cambios: a.cambios,
    },
  };
}

// La curacion como funcion pura sobre el texto de la cruda: deriva de esos bytes y no de otros.
// Lanza CuracionDetenida si algun fallo no se puede curar.
export function curar(textoCruda) {
  const registros = textoCruda.split('\n').filter(Boolean).map((l) => JSON.parse(l));
  const de = (elemento) => registros.filter((r) => r.elemento === elemento);
  const cabecera = registros.find((r) => r.elemento === 'acta');

  const acciones = [];
  const reemplazo = new Map();
  const motivos = [];
  const pendientes = new Map();
  for (const a of de('accion')) {
    const objetivo = objetivoDe(a);
    const clave = objetivo === null ? null : JSON.stringify([a.herramienta, objetivo]);
    if (a.exito === true) {
      acciones.push({ ...a, intentosPrevios: clave === null ? [] : pendientes.get(clave) || [] });
      if (clave !== null) pendientes.delete(clave);
      continue;
    }
    if (clave !== null) pendientes.set(clave, [...(pendientes.get(clave) || []), a.id]);
    const r = residuoDe(a);
    if (r.motivo) motivos.push(r.motivo);
    if (r.residuo) {
      acciones.push({ ...r.residuo, intentosPrevios: [] });
      reemplazo.set(a.id, r.residuo.id);
    }
  }
  if (motivos.length) throw new CuracionDetenida(motivos);

  const idsAccion = new Set(acciones.map((a) => a.id));
  const idsPaso = new Set(acciones.map((a) => a.paso));
  const pasos = de('paso').filter((p) => idsPaso.has(p.id));
  const idsTurno = new Set(pasos.map((p) => p.turno));
  const turnos = de('turno').filter((t) => idsTurno.has(t.id));

  const decisiones = de('decision')
    .map((d) => ({ ...d, motiva: d.motiva.map((m) => reemplazo.get(m) || m)
      .filter((m) => idsAccion.has(m)) }))
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
  const texto = fs.readFileSync(cruda, 'utf8');
  let registros;
  try {
    registros = curar(texto);
  } catch (e) {
    if (!(e instanceof CuracionDetenida)) throw e;
    for (const m of e.motivos) log.error(`detenida: ${m}`);
    log.error(`La curada de ${cruda} no se escribio: hay fallos sin residuo verificable.`);
    return 3;
  }
  const errores = validarActa(registros, { cruda: leerActa(cruda) });
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
