// Pruebas de la curacion del acta (#218). Desde la raiz:
//   node --test scripts/acta/pruebas/curar-acta.test.mjs
//
// Cada escenario es un transcript sintetico (fabrica.mjs) que se compila a la cruda y se cura.
// Las invariantes I6 e I7 se prueban en las dos direcciones: la curada las cumple, y una curada
// sembrada con cada rotura sale en rojo con la que corresponde (REVIEW.md, seccion 3).
//
// Un fallo sin arbol que pudo escribir detiene la curacion, asi que los escenarios con fallos le
// pasan al compilador una captura: `sinCambio` dice que el fallo no toco el arbol.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { compilar, compilarYEscribir, serializar } from '../compilar-acta.mjs';
import { CuracionDetenida, curar, curarYEscribir } from '../curar-acta.mjs';
import { validarActa } from '../validar-acta.mjs';
import { arbolActual } from '../git.mjs';
import { carpetaTemporal, enCaptura, fabrica, repoQueAvanza, repoTemporal, sesionConHook,
  sesionConSubagente, subagente } from './fabrica.mjs';

const silencio = { log: () => {}, error: () => {} };
const de = (registros, elemento) => registros.filter((r) => r.elemento === elemento);
const ids = (registros, elemento) => de(registros, elemento).map((r) => r.id);

// La captura de acciones que no cambiaron el arbol: el mismo hash antes y despues.
function sinCambio(...toolUseIds) {
  return toolUseIds.flatMap((toolUseId) => [
    { evento: 'PreToolUse', toolUseId, arbol: 'arbol-igual' },
    { evento: 'PostToolUseFailure', toolUseId, arbol: 'arbol-igual' },
  ]);
}

function crudaDe(f, { antes, captura = [], raizRepo = null } = {}) {
  const carpeta = carpetaTemporal('sesion');
  const transcript = f.escribir(carpeta);
  if (antes) antes(transcript.replace(/\.jsonl$/, ''));
  const { actas } = compilar({ transcript, captura, raizRepo });
  assert.equal(actas.size, 1, 'se esperaba un acta');
  const cruda = [...actas.values()][0];
  assert.deepEqual(validarActa(cruda), [], 'la cruda tiene que estar en verde');
  return cruda;
}

function curarYValidar(cruda) {
  const curada = curar(serializar(cruda));
  assert.deepEqual(validarActa(curada, { cruda }), []);
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
    .resultado('tu2', 'The file has been updated'), { captura: sinCambio('tu1') });
  const curada = curarYValidar(cruda);
  const [fallida, exitosa] = de(cruda, 'accion');
  assert.deepEqual(de(curada, 'accion'), [{ ...exitosa, intentosPrevios: [fallida.id] }]);
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
    .resultado('tu2', 'a'), { captura: sinCambio('tu1') });
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
  // Un !git push de la persona: sin arbol, y fallido para el compilador porque git escribe su
  // avance en stderr. Como escribe fuera de la maquina, no deja residuo ni detiene la curacion.
  const cruda = crudaDe(fabrica()
    .prompt('<bash-input>git push</bash-input>')
    .prompt('<bash-stdout></bash-stdout><bash-stderr>To github.com:x/y.git</bash-stderr>')
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

// --- Residuos de los fallos -------------------------------------------------------------------
//
// Los caminos sin residuo ya tienen su escenario arriba: arboles iguales (fallo con reintento),
// herramienta que no cambia el arbol (turnos vacios) y efecto externo sin arbol (la persona).

test('residuo: un Bash que fallo despues de escribir entra como accion derivada y su diff', () => {
  const raiz = repoTemporal({ 'a.md': 'uno\n' });
  const antes = arbolActual(raiz);
  fs.writeFileSync(path.join(raiz, 'a.md'), 'dos\n');
  const despues = arbolActual(raiz);
  const cruda = crudaDe(fabrica()
    .prompt('Genera el archivo')
    .texto('Corro el generador.')
    .llamada('tu1', 'Bash', { command: 'npm run generar' })
    .resultado('tu1', 'Error: fallo a mitad de camino', { error: true }), {
    captura: [
      { evento: 'PreToolUse', toolUseId: 'tu1', arbol: antes },
      { evento: 'PostToolUseFailure', toolUseId: 'tu1', arbol: despues },
    ],
    raizRepo: raiz,
  });
  const curada = curarYValidar(cruda);
  const [fallida] = de(cruda, 'accion');
  const [residuo] = de(curada, 'accion');
  assert.equal(residuo.id, `${fallida.id}.r`);
  assert.equal(residuo.herramienta, 'residuo');
  assert.equal(residuo.residuoDe, fallida.id);
  assert.equal(residuo.claseDeterminismo, 'pura');
  assert.equal(residuo.exito, true);
  assert.equal(residuo.paso, fallida.paso);
  assert.deepEqual([residuo.arbolAntes, residuo.arbolDespues], [antes, despues]);
  assert.deepEqual(residuo.cambios.map((c) => c.archivo), ['a.md']);
  assert.match(residuo.cambios[0].diff, /-uno\n\+dos/);
  assert.deepEqual(residuo.intentosPrevios, []);
  assert.deepEqual(de(curada, 'decision')[0].motiva, [residuo.id], 'hereda la decision');
});

test('residuo: una accion de subagente sin resultado lo deja en el paso de la llamada', () => {
  const cruda = crudaDe(fabrica()
    .prompt('Delega')
    .llamada('tu1', 'Agent', { subagent_type: 'general-purpose', prompt: 'x' })
    .resultado('tu1', 'ok', { extra: { agentId: 'ab12' } }), {
    antes: (carpeta) => subagente(carpeta, 'ab12', [
      { id: 'su1', name: 'Write', input: { file_path: 'b.md', content: 'b' } },
    ]),
  });
  // Un subagente cortado a mitad de una escritura: sin resultado, pero con el arbol cambiado.
  const escrita = de(cruda, 'accion').find((a) => a.toolUseId === 'su1');
  Object.assign(escrita, { exito: null, resultado: null, arbolAntes: 'arbol-a',
    arbolDespues: 'arbol-b', cambios: [{ archivo: 'b.md', diff: '+b\n' }] });
  const curada = curarYValidar(cruda);
  const residuo = de(curada, 'accion').find((a) => a.id === `${escrita.id}.r`);
  assert.equal(residuo.agente, 'subagente:ab12');
  assert.equal(residuo.lanzadaPor, escrita.lanzadaPor);
  assert.equal(residuo.paso, escrita.paso);
  residuo.intentosPrevios = [escrita.id];
  assert.deepEqual(validarActa(curada, { cruda }).map((e) => e.invariante), ['I7'],
    'un residuo no tiene intentos previos');
});

test('sin residuo: una herramienta que no llego a correr, con la captura como testigo', () => {
  // Paso en dos sesiones reales: el Edit no paso la validacion y el Write lo freno el
  // clasificador. Ningun hook se disparo, asi que la captura no tiene ningun evento suyo.
  const cruda = crudaDe(fabrica()
    .prompt('Edita')
    .llamada('tu1', 'Edit', { file_path: 'a.md', old_string: 'x', new_string: 'x' })
    .resultado('tu1', '<tool_use_error>No changes to make</tool_use_error>', { error: true })
    .llamada('tu2', 'Write', { file_path: 'b.md', content: 'b' })
    .resultado('tu2', 'Not run: stopped by a safety classifier.', { error: true }), {
    captura: [{ evento: 'SessionStart', momento: enCaptura(0, 0), arbol: 'arbol-a' }],
  });
  assert.deepEqual(de(cruda, 'accion').map((a) => a.capturada), [false, false]);
  const curada = curarYValidar(cruda);
  assert.deepEqual(de(curada, 'accion'), []);
});

test('detenida: un fallo de antes de que empezara la captura no se da por no corrido', () => {
  const cruda = crudaDe(fabrica()
    .prompt('Edita')
    .llamada('tu1', 'Write', { file_path: 'b.md', content: 'b' })
    .resultado('tu1', 'Error: disco lleno', { error: true }), {
    captura: [{ evento: 'SessionStart', momento: enCaptura(5, 0), arbol: 'arbol-a' }],
  });
  assert.equal(de(cruda, 'accion')[0].capturada, null);
  assert.throws(() => curar(serializar(cruda)), CuracionDetenida);
});

test('detenida: un fallo sin arbol que pudo escribir', () => {
  const cruda = crudaDe(fabrica()
    .prompt('Compila')
    .llamada('tu1', 'Bash', { command: 'make build' })
    .resultado('tu1', 'make: *** Error 2', { error: true }));
  assert.throws(() => curar(serializar(cruda)), (e) => e instanceof CuracionDetenida &&
    /a1 \(Bash\) fallo sin arbol/.test(e.motivos.join()));
});

test('detenida: un fallo que cambio el arbol y no trae su diff', () => {
  const cruda = crudaDe(fabrica()
    .prompt('Compila')
    .llamada('tu1', 'Bash', { command: 'make build' })
    .resultado('tu1', 'make: *** Error 2', { error: true }), {
    captura: [
      { evento: 'PreToolUse', toolUseId: 'tu1', arbol: 'arbol-a' },
      { evento: 'PostToolUseFailure', toolUseId: 'tu1', arbol: 'arbol-b' },
    ],
  });
  assert.throws(() => curar(serializar(cruda)), (e) => e instanceof CuracionDetenida &&
    /no trae su diff/.test(e.motivos.join()));
});

test('curarYEscribir: una curacion detenida no escribe nada (codigo 3)', () => {
  const carpeta = carpetaTemporal('detenida');
  const cruda = path.join(carpeta, 'sesion-1.acta.cruda.jsonl');
  fs.writeFileSync(cruda, serializar(crudaDe(fabrica()
    .prompt('Compila')
    .llamada('tu1', 'Bash', { command: 'make build' })
    .resultado('tu1', 'make: *** Error 2', { error: true }))));
  assert.equal(curarYEscribir({ cruda, log: silencio }), 3);
  assert.deepEqual(fs.readdirSync(carpeta), ['sesion-1.acta.cruda.jsonl']);
});

// --- Efectos de hooks y cadena de arboles -----------------------------------------------------

test('efecto de hook: entra en la curada y la cadena de arboles queda continua', () => {
  const { f, captura, raiz } = sesionConHook();
  const curada = curarYValidar(crudaDe(f, { captura, raizRepo: raiz }));
  assert.deepEqual(de(curada, 'accion').map((a) => a.herramienta), ['Edit', 'hook', 'Bash']);
  assert.ok(ids(curada, 'agente').includes('hook:sin-identificar'));
});

test('detenida: un hueco en la cadena de arboles que ninguna accion explica', () => {
  const { f, captura, raiz } = sesionConHook();
  const cruda = crudaDe(f, { captura, raizRepo: raiz });
  const hook = de(cruda, 'accion').find((a) => a.herramienta === 'hook');
  cruda.splice(cruda.indexOf(hook), 1);
  assert.throws(() => curar(serializar(cruda)), (e) => e instanceof CuracionDetenida &&
    /sin una accion que lo explique/.test(e.motivos.join()));
});

test('detenida: un efecto de hook que no trae su diff', () => {
  const { f, captura } = sesionConHook();
  const cruda = crudaDe(f, { captura });
  assert.throws(() => curar(serializar(cruda)), (e) => e instanceof CuracionDetenida &&
    /efecto de hook a\d+ .*no trae su diff/.test(e.motivos.join()));
});

test('cadena: la llamada Agent envuelve a su subagente y no la corta', () => {
  const repo = repoQueAvanza({ 'a.md': 'a\n' });
  const [a, b] = [repo.arbol(), repo.cambiar({ 'a.md': 'b\n' })];
  const cruda = crudaDe(fabrica()
    .prompt('Delega')
    .llamada('tu1', 'Agent', { subagent_type: 'general-purpose', prompt: 'x' })
    .resultado('tu1', 'ok', { extra: { agentId: 'ab12' } }), {
    antes: (carpeta) => subagente(carpeta, 'ab12', [
      { id: 'su1', name: 'Bash', input: { command: 'gen' } },
    ]),
    captura: [
      { evento: 'PreToolUse', toolUseId: 'tu1', arbol: a },
      { evento: 'PreToolUse', toolUseId: 'su1', arbol: a },
      { evento: 'PostToolUse', toolUseId: 'su1', arbol: b },
      { evento: 'PostToolUse', toolUseId: 'tu1', arbol: b },
    ],
    raizRepo: repo.raiz,
  });
  // La llamada (a -> b) va antes que su subagente (a -> b): como eslabon, cortaria la cadena.
  assert.deepEqual(de(cruda, 'accion').map((a) => a.toolUseId), ['tu1', 'su1']);
  curarYValidar(cruda);
});

// --- Subagentes no integrados -----------------------------------------------------------------

function crudaConSubagente(revierte, { conRepo = true } = {}) {
  const { f, captura, raiz, conSubagente } = sesionConSubagente({ revierte });
  return crudaDe(f, { captura, raizRepo: conRepo ? raiz : null, antes: conSubagente });
}

test('subagente integrado: entra con sus acciones', () => {
  const curada = curarYValidar(crudaConSubagente('nadie'));
  assert.ok(ids(curada, 'agente').includes('subagente:ab12'));
  assert.ok(de(curada, 'accion').some((a) => a.agente === 'subagente:ab12'));
});

test('subagente descartado que deshizo lo suyo: sale entero y la cadena aguanta', () => {
  const cruda = crudaConSubagente('el-mismo');
  const curada = curarYValidar(cruda);
  assert.deepEqual(de(curada, 'accion').map((a) => a.toolUseId), ['tu1', 'tu2']);
  assert.ok(!ids(curada, 'agente').includes('subagente:ab12'));
  const [, edit] = de(curada, 'accion');
  const fallido = de(cruda, 'accion').find((a) => a.toolUseId === 'su0');
  assert.deepEqual(edit.intentosPrevios, [fallido.id], 'sus fallos siguen contando para I7');
});

test('detenida: un subagente descartado que el orquestador revirtio corta la cadena', () => {
  const cruda = crudaConSubagente('orquestador');
  assert.throws(() => curar(serializar(cruda)), (e) => e instanceof CuracionDetenida &&
    /quitar subagente:ab12, descartado, corta la cadena/.test(e.motivos.join()));
});

test('detenida: un subagente que cambio el arbol y no se sabe si llego al final', () => {
  const cruda = crudaConSubagente('nadie', { conRepo: false });
  assert.throws(() => curar(serializar(cruda)), (e) => e instanceof CuracionDetenida &&
    /subagente:ab12 cambio el arbol y no se sabe/.test(e.motivos.join()));
});

function sembrarSubagente(mutar) {
  const curada = structuredClone(curarYValidar(crudaConSubagente('nadie')));
  mutar(de(curada, 'agente').find((a) => a.id === 'subagente:ab12'), curada);
  return validarActa(curada).map((e) => e.invariante);
}

test('rojo I3: un subagente descartado dentro de la curada', () => {
  assert.deepEqual(sembrarSubagente((a) => { a.integracion = 'descartado'; }), ['I3']);
});

test('rojo I3: un subagente sin integracion conocida dentro de la curada', () => {
  assert.deepEqual(sembrarSubagente((a) => { a.integracion = null; }), ['I3']);
});

test('rojo esquema: una integracion fuera de los valores, o en quien no es subagente', () => {
  assert.deepEqual(sembrarSubagente((a) => { a.integracion = 'quizas'; }), ['esquema']);
  assert.deepEqual(sembrarSubagente((a, c) => {
    de(c, 'agente').find((x) => x.id === 'orquestador').integracion = 'integrado';
  }), ['esquema']);
});

// --- Anexo de verificaciones negativas --------------------------------------------------------

const MARCADO = 'node scripts/verificar-ancho.mjs  # rojo-esperado: ancho de linea';

// Sembrar un sensor: se rompe a.md, el sensor sale en rojo y se revierte.
function crudaConRojo({ verde = false } = {}) {
  return crudaDe(fabrica()
    .prompt('Siembra el sensor')
    .texto('Rompo una linea y corro el sensor.')
    .llamada('tu1', 'Edit', { file_path: 'a.md', old_string: 'a', new_string: 'a'.repeat(120) })
    .resultado('tu1', 'ok')
    .llamada('tu2', 'Bash', { command: MARCADO })
    .resultado('tu2', verde ? 'Ninguna linea pasa del tope' : 'Fuera de regla (1)',
      { error: !verde })
    .llamada('tu3', 'Bash', { command: 'git checkout -- a.md' })
    .resultado('tu3', 'ok'), { captura: sinCambio('tu2') });
}

test('anexo: un rojo esperado sale de la secuencia y queda al final de la curada', () => {
  const cruda = crudaConRojo();
  const curada = curarYValidar(cruda);
  assert.deepEqual(de(curada, 'accion').map((a) => a.toolUseId), ['tu1', 'tu3']);
  const rojo = de(cruda, 'accion').find((a) => a.toolUseId === 'tu2');
  assert.deepEqual(curada[curada.length - 1], {
    elemento: 'verificacion_negativa',
    id: `${rojo.id}.v`,
    accion: rojo.id,
    agente: 'orquestador',
    comando: MARCADO,
    rojoEsperado: 'ancho de linea',
    enRojo: true,
    resultado: 'Fuera de regla (1)',
    momento: rojo.momento,
  });
});

test('anexo: un rojo esperado que sale verde queda con enRojo = false, fuera de secuencia', () => {
  const curada = curarYValidar(crudaConRojo({ verde: true }));
  assert.deepEqual(de(curada, 'accion').map((a) => a.toolUseId), ['tu1', 'tu3']);
  assert.equal(de(curada, 'verificacion_negativa')[0].enRojo, false);
});

test('anexo: la marca vale en PowerShell y en cualquier linea del comando', () => {
  const comando = 'mvn -q test  # rojo-esperado: prueba de aceptacion 3\nWrite-Host listo';
  const cruda = crudaDe(fabrica()
    .prompt('TDD')
    .llamada('tu1', 'PowerShell', { command: comando })
    .resultado('tu1', 'BUILD FAILURE', { error: true }), { captura: sinCambio('tu1') });
  const curada = curarYValidar(cruda);
  assert.deepEqual(de(curada, 'verificacion_negativa').map((v) => v.rojoEsperado),
    ['prueba de aceptacion 3']);
  assert.deepEqual(de(curada, 'turno'), [], 'el turno de solo el rojo queda vacio y sale');
});

test('anexo: un rojo esperado que cambio el arbol deja su residuo en la secuencia', () => {
  const cruda = crudaConRojo();
  const rojo = de(cruda, 'accion').find((a) => a.toolUseId === 'tu2');
  Object.assign(rojo, { arbolAntes: 'arbol-a', arbolDespues: 'arbol-b',
    cambios: [{ archivo: 'reporte.txt', diff: '+x\n' }] });
  cruda[0].arbolBase = 'arbol-a';
  const curada = curar(serializar(cruda));
  assert.ok(de(curada, 'accion').some((a) => a.id === `${rojo.id}.r`));
  assert.equal(de(curada, 'verificacion_negativa')[0].accion, rojo.id);
});

test('detenida: un rojo esperado que salio verde sin arbol y pudo escribir', () => {
  const cruda = crudaDe(fabrica()
    .prompt('Siembra')
    .llamada('tu1', 'Bash', { command: MARCADO })
    .resultado('tu1', 'Ninguna linea pasa del tope'));
  assert.throws(() => curar(serializar(cruda)), (e) => e instanceof CuracionDetenida &&
    /salio verde de la secuencia sin arbol/.test(e.motivos.join()));
});

test('anexo: el rojo de un subagente descartado se conserva', () => {
  const cruda = crudaConSubagente('el-mismo');
  const sembrado = de(cruda, 'accion').find((a) => a.toolUseId === 'su2');
  sembrado.entrada.command = `npm test  # rojo-esperado: revertir deja la prueba en rojo`;
  const curada = curarYValidar(cruda);
  assert.ok(!ids(curada, 'agente').includes('subagente:ab12'));
  assert.deepEqual(de(curada, 'verificacion_negativa').map((v) => v.accion), [sembrado.id]);
});

function sembrarAnexo(mutar) {
  const cruda = crudaConRojo();
  const curada = structuredClone(curarYValidar(cruda));
  mutar(curada, cruda);
  return validarActa(curada, { cruda }).map((e) => e.invariante);
}

test('rojo anexo: un rojo esperado dentro de la secuencia', () => {
  const r = sembrarAnexo((c, cruda) => {
    c.push({ ...de(cruda, 'accion').find((a) => a.toolUseId === 'tu2'), exito: true,
      intentosPrevios: [] });
  });
  assert.ok(r.includes('anexo'), r.join());
});

test('rojo anexo: falta una verificacion, sobra otra o no dice lo que declara la accion', () => {
  const v = (c) => de(c, 'verificacion_negativa')[0];
  assert.deepEqual(sembrarAnexo((c) => { c.splice(c.indexOf(v(c)), 1); }), ['anexo']);
  assert.deepEqual(sembrarAnexo((c) => { c.push({ ...v(c), id: 'x.v', accion: 'a1' }); }),
    ['anexo']);
  assert.deepEqual(sembrarAnexo((c) => { v(c).rojoEsperado = 'otra cosa'; }), ['anexo']);
  assert.deepEqual(sembrarAnexo((c) => { v(c).enRojo = false; }), ['anexo']);
  assert.deepEqual(sembrarAnexo((c) => { v(c).enRojo = 'si'; }), ['anexo', 'anexo']);
});

test('rojo anexo: la cruda no tiene anexo', () => {
  const cruda = crudaConRojo();
  const curada = curar(serializar(cruda));
  cruda.push(de(curada, 'verificacion_negativa')[0]);
  assert.deepEqual(validarActa(cruda).map((e) => e.invariante), ['anexo']);
});

// --- Determinismo (I8) ------------------------------------------------------------------------

test('determinismo: curar dos veces la misma cruda da el mismo archivo, byte a byte', () => {
  // Con un Edit en cuya ventana escribio un hook: la verificacion lo parte y escribe un arbol
  // intermedio en el repo, y aun asi las dos curadas son iguales.
  const repo = repoQueAvanza({ 'a.md': 'a\n', 'b.md': 'b\n' });
  const a0 = repo.arbol();
  repo.cambiar({ 'a.md': 'b\n' });
  const a2 = repo.cambiar({ 'b.md': 'B\n' });
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
  const captura = path.join(carpeta, 'captura.jsonl');
  fs.writeFileSync(captura, [
    { evento: 'SessionStart', arbol: a0 },
    { evento: 'PreToolUse', toolUseId: 'tu1', arbol: a0 },
    { evento: 'PostToolUseFailure', toolUseId: 'tu1', arbol: a0 },
    { evento: 'PreToolUse', toolUseId: 'tu2', arbol: a0 },
    { evento: 'PostToolUse', toolUseId: 'tu2', arbol: a2 },
    { evento: 'SessionEnd', arbol: a2 },
  ].map((c) => JSON.stringify(c)).join('\n'));
  const salida = carpetaTemporal('det-cruda');
  assert.equal(compilarYEscribir({ transcript, captura, salida, raizRepo: repo.raiz,
    verificar: false, log: silencio }), 0);
  const cruda = path.join(salida, '10', 'sesion-1.acta.cruda.jsonl');
  const destinos = [carpetaTemporal('det-a'), carpetaTemporal('det-b')]
    .map((c) => path.join(c, 'sesion-1.acta.curada.jsonl'));
  for (const d of destinos) {
    assert.equal(curarYEscribir({ cruda, salida: d, raizRepo: repo.raiz, log: silencio }), 0);
  }
  const [a, b] = destinos.map((d) => fs.readFileSync(d));
  assert.match(a.toString(), /"herramienta":"hook"/, 'la verificacion partio el Edit');
  assert.ok(a.equals(b));
  assert.equal(curarYEscribir({ cruda, raizRepo: repo.raiz, log: silencio }), 0);
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
  const raizRepo = repoTemporal({ 'a.md': 'a\n' });
  assert.equal(curarYEscribir({ cruda, raizRepo, log: silencio }), 1);
  assert.deepEqual(fs.readdirSync(carpeta), ['sesion-1.acta.cruda.jsonl']);
});

// --- Intentos previos (I7) --------------------------------------------------------------------

// a.md con Edit: dos fallos, un exito, un fallo y otro exito. En medio, un fallo de Edit sobre
// b.md y un Write exitoso sobre a.md: otra clave, no cuentan.
function crudaConIntentos() {
  const f = fabrica().prompt('Edita');
  const pasos = [
    ['tu1', 'Edit', 'a.md', false],
    ['tu2', 'Edit', 'b.md', false],
    ['tu3', 'Edit', 'a.md', false],
    ['tu4', 'Write', 'a.md', true],
    ['tu5', 'Edit', 'a.md', true],
    ['tu6', 'Edit', 'a.md', false],
    ['tu7', 'Edit', 'a.md', true],
  ];
  for (const [id, herramienta, archivo, exito] of pasos) {
    f.llamada(id, herramienta, { file_path: archivo, old_string: id, new_string: 'y' })
      .resultado(id, exito ? 'ok' : 'String to replace not found in file.', { error: !exito });
  }
  return crudaDe(f, { captura: sinCambio('tu1', 'tu2', 'tu3', 'tu6') });
}

const idDe = (acta, toolUseId) => de(acta, 'accion').find((a) => a.toolUseId === toolUseId).id;

test('intentos previos: los fallos con su herramienta y su archivo desde el exito anterior', () => {
  const cruda = crudaConIntentos();
  const curada = curarYValidar(cruda);
  const previos = Object.fromEntries(de(curada, 'accion')
    .map((a) => [a.toolUseId, a.intentosPrevios]));
  assert.deepEqual(previos, {
    tu4: [],
    tu5: [idDe(cruda, 'tu1'), idDe(cruda, 'tu3')],
    tu7: [idDe(cruda, 'tu6')],
  });
});

function sembrarIntentos(mutar) {
  const cruda = crudaConIntentos();
  const curada = structuredClone(curarYValidar(cruda));
  const accion = (toolUseId) => de(curada, 'accion').find((a) => a.toolUseId === toolUseId);
  mutar(accion, (toolUseId) => idDe(cruda, toolUseId));
  return { con: validarActa(curada, { cruda }).map((e) => e.invariante),
    sin: validarActa(curada).map((e) => e.invariante) };
}

test('rojo I7: le falta un intento previo', () => {
  const r = sembrarIntentos((accion, id) => { accion('tu5').intentosPrevios = [id('tu3')]; });
  assert.deepEqual(r, { con: ['I7'], sin: [] }, 'sin la cruda, I7 no se valida');
});

test('rojo I7: un exito contado como intento previo', () => {
  const r = sembrarIntentos((accion, id) => { accion('tu7').intentosPrevios.push(id('tu5')); });
  assert.deepEqual(r.con, ['I7']);
});

test('rojo I7: un fallo sobre otro archivo', () => {
  const r = sembrarIntentos((accion, id) => { accion('tu5').intentosPrevios.push(id('tu2')); });
  assert.deepEqual(r.con, ['I7']);
});

test('rojo I7: un fallo con otra herramienta sobre el mismo archivo', () => {
  const r = sembrarIntentos((accion, id) => { accion('tu4').intentosPrevios.push(id('tu3')); });
  assert.deepEqual(r.con, ['I7']);
});

test('rojo I7: un fallo anterior al exito previo', () => {
  const r = sembrarIntentos((accion, id) => { accion('tu7').intentosPrevios.unshift(id('tu1')); });
  assert.deepEqual(r.con, ['I7']);
});

test('rojo I7: una accion curada sin intentosPrevios', () => {
  const r = sembrarIntentos((accion) => { delete accion('tu4').intentosPrevios; });
  assert.deepEqual(r.con, ['I7']);
});
