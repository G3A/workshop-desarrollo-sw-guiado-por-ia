// Pruebas de la validacion del arbol final (#218). Desde la raiz:
//   node --test $ACTA/pruebas/verificar-arbol.test.mjs
//
// Cada escenario usa un repo real de prueba (repoQueAvanza): la verificacion lee blobs y escribe
// arboles intermedios, y eso solo se prueba contra objetos de verdad.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { compilar, serializar } from '../compilar-acta.mjs';
import { curar } from '../curar-acta.mjs';
import { validarActa } from '../validar-acta.mjs';
import { rutaEnRepo, verificarArbol } from '../verificar-arbol.mjs';
import { carpetaTemporal, fabrica, repoQueAvanza } from './fabrica.mjs';

const de = (registros, elemento) => registros.filter((r) => r.elemento === elemento);

// Una sesion de una sola accion, con la captura de su ventana y el cierre.
function sesion(repo, herramienta, entrada, antes, despues) {
  const f = fabrica().prompt('Edita').llamada('tu1', herramienta, entrada).resultado('tu1', 'ok');
  const captura = [
    { evento: 'SessionStart', arbol: antes },
    { evento: 'PreToolUse', toolUseId: 'tu1', arbol: antes },
    { evento: 'PostToolUse', toolUseId: 'tu1', arbol: despues },
    { evento: 'SessionEnd', arbol: despues },
  ];
  const transcript = f.escribir(carpetaTemporal('sesion'));
  const [cruda] = compilar({ transcript, captura, raizRepo: repo.raiz }).actas.values();
  return { cruda, curada: curar(serializar(cruda)) };
}

function verificar(repo, ...args) {
  const { cruda, curada } = sesion(repo, ...args);
  const r = verificarArbol(curada, repo.raiz);
  if (!r.motivos.length) assert.deepEqual(validarActa(r.registros, { cruda }), []);
  return { ...r, curada };
}

test('un Edit que reproduce su arbol no se toca', () => {
  const repo = repoQueAvanza({ 'a.md': 'uno\n' });
  const a0 = repo.arbol();
  const a1 = repo.cambiar({ 'a.md': 'dos\n' });
  const r = verificar(repo, 'Edit', { file_path: 'a.md', old_string: 'uno', new_string: 'dos' },
    a0, a1);
  assert.deepEqual(r.motivos, []);
  assert.deepEqual(r.registros, r.curada);
});

test('hook en la ventana de un Edit, sobre otro archivo: la accion se parte en dos', () => {
  const repo = repoQueAvanza({ 'a.md': 'uno\n', 'b.md': 'b\n' });
  const a0 = repo.arbol();
  const a1 = repo.cambiar({ 'a.md': 'dos\n' });
  const a2 = repo.cambiar({ 'b.md': 'formateado\n' });
  const r = verificar(repo, 'Edit', { file_path: 'a.md', old_string: 'uno', new_string: 'dos' },
    a0, a2);
  assert.deepEqual(r.motivos, []);
  const [edit, hook] = de(r.registros, 'accion');
  assert.deepEqual([edit.arbolAntes, edit.arbolDespues], [a0, a1]);
  assert.deepEqual(edit.cambios.map((c) => c.archivo), ['a.md']);
  assert.equal(hook.id, `${edit.id}.h`);
  assert.deepEqual([hook.herramienta, hook.parteDe, hook.agente],
    ['hook', edit.id, 'hook:sin-identificar']);
  assert.deepEqual([hook.arbolAntes, hook.arbolDespues], [a1, a2]);
  assert.deepEqual(hook.cambios.map((c) => c.archivo), ['b.md']);
  const agente = de(r.registros, 'agente').find((a) => a.id === 'hook:sin-identificar');
  assert.equal(agente.tipo, 'automatismo');
});

test('hook en la ventana de un Edit, sobre el mismo archivo: el formato queda en el hook', () => {
  const repo = repoQueAvanza({ 'A.java': 'class A {}\n' });
  const a0 = repo.arbol();
  const a1 = repo.cambiar({ 'A.java': 'class A { int x; }\n' });
  const a2 = repo.cambiar({ 'A.java': 'class A {\n  int x;\n}\n' });
  const r = verificar(repo, 'Edit', { file_path: 'A.java', old_string: '{}',
    new_string: '{ int x; }' }, a0, a2);
  assert.deepEqual(r.motivos, []);
  const [edit, hook] = de(r.registros, 'accion');
  assert.equal(edit.arbolDespues, a1);
  assert.match(hook.cambios[0].diff, /-class A \{ int x; \}/);
});

test('Write, MultiEdit y replace_all se aplican como en Claude Code', () => {
  const repo = repoQueAvanza({ 'a.md': 'x y x\n' });
  const a0 = repo.arbol();
  const a1 = repo.cambiar({ 'nuevo.md': 'hola\n' });
  assert.deepEqual(verificar(repo, 'Write', { file_path: 'nuevo.md', content: 'hola\n' },
    a0, a1).motivos, []);
  const a2 = repo.cambiar({ 'a.md': 'z w z\n' });
  const r = verificar(repo, 'MultiEdit', { file_path: '.\\a.md', edits: [
    { old_string: 'x', new_string: 'z', replace_all: true },
    { old_string: 'y', new_string: 'w' },
  ] }, a1, a2);
  assert.deepEqual(r.motivos, []);
  assert.deepEqual(r.registros, r.curada);
});

test('un archivo fuera del repo, o que git no versiona, no es efecto de la accion', () => {
  const repo = repoQueAvanza({ 'b.md': 'b\n' });
  const a0 = repo.arbol();
  const a1 = repo.cambiar({ 'b.md': 'formateado\n' });
  for (const archivo of ['~\\notas.md', '.ia/borrador.md']) {
    const r = verificar(repo, 'Write', { file_path: archivo, content: 'x' }, a0, a1);
    assert.deepEqual(r.motivos, [], archivo);
    const [write, hook] = de(r.registros, 'accion');
    assert.deepEqual([write.arbolDespues, hook.arbolAntes, hook.arbolDespues], [a0, a0, a1]);
  }
});

test('rutaEnRepo: relativa al repo, o null si apunta fuera', () => {
  assert.equal(rutaEnRepo('.\\docs\\a.md'), 'docs/a.md');
  assert.equal(rutaEnRepo('./a.md'), 'a.md');
  for (const fuera of ['~/x', 'C:\\x', '/tmp/x', '../x', '', null]) {
    assert.equal(rutaEnRepo(fuera), null, String(fuera));
  }
});

test('detenida: un Edit que no se aplica sobre su arbol de antes', () => {
  const repo = repoQueAvanza({ 'a.md': 'uno\n' });
  const a0 = repo.arbol();
  const a1 = repo.cambiar({ 'a.md': 'dos\n' });
  const r = verificar(repo, 'Edit', { file_path: 'a.md', old_string: 'zzz', new_string: 'dos' },
    a0, a1);
  assert.match(r.motivos.join(), /no se aplica sobre su arbol de antes/);
});

test('detenida: la curada no termina en el arbol final de la sesion', () => {
  // De una compilacion no sale: lo que cambie antes de SessionEnd entra como accion `hook`. Es
  // el respaldo de la cadena, asi que se siembra en la cabecera.
  const repo = repoQueAvanza({ 'a.md': 'uno\n' });
  const a0 = repo.arbol();
  const a1 = repo.cambiar({ 'a.md': 'dos\n' });
  const a2 = repo.cambiar({ 'a.md': 'tres\n' });
  const { curada } = sesion(repo, 'Edit', { file_path: 'a.md', old_string: 'uno',
    new_string: 'dos' }, a0, a1);
  assert.equal(curada[0].arbolFinal, a1);
  curada[0].arbolFinal = a2;
  assert.match(verificarArbol(curada, repo.raiz).motivos.join(),
    new RegExp(`termina en el arbol ${a1} y la sesion en ${a2}`));
});

test('detenida: sin repo, o con un arbol que el repo no tiene', () => {
  const repo = repoQueAvanza({ 'a.md': 'uno\n' });
  const a0 = repo.arbol();
  const a1 = repo.cambiar({ 'a.md': 'dos\n' });
  const { curada } = sesion(repo, 'Edit', { file_path: 'a.md', old_string: 'uno',
    new_string: 'dos' }, a0, a1);
  assert.match(verificarArbol(curada, null).motivos.join(), /sin el repo/);
  const falso = 'f'.repeat(40);
  de(curada, 'accion')[0].arbolAntes = falso;
  assert.match(verificarArbol(curada, repo.raiz).motivos.join(), new RegExp(`faltan .*${falso}`));
});
