// Pruebas del hook de captura (#216): node --test $ACTA/pruebas/capturar.test.mjs
//
// El hook se corre como lo corre Claude Code: un proceso aparte con el JSON del evento en stdin.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { git } from '../git.mjs';
import { TOPE_POR_ARCHIVO, huellaDeEntrada } from '../nucleo-captura.mjs';
import { carpetaTemporal, fabrica, repoTemporal } from './fabrica.mjs';

const HOOK = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'capturar.mjs');

function correrHook(entrada, hook = HOOK, args = []) {
  const env = { ...process.env };
  delete env.GIT_DIR;
  delete env.GIT_WORK_TREE;
  delete env.GIT_INDEX_FILE;
  return spawnSync(process.execPath, [hook, ...args], {
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

// Las tres formas del repo: uno que no conoce .ia/, otro que la ignora, y otro que la ignora y ya
// versiona un acta, como este monorepo desde #237. La segunda fallo en vivo: git rechaza el
// pathspec de exclusion sobre una ruta ignorada, y la captura dejaba el arbol en null sin que
// ninguna prueba lo viera. La tercera fallo igual (#244): con un archivo versionado adentro,
// `git check-ignore` sin --no-index ya no dice que .ia este ignorada.
const versionarActa = (raiz) => {
  const acta = path.join(raiz, '.ia', 'registros', '1', 's0.acta.cruda.jsonl');
  fs.mkdirSync(path.dirname(acta), { recursive: true });
  fs.writeFileSync(acta, '{}\n');
  git(['add', '-f', '.ia'], { cwd: raiz });
  git(['commit', '-q', '-m', 'acta'], { cwd: raiz });
};
for (const [nombre, archivos, preparar] of [
  ['sin ignorar', { 'a.md': 'uno\n' }, () => {}],
  ['ignorada', { 'a.md': 'uno\n', '.gitignore': '.ia/\n' }, () => {}],
  ['ignorada y con un acta versionada', { 'a.md': 'uno\n', '.gitignore': '.ia/\n' },
    versionarActa],
]) {
  test(`captura: escribir en .ia/ no cambia el hash del arbol (${nombre})`, () => {
    const raiz = repoTemporal(archivos);
    preparar(raiz);
    const comun = { session_id: 's2', cwd: raiz, tool_name: 'Bash' };
    correrHook({ ...comun, hook_event_name: 'PreToolUse', tool_use_id: 'tu1' });
    correrHook({ ...comun, hook_event_name: 'PostToolUse', tool_use_id: 'tu1' });
    const [a, b] = leer(path.join(raiz, '.ia', 'captura', 's2.jsonl'));
    assert.match(a.arbol || '', /^[0-9a-f]{40}$/, 'la captura tiene que tener arbol');
    assert.equal(a.arbol, b.arbol);
  });
}

// El hook del plugin (#247, ADR-0007). En un repo que solo instala el plugin captura; en uno que
// declara su propia captura, como el monorepo que desarrolla el acta, cede: Claude Code corre los
// dos hooks aunque sean el mismo, y cada evento quedaria dos veces.
test('captura desde el plugin: registra en un repo sin captura propia, cede en uno que la tiene',
  () => {
    const entrada = (raiz) => ({ session_id: 's3', cwd: raiz, hook_event_name: 'SessionStart' });
    const sinPropia = repoTemporal({ 'a.md': 'uno\n' });
    assert.equal(correrHook(entrada(sinPropia), HOOK, ['--desde-plugin']).status, 0);
    assert.ok(fs.existsSync(path.join(sinPropia, '.ia', 'captura', 's3.jsonl')));

    const conPropia = repoTemporal({ 'a.md': 'uno\n', '.claude/settings.json':
      '{"hooks":{"SessionStart":[{"hooks":[{"command":"node acta/capturar.mjs"}]}]}}' });
    assert.equal(correrHook(entrada(conPropia), HOOK, ['--desde-plugin']).status, 0);
    assert.ok(!fs.existsSync(path.join(conPropia, '.ia', 'captura', 's3.jsonl')),
      'el hook del plugin no cede ante la captura del proyecto');
    correrHook(entrada(conPropia));
    assert.ok(fs.existsSync(path.join(conPropia, '.ia', 'captura', 's3.jsonl')),
      'la captura del proyecto, sin el flag, si registra');
  });

// Las dos declaraciones de la captura tienen que decir lo mismo: los seis eventos, con los mismos
// tiempos. Si una suma un evento y la otra no, el acta de un repo con el plugin y la de este
// monorepo dejarian de ser comparables, sin que nada fallara.
test('captura: el hooks.json del plugin y el settings.json del monorepo registran lo mismo', () => {
  const raiz = path.join(path.dirname(HOOK), '..', '..', '..');
  const capturas = (archivo, esCaptura) => {
    const { hooks } = JSON.parse(fs.readFileSync(archivo, 'utf8'));
    return Object.fromEntries(Object.entries(hooks).flatMap(([evento, grupos]) =>
      grupos.flatMap((g) => g.hooks).filter(esCaptura).map((h) => [evento, h.timeout])));
  };
  const delPlugin = capturas(path.join(raiz, 'instrumentacion-java-ia', 'sdlc-ia', 'hooks',
    'hooks.json'), (h) => (h.args || []).some((a) => a.endsWith('/acta/capturar.mjs')) &&
    h.args.includes('--desde-plugin'));
  const delMonorepo = capturas(path.join(raiz, '.claude', 'settings.json'),
    (h) => String(h.command).includes('acta/capturar.mjs'));
  assert.deepEqual(Object.keys(delPlugin).sort(), ['PermissionRequest', 'PostToolUse',
    'PostToolUseFailure', 'PreToolUse', 'SessionEnd', 'SessionStart']);
  assert.deepEqual(delPlugin, delMonorepo);
});

// La perdida de eventos de la sesion de #218 (#222). Una accion dejo curar-acta.mjs con un salto
// de linea dentro de un literal, y capturar.mjs, que lo importaba, no cargaba: de a236 a a239 no
// se registro ningun evento. Aqui se copia la carpeta del acta y se rompe asi cada modulo salvo
// los dos que el hook carga en cada evento. Si la captura vuelve a importar alguno, esta prueba
// lo ve.
test('captura: un modulo del acta roto a mitad de sesion no le quita eventos', () => {
  const origen = path.dirname(HOOK);
  const copia = path.join(carpetaTemporal('acta-rota'), 'acta');
  fs.mkdirSync(copia);
  for (const nombre of fs.readdirSync(origen).filter((n) => n.endsWith('.mjs'))) {
    const intactos = ['capturar.mjs', 'nucleo-captura.mjs'];
    const texto = intactos.includes(nombre) ? fs.readFileSync(path.join(origen, nombre), 'utf8')
      : "export const roto = 'un salto\nde linea';\n";
    fs.writeFileSync(path.join(copia, nombre), texto);
  }
  const hook = path.join(copia, 'capturar.mjs');
  const raiz = repoTemporal({ 'a.md': 'uno\n' });
  const comun = { session_id: 's5', cwd: raiz };
  const correr = (entrada) => correrHook(entrada, hook);
  correr({ ...comun, hook_event_name: 'SessionStart', source: 'startup' });
  correr({ ...comun, hook_event_name: 'PreToolUse', tool_name: 'Edit', tool_use_id: 'tu1' });
  fs.writeFileSync(path.join(raiz, 'a.md'), 'dos\n');
  correr({ ...comun, hook_event_name: 'PostToolUse', tool_name: 'Edit', tool_use_id: 'tu1' });
  correr({ ...comun, hook_event_name: 'PreToolUse', tool_name: 'Read', tool_use_id: 'tu2' });
  correr({ ...comun, hook_event_name: 'PostToolUse', tool_name: 'Read', tool_use_id: 'tu2' });
  const transcript = fabrica({ sesion: 's5', cwd: raiz }).prompt('Edita')
    .escribir(carpetaTemporal('t'));
  const cierre = correr({ ...comun, hook_event_name: 'SessionEnd', reason: 'other',
    transcript_path: transcript });

  assert.equal(cierre.status, 0);
  assert.match(cierre.stderr, /la captura quedo escrita, pero el compilador no carga/);
  const registros = leer(path.join(raiz, '.ia', 'captura', 's5.jsonl'));
  assert.deepEqual(registros.map((r) => [r.evento, r.toolUseId ?? null]), [
    ['SessionStart', null], ['PreToolUse', 'tu1'], ['PostToolUse', 'tu1'],
    ['PreToolUse', 'tu2'], ['PostToolUse', 'tu2'], ['SessionEnd', null],
  ]);
  const [, antes, despues] = registros;
  assert.match(antes.arbol || '', /^[0-9a-f]{40}$/);
  assert.notEqual(despues.arbol, antes.arbol);
});

// La forma del PermissionRequest de Claude Code 2.1.287, medida con una sonda (#222, fase 5): trae
// tool_name, tool_input, permission_mode y permission_suggestions, pero no tool_use_id.
test('captura: el pedido de permiso, con el hash de su entrada y sin responder nada', () => {
  const raiz = repoTemporal({ 'a.md': 'uno\n' });
  const tool_input = { file_path: 'a.md', content: 'dos\n' };
  const comun = { session_id: 's6', cwd: raiz, tool_name: 'Write', tool_input };
  correrHook({ ...comun, hook_event_name: 'PreToolUse', tool_use_id: 'tu1' });
  const r = correrHook({ ...comun, hook_event_name: 'PermissionRequest',
    permission_mode: 'default', permission_suggestions: [] });
  assert.deepEqual([r.status, r.stdout], [0, ''], 'sin salida no decide el permiso');
  const [pre, permiso] = leer(path.join(raiz, '.ia', 'captura', 's6.jsonl'));
  assert.equal(permiso.evento, 'PermissionRequest');
  assert.equal(permiso.herramienta, 'Write');
  assert.equal(permiso.modo, 'default');
  assert.match(permiso.entrada, /^[0-9a-f]{64}$/);
  assert.equal(permiso.entrada, pre.entrada, 'se empareja con su PreToolUse por la entrada');
  assert.equal(permiso.entrada, huellaDeEntrada({ content: 'dos\n', file_path: 'a.md' }),
    'el orden de las claves no cambia el hash');
});

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
  const [cierre] = leer(path.join(raiz, '.ia', 'captura', 's3.jsonl'));
  assert.match(cierre.arbol || '', /^[0-9a-f]{40}$/, 'el cierre deja ver el ultimo efecto de hook');
  if (/No hay gitleaks/.test(r.stderr)) {
    t.skip('gitleaks no esta en el PATH');
    return;
  }
  const acta = path.join(raiz, '.ia', 'registros', '10', 's3.acta.cruda.jsonl');
  assert.ok(fs.existsSync(acta), r.stderr);
  assert.equal(leer(acta)[0].elemento, 'acta');
  const curada = path.join(raiz, '.ia', 'registros', '10', 's3.acta.curada.jsonl');
  assert.ok(fs.existsSync(curada), `SessionEnd tambien cura el acta: ${r.stderr}`);
  assert.equal(leer(curada)[0].derivadaDe.acta, 's3.acta.cruda.jsonl');
  const indice = JSON.parse(fs.readFileSync(path.join(path.dirname(acta), 'indice.json'), 'utf8'));
  assert.deepEqual(indice.actas.map((a) => [a.sesion, a.curada]),
    [['s3', 's3.acta.curada.jsonl']], 'el indice se escribe despues de curar');
});

test('captura: si la curacion no sale, avisa, no bloquea y no deja una curada vieja', (t) => {
  const raiz = repoTemporal({ 'a.md': 'uno\n' });
  // Un Bash que fallo antes de la captura: no se sabe si escribio, y la curacion se detiene.
  const transcript = fabrica({ sesion: 's4', cwd: raiz })
    .prompt('Compila')
    .llamada('tu1', 'Bash', { command: 'make build' })
    .resultado('tu1', 'make: *** Error 2', { error: true })
    .escribir(carpetaTemporal('t'));
  const vieja = path.join(raiz, '.ia', 'registros', '10', 's4.acta.curada.jsonl');
  fs.mkdirSync(path.dirname(vieja), { recursive: true });
  fs.writeFileSync(vieja, '{"elemento":"acta","de":"un cierre anterior"}\n');
  const r = correrHook({ session_id: 's4', cwd: raiz, hook_event_name: 'SessionEnd',
    reason: 'other', transcript_path: transcript });
  assert.equal(r.status, 0);
  if (/No hay gitleaks/.test(r.stderr)) {
    t.skip('gitleaks no esta en el PATH');
    return;
  }
  assert.ok(fs.existsSync(path.join(raiz, '.ia', 'registros', '10', 's4.acta.cruda.jsonl')));
  assert.equal(fs.existsSync(vieja), false, 'la curada vieja no queda junto a la cruda nueva');
  assert.match(r.stderr, /la curacion de la tarea 10 termino con codigo 3; la cruda quedo/);
});

test('captura: lo que Playwright deja en test-results/ durante la accion, con su sha256', () => {
  const raiz = repoTemporal({ 'a.md': 'uno\n' });
  const comun = { session_id: 's1', cwd: raiz };
  const carpeta = path.join(raiz, 'test-results', 'login');
  fs.mkdirSync(carpeta, { recursive: true });
  const vieja = path.join(carpeta, 'de-antes.png');
  fs.writeFileSync(vieja, 'vieja');
  const hace = new Date(Date.now() - 60000);
  fs.utimesSync(vieja, hace, hace);
  correrHook({ ...comun, hook_event_name: 'SessionStart', source: 'startup' });
  correrHook({ ...comun, hook_event_name: 'PreToolUse', tool_name: 'Bash', tool_use_id: 'tu1',
    tool_input: { command: 'npx playwright test login' } });
  fs.writeFileSync(path.join(carpeta, 'test-failed-1.png'), 'png de prueba');
  fs.writeFileSync(path.join(carpeta, 'trace.zip'), 'zip de prueba');
  fs.writeFileSync(path.join(carpeta, 'notas.txt'), 'no es evidencia');
  fs.writeFileSync(path.join(carpeta, 'video.webm'), Buffer.alloc(TOPE_POR_ARCHIVO + 1));
  correrHook({ ...comun, hook_event_name: 'PostToolUse', tool_name: 'Bash', tool_use_id: 'tu1' });

  const eventos = leer(path.join(raiz, '.ia', 'captura', 's1.jsonl'));
  const post = eventos.find((e) => e.evento === 'PostToolUse');
  assert.deepEqual(post.evidencias.map((e) => [e.ruta, e.copiada]), [
    ['test-results/login/test-failed-1.png', true],
    ['test-results/login/trace.zip', true],
    ['test-results/login/video.webm', false],
  ], 'ni lo de antes de la accion ni lo que no es captura, trace o video');
  const copiada = path.join(raiz, '.ia', 'captura', 'evidencias', post.evidencias[0].sha256);
  assert.equal(fs.readFileSync(copiada, 'utf8'), 'png de prueba');
  assert.equal(fs.existsSync(path.join(raiz, '.ia', 'captura', 'evidencias',
    post.evidencias[2].sha256)), false, 'lo que pasa del tope no se copia');
});
