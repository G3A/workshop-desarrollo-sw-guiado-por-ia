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
import { validarActa } from '../validar-acta.mjs';
import { claseDeterminismo } from '../clasificar.mjs';
import { arbolActual, git } from '../git.mjs';
import { carpetaTemporal, fabrica, repoTemporal, subagente } from './fabrica.mjs';

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
