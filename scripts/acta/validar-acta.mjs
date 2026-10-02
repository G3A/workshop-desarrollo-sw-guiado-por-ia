// Las invariantes del acta, del modelo conceptual (docs/modelo-conceptual-registro-ia.md, sec. 4).
// El compilador no emite un acta que las rompa, y la curacion las va a volver a pedir.
//
//   I1  una accion pertenece a un solo paso, un paso a un solo turno, un turno a la sesion.
//   I2  todos los turnos del acta son de la misma tarea.
//   I3  las acciones de un subagente estan en el paso de la llamada Agent que lo lanzo.
//   I4  una accion en segundo plano esta en el paso que la lanzo, no en el de su resultado.
//   I5  sin marcador de la skill, el paso es el turno completo: procedencia ausente y unico.
//   I6  solo en la curada (la que trae `derivadaDe` en la cabecera): toda accion tiene
//       exito = true, todo paso al menos una accion y todo turno al menos un paso.
//
// Ademas se valida el esquema: valores cerrados y referencias que existen.
//
// Uso: node scripts/acta/validar-acta.mjs <acta.cruda.jsonl | acta.curada.jsonl>
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';

const CLASES = new Set(['pura', 'local', 'externa_lectura', 'efecto_externo']);
const PROCEDENCIAS = new Set(['marcado', 'ausente']);
const TIPOS_AGENTE = new Set(['persona', 'automatismo', 'ia']);
const INTERVENCIONES = new Set(['permiso_aprobado', 'permiso_rechazado', 'interrupcion',
  'comando_usuario', 'correccion']);

export function validarActa(registros) {
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
  }

  for (const a of acciones) {
    const p = porId.get(a.paso);
    if (!p || p.elemento !== 'paso') falla('I1', `la accion ${a.id} apunta a un paso inexistente`);
    if (!agentes.has(a.agente)) falla('esquema', `la accion ${a.id} no tiene un agente conocido`);
    if (!CLASES.has(a.claseDeterminismo)) {
      falla('esquema', `la accion ${a.id} tiene clase ${a.claseDeterminismo}`);
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
    const errores = validarActa(registros);
    for (const e of errores) console.error(`${e.invariante}: ${e.mensaje}`);
    const hasta = registros.some((r) => r.elemento === 'acta' && r.derivadaDe) ? 'I6' : 'I5';
    if (errores.length) process.exitCode = 1;
    else console.log(`Acta valida: cumple las invariantes I1 a ${hasta} y el esquema.`);
  }
}
