// Pruebas de la curacion del acta (#218). Desde la raiz:
//   node --test scripts/acta/pruebas/curar-acta.test.mjs
//
// Cada escenario es un transcript sintetico (fabrica.mjs) que se compila a la cruda y se cura.
// La invariante I6 se prueba en las dos direcciones: la curada la cumple, y una curada sembrada
// con cada rotura sale en rojo con I6 (REVIEW.md, seccion 3).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { compilar, compilarYEscribir, serializar } from '../compilar-acta.mjs';
import { curar, curarYEscribir } from '../curar-acta.mjs';
import { validarActa } from '../validar-acta.mjs';
import { carpetaTemporal, fabrica, subagente } from './fabrica.mjs';

const silencio = { log: () => {}, error: () => {} };
const de = (registros, elemento) => registros.filter((r) => r.elemento === elemento);
const ids = (registros, elemento) => de(registros, elemento).map((r) => r.id);

function crudaDe(f, { antes } = {}) {
  const carpeta = carpetaTemporal('sesion');
  const transcript = f.escribir(carpeta);
  if (antes) antes(transcript.replace(/\.jsonl$/, ''));
  const { actas } = compilar({ transcript });
  assert.equal(actas.size, 1, 'se esperaba un acta');
  const cruda = [...actas.values()][0];
  assert.deepEqual(validarActa(cruda), [], 'la cruda tiene que estar en verde');
  return cruda;
}

function curarYValidar(cruda) {
  const curada = curar(serializar(cruda));
  assert.deepEqual(validarActa(curada), []);
  return curada;
}

// --- Escenarios -------------------------------------------------------------------------------

test('fallo con reintento: entra el exito con el id de la cruda y el fallo queda afuera', () => {
  const cruda = crudaDe(fabrica()
    .prompt('Cambia el texto')
    .texto('Edito.')
    .llamada('tu1', 'Edit', { file_path: 'a.md', old_string: 'zz', new_string: 'b' })
    .resultado('tu1', 'String to replace not found in file.', { error: true })
    .llamada('tu2', 'Edit', { file_path: 'a.md', old_string: 'a', new_string: 'b' })
    .resultado('tu2', 'The file has been updated'));
  const curada = curarYValidar(cruda);
  const [fallida, exitosa] = de(cruda, 'accion');
  assert.deepEqual(de(curada, 'accion'), [exitosa]);
  assert.notEqual(fallida.id, exitosa.id);
  const [decision] = de(curada, 'decision');
  assert.deepEqual(decision.motiva, [exitosa.id], 'la decision pierde la accion que salio');
});

test('turnos vacios: el de solo texto y el que solo fallo quedan en la cruda', () => {
  const cruda = crudaDe(fabrica()
    .prompt('Hola, solo una pregunta')
    .texto('Respuesta sin herramientas.')
    .prompt('Lee algo que no existe')
    .texto('Lo leo.')
    .llamada('tu1', 'Read', { file_path: 'no.md' })
    .resultado('tu1', 'File does not exist.', { error: true })
    .prompt('Ahora uno que si')
    .llamada('tu2', 'Read', { file_path: 'a.md' })
    .resultado('tu2', 'a'));
  assert.deepEqual(ids(cruda, 'turno'), ['t1', 't2', 't3']);
  const curada = curarYValidar(cruda);
  assert.deepEqual(ids(curada, 'turno'), ['t3']);
  assert.deepEqual(ids(curada, 'paso'), ['t3.p1']);
  assert.deepEqual(de(curada, 'decision'), [], 'la decision del turno que fallo sale con el');
});

test('accion sin resultado: con exito null no entra', () => {
  const cruda = crudaDe(fabrica()
    .prompt('Corre algo')
    .llamada('tu1', 'Bash', { command: 'npm test' })
    .prompt('Otra cosa')
    .llamada('tu2', 'Read', { file_path: 'a.md' })
    .resultado('tu2', 'a'));
  assert.equal(de(cruda, 'accion')[0].exito, null);
  const curada = curarYValidar(cruda);
  assert.deepEqual(de(curada, 'accion').map((a) => a.toolUseId), ['tu2']);
});

test('subagente: entran sus exitos, sale su fallo y conserva en nombre de quien actua', () => {
  const cruda = crudaDe(fabrica()
    .prompt('Revisa el cambio')
    .llamada('tu1', 'Agent', { subagent_type: 'Explore', prompt: 'x' })
    .resultado('tu1', 'sin hallazgos', { extra: { agentId: 'ab12' } }), {
    antes: (carpeta) => subagente(carpeta, 'ab12', [
      { id: 'su1', name: 'Bash', input: { command: 'git diff' } },
      { id: 'su2', name: 'Read', input: { file_path: 'a.md' } },
    ]),
  });
  // La fabrica no siembra fallos de subagente: se marca uno a mano sobre la cruda compilada.
  const leida = de(cruda, 'accion').find((a) => a.toolUseId === 'su2');
  leida.exito = false;
  leida.error = 'File does not exist.';
  const curada = curarYValidar(cruda);
  assert.deepEqual(de(curada, 'accion').map((a) => a.toolUseId), ['tu1', 'su1']);
  const agente = de(curada, 'agente').find((a) => a.id === 'subagente:ab12');
  assert.equal(agente.actuoEnNombreDe, 'orquestador');
});

test('agentes: la persona sale si no le queda ninguna accion ni intervencion', () => {
  const cruda = crudaDe(fabrica()
    .prompt('<bash-input>git sttaus</bash-input>')
    .prompt('<bash-stdout></bash-stdout><bash-stderr>no es un comando</bash-stderr>')
    .prompt('Arregla el titulo')
    .llamada('tu1', 'Edit', { file_path: 'a.md', old_string: 'a', new_string: 'b' })
    .resultado('tu1', 'ok'));
  assert.ok(ids(cruda, 'agente').includes('usuario'));
  const curada = curarYValidar(cruda);
  assert.deepEqual(ids(curada, 'agente'), ['orquestador']);
  assert.deepEqual(de(curada, 'intervencion'), []);
});

test('intervenciones: entran las que apuntan a lo que quedo', () => {
  const cruda = crudaDe(fabrica()
    .prompt('<bash-input>git status</bash-input>')
    .prompt('<bash-stdout>limpio</bash-stdout><bash-stderr></bash-stderr>')
    .prompt('Borra la rama y lee el archivo')
    .llamada('tu1', 'Read', { file_path: 'a.md' })
    .resultado('tu1', 'a')
    .llamada('tu2', 'Bash', { command: 'git push origin --delete x' })
    .resultado('tu2', "The user doesn't want to proceed with this tool use. The tool use was " +
      'rejected.', { error: true })
    .prompt('[Request interrupted by user for tool use]')
    .prompt('Solo conversemos')
    .texto('Claro.')
    .prompt('[Request interrupted by user]'));
  const tipos = (acta) => de(acta, 'intervencion').map((i) => i.tipo);
  assert.deepEqual(tipos(cruda),
    ['comando_usuario', 'permiso_rechazado', 'interrupcion', 'interrupcion']);
  const curada = curarYValidar(cruda);
  // El rechazo sale con su accion; la segunda interrupcion, con su turno de solo texto.
  assert.deepEqual(tipos(curada), ['comando_usuario', 'interrupcion']);
  assert.ok(ids(curada, 'agente').includes('usuario'));
});

test('cabecera: la de la cruda, con el sha256 de sus bytes en derivadaDe', () => {
  const cruda = crudaDe(fabrica()
    .prompt('Uno')
    .llamada('tu1', 'Read', { file_path: 'a.md' })
    .resultado('tu1', 'a'));
  const texto = serializar(cruda);
  const [cabecera] = curar(texto);
  const { derivadaDe, ...resto } = cabecera;
  assert.deepEqual(resto, cruda[0]);
  assert.deepEqual(derivadaDe, {
    acta: 'sesion-1.acta.cruda.jsonl',
    sha256: crypto.createHash('sha256').update(texto).digest('hex'),
  });
});

// --- Determinismo (I8) ------------------------------------------------------------------------

test('determinismo: curar dos veces la misma cruda da el mismo archivo, byte a byte', () => {
  const carpeta = carpetaTemporal('det');
  const transcript = fabrica()
    .prompt('Uno')
    .texto('Edito.')
    .llamada('tu1', 'Edit', { file_path: 'a.md', old_string: 'zz', new_string: 'b' })
    .resultado('tu1', 'not found', { error: true })
    .llamada('tu2', 'Edit', { file_path: 'a.md', old_string: 'a', new_string: 'b' })
    .resultado('tu2', 'ok')
    .llamada('tu3', 'Agent', { subagent_type: 'Explore', prompt: 'x' })
    .resultado('tu3', 'ok', { extra: { agentId: 'cd34' } })
    .prompt('Solo texto')
    .texto('Nada que hacer.')
    .escribir(carpeta);
  subagente(transcript.replace(/\.jsonl$/, ''), 'cd34', [
    { id: 'su1', name: 'Grep', input: { pattern: 'x' } },
  ]);
  const salida = carpetaTemporal('det-cruda');
  assert.equal(compilarYEscribir({ transcript, salida, verificar: false, log: silencio }), 0);
  const cruda = path.join(salida, '10', 'sesion-1.acta.cruda.jsonl');
  const destinos = [carpetaTemporal('det-a'), carpetaTemporal('det-b')]
    .map((c) => path.join(c, 'sesion-1.acta.curada.jsonl'));
  for (const d of destinos) assert.equal(curarYEscribir({ cruda, salida: d, log: silencio }), 0);
  const [a, b] = destinos.map((d) => fs.readFileSync(d));
  assert.ok(a.length > 0);
  assert.ok(a.equals(b));
  assert.equal(curarYEscribir({ cruda, log: silencio }), 0);
  const junto = path.join(salida, '10', 'sesion-1.acta.curada.jsonl');
  assert.ok(fs.readFileSync(junto).equals(a), 'sin --salida queda junto a la cruda');
});

// --- Rojos sembrados: invariante I6 -----------------------------------------------------------

function sembrar(mutar) {
  const curada = structuredClone(curarYValidar(crudaDe(fabrica()
    .prompt('Uno')
    .llamada('tu1', 'Read', { file_path: 'a.md' })
    .resultado('tu1', 'a')
    .prompt('Dos')
    .llamada('tu2', 'Read', { file_path: 'b.md' })
    .resultado('tu2', 'b'))));
  mutar(curada);
  return validarActa(curada).map((e) => e.invariante);
}

test('rojo I6: una accion fallida dentro de la curada', () => {
  assert.deepEqual(sembrar((c) => { de(c, 'accion')[0].exito = false; }), ['I6']);
});

test('rojo I6: una accion sin resultado dentro de la curada', () => {
  assert.deepEqual(sembrar((c) => { de(c, 'accion')[0].exito = null; }), ['I6']);
});

test('rojo I6: un paso sin acciones dentro de la curada', () => {
  const r = sembrar((c) => {
    c.splice(c.indexOf(de(c, 'accion')[1]), 1);
  });
  assert.deepEqual(r, ['I6']);
});

test('rojo I6: un turno sin pasos dentro de la curada', () => {
  const r = sembrar((c) => {
    c.push({ ...de(c, 'turno')[1], id: 't9', numero: 9 });
  });
  assert.deepEqual(r, ['I6']);
});

test('I6 no aplica a la cruda: sin derivadaDe, los fallos y los turnos vacios estan bien', () => {
  const r = sembrar((c) => {
    delete c[0].derivadaDe;
    de(c, 'accion')[0].exito = false;
    c.splice(c.indexOf(de(c, 'accion')[1]), 1);
  });
  assert.deepEqual(r, []);
});

test('curarYEscribir: una curada que rompe el modelo no se escribe (codigo 1)', () => {
  const carpeta = carpetaTemporal('rota');
  const cruda = path.join(carpeta, 'sesion-1.acta.cruda.jsonl');
  const registros = crudaDe(fabrica()
    .prompt('Uno')
    .llamada('tu1', 'Read', { file_path: 'a.md' })
    .resultado('tu1', 'a'));
  de(registros, 'turno')[0].tarea = '99';
  fs.writeFileSync(cruda, serializar(registros));
  assert.equal(curarYEscribir({ cruda, log: silencio }), 1);
  assert.deepEqual(fs.readdirSync(carpeta), ['sesion-1.acta.cruda.jsonl']);
});
