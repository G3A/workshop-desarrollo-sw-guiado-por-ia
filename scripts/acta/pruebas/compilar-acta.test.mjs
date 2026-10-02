// Pruebas del compilador del acta (#216). Desde la raiz: node --test scripts/acta/pruebas/
//
// Cada escenario del issue es un transcript sintetico (fabrica.mjs). Las invariantes se prueban
// en las dos direcciones: el acta compilada las cumple, y un acta sembrada con cada rotura sale
// en rojo con la invariante que corresponde (REVIEW.md, seccion 3).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { compilar, compilarYEscribir, serializar } from '../compilar-acta.mjs';
import { CuracionDetenida, curar } from '../curar-acta.mjs';
import { validarActa } from '../validar-acta.mjs';
import { claseDeterminismo } from '../clasificar.mjs';
import { arbolActual, git } from '../git.mjs';
import { huellaDeEntrada } from '../nucleo-captura.mjs';
import { carpetaTemporal, enCaptura, fabrica, repoQueAvanza, repoTemporal,
  marcaDePaso, sesionConGrepQueOcultaUnFallo, sesionConHook, sesionConSubagente, sesionMarcada,
  subagente,
} from './fabrica.mjs';

const silencio = { log: () => {}, error: () => {} };
const de = (registros, elemento) => registros.filter((r) => r.elemento === elemento);

function compilarFabrica(f, opciones = {}) {
  const carpeta = carpetaTemporal('sesion');
  const transcript = f.escribir(carpeta);
  if (opciones.antes) opciones.antes(transcript.replace(/\.jsonl$/, ''));
  return compilar({ transcript, ...opciones });
}

function unicaActa(resultado) {
  assert.equal(resultado.actas.size, 1, 'se esperaba un acta');
  return [...resultado.actas.values()][0];
}

// --- Escenarios del issue ---------------------------------------------------------------------

test('sesion simple: un turno, un paso ausente, una decision y una accion pura', () => {
  const f = fabrica()
    .prompt('Arregla el titulo')
    .texto('Voy a editar el archivo.')
    .llamada('tu1', 'Edit', { file_path: 'D:\\Repo\\proyecto\\a.md', old_string: 'a',
      new_string: 'b' })
    .resultado('tu1', 'The file has been updated')
    .texto('Listo.');
  const acta = unicaActa(compilarFabrica(f));
  assert.deepEqual(validarActa(acta), []);
  assert.equal(acta[0].tarea, '10');
  assert.equal(de(acta, 'turno').length, 1);
  const [paso] = de(acta, 'paso');
  assert.equal(paso.procedencia, 'ausente');
  const [accion] = de(acta, 'accion');
  assert.equal(accion.claseDeterminismo, 'pura');
  assert.equal(accion.exito, true);
  assert.equal(accion.entrada.file_path, '.\\a.md');
  assert.equal(accion.directorio, '.', 'el directorio de la llamada, para que el motor la corra');
  const [decision] = de(acta, 'decision');
  assert.equal(decision.texto, 'Voy a editar el archivo.');
  assert.deepEqual(decision.motiva, [accion.id]);
  assert.equal(de(acta, 'decision').length, 1, 'la respuesta final no es una decision');
});

test('subagente: sus acciones van al paso de la llamada y actua en nombre del orquestador', () => {
  const f = fabrica()
    .prompt('Revisa el cambio')
    .llamada('tu1', 'Agent', { description: 'revisar', subagent_type: 'Explore', prompt: 'x' })
    .resultado('tu1', 'sin hallazgos', { extra: { agentId: 'ab12', status: 'completed' } });
  const acta = unicaActa(compilarFabrica(f, {
    antes: (carpeta) => subagente(carpeta, 'ab12', [
      { id: 'su1', name: 'Bash', input: { command: 'git diff' } },
      { id: 'su2', name: 'Read', input: { file_path: 'a.md' } },
    ]),
  }));
  assert.deepEqual(validarActa(acta), []);
  const agente = de(acta, 'agente').find((a) => a.id === 'subagente:ab12');
  assert.equal(agente.rol, 'subagente:Explore');
  assert.equal(agente.actuoEnNombreDe, 'orquestador');
  const llamada = de(acta, 'accion').find((a) => a.herramienta === 'Agent');
  const delSub = de(acta, 'accion').filter((a) => a.agente === 'subagente:ab12');
  assert.equal(delSub.length, 2);
  for (const a of delSub) {
    assert.equal(a.paso, llamada.paso);
    assert.equal(a.lanzadaPor, llamada.id);
  }
});

test('fallo con reintento: la cruda guarda el fallo y no lo cuenta como intervencion', () => {
  const f = fabrica()
    .prompt('Cambia el texto')
    .llamada('tu1', 'Edit', { file_path: 'a.md', old_string: 'zz', new_string: 'b' })
    .resultado('tu1', 'String to replace not found in file.', { error: true })
    .llamada('tu2', 'Edit', { file_path: 'a.md', old_string: 'a', new_string: 'b' })
    .resultado('tu2', 'The file has been updated');
  const acta = unicaActa(compilarFabrica(f));
  assert.deepEqual(validarActa(acta), []);
  const [fallida, exitosa] = de(acta, 'accion');
  assert.equal(fallida.exito, false);
  assert.match(fallida.error, /not found/);
  assert.equal(exitosa.exito, true);
  assert.equal(de(acta, 'intervencion').length, 0);
});

test('segundo plano: la accion queda en el paso que la lanzo y recibe su resultado despues', () => {
  const f = fabrica()
    .prompt('Corre las pruebas en segundo plano')
    .llamada('tu1', 'Bash', { command: 'npm test', run_in_background: true })
    .resultado('tu1', 'Command running in background with ID: b77',
      { extra: { backgroundTaskId: 'b77' } })
    .prompt('Mientras tanto, otra cosa')
    .prompt('<task-notification><task-id>b77</task-id><status>completed</status>' +
      '</task-notification>');
  const acta = unicaActa(compilarFabrica(f));
  assert.deepEqual(validarActa(acta), []);
  assert.equal(de(acta, 'turno').length, 2, 'la notificacion no es un turno');
  const [accion] = de(acta, 'accion');
  assert.equal(accion.segundoPlano, true);
  assert.equal(accion.paso, 't1.p1');
  assert.match(accion.resultadoDiferido.texto, /completed/);
});

test('cambio de rama: la sesion se parte en un acta por tarea', () => {
  const f = fabrica()
    .prompt('Primera tarea')
    .llamada('tu1', 'Write', { file_path: 'a.md', content: 'a' })
    .resultado('tu1', 'ok')
    .rama('fix/11-otra')
    .prompt('Segunda tarea')
    .llamada('tu2', 'Write', { file_path: 'b.md', content: 'b' })
    .resultado('tu2', 'ok')
    .rama('dev')
    .prompt('Sin tarea');
  const { actas } = compilarFabrica(f);
  assert.deepEqual([...actas.keys()], ['10', '11', 'sin-tarea']);
  for (const acta of actas.values()) {
    assert.deepEqual(validarActa(acta), []);
    assert.equal(de(acta, 'turno').length, 1);
  }
  assert.deepEqual(actas.get('11')[0].ramas, ['fix/11-otra']);
});

test('cambio de rama a mitad de turno: el turno es de la rama con numero que crea', () => {
  const f = fabrica({ rama: 'dev' })
    .prompt('Arranca con el issue 12')
    .llamada('tu1', 'Bash', { command: 'git switch -c feat/12-algo' })
    .resultado('tu1', 'Switched to a new branch')
    .rama('feat/12-algo')
    .llamada('tu2', 'Write', { file_path: 'a.md', content: 'a' })
    .resultado('tu2', 'ok');
  const { actas } = compilarFabrica(f);
  assert.deepEqual([...actas.keys()], ['12']);
  const acta = actas.get('12');
  assert.deepEqual(validarActa(acta), []);
  assert.equal(de(acta, 'turno')[0].rama, 'feat/12-algo');
  assert.equal(de(acta, 'accion').length, 2);
});

test('permiso rechazado e interrupcion: dos intervenciones de la persona', () => {
  const f = fabrica()
    .prompt('Borra la rama')
    .llamada('tu1', 'Bash', { command: 'git push origin --delete x' })
    .resultado('tu1', "The user doesn't want to proceed with this tool use. The tool use was " +
      'rejected.', { error: true })
    .prompt('[Request interrupted by user for tool use]');
  const acta = unicaActa(compilarFabrica(f));
  assert.deepEqual(validarActa(acta), []);
  const tipos = de(acta, 'intervencion').map((i) => i.tipo);
  assert.deepEqual(tipos, ['permiso_rechazado', 'interrupcion']);
  assert.equal(de(acta, 'intervencion')[0].accion, de(acta, 'accion')[0].id);
  assert.equal(de(acta, 'accion')[0].claseDeterminismo, 'efecto_externo');
  assert.equal(de(acta, 'turno').length, 1, 'la interrupcion no es un turno');
});

// --- Intervenciones completas (#222, fase 5) --------------------------------------------------

const RECHAZO = "The user doesn't want to proceed with this tool use. The tool use was rejected " +
  '(eg. if it was a file edit, the new_string was NOT written to the file).';
const conTexto = (dicho) => `${RECHAZO} To tell you how to proceed, the user said:\n${dicho}`;
const SIN_TEXTO = `${RECHAZO} STOP what you are doing and wait for the user to tell you how to ` +
  'proceed.';

// El PreToolUse y el PermissionRequest de una llamada, como los escribe la captura: el pedido no
// trae tool_use_id, solo la herramienta y el hash de la entrada.
const pedido = (id, herramienta, entrada, seg, modo = 'default') => [
  { evento: 'PreToolUse', toolUseId: id, herramienta, entrada: huellaDeEntrada(entrada),
    momento: enCaptura(0, seg), arbol: null },
  { evento: 'PermissionRequest', herramienta, entrada: huellaDeEntrada(entrada), modo,
    momento: enCaptura(0, seg) },
];

test('intervenciones: cada tipo del modelo tiene un escenario que lo produce', () => {
  const instalar = { command: 'npm install left-pad' };
  const editar = { file_path: 'a.md', old_string: 'a', new_string: 'b' };
  const f = fabrica()
    .prompt('<bash-input>git status</bash-input>')
    .prompt('<bash-stdout>limpio</bash-stdout><bash-stderr></bash-stderr>')
    .prompt('Instala y edita')
    .llamada('tu1', 'Bash', instalar)
    .resultado('tu1', 'added 1 package')
    .llamada('tu2', 'Bash', { command: 'git push' })
    .resultado('tu2', SIN_TEXTO, { error: true })
    .llamada('tu3', 'Edit', editar)
    .resultado('tu3', conTexto('usa b.md, no a.md'), { error: true })
    .llamada('tu4', 'AskUserQuestion', { questions: [] })
    .resultado('tu4', SIN_TEXTO, { error: true })
    .prompt('[Request interrupted by user]')
    .prompt('Mejor no instales nada');
  const captura = [
    { evento: 'SessionStart', momento: enCaptura(0, 0), arbol: null },
    ...pedido('tu1', 'Bash', instalar, 4),
    ...pedido('tu3', 'Edit', editar, 8),
  ];
  const acta = unicaActa(compilarFabrica(f, { captura }));
  assert.deepEqual(validarActa(acta), []);
  const filas = de(acta, 'intervencion').map((i) => [i.tipo, i.accion, i.texto]);
  assert.deepEqual(filas, [
    ['comando_usuario', 'a1', null],
    ['permiso_aprobado', 'a2', null],
    ['permiso_rechazado', 'a3', null],
    ['permiso_rechazado', 'a4', null],
    ['correccion', 'a4', 'usa b.md, no a.md'],
    ['correccion', 'a5', null],
    ['interrupcion', null, null],
    ['correccion', null, 'Mejor no instales nada'],
  ]);
  assert.deepEqual([...new Set(filas.map(([t]) => t))].sort(), ['comando_usuario', 'correccion',
    'interrupcion', 'permiso_aprobado', 'permiso_rechazado']);
  assert.ok(de(acta, 'intervencion').every((i) => i.agente === 'usuario'));
});

test('permiso aprobado: no lo es en modo auto, ni sin persona, ni si despues se rechazo', () => {
  const a = { command: 'npm ci' };
  const b = { file_path: 'x.md', content: 'x' };
  const c = { command: 'rm -rf dist' };
  const f = fabrica()
    .prompt('Tres pedidos')
    .llamada('tu1', 'Bash', a)
    .resultado('tu1', 'ok')
    .llamada('tu2', 'Write', b)
    .resultado('tu2', "Claude requested permissions to write to x.md, but you haven't granted " +
      'it yet.', { error: true })
    .llamada('tu3', 'Bash', c)
    .resultado('tu3', SIN_TEXTO, { error: true });
  const captura = [...pedido('tu1', 'Bash', a, 2, 'auto'), ...pedido('tu2', 'Write', b, 4),
    ...pedido('tu3', 'Bash', c, 6)];
  const { actas, avisos } = compilarFabrica(f, { captura });
  assert.deepEqual(de(unicaActa({ actas }), 'intervencion').map((i) => [i.tipo, i.accion]),
    [['permiso_rechazado', 'a3']], 'solo el rechazo de una persona');
  assert.ok(avisos.some((x) => /se resolvio en modo auto/.test(x)), avisos.join());
});

test('permiso aprobado: el pedido se empareja por la entrada, no por el orden', () => {
  // Dos llamadas en paralelo: los PreToolUse llegan antes que los pedidos, en otro orden.
  const a = { command: 'npm ci' };
  const b = { command: 'npm run build' };
  const f = fabrica().prompt('En paralelo')
    .llamada('tu1', 'Bash', a).llamada('tu2', 'Bash', b)
    .resultado('tu1', 'ok').resultado('tu2', SIN_TEXTO, { error: true });
  const [preA, pedidoA] = pedido('tu1', 'Bash', a, 2);
  const [preB, pedidoB] = pedido('tu2', 'Bash', b, 2);
  const { actas } = compilarFabrica(f, { captura: [preA, preB, pedidoB, pedidoA] });
  assert.deepEqual(de(unicaActa({ actas }), 'intervencion').map((i) => [i.tipo, i.accion]),
    [['permiso_aprobado', 'a1'], ['permiso_rechazado', 'a2']]);
});

test('comando con ! del usuario: turno propio, accion de la persona e intervencion', () => {
  const f = fabrica()
    .prompt('<bash-input>git status</bash-input>')
    .prompt('<bash-stdout>nada que commitear</bash-stdout><bash-stderr></bash-stderr>');
  const acta = unicaActa(compilarFabrica(f));
  assert.deepEqual(validarActa(acta), []);
  const [turno] = de(acta, 'turno');
  assert.equal(turno.origen, 'comando_usuario');
  const [accion] = de(acta, 'accion');
  assert.equal(accion.agente, 'usuario');
  assert.equal(accion.exito, true);
  assert.equal(accion.resultado, 'nada que commitear');
  assert.equal(de(acta, 'agente').find((a) => a.id === 'usuario').tipo, 'persona');
  assert.deepEqual(de(acta, 'intervencion').map((i) => i.tipo), ['comando_usuario']);
});

test('rutas: la raiz pasa a "." y la carpeta del usuario a "~", en todas sus formas', () => {
  const f = fabrica()
    .prompt('Mira D:\\Repo\\proyecto\\docs y /d/Repo/proyecto/src')
    .llamada('tu1', 'Bash', { command: 'ls C:\\Users\\ana\\tmp /c/Users/ana/x' })
    .resultado('tu1', 'D:/Repo/proyecto/a.md y {"p":"D:\\\\Repo\\\\proyecto\\\\b.md"}');
  const acta = unicaActa(compilarFabrica(f));
  const texto = serializar(acta);
  assert.doesNotMatch(texto, /Repo[\\/]+proyecto/);
  assert.doesNotMatch(texto, /Users[\\/]+ana/);
  const [turno] = de(acta, 'turno');
  assert.equal(turno.prompt, 'Mira .\\docs y ./src');
  assert.equal(de(acta, 'accion')[0].entrada.command, 'ls ~\\tmp ~/x');
});

// --- Determinismo -----------------------------------------------------------------------------

test('determinismo: compilar dos veces la misma entrada da el mismo archivo, byte a byte', () => {
  const carpeta = carpetaTemporal('det');
  const transcript = fabrica()
    .prompt('Uno')
    .texto('Edito.')
    .llamada('tu1', 'Edit', { file_path: 'a.md', old_string: 'a', new_string: 'b' })
    .resultado('tu1', 'ok')
    .llamada('tu2', 'Agent', { subagent_type: 'Explore', prompt: 'x' })
    .resultado('tu2', 'ok', { extra: { agentId: 'cd34' } })
    .escribir(carpeta);
  subagente(transcript.replace(/\.jsonl$/, ''), 'cd34', [
    { id: 'su1', name: 'Grep', input: { pattern: 'x' } },
  ]);
  const salidas = [carpetaTemporal('det-a'), carpetaTemporal('det-b')];
  for (const salida of salidas) {
    assert.equal(compilarYEscribir({ transcript, salida, verificar: false, log: silencio }), 0);
  }
  const [a, b] = salidas.map((s) => fs.readFileSync(path.join(s, '10',
    'sesion-1.acta.cruda.jsonl')));
  assert.ok(a.length > 0);
  assert.ok(a.equals(b));
});

// --- Captura y git ----------------------------------------------------------------------------

test('captura: los arboles antes y despues dan los cambios por archivo y la huella', () => {
  const skill = 'instrumentacion-java-ia/sdlc-ia/skills/demo/SKILL.md';
  const raiz = repoTemporal({
    'a.md': 'uno\n',
    [skill]: '# demo\n',
    'instrumentacion-java-ia/sdlc-ia/.claude-plugin/plugin.json': '{"version":"9.9.9"}',
    'scripts/acta/motor/Dockerfile': `FROM node@sha256:${'ab'.repeat(32)}\nUSER node\n`,
  });
  const headBase = git(['rev-parse', 'HEAD'], { cwd: raiz }).trim();
  const antes = arbolActual(raiz);
  fs.writeFileSync(path.join(raiz, 'a.md'), 'dos\n');
  const despues = arbolActual(raiz);
  assert.notEqual(antes, despues);
  assert.equal(git(['status', '--porcelain'], { cwd: raiz }).trim(), 'M a.md',
    'el indice real no se toca');

  const f = fabrica()
    .prompt('/sdlc-ia:demo 10')
    .llamada('tu0', 'Skill', { skill: 'sdlc-ia:demo' })
    .resultado('tu0', 'ok')
    .llamada('tu1', 'Edit', { file_path: 'a.md', old_string: 'uno', new_string: 'dos' })
    .resultado('tu1', 'ok');
  const captura = [
    { evento: 'SessionStart', head: headBase, arbol: antes },
    { evento: 'PreToolUse', toolUseId: 'tu1', arbol: antes },
    { evento: 'PostToolUse', toolUseId: 'tu1', arbol: despues },
  ];
  const acta = unicaActa(compilarFabrica(f, { captura, raizRepo: raiz }));
  assert.deepEqual(validarActa(acta), []);
  const edit = de(acta, 'accion').find((a) => a.herramienta === 'Edit');
  assert.equal(edit.arbolAntes, antes);
  assert.equal(edit.arbolDespues, despues);
  assert.deepEqual(edit.cambios.map((c) => c.archivo), ['a.md']);
  assert.match(edit.cambios[0].diff, /-uno\n\+dos/);
  const cabecera = acta[0];
  assert.equal(cabecera.headBase, headBase);
  assert.equal(cabecera.arbolBase, antes);
  assert.deepEqual(cabecera.actividades, ['sdlc-ia:demo']);
  assert.equal(cabecera.huella.plugin, '9.9.9');
  assert.deepEqual(cabecera.huella.documentos.map((d) => d.ruta), [skill]);
  assert.equal(cabecera.huella.documentos[0].commit, headBase);
  // La imagen del motor (#222, fase 2): el Dockerfile del HEAD base y el digest de su base.
  assert.equal(cabecera.huella.imagen.dockerfile.commit, headBase);
  assert.match(cabecera.huella.imagen.dockerfile.hash, /^[0-9a-f]{40}$/);
  assert.equal(cabecera.huella.imagen.base, `node@sha256:${'ab'.repeat(32)}`);
});

// --- Efectos entre acciones (#218) ------------------------------------------------------------

test('capturada: si la captura vio el PreToolUse, con null cuando no se puede saber', () => {
  const f = fabrica()
    .prompt('Varias cosas')
    .llamada('tu0', 'Bash', { command: 'antes de la captura' })
    .resultado('tu0', 'ok')
    .llamada('tu1', 'Edit', { file_path: 'a.md', old_string: 'x', new_string: 'x' })
    .resultado('tu1', '<tool_use_error>No changes to make</tool_use_error>', { error: true })
    .llamada('tu2', 'Bash', { command: 'npm test' })
    .resultado('tu2', 'ok')
    .prompt('<bash-input>git status</bash-input>')
    .prompt('<bash-stdout>limpio</bash-stdout><bash-stderr></bash-stderr>');
  const captura = [
    { evento: 'SessionStart', momento: enCaptura(0, 3), arbol: 'x' },
    { evento: 'PreToolUse', toolUseId: 'tu2', momento: enCaptura(0, 6), arbol: 'x' },
    { evento: 'PostToolUse', toolUseId: 'tu2', momento: enCaptura(0, 6), arbol: 'x' },
  ];
  const acta = unicaActa(compilarFabrica(f, { captura }));
  assert.deepEqual(de(acta, 'accion').map((a) => [a.toolUseId, a.capturada]), [
    ['tu0', null],
    ['tu1', false],
    ['tu2', true],
    ['usuario-t2', null],
  ]);
});

// La forma de la sesion de #218 (#222): un Bash rompe un modulo que el hook importa, y hasta que
// un Edit lo arregla, el hook no carga. El Bash pierde su PostToolUse, el Read y el primer Edit
// pierden los dos eventos, y el segundo Edit pierde el PreToolUse.
test('eventos perdidos: lo que corrio sin captura no queda como no corrido, y se avisa', () => {
  const repo = repoQueAvanza({ 'a.md': 'uno\n' });
  const a0 = repo.arbol();
  repo.cambiar({ 'a.md': 'roto\n' });
  const a2 = repo.cambiar({ 'a.md': 'dos\n' });
  const f = fabrica()
    .prompt('Arregla')
    .llamada('tu1', 'Bash', { command: 'node arreglar.mjs' })
    .resultado('tu1', 'ok')
    .llamada('tu2', 'Read', { file_path: 'a.md' })
    .resultado('tu2', 'roto')
    .llamada('tu3', 'Edit', { file_path: 'a.md', old_string: 'roto', new_string: 'medio' })
    .resultado('tu3', 'ok')
    .llamada('tu4', 'Edit', { file_path: 'a.md', old_string: 'medio', new_string: 'dos' })
    .resultado('tu4', 'ok')
    .llamada('tu5', 'Bash', { command: 'npm test' })
    .resultado('tu5', 'ok');
  const captura = [
    { evento: 'SessionStart', momento: enCaptura(0, 0), arbol: a0 },
    { evento: 'PreToolUse', toolUseId: 'tu1', momento: enCaptura(0, 2), arbol: a0 },
    { evento: 'PostToolUse', toolUseId: 'tu4', momento: enCaptura(0, 9), arbol: a2 },
    { evento: 'PreToolUse', toolUseId: 'tu5', momento: enCaptura(0, 10), arbol: a2 },
    { evento: 'PostToolUse', toolUseId: 'tu5', momento: enCaptura(0, 10), arbol: a2 },
    { evento: 'SessionEnd', momento: enCaptura(0, 12), arbol: a2 },
  ];
  const { actas, avisos } = compilarFabrica(f, { captura, raizRepo: repo.raiz });
  const acta = unicaActa({ actas });
  assert.deepEqual(validarActa(acta), []);
  assert.deepEqual(de(acta, 'accion').map((a) => [a.toolUseId, a.capturada]), [
    ['tu1', true], ['tu2', null], ['tu3', null], ['tu4', null], ['tu5', true],
  ]);
  assert.ok(avisos.some((a) => /perdio eventos de 4 acciones que corrieron \(a1, a2, a3, a4\)/
    .test(a)), avisos.join('\n'));
  assert.throws(() => curar(serializar(acta)),
    (e) => e instanceof CuracionDetenida && /sin una accion que lo explique/.test(e.message));
});

test('efecto de hook: un cambio entre dos acciones entra como accion derivada con su diff', () => {
  const { f, captura, raiz, arboles: [, a1, a2] } = sesionConHook();
  const acta = unicaActa(compilarFabrica(f, { captura, raizRepo: raiz }));
  assert.deepEqual(validarActa(acta), []);
  const [edit, hook, bash] = de(acta, 'accion');
  assert.deepEqual([edit.herramienta, hook.herramienta, bash.herramienta],
    ['Edit', 'hook', 'Bash'], 'el efecto queda entre las dos acciones');
  assert.equal(hook.agente, 'hook:sin-identificar');
  assert.equal(hook.claseDeterminismo, 'pura');
  assert.equal(hook.exito, true);
  assert.equal(hook.paso, edit.paso);
  assert.deepEqual([hook.despuesDe, hook.antesDe], [edit.id, bash.id]);
  assert.deepEqual([hook.arbolAntes, hook.arbolDespues], [a1, a2]);
  assert.deepEqual(hook.cambios.map((c) => c.archivo), ['b.md']);
  const agente = de(acta, 'agente').find((a) => a.id === 'hook:sin-identificar');
  assert.deepEqual([agente.tipo, agente.rol], ['automatismo', 'hook:sin-identificar']);
});

test('efecto de hook: despues de la ultima accion lo ve el arbol de SessionEnd', () => {
  const { f, captura, raiz } = sesionConHook({ alFinal: true });
  const acta = unicaActa(compilarFabrica(f, { captura, raizRepo: raiz }));
  assert.deepEqual(validarActa(acta), []);
  const acciones = de(acta, 'accion');
  const hook = acciones[acciones.length - 1];
  assert.equal(hook.herramienta, 'hook');
  assert.deepEqual([hook.despuesDe, hook.antesDe], [acciones[1].id, null]);
});

test('sin efecto de hook: con dos acciones en paralelo el cambio es de ellas', () => {
  const repo = repoQueAvanza({ 'a.md': 'a\n', 'b.md': 'b\n' });
  const a0 = repo.arbol();
  const a1 = repo.cambiar({ 'a.md': 'a2\n' });
  const a2 = repo.cambiar({ 'b.md': 'b2\n' });
  const f = fabrica()
    .prompt('Dos comandos a la vez')
    .llamada('tu1', 'Bash', { command: 'gen a' })
    .llamada('tu2', 'Bash', { command: 'gen b' })
    .resultado('tu1', 'ok')
    .resultado('tu2', 'ok');
  const captura = [
    { evento: 'PreToolUse', toolUseId: 'tu1', momento: enCaptura(0, 2), arbol: a0 },
    // tu2 arranca cuando tu1 ya escribio a.md, pero tu1 sigue abierta: el cambio es suyo.
    { evento: 'PreToolUse', toolUseId: 'tu2', momento: enCaptura(0, 3), arbol: a1 },
    { evento: 'PostToolUse', toolUseId: 'tu1', momento: enCaptura(0, 4), arbol: a1 },
    { evento: 'PostToolUse', toolUseId: 'tu2', momento: enCaptura(0, 5), arbol: a2 },
  ];
  const acta = unicaActa(compilarFabrica(f, { captura, raizRepo: repo.raiz }));
  assert.deepEqual(de(acta, 'accion').map((a) => a.herramienta), ['Bash', 'Bash']);
});

test('efecto de hook: un PreToolUse que nunca cierra no apaga la deteccion', () => {
  // Paso en vivo: la captura vio un PreToolUse de una llamada que el transcript no tiene, y una
  // accion interrumpida tampoco recibe su Post.
  const { f, captura, raiz, arboles: [a0] } = sesionConHook();
  f.llamada('tu3', 'Bash', { command: 'npm run largo' });
  const huerfanos = [
    { evento: 'PreToolUse', toolUseId: 'sin-llamada', momento: enCaptura(0, 1), arbol: a0 },
    { evento: 'PreToolUse', toolUseId: 'tu3', momento: enCaptura(0, 1), arbol: a0 },
  ];
  const acta = unicaActa(compilarFabrica(f, { captura: [captura[0], ...huerfanos,
    ...captura.slice(1)], raizRepo: raiz }));
  assert.equal(de(acta, 'accion').find((a) => a.toolUseId === 'tu3').exito, null);
  const hooks = de(acta, 'accion').filter((a) => a.herramienta === 'hook');
  assert.deepEqual(hooks.map((h) => h.cambios.map((c) => c.archivo)), [['b.md']]);
});

test('efecto de hook dentro de un subagente: va al paso de la llamada, que no es eslabon', () => {
  const repo = repoQueAvanza({ 'a.md': 'a\n', 'b.md': 'b\n' });
  const a0 = repo.arbol();
  const a1 = repo.cambiar({ 'a.md': 'a2\n' });
  const a2 = repo.cambiar({ 'b.md': 'b2\n' });
  const f = fabrica()
    .prompt('Delega')
    .llamada('tu1', 'Agent', { subagent_type: 'general-purpose', prompt: 'x' })
    .resultado('tu1', 'ok', { extra: { agentId: 'ab12' } });
  const captura = [
    { evento: 'PreToolUse', toolUseId: 'tu1', momento: enCaptura(0, 2), arbol: a0 },
    { evento: 'PreToolUse', toolUseId: 'su1', momento: enCaptura(30, 1), arbol: a0 },
    { evento: 'PostToolUse', toolUseId: 'su1', momento: enCaptura(30, 2), arbol: a1 },
    { evento: 'PreToolUse', toolUseId: 'su2', momento: enCaptura(30, 3), arbol: a2 },
    { evento: 'PostToolUse', toolUseId: 'su2', momento: enCaptura(30, 4), arbol: a2 },
    { evento: 'PostToolUse', toolUseId: 'tu1', momento: enCaptura(31, 0), arbol: a2 },
  ];
  const acta = unicaActa(compilarFabrica(f, { captura, raizRepo: repo.raiz,
    antes: (c) => subagente(c, 'ab12', [
      { id: 'su1', name: 'Write', input: { file_path: 'a.md', content: 'a2\n' } },
      { id: 'su2', name: 'Bash', input: { command: 'npm test' } },
    ]) }));
  assert.deepEqual(validarActa(acta), []);
  const hooks = de(acta, 'accion').filter((a) => a.herramienta === 'hook');
  assert.equal(hooks.length, 1, 'ni la entrada ni la salida de la llamada Agent son huecos');
  const llamada = de(acta, 'accion').find((a) => a.herramienta === 'Agent');
  assert.equal(hooks[0].paso, llamada.paso);
  assert.deepEqual(hooks[0].cambios.map((c) => c.archivo), ['b.md']);
});

// --- Arbol final (#218) -----------------------------------------------------------------------

test('arbol final: el de SessionEnd, solo en el acta de la ultima accion con arbol', () => {
  const f = fabrica()
    .prompt('Primera tarea')
    .llamada('tu1', 'Write', { file_path: 'a.md', content: 'a' })
    .resultado('tu1', 'ok')
    .rama('fix/11-otra')
    .prompt('Segunda tarea')
    .llamada('tu2', 'Write', { file_path: 'b.md', content: 'b' })
    .resultado('tu2', 'ok');
  const captura = [
    { evento: 'PreToolUse', toolUseId: 'tu1', arbol: 'x0' },
    { evento: 'PostToolUse', toolUseId: 'tu1', arbol: 'x1' },
    { evento: 'PreToolUse', toolUseId: 'tu2', arbol: 'x1' },
    { evento: 'PostToolUse', toolUseId: 'tu2', arbol: 'x2' },
  ];
  const conCierre = compilarFabrica(f, { captura: [...captura,
    { evento: 'SessionEnd', arbol: 'x2' }] }).actas;
  assert.deepEqual([...conCierre].map(([t, acta]) => [t, acta[0].arbolFinal]),
    [['10', null], ['11', 'x2']]);
  const sinCierre = compilarFabrica(f, { captura }).actas;
  assert.equal(sinCierre.get('11')[0].arbolFinal, null, 'sin SessionEnd con arbol no hay final');
});

// --- Integracion de subagentes (#218) ---------------------------------------------------------

const integracionDe = (acta) => de(acta, 'agente').find((a) => a.id === 'subagente:ab12')
  .integracion;

function compilarConSubagente(revierte, { conRepo = true } = {}) {
  const { f, captura, raiz, conSubagente } = sesionConSubagente({ revierte });
  return unicaActa(compilarFabrica(f, { captura, raizRepo: conRepo ? raiz : null,
    antes: conSubagente }));
}

test('integracion: integrado si su cambio sigue en el arbol final', () => {
  const acta = compilarConSubagente('nadie');
  assert.deepEqual(validarActa(acta), []);
  assert.equal(integracionDe(acta), 'integrado');
  assert.equal(de(acta, 'agente').find((a) => a.id === 'orquestador').integracion, null);
});

test('integracion: descartado si todo lo que cambio volvio a como estaba antes de el', () => {
  for (const revierte of ['el-mismo', 'orquestador']) {
    const acta = compilarConSubagente(revierte);
    assert.deepEqual(validarActa(acta), []);
    assert.equal(integracionDe(acta), 'descartado', revierte);
  }
});

test('integracion: un subagente que no cambio el arbol esta integrado, por vacuidad', () => {
  const f = fabrica()
    .prompt('Revisa')
    .llamada('tu1', 'Agent', { subagent_type: 'Explore', prompt: 'x' })
    .resultado('tu1', 'ok', { extra: { agentId: 'ab12' } });
  const acta = unicaActa(compilarFabrica(f, {
    antes: (c) => subagente(c, 'ab12', [{ id: 'su1', name: 'Read', input: { file_path: 'a' } }]),
  }));
  assert.equal(integracionDe(acta), 'integrado');
});

test('integracion: null si cambio el arbol y no hay repo para comparar', () => {
  assert.equal(integracionDe(compilarConSubagente('nadie', { conRepo: false })), null);
});

// --- Secretos ---------------------------------------------------------------------------------

test('secretos: con un posible secreto el acta no se escribe (codigo 3)', (t) => {
  const carpeta = carpetaTemporal('sec');
  const clave = 'AKIA' + 'QWERTYUIOPASDFGH';
  const transcript = fabrica()
    .prompt('Configura la nube')
    .llamada('tu1', 'Bash', { command: `export AWS_ACCESS_KEY_ID=${clave}` })
    .resultado('tu1', 'ok')
    .escribir(carpeta);
  const salida = carpetaTemporal('sec-salida');
  const codigo = compilarYEscribir({ transcript, salida, log: silencio });
  if (codigo === 4) {
    t.skip('gitleaks no esta en el PATH');
    return;
  }
  assert.equal(codigo, 3);
  assert.equal(fs.existsSync(path.join(salida, '10')), true);
  assert.deepEqual(fs.readdirSync(path.join(salida, '10')), []);
});

test('secretos: sin gitleaks en el PATH el acta no se escribe (codigo 4)', () => {
  const carpeta = carpetaTemporal('sin');
  const transcript = fabrica().prompt('Hola').escribir(carpeta);
  const salida = carpetaTemporal('sin-salida');
  const path0 = process.env.PATH;
  process.env.PATH = '';
  try {
    assert.equal(compilarYEscribir({ transcript, salida, log: silencio }), 4);
  } finally {
    process.env.PATH = path0;
  }
  assert.deepEqual(fs.readdirSync(path.join(salida, '10')), []);
});

// --- Clasificacion ----------------------------------------------------------------------------

test('clases de determinismo: ante la duda, efecto externo', () => {
  const casos = [
    ['Edit', {}, 'pura'],
    ['Read', {}, 'local'],
    ['Bash', { command: 'npm test' }, 'local'],
    ['Bash', { command: 'git commit -m x' }, 'local'],
    ['Bash', { command: 'git push -u origin x' }, 'efecto_externo'],
    ['Bash', { command: 'gh pr create --base dev' }, 'efecto_externo'],
    ['Bash', { command: 'gh pr view 12' }, 'externa_lectura'],
    ['Bash', { command: 'gh api repos/x -X DELETE' }, 'efecto_externo'],
    ['Bash', { command: 'git fetch origin' }, 'externa_lectura'],
    ['PowerShell', { command: 'Invoke-RestMethod -Uri u -Method Post' }, 'efecto_externo'],
    ['WebSearch', {}, 'externa_lectura'],
    ['mcp__azure__get_work_item', {}, 'externa_lectura'],
    ['mcp__azure__create_bug', {}, 'efecto_externo'],
    ['Artifact', { action: 'read' }, 'externa_lectura'],
    ['Artifact', {}, 'efecto_externo'],
    ['ArtifactData', { action: 'batch' }, 'efecto_externo'],
    ['HerramientaNueva', {}, 'efecto_externo'],
  ];
  for (const [herramienta, entrada, esperada] of casos) {
    assert.equal(claseDeterminismo(herramienta, entrada), esperada, herramienta);
  }
});

// --- Rojos sembrados: uno por invariante ------------------------------------------------------

function actaDeDosTurnosConSubagente() {
  const f = fabrica()
    .prompt('Uno')
    .llamada('tu1', 'Agent', { subagent_type: 'Explore', prompt: 'x' })
    .resultado('tu1', 'ok', { extra: { agentId: 'ef56' } })
    .llamada('tu2', 'Bash', { command: 'sleep 5', run_in_background: true })
    .resultado('tu2', 'en segundo plano', { extra: { backgroundTaskId: 'b1' } })
    .prompt('Dos')
    .llamada('tu3', 'Read', { file_path: 'a.md' })
    .resultado('tu3', 'a');
  return unicaActa(compilarFabrica(f, {
    antes: (c) => subagente(c, 'ef56', [{ id: 'su1', name: 'Grep', input: { pattern: 'x' } }]),
  }));
}

function sembrar(mutar) {
  const acta = structuredClone(actaDeDosTurnosConSubagente());
  assert.deepEqual(validarActa(acta), [], 'el acta sin sembrar tiene que estar en verde');
  mutar(acta);
  return validarActa(acta).map((e) => e.invariante);
}

test('codigo reinterpretado: el | grep que oculta un fallo queda con exito null y su marca', () => {
  const { f, captura, raiz } = sesionConGrepQueOcultaUnFallo();
  const acta = unicaActa(compilarFabrica(f, { captura, raizRepo: raiz }));
  assert.deepEqual(validarActa(acta), []);
  const [oculta, repetida] = de(acta, 'accion');
  assert.equal(oculta.exito, null, 'no se sabe si fallo: no se infiere de la salida');
  assert.equal(oculta.codigoReinterpretado, 'No matches found');
  assert.equal(oculta.error, null);
  assert.match(oculta.resultado, /Error: boom/, 'la salida queda en la cruda tal cual');
  assert.equal(repetida.exito, true);
  assert.equal(repetida.codigoReinterpretado, null);
});

test('codigo reinterpretado: con is_error manda el fallo, y una cadena vacia no es marca', () => {
  const f = fabrica()
    .prompt('Corre')
    .llamada('tu1', 'Bash', { command: 'diff a b' })
    .resultado('tu1', 'Exit code 2', { error: true,
      extra: { stdout: '', returnCodeInterpretation: 'Files differ' } })
    .llamada('tu2', 'Bash', { command: 'grep x a' })
    .resultado('tu2', '', { extra: { stdout: '', returnCodeInterpretation: '  ' } });
  const [fallo, vacia] = de(unicaActa(compilarFabrica(f)), 'accion');
  assert.deepEqual([fallo.exito, fallo.codigoReinterpretado], [false, null]);
  assert.deepEqual([vacia.exito, vacia.codigoReinterpretado], [true, null]);
});

test('codigo reinterpretado: la accion termino, y su ventana no deja ver un hook falso', () => {
  // Un PreToolUse ajeno dentro de la ventana de la accion ve el arbol a medio cambiar: es de ella,
  // no de un hook. Con la ventana cerrada por su exito null, se inventaba una accion `hook`.
  const { f, captura, raiz, arboles: [, a1] } = sesionConGrepQueOcultaUnFallo({ escribe: true });
  const ajeno = { evento: 'PreToolUse', toolUseId: 'sin-llamada', arbol: a1 };
  const conAjeno = [...captura.slice(0, 2), ajeno, ...captura.slice(2)];
  const acta = unicaActa(compilarFabrica(f, { captura: conAjeno, raizRepo: raiz }));
  assert.deepEqual(de(acta, 'accion').map((a) => a.herramienta), ['Bash', 'Bash']);
});

test('rojo esquema: una accion con el codigo reinterpretado que figura como exito o fallo', () => {
  for (const [exito, error] of [[true, null], [false, 'Exit code 1']]) {
    const r = sembrar((acta) => {
      Object.assign(de(acta, 'accion')[0], { codigoReinterpretado: 'No matches found', exito,
        error });
    });
    assert.ok(r.includes('esquema'), `${exito}: ${r.join()}`);
  }
});

test('rojo I1: una accion que apunta a un paso que no existe', () => {
  const r = sembrar((acta) => {
    de(acta, 'accion')[0].paso = 't9.p1';
  });
  assert.ok(r.includes('I1'), r.join());
});

test('rojo I1: dos elementos con el mismo id', () => {
  const r = sembrar((acta) => {
    de(acta, 'accion')[1].id = de(acta, 'accion')[0].id;
  });
  assert.ok(r.includes('I1'), r.join());
});

test('rojo I2: un turno de otra tarea dentro del acta', () => {
  const r = sembrar((acta) => {
    de(acta, 'turno')[1].tarea = '99';
  });
  assert.deepEqual(r, ['I2']);
});

test('rojo I3: una accion de subagente fuera del paso que lo lanzo', () => {
  const r = sembrar((acta) => {
    acta.find((x) => x.elemento === 'accion' && x.agente.startsWith('subagente:')).paso = 't2.p1';
  });
  assert.deepEqual(r, ['I3']);
});

test('rojo I3: una accion de subagente sin la llamada que lo lanzo', () => {
  const r = sembrar((acta) => {
    acta.find((x) => x.elemento === 'accion' && x.agente.startsWith('subagente:')).lanzadaPor =
      null;
  });
  assert.deepEqual(r, ['I3']);
});

test('rojo I4: una accion en segundo plano movida al paso de su resultado', () => {
  const r = sembrar((acta) => {
    acta.find((x) => x.elemento === 'accion' && x.segundoPlano).paso = 't2.p1';
  });
  assert.deepEqual(r, ['I4']);
});

test('rojo I5: dos pasos sin marcador en el mismo turno', () => {
  const r = sembrar((acta) => {
    acta.push({ elemento: 'paso', id: 't1.p2', turno: 't1', procedencia: 'ausente',
      pasoPrescrito: null });
  });
  assert.deepEqual([...new Set(r)], ['I5']);
});

test('rojo I5: un paso con procedencia inferida', () => {
  const r = sembrar((acta) => {
    de(acta, 'paso')[0].procedencia = 'inferido';
  });
  assert.deepEqual(r, ['I5']);
});

// --- Marcadores de paso (#222, fase 4) ---------------------------------------------------------

const marca = marcaDePaso;

test('marcadores: el turno se parte en los pasos que la skill marco, con su fase', () => {
  const acta = unicaActa(compilarFabrica(sesionMarcada()));
  assert.deepEqual(validarActa(acta), []);
  const paso = (id) => de(acta, 'paso').find((p) => p.id === id);
  assert.deepEqual(de(acta, 'paso').map((p) => [p.id, p.procedencia,
    p.pasoPrescrito?.letra ?? null]), [
    ['t1.p1', 'ausente', null],
    ['t1.p2', 'marcado', '1'],
    ['t1.p3', 'marcado', '2'],
    ['t2.p1', 'marcado', '2'],
    ['t2.p2', 'marcado', '3'],
  ]);
  assert.deepEqual(paso('t1.p2').pasoPrescrito, { skill: 'debt-triage', letra: '1' });
  assert.equal(paso('t1.p2').faseDeclarada, 2);
  assert.deepEqual(de(acta, 'accion').map((a) => a.paso),
    ['t1.p1', 't1.p2', 't1.p3', 't2.p1', 't2.p2']);
  assert.equal(acta[0].fase, 2, 'la fase del acta es la que declaran sus pasos');
  assert.ok(de(acta, 'decision').every((d) => !d.texto.includes('sdlc-ia:step')),
    'el marcador no es parte de la decision');
});

test('marcadores: dos fases distintas dejan el acta sin fase, con un aviso', () => {
  const f = sesionMarcada().texto(marca(4, 3))
    .llamada('tu5', 'Edit', { file_path: 'a.java', old_string: 'A', new_string: 'A2' })
    .resultado('tu5', 'ok');
  const { actas, avisos } = compilarFabrica(f);
  assert.equal(unicaActa({ actas })[0].fase, null);
  assert.ok(avisos.some((a) => /declara las fases 2, 3; queda sin fase/.test(a)), avisos.join());
});

test('rojo I5: un paso marcado sin su fase', () => {
  const acta = unicaActa(compilarFabrica(sesionMarcada()));
  de(acta, 'paso')[1].faseDeclarada = null;
  assert.deepEqual(validarActa(acta).map((e) => e.invariante), ['I5']);
});

test('rojo I5: un paso sin marcador despues de uno marcado', () => {
  const acta = unicaActa(compilarFabrica(sesionMarcada()));
  Object.assign(de(acta, 'paso')[2], { procedencia: 'ausente', pasoPrescrito: null,
    faseDeclarada: null });
  assert.deepEqual(validarActa(acta).map((e) => e.invariante), ['I5']);
});

test('rojo I5: un paso sin marcador que apunta a un paso prescrito', () => {
  const acta = unicaActa(compilarFabrica(sesionMarcada()));
  de(acta, 'paso')[0].pasoPrescrito = { skill: 'debt-triage', letra: '1' };
  assert.deepEqual(validarActa(acta).map((e) => e.invariante), ['I5']);
});
