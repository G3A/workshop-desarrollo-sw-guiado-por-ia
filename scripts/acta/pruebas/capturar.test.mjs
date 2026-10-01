// Pruebas del hook de captura (#216): node --test scripts/acta/pruebas/capturar.test.mjs
//
// El hook se corre como lo corre Claude Code: un proceso aparte con el JSON del evento en stdin.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { git } from '../git.mjs';
import { carpetaTemporal, fabrica, repoTemporal } from './fabrica.mjs';

const HOOK = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'capturar.mjs');

function correrHook(entrada) {
  const env = { ...process.env };
  delete env.GIT_DIR;
  delete env.GIT_WORK_TREE;
  delete env.GIT_INDEX_FILE;
  return spawnSync(process.execPath, [HOOK], {
    input: typeof entrada === 'string' ? entrada : JSON.stringify(entrada),
    env,
    encoding: 'utf8',
  });
}

const leer = (archivo) => fs.readFileSync(archivo, 'utf8').trim().split('\n').map(JSON.parse);

test('captura: arbol antes y despues de una edicion, nada para una lectura', () => {
  const raiz = repoTemporal({ 'a.md': 'uno\n' });
  const comun = { session_id: 's1', cwd: raiz };
  assert.equal(correrHook({ ...comun, hook_event_name: 'SessionStart', source: 'startup' })
    .status, 0);
  correrHook({ ...comun, hook_event_name: 'PreToolUse', tool_name: 'Edit', tool_use_id: 'tu1' });
  fs.writeFileSync(path.join(raiz, 'a.md'), 'dos\n');
  correrHook({ ...comun, hook_event_name: 'PostToolUse', tool_name: 'Edit', tool_use_id: 'tu1' });
  correrHook({ ...comun, hook_event_name: 'PreToolUse', tool_name: 'Read', tool_use_id: 'tu2',
    agent_id: 'ab12' });

  const registros = leer(path.join(raiz, '.ia', 'captura', 's1.jsonl'));
  assert.deepEqual(registros.map((r) => r.evento),
    ['SessionStart', 'PreToolUse', 'PostToolUse', 'PreToolUse']);
  const [inicio, antes, despues, lectura] = registros;
  assert.equal(inicio.head, git(['rev-parse', 'HEAD'], { cwd: raiz }).trim());
  assert.match(antes.arbol, /^[0-9a-f]{40}$/);
  assert.equal(antes.arbol, inicio.arbol);
  assert.notEqual(despues.arbol, antes.arbol);
  assert.equal(lectura.arbol, null);
  assert.equal(lectura.agenteId, 'ab12');
});

// Las dos formas del repo: uno que no conoce .ia/ y otro que la ignora, como este monorepo. La
// segunda fallo en vivo: git rechaza el pathspec de exclusion sobre una ruta ignorada, y la
// captura dejaba el arbol en null sin que ninguna prueba lo viera.
for (const [nombre, archivos] of [
  ['sin ignorar', { 'a.md': 'uno\n' }],
  ['ignorada', { 'a.md': 'uno\n', '.gitignore': '.ia/\n' }],
]) {
  test(`captura: escribir en .ia/ no cambia el hash del arbol (${nombre})`, () => {
    const raiz = repoTemporal(archivos);
    const comun = { session_id: 's2', cwd: raiz, tool_name: 'Bash' };
    correrHook({ ...comun, hook_event_name: 'PreToolUse', tool_use_id: 'tu1' });
    correrHook({ ...comun, hook_event_name: 'PostToolUse', tool_use_id: 'tu1' });
    const [a, b] = leer(path.join(raiz, '.ia', 'captura', 's2.jsonl'));
    assert.match(a.arbol || '', /^[0-9a-f]{40}$/, 'la captura tiene que tener arbol');
    assert.equal(a.arbol, b.arbol);
  });
}

test('captura: nunca bloquea la sesion, ni con entrada rota ni fuera de un repo', () => {
  assert.equal(correrHook('esto no es json').status, 0);
  assert.equal(correrHook({ session_id: 'x', hook_event_name: 'PreToolUse',
    cwd: carpetaTemporal('fuera') }).status, 0);
});

test('captura: SessionEnd compila el acta de la sesion', (t) => {
  const raiz = repoTemporal({ 'a.md': 'uno\n' });
  const transcript = fabrica({ sesion: 's3', cwd: raiz })
    .prompt('Edita')
    .llamada('tu1', 'Edit', { file_path: 'a.md', old_string: 'uno', new_string: 'dos' })
    .resultado('tu1', 'ok')
    .escribir(carpetaTemporal('t'));
  const r = correrHook({ session_id: 's3', cwd: raiz, hook_event_name: 'SessionEnd',
    reason: 'other', transcript_path: transcript });
  assert.equal(r.status, 0);
  if (/No hay gitleaks/.test(r.stderr)) {
    t.skip('gitleaks no esta en el PATH');
    return;
  }
  const acta = path.join(raiz, '.ia', 'registros', '10', 's3.acta.cruda.jsonl');
  assert.ok(fs.existsSync(acta), r.stderr);
  assert.equal(leer(acta)[0].elemento, 'acta');
});
