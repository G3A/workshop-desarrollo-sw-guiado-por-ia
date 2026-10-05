// Curacion del acta (#218, ADR-0005 punto 3), desde la raiz del repo:
//
//   node scripts/acta/curar-acta.mjs <acta.cruda.jsonl> [--salida <acta.curada.jsonl>]
//   node scripts/acta/curar-acta.mjs <acta.cruda.jsonl> --comprobar <acta.curada.jsonl>
//
// Deriva el acta curada de la cruda y la escribe junto a ella, como <sesion>.acta.curada.jsonl.
// La curada es lo que re-ejecuta el motor: solo lo que salio bien.
//
// Lo que entra (primer comentario del #218):
//
// 1. Las acciones con `exito = true`, de todos los agentes: orquestador, subagentes y persona.
//    Una accion sin resultado (exito null) no entra: no se sabe que salio bien. Tampoco una con
//    `codigoReinterpretado` (#219), que el compilador deja en exito null: salio con un codigo
//    distinto de 0 que Claude Code leyo como benigno, y el 1 de un `| grep` puede ser «sin
//    coincidencias» o el fallo de un eslabon anterior. Como cualquier exito null, pasa por la
//    regla de residuos, cuenta como intento previo y, si es un rojo esperado, queda con enRojo
//    null. Un grep legitimo que no cambio el arbol sale de la curada sin quitarle nada al motor.
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
//    que el compilador los marca como fallidos. Tampoco hay residuo si la herramienta no llego a
//    correr (`capturada = false`): un Edit que no paso la validacion o un Write que freno el
//    clasificador. Los dos aparecieron al curar sesiones reales de este repo.
// 7. En cualquier otro caso la curacion se DETIENE: un fallo sin arbol que pudo escribir, o un
//    residuo sin diff (la cruda se compilo sin repo). Una curada que no sabe si falta un cambio
//    miente sobre lo que el motor va a reproducir.
//
// Efectos de hooks. El compilador ya los trae como acciones `hook`, de clase pura y con exito,
// asi que entran como cualquier otra. La curacion comprueba que no falte ninguno:
//
// 8. La cadena de arboles es continua: cada accion con arbol empieza donde termino la anterior.
//    Si no, se DETIENE, igual que con un `hook` que cambio el arbol sin traer su diff. Un hueco
//    sale de dos acciones en paralelo, o de una captura con eventos perdidos.
//
// Subagentes (ADR-0005, segunda regla; PC-11). El compilador marca cada uno como `integrado` o
// `descartado` comparando por blob los archivos que cambio contra el arbol final:
//
// 9. Un subagente descartado sale entero de la curada. Si sin el la cadena de arboles queda
//    continua (por ejemplo, el mismo deshizo lo suyo), la curada sale; si no, se DETIENE
//    diciendo que subagente la corta. No se infiere que accion revirtio que.
// 10. Un subagente con integracion null cambio el arbol y no se pudo comparar: la curacion se
//    DETIENE. En los datos reales de este repo ningun subagente trabajo fuera del arbol de la
//    sesion, asi que un descartado es siempre trabajo que se revirtio despues.
//
// Anexo de verificaciones negativas (ADR-0005; PC-16). Un rojo esperado, como el de TDD o el de
// sembrar un sensor (REVIEW.md, seccion 3), se declara en el propio comando con un comentario
// que vale en bash y en PowerShell: `<comando>  # rojo-esperado: <sensor o prueba>`.
//
// 11. Un comando marcado sale de la secuencia y entra al anexo, al final de la curada, con
//    `enRojo`: true si fallo, false si salio verde, null sin resultado. El motor no lo ejecuta.
//    Si cambio el arbol, su residuo si entra a la secuencia, como el de cualquier fallo. Los de
//    un subagente descartado tambien entran: sembrar y revertir es lo que lo deja descartado.
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
// Validacion del arbol final (regla 12): antes de escribirla, verificar-arbol.mjs aplica en
// memoria sus Edit y Write contra el repo y comprueba que la cadena llegue al arbol final. Un
// Edit en cuya ventana escribio un hook se parte en dos. Sin repo, la curacion se DETIENE.
//
// Invariante I8: la curada no se edita a mano. Con --comprobar, se vuelve a derivar desde la cruda
// y el repo y se compara byte a byte con la que existe; tambien se mira que su derivadaDe sea el
// sha256 de esa cruda. Es lo que va a necesitar el sensor del CI del trailer Registro-IA.
//
// Codigos de salida: 0 escrita (o, con --comprobar, coincide), 1 la curada rompe una invariante
// (o no coincide), 2 uso incorrecto, 3 la curacion se detuvo: un fallo sin residuo verificable, o
// un arbol que no se pudo verificar. En 1 y 3 no se escribe nada.
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { ENVOLTORIOS, SIN_ARBOL } from './clasificar.mjs';
import { serializar } from './compilar-acta.mjs';
import { raizDelRepo } from './git.mjs';
import { leerActa, objetivoDe, rojoEsperadoDe, validarActa } from './validar-acta.mjs';
import { verificarArbol } from './verificar-arbol.mjs';

const SUFIJO_CRUDA = '.acta.cruda.jsonl';

export class CuracionDetenida extends Error {
  constructor(motivos) {
    super(`la curacion se detuvo: ${motivos.join('; ')}`);
    this.motivos = motivos;
  }
}

// Que deja en la curada una accion que sale de la secuencia (un fallo, o un rojo esperado que
// salio verde): un residuo, nada, o un motivo para detenerse.
function residuoDe(a, que = 'fallo') {
  if (a.capturada === false) return { nada: true };
  const sinArbol = !a.arbolAntes || !a.arbolDespues;
  if (!sinArbol && a.arbolAntes === a.arbolDespues) return { nada: true };
  if (sinArbol && (SIN_ARBOL.has(a.herramienta) || a.claseDeterminismo === 'efecto_externo')) {
    return { nada: true };
  }
  if (sinArbol) {
    return { motivo: `la accion ${a.id} (${a.herramienta}) ${que} sin arbol y pudo escribir` };
  }
  if (!a.cambios.length) {
    return { motivo: `la accion ${a.id} ${que} y cambio el arbol, y la cruda no trae su diff` };
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

// La cadena de arboles de la curada (regla 8): arranca en el arbol base del acta, y cada accion
// con arbol empieza donde termino la anterior. Las llamadas Agent y Task no son eslabones:
// envuelven a su subagente.
function cadenaRota(acciones, arbolBase) {
  const motivos = [];
  let previa = arbolBase ? { id: 'el arbol base', arbolDespues: arbolBase } : null;
  for (const a of acciones) {
    if (a.herramienta === 'hook' && a.arbolAntes !== a.arbolDespues && !a.cambios.length) {
      motivos.push(`el efecto de hook ${a.id} cambio el arbol, pero la cruda no trae su diff`);
    }
    if (!a.arbolAntes || !a.arbolDespues || ENVOLTORIOS.has(a.herramienta)) continue;
    if (previa && previa.arbolDespues !== a.arbolAntes) {
      motivos.push(`entre ${previa.id} y ${a.id} el arbol cambio sin una accion que lo explique`);
    }
    previa = a;
  }
  return motivos;
}

// La curacion como funcion pura sobre el texto de la cruda: deriva de esos bytes y no de otros.
// Lanza CuracionDetenida si algun fallo no se puede curar.
export function curar(textoCruda) {
  const registros = textoCruda.split('\n').filter(Boolean).map((l) => JSON.parse(l));
  const de = (elemento) => registros.filter((r) => r.elemento === elemento);
  const cabecera = registros.find((r) => r.elemento === 'acta');

  const motivos = [];
  const descartados = new Set();
  for (const ag of de('agente')) {
    if (ag.integracion === 'descartado') descartados.add(ag.id);
    if (ag.id.startsWith('subagente:') && ag.integracion === null) {
      motivos.push(`el subagente ${ag.id} cambio el arbol y no se sabe si llego al final`);
    }
  }

  const acciones = [];
  const anexo = [];
  const reemplazo = new Map();
  const pendientes = new Map();
  for (const a of de('accion')) {
    const objetivo = objetivoDe(a);
    const clave = objetivo === null ? null : JSON.stringify([a.herramienta, objetivo]);
    // Un subagente descartado sale entero (regla 9), pero sus fallos y sus exitos siguen
    // contando para los intentos previos de los demas: I7 se mide sobre la cruda.
    const sale = descartados.has(a.agente);
    const rojo = rojoEsperadoDe(a);
    if (rojo !== null) anexo.push(verificacionNegativa(a, rojo));
    if (a.exito === true) {
      const intentosPrevios = clave === null ? [] : pendientes.get(clave) || [];
      if (!sale && rojo === null) acciones.push({ ...a, intentosPrevios });
      if (clave !== null) pendientes.delete(clave);
      if (sale || rojo === null) continue;
    } else {
      if (clave !== null) pendientes.set(clave, [...(pendientes.get(clave) || []), a.id]);
      if (sale) continue;
    }
    const reinterpretado = `termino con el codigo reinterpretado «${a.codigoReinterpretado}»`;
    const que = a.exito === true ? 'salio verde de la secuencia'
      : a.codigoReinterpretado ? reinterpretado : 'fallo';
    const r = residuoDe(a, que);
    if (r.motivo) motivos.push(r.motivo);
    if (r.residuo) {
      acciones.push({ ...r.residuo, intentosPrevios: [] });
      reemplazo.set(a.id, r.residuo.id);
    }
  }
  const rota = cadenaRota(acciones, cabecera.arbolBase);
  if (rota.length && descartados.size) {
    motivos.push(`quitar ${[...descartados].join(', ')}, descartado, corta la cadena de arboles`);
  }
  motivos.push(...rota);
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
    ...acciones, ...intervenciones, ...anexo];
}

// Un rojo esperado del anexo (regla 11). Apunta a su accion de la cruda: no es una accion de la
// curada, y el motor no lo ejecuta.
function verificacionNegativa(a, rojoEsperado) {
  return {
    elemento: 'verificacion_negativa',
    id: `${a.id}.v`,
    accion: a.id,
    agente: a.agente,
    comando: a.entrada.command,
    rojoEsperado,
    enRojo: a.exito === false ? true : a.exito === true ? false : null,
    resultado: a.error ?? a.resultado ?? null,
    momento: a.momento,
  };
}

// Cura y escribe. Devuelve el codigo de salida.
// Invariante I8: los motivos por los que `curada` no es la que sale de `cruda`; vacio si lo es.
export function comprobarCurada({ cruda, curada, raizRepo }) {
  const textoCruda = fs.readFileSync(cruda, 'utf8');
  const textoCurada = fs.readFileSync(curada, 'utf8');
  const errores = [];
  let derivadaDe = null;
  try {
    derivadaDe = JSON.parse(textoCurada.split('\n')[0]).derivadaDe;
  } catch {
    errores.push('la curada no empieza con una cabecera legible');
  }
  const sha256 = crypto.createHash('sha256').update(textoCruda).digest('hex');
  if (derivadaDe?.sha256 !== sha256) {
    errores.push(`la curada dice derivar de ${derivadaDe?.sha256}, y esta cruda es ${sha256}`);
  }
  let registros;
  try {
    registros = curar(textoCruda);
  } catch (e) {
    if (!(e instanceof CuracionDetenida)) throw e;
    return [...errores, `la cruda no se puede curar: ${e.motivos.join('; ')}`];
  }
  const verificada = verificarArbol(registros, raizRepo);
  if (verificada.motivos.length) {
    return [...errores, `la cruda no se puede verificar: ${verificada.motivos.join('; ')}`];
  }
  const esperada = serializar(verificada.registros).split('\n');
  const real = textoCurada.split('\n');
  const linea = esperada.findIndex((l, i) => l !== real[i]);
  if (linea !== -1 || real.length !== esperada.length) {
    const n = linea === -1 ? Math.min(real.length, esperada.length) + 1 : linea + 1;
    errores.push(`la curada no es la que sale de su cruda: difiere desde la linea ${n}`);
  }
  return errores;
}

// Cura, verifica el arbol contra el repo (verificar-arbol.mjs) y escribe. Devuelve el codigo de
// salida.
export function curarYEscribir({ cruda, salida = null, raizRepo = null, log = console }) {
  const texto = fs.readFileSync(cruda, 'utf8');
  let registros;
  const detenida = (motivos) => {
    for (const m of motivos) log.error(`detenida: ${m}`);
    log.error(`La curada de ${cruda} no se escribio: no se puede curar o verificar.`);
    return 3;
  };
  try {
    registros = curar(texto);
  } catch (e) {
    if (!(e instanceof CuracionDetenida)) throw e;
    return detenida(e.motivos);
  }
  const verificada = verificarArbol(registros, raizRepo);
  if (verificada.motivos.length) return detenida(verificada.motivos);
  registros = verificada.registros;
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
  const [cruda, opcion, otra, ...resto] = process.argv.slice(2);
  const usoValido = cruda && cruda.endsWith(SUFIJO_CRUDA) && fs.existsSync(cruda) &&
    resto.length === 0 && (opcion === undefined ||
      (opcion === '--salida' && otra) || (opcion === '--comprobar' && otra && fs.existsSync(otra)));
  const raizRepo = raizDelRepo(process.cwd());
  if (!usoValido) {
    console.error('Uso: node scripts/acta/curar-acta.mjs <acta.cruda.jsonl> ' +
      '[--salida <acta.curada.jsonl> | --comprobar <acta.curada.jsonl>]');
    process.exitCode = 2;
  } else if (opcion === '--comprobar') {
    const errores = comprobarCurada({ cruda, curada: otra, raizRepo });
    for (const e of errores) console.error(`I8: ${e}`);
    if (errores.length) process.exitCode = 1;
    else console.log('La curada es la que sale de su cruda (I8).');
  } else {
    process.exitCode = curarYEscribir({ cruda, salida: otra, raizRepo });
  }
}
