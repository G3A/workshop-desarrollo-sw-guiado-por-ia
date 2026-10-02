// Las invariantes del acta, del modelo conceptual (docs/modelo-conceptual-registro-ia.md, sec. 4).
// El compilador no emite un acta que las rompa, y la curacion las va a volver a pedir.
//
//   I1  una accion pertenece a un solo paso, un paso a un solo turno, un turno a la sesion.
//   I2  todos los turnos del acta son de la misma tarea.
//   I3  las acciones de un subagente estan en el paso de la llamada Agent que lo lanzo. Cada
//       subagente lleva su integracion (integrado, descartado, o null si no se pudo saber), y
//       en la curada solo hay subagentes integrados.
//   I4  una accion en segundo plano esta en el paso que la lanzo, no en el de su resultado.
//   I5  sin marcador de la skill, el paso es el turno completo: procedencia ausente y unico.
//   I6  solo en la curada (la que trae `derivadaDe` en la cabecera): toda accion tiene
//       exito = true, todo paso al menos una accion y todo turno al menos un paso.
//   I7  solo en la curada, y solo si se pasa la cruda: los intentosPrevios de cada accion son
//       exactamente los fallos de la cruda con su misma herramienta y su mismo archivo o
//       comando, posteriores al exito anterior con esa clave. Una accion derivada
//       (un residuo, o el hook de una accion partida) no tiene.
//
// Ademas se valida el esquema: valores cerrados y referencias que existen, y que una accion con
// el codigo de salida reinterpretado por Claude Code (#219) no figure como exito ni como fallo.
// Y el anexo de verificaciones negativas: solo la curada lo tiene, ningun rojo esperado queda en
// la secuencia y, con la cruda, el anexo trae exactamente sus acciones marcadas, con su resultado.
//
// Uso: node scripts/acta/validar-acta.mjs <acta.cruda.jsonl | acta.curada.jsonl>
// Con una curada, busca la cruda de la que deriva en la misma carpeta para validar I7.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const CLASES = new Set(['pura', 'local', 'externa_lectura', 'efecto_externo']);
const PROCEDENCIAS = new Set(['marcado', 'ausente']);
const TIPOS_AGENTE = new Set(['persona', 'automatismo', 'ia']);
const INTEGRACIONES = new Set(['integrado', 'descartado', null]);
const INTERVENCIONES = new Set(['permiso_aprobado', 'permiso_rechazado', 'interrupcion',
  'comando_usuario', 'correccion']);

// El archivo o el comando sobre el que actua una accion: la clave de sus intentos previos (I7).
export function objetivoDe(accion) {
  const e = accion.entrada || {};
  const objetivo = e.file_path ?? e.notebook_path ?? e.command ?? null;
  return objetivo === null ? null : String(objetivo);
}

// Lo que demuestra un rojo esperado, declarado en el propio comando con un comentario que vale en
// bash y en PowerShell: `<comando>  # rojo-esperado: <sensor o prueba>`. Null si no lo declara.
const MARCA_ROJO = /#\s*rojo-esperado:\s*(\S[^\n]*?)\s*$/m;
export function rojoEsperadoDe(accion) {
  if (accion.herramienta !== 'Bash' && accion.herramienta !== 'PowerShell') return null;
  const m = MARCA_ROJO.exec(String(accion.entrada?.command ?? ''));
  return m ? m[1] : null;
}

const enRojoDe = (exito) => (exito === false ? true : exito === true ? false : null);

const claveDe = (a) => {
  const objetivo = objetivoDe(a);
  return objetivo === null ? null : JSON.stringify([a.herramienta, objetivo]);
};

export function validarActa(registros, { cruda = null } = {}) {
  const errores = [];
  const falla = (invariante, mensaje) => errores.push({ invariante, mensaje });

  const cabeceras = registros.filter((r) => r.elemento === 'acta');
  if (cabeceras.length !== 1) {
    falla('esquema', `el acta tiene ${cabeceras.length} cabeceras; debe tener una`);
    return errores;
  }
  const acta = cabeceras[0];
  const porId = new Map();
  for (const r of registros) {
    if (r.elemento === 'acta') continue;
    if (!r.id) falla('esquema', `un registro de tipo ${r.elemento} no tiene id`);
    else if (porId.has(r.id)) falla('I1', `el id ${r.id} aparece dos veces`);
    else porId.set(r.id, r);
  }
  const de = (elemento) => registros.filter((r) => r.elemento === elemento);
  const turnos = de('turno');
  const pasos = de('paso');
  const acciones = de('accion');

  for (const t of turnos) {
    if (t.sesion !== acta.sesion) falla('I1', `el turno ${t.id} es de otra sesion`);
    if (t.tarea !== acta.tarea) {
      falla('I2', `el turno ${t.id} es de la tarea ${t.tarea}, y el acta de la ${acta.tarea}`);
    }
  }

  const pasosPorTurno = new Map();
  for (const p of pasos) {
    const t = porId.get(p.turno);
    if (!t || t.elemento !== 'turno') falla('I1', `el paso ${p.id} apunta a un turno inexistente`);
    pasosPorTurno.set(p.turno, (pasosPorTurno.get(p.turno) || 0) + 1);
    if (!PROCEDENCIAS.has(p.procedencia)) {
      falla('I5', `el paso ${p.id} tiene procedencia ${p.procedencia}`);
    }
  }
  for (const p of pasos) {
    if (p.procedencia === 'ausente' && pasosPorTurno.get(p.turno) !== 1) {
      falla('I5', `el paso ${p.id} no tiene marcador y no es el unico de su turno`);
    }
  }

  const agentes = new Map(de('agente').map((a) => [a.id, a]));
  for (const a of agentes.values()) {
    if (!TIPOS_AGENTE.has(a.tipo)) falla('esquema', `el agente ${a.id} tiene tipo ${a.tipo}`);
    const esSubagente = a.id.startsWith('subagente:');
    if (!INTEGRACIONES.has(a.integracion) || (!esSubagente && a.integracion !== null)) {
      falla('esquema', `el agente ${a.id} tiene integracion ${a.integracion}`);
    } else if (esSubagente && acta.derivadaDe && a.integracion !== 'integrado') {
      falla('I3', `el subagente ${a.id} de la curada no esta integrado (${a.integracion})`);
    }
  }

  for (const a of acciones) {
    const p = porId.get(a.paso);
    if (!p || p.elemento !== 'paso') falla('I1', `la accion ${a.id} apunta a un paso inexistente`);
    if (!agentes.has(a.agente)) falla('esquema', `la accion ${a.id} no tiene un agente conocido`);
    if (!CLASES.has(a.claseDeterminismo)) {
      falla('esquema', `la accion ${a.id} tiene clase ${a.claseDeterminismo}`);
    }
    if (a.codigoReinterpretado != null && (a.exito !== null || a.error !== null)) {
      falla('esquema', `la accion ${a.id} tiene el codigo reinterpretado y exito = ${a.exito}`);
    }
    if (a.agente.startsWith('subagente:')) {
      const lanzadora = porId.get(a.lanzadaPor);
      if (!lanzadora || lanzadora.elemento !== 'accion') {
        falla('I3', `la accion ${a.id} es de un subagente y no dice que llamada lo lanzo`);
      } else if (lanzadora.paso !== a.paso) {
        falla('I3', `la accion ${a.id} esta en ${a.paso} y su subagente se lanzo en ` +
          `${lanzadora.paso}`);
      }
    }
    if (a.segundoPlano && p && p.elemento === 'paso') {
      const t = porId.get(p.turno);
      if (t && t.momento && a.momento && a.momento < t.momento) {
        falla('I4', `la accion en segundo plano ${a.id} se lanzo antes del turno de su paso`);
      }
    }
  }

  for (const d of de('decision')) {
    for (const m of d.motiva || []) {
      if (porId.get(m)?.elemento !== 'accion') {
        falla('esquema', `la decision ${d.id} motiva ${m}, que no es una accion`);
      }
    }
  }
  for (const i of de('intervencion')) {
    if (!INTERVENCIONES.has(i.tipo)) falla('esquema', `la intervencion ${i.id} es ${i.tipo}`);
    if (porId.get(i.paso)?.elemento !== 'paso') {
      falla('esquema', `la intervencion ${i.id} apunta a un paso inexistente`);
    }
  }
  if (acta.derivadaDe) {
    const conAccion = new Set(acciones.map((a) => a.paso));
    const conPaso = new Set(pasos.map((p) => p.turno));
    for (const a of acciones) {
      if (a.exito !== true) falla('I6', `la accion ${a.id} de la curada tiene exito = ${a.exito}`);
    }
    for (const p of pasos) {
      if (!conAccion.has(p.id)) falla('I6', `el paso ${p.id} de la curada no tiene acciones`);
    }
    for (const t of turnos) {
      if (!conPaso.has(t.id)) falla('I6', `el turno ${t.id} de la curada no tiene pasos`);
    }
  }
  if (acta.derivadaDe && cruda) {
    for (const e of intentosPreviosRotos(acciones, cruda)) falla('I7', e);
  }
  for (const e of anexoRoto(acta, acciones, de('verificacion_negativa'), cruda)) falla('anexo', e);
  return errores;
}

function anexoRoto(acta, acciones, anexo, cruda) {
  const errores = [];
  if (!acta.derivadaDe) {
    if (anexo.length) errores.push('la cruda no tiene anexo de verificaciones negativas');
    return errores;
  }
  for (const a of acciones) {
    if (rojoEsperadoDe(a) !== null) {
      errores.push(`la accion ${a.id} declara un rojo esperado y esta en la secuencia`);
    }
  }
  for (const v of anexo) {
    if (typeof v.rojoEsperado !== 'string' || !v.rojoEsperado) {
      errores.push(`la verificacion ${v.id} no dice que demuestra`);
    }
    if (![true, false, null].includes(v.enRojo)) {
      errores.push(`la verificacion ${v.id} tiene enRojo = ${v.enRojo}`);
    }
  }
  if (!cruda) return errores;
  const marcadas = cruda.filter((r) => r.elemento === 'accion' && rojoEsperadoDe(r) !== null);
  const porAccion = new Map();
  for (const v of anexo) porAccion.set(v.accion, [...(porAccion.get(v.accion) || []), v]);
  for (const a of marcadas) {
    const vs = porAccion.get(a.id) || [];
    porAccion.delete(a.id);
    if (vs.length !== 1) {
      errores.push(`el rojo esperado ${a.id} esta ${vs.length} veces en el anexo`);
      continue;
    }
    if (vs[0].rojoEsperado !== rojoEsperadoDe(a)) {
      errores.push(`la verificacion ${vs[0].id} no dice lo que declara la accion ${a.id}`);
    }
    if (vs[0].enRojo !== enRojoDe(a.exito)) {
      errores.push(`la verificacion ${vs[0].id} dice enRojo = ${vs[0].enRojo}, y la cruda no`);
    }
  }
  for (const id of porAccion.keys()) {
    errores.push(`el anexo trae ${id}, que no es una accion marcada de la cruda`);
  }
  return errores;
}

// Recorre la cruda hacia atras desde cada accion curada hasta el exito anterior con su clave.
function intentosPreviosRotos(acciones, cruda) {
  const errores = [];
  const orden = cruda.filter((r) => r.elemento === 'accion');
  const indice = new Map(orden.map((a, i) => [a.id, i]));
  for (const c of acciones) {
    const previos = c.intentosPrevios;
    if (!Array.isArray(previos)) {
      errores.push(`la accion ${c.id} no tiene intentosPrevios`);
      continue;
    }
    if (c.residuoDe || c.parteDe) {
      if (previos.length) errores.push(`la accion derivada ${c.id} tiene intentos previos`);
      continue;
    }
    const i = indice.get(c.id);
    if (i === undefined) {
      errores.push(`la accion ${c.id} no esta en la cruda`);
      continue;
    }
    const clave = claveDe(c);
    const esperados = [];
    for (let j = i - 1; j >= 0 && clave !== null; j--) {
      const a = orden[j];
      if (claveDe(a) !== clave) continue;
      if (a.exito === true) break;
      esperados.unshift(a.id);
    }
    for (const p of previos) {
      if (!esperados.includes(p)) {
        errores.push(`la accion ${c.id} cuenta como intento previo a ${p}, que no lo es`);
      }
    }
    for (const p of esperados) {
      if (!previos.includes(p)) errores.push(`a la accion ${c.id} le falta el intento previo ${p}`);
    }
  }
  return errores;
}

export function leerActa(archivo) {
  return fs.readFileSync(archivo, 'utf8').split('\n').filter(Boolean).map((l) => JSON.parse(l));
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const archivo = process.argv[2];
  if (!archivo) {
    console.error('Uso: node scripts/acta/validar-acta.mjs <acta.cruda.jsonl | ' +
      'acta.curada.jsonl>');
    process.exitCode = 2;
  } else {
    const registros = leerActa(archivo);
    const derivada = registros.find((r) => r.elemento === 'acta')?.derivadaDe;
    const rutaCruda = derivada ? path.join(path.dirname(archivo), derivada.acta) : null;
    const cruda = rutaCruda && fs.existsSync(rutaCruda) ? leerActa(rutaCruda) : null;
    if (derivada && !cruda) console.error(`aviso: sin ${derivada.acta} al lado, I7 no se valida`);
    const errores = validarActa(registros, { cruda });
    for (const e of errores) console.error(`${e.invariante}: ${e.mensaje}`);
    const hasta = !derivada ? 'I5' : cruda ? 'I7' : 'I6';
    if (errores.length) process.exitCode = 1;
    else console.log(`Acta valida: cumple las invariantes I1 a ${hasta} y el esquema.`);
  }
}
