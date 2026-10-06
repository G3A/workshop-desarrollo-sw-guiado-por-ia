// Pruebas del ejecutor del motor (#222, fase 2), sin Docker:
//   node --test $ACTA/pruebas/ejecutar-acta.test.mjs
//
// El ejecutor corre aqui en el host, sobre un repo armado desde el mismo pack que viaja al
// contenedor. La corrida dentro de Docker la prueba reejecutar-acta.test.mjs.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { motivoNoReejecutable } from '../clasificar.mjs';
import { ejecutar, prepararRepo } from '../ejecutar-acta.mjs';
import { empaquetar } from '../reejecutar-acta.mjs';
import { carpetaTemporal, curadaParaElMotor, sembrar } from './fabrica.mjs';

function correrEnCopia(registros, raizOrigen) {
  const cabecera = registros[0];
  const pack = path.join(carpetaTemporal('pack'), 'objetos.pack');
  empaquetar(raizOrigen, registros, pack);
  const raiz = path.join(carpetaTemporal('motor'), 'repo');
  assert.equal(prepararRepo({ raiz, pack, cabecera }), true, 'el repo arranca en el arbol base');
  return ejecutar({ registros, raiz, tiempo: 60 });
}

const filas = (reporte) => reporte.veredictos.map((v) => [v.accion, v.modo, v.veredicto]);

test('motor: la curada llega a su arbol final, con un veredicto por accion y su modo', () => {
  const { registros, raiz } = curadaParaElMotor();
  const reporte = correrEnCopia(registros, raiz);
  assert.deepEqual(filas(reporte), [
    ['a1', 'aplicada', 'igual'],
    ['a2', 'aplicada', 'igual'],
    ['a7', 'aplicada', 'igual'],
    ['a3', 'ejecutada', 'igual'],
    ['a4', 'comparada', 'igual'],
    ['a5', 'fixture', 'no_verificable'],
    ['a6', 'omitida', 'no_verificable'],
  ]);
  assert.equal(reporte.llegaAlArbolFinal, true);
  assert.equal(reporte.arbolFinalObtenido, registros[0].arbolFinal);
  assert.equal(reporte.primeraDivergencia, null);
});

test('motor: el reporte separa lo verificado de lo que solo salio del fixture', () => {
  const { registros, raiz } = curadaParaElMotor();
  const { resumen, veredictos } = correrEnCopia(registros, raiz);
  assert.deepEqual(resumen, { acciones: 7, verificadas: 5, divergentes: 0, desdeFixture: 1,
    omitidas: 1, sinVerificar: 0 });
  const gh = veredictos.find((v) => v.accion === 'a5');
  assert.match(gh.motivo, /salen del registro/);
  const push = veredictos.find((v) => v.accion === 'a6');
  assert.match(push.motivo, /invariante 9/);
});

// --- Rojos sembrados (REVIEW.md, seccion 3) ----------------------------------------------------

test('rojo sembrado: un comando que deja otro arbol nombra la primera accion que diverge', () => {
  const { registros, raiz } = curadaParaElMotor();
  const reporte = correrEnCopia(sembrar(registros, 'a3', { command: "printf 'x\\n' > c.md" }),
    raiz);
  assert.equal(reporte.llegaAlArbolFinal, false);
  assert.equal(reporte.primeraDivergencia.accion, 'a3');
  assert.match(reporte.primeraDivergencia.motivo, /deja el arbol \w+ y el registro dice/);
  // Restaura el arbol del registro y sigue: el Read de despues se mide desde el de la sesion.
  assert.deepEqual(filas(reporte).find(([id]) => id === 'a4'), ['a4', 'comparada', 'igual']);
  assert.equal(reporte.resumen.divergentes, 1);
});

test('rojo sembrado: un Write editado a mano diverge, y el Edit de despues se verifica', () => {
  const { registros, raiz } = curadaParaElMotor();
  const reporte = correrEnCopia(sembrar(registros, 'a1', { content: 'uno\notro dos\n' }), raiz);
  assert.equal(reporte.primeraDivergencia.accion, 'a1');
  assert.deepEqual(filas(reporte).slice(0, 2),
    [['a1', 'aplicada', 'diverge'], ['a2', 'aplicada', 'igual']]);
});

test('rojo sembrado: un Edit que no se aplica, y un comando que sale mal', () => {
  const { registros, raiz } = curadaParaElMotor();
  const reporte = correrEnCopia(sembrar(sembrar(registros, 'a2', { old_string: 'cuatro' }),
    'a3', { command: "printf 'c\\n' > c.md && exit 4" }), raiz);
  const [, edit, , bash] = reporte.veredictos;
  assert.equal(edit.veredicto, 'diverge');
  assert.match(edit.motivo, /el texto a reemplazar no esta/);
  assert.equal(bash.veredicto, 'diverge');
  assert.match(bash.motivo, /sale con codigo 4/);
});

test('motor: un comando que no esta en la imagen queda ejecutado sin verificar', () => {
  const { registros, raiz } = curadaParaElMotor();
  const reporte = correrEnCopia(sembrar(registros, 'a3',
    { command: 'comando-que-no-existe-en-la-imagen' }), raiz);
  const bash = reporte.veredictos.find((v) => v.accion === 'a3');
  assert.deepEqual([bash.modo, bash.veredicto, bash.codigo], ['ejecutada', 'no_verificable', 127]);
  assert.equal(reporte.resumen.sinVerificar, 1);
  assert.equal(reporte.primeraDivergencia, null, 'sin verificar no es divergir');
});

test('no re-ejecutable: Windows PowerShell 5.1, la carpeta del usuario y el segundo plano', () => {
  const ps = (command) => motivoNoReejecutable('PowerShell', { command });
  assert.match(ps('Get-Service sshd'), /solo existe en Windows/);
  assert.match(ps('Get-ItemProperty HKLM:\\SOFTWARE\\x'), /registro de Windows/);
  assert.match(ps("Set-Content a.txt 'x' -Encoding Default"), /ANSI/);
  assert.match(ps('& "C:\\Program Files\\x\\y.exe" --version'), /letra de unidad/);
  assert.match(ps('where.exe node'), /ejecutable de Windows/);
  assert.equal(ps("Get-ChildItem -Recurse | Select-Object -First 3"), null);
  assert.equal(ps("Set-Content -Path d.md -Value 'd'"), null);
  assert.match(motivoNoReejecutable('Bash', { command: 'cat ~/.claude/x.md' }),
    /carpeta del usuario/);
  assert.equal(motivoNoReejecutable('Bash', { command: 'git log -1 --format=%h~1' }), null);
  assert.match(motivoNoReejecutable('Bash', { command: 'npm run dev', run_in_background: true }),
    /segundo plano/);
});
