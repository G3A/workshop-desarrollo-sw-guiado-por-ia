// Fabrica de transcripts SINTETICOS para las pruebas del acta. Nunca transcripts reales: traen
// rutas, contenido leido y posibles secretos.
//
// Reproduce la forma que escribe Claude Code 2.1.x (medida sobre sesiones de este repo, sin
// copiar su contenido): una linea por bloque, `user` con el prompt como texto o con tool_result,
// `assistant` con text o tool_use, y `toolUseResult` con el agentId de un subagente.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { compilar, serializar } from '../compilar-acta.mjs';
import { curarYEscribir } from '../curar-acta.mjs';
import { arbolActual, git } from '../git.mjs';
import { huellaDeEntrada } from '../nucleo-captura.mjs';
import { leerActa } from '../validar-acta.mjs';

export const RAIZ_FICTICIA = 'D:\\Repo\\proyecto';

export function fabrica({ sesion = 'sesion-1', cwd = RAIZ_FICTICIA, rama = 'feat/10-demo' } = {}) {
  let reloj = Date.UTC(2026, 9, 1, 12, 0, 0);
  let uuid = 0;
  const lineas = [];
  const estado = { rama };
  const base = (extra) => ({
    parentUuid: uuid ? `u${uuid}` : null,
    isSidechain: false,
    cwd,
    sessionId: sesion,
    version: '2.1.282',
    gitBranch: estado.rama,
    uuid: `u${++uuid}`,
    timestamp: new Date((reloj += 1000)).toISOString(),
    ...extra,
  });
  const f = {
    lineas,
    rama(r) {
      estado.rama = r;
      return f;
    },
    // Adelanta el reloj: lo que sigue queda despues de las lineas de un subagente, que empiezan
    // a las 12:30.
    pausa(minutos) {
      reloj += minutos * 60 * 1000;
      return f;
    },
    prompt(texto) {
      lineas.push(base({ type: 'user', message: { role: 'user', content: texto } }));
      return f;
    },
    meta(texto) {
      lineas.push(base({ type: 'user', isMeta: true, message: { role: 'user', content: texto } }));
      return f;
    },
    texto(t) {
      lineas.push(base({ type: 'assistant', message: { role: 'assistant',
        model: 'claude-prueba', content: [{ type: 'text', text: t }] } }));
      return f;
    },
    llamada(id, name, input) {
      lineas.push(base({ type: 'assistant', message: { role: 'assistant',
        model: 'claude-prueba', content: [{ type: 'tool_use', id, name, input }] } }));
      return f;
    },
    resultado(id, content, { error = false, extra = null } = {}) {
      const bloque = { type: 'tool_result', tool_use_id: id, content };
      if (error) bloque.is_error = true;
      const l = base({ type: 'user', message: { role: 'user', content: [bloque] } });
      if (extra) l.toolUseResult = extra;
      lineas.push(l);
      return f;
    },
    escribir(carpeta) {
      fs.mkdirSync(carpeta, { recursive: true });
      const archivo = path.join(carpeta, `${sesion}.jsonl`);
      fs.writeFileSync(archivo, lineas.map((l) => JSON.stringify(l)).join('\n') + '\n');
      return archivo;
    },
  };
  return f;
}

// El transcript de un subagente: sin prompt de usuario propio que cuente, con agentId.
export function subagente(carpetaSesion, agentId, pasos, { sesion = 'sesion-1' } = {}) {
  let reloj = Date.UTC(2026, 9, 1, 12, 30, 0);
  const comun = () => ({
    isSidechain: true,
    agentId,
    sessionId: sesion,
    cwd: RAIZ_FICTICIA,
    timestamp: new Date((reloj += 1000)).toISOString(),
  });
  const lineas = [{ ...comun(), type: 'user', message: { role: 'user', content: 'encargo' } }];
  for (const p of pasos) {
    lineas.push({ ...comun(), type: 'assistant', message: { role: 'assistant',
      model: 'claude-prueba', content: [{ type: 'tool_use', id: p.id, name: p.name,
        input: p.input }] } });
    lineas.push({ ...comun(), type: 'user', message: { role: 'user', content: [
      { type: 'tool_result', tool_use_id: p.id, content: p.salida || 'ok',
        ...(p.error ? { is_error: true } : {}) }] } });
  }
  const carpeta = path.join(carpetaSesion, 'subagents');
  fs.mkdirSync(carpeta, { recursive: true });
  fs.writeFileSync(path.join(carpeta, `agent-${agentId}.jsonl`),
    lineas.map((l) => JSON.stringify(l)).join('\n') + '\n');
}

export function carpetaTemporal(prefijo) {
  return fs.mkdtempSync(path.join(os.tmpdir(), `acta-${prefijo}-`));
}

// Un repo git de prueba, aislado del repo real (git.mjs limpia GIT_DIR del entorno).
export function repoTemporal(archivos) {
  const raiz = carpetaTemporal('repo');
  git(['init', '-q', '-b', 'main'], { cwd: raiz });
  git(['config', 'user.email', 'prueba@example.com'], { cwd: raiz });
  git(['config', 'user.name', 'Prueba'], { cwd: raiz });
  git(['config', 'core.autocrlf', 'false'], { cwd: raiz });
  for (const [ruta, contenido] of Object.entries(archivos)) {
    fs.mkdirSync(path.dirname(path.join(raiz, ruta)), { recursive: true });
    fs.writeFileSync(path.join(raiz, ruta), contenido);
  }
  git(['add', '-A'], { cwd: raiz });
  git(['commit', '-q', '-m', 'inicio'], { cwd: raiz });
  return raiz;
}

// Un repo de prueba que avanza: `cambiar` escribe archivos y devuelve el hash del arbol nuevo,
// como lo tomaria la captura. El indice real no se toca.
export function repoQueAvanza(archivos) {
  const raiz = repoTemporal(archivos);
  return {
    raiz,
    arbol: () => arbolActual(raiz),
    cambiar(cambios) {
      for (const [ruta, contenido] of Object.entries(cambios)) {
        fs.writeFileSync(path.join(raiz, ruta), contenido);
      }
      return arbolActual(raiz);
    },
  };
}

// El momento de un evento de captura, medio segundo despues del segundo `seg` del reloj de la
// fabrica (que da un segundo por linea): asi cae entre dos lineas del transcript.
export const enCaptura = (minuto, seg) =>
  new Date(Date.UTC(2026, 9, 1, 12, minuto, seg, 500)).toISOString();

// Un Edit sobre a.md y un Bash, con un hook que reescribe b.md despues del Edit: en el hueco
// entre las dos acciones o, con `alFinal`, despues del Bash, donde solo lo ve SessionEnd.
export function sesionConHook({ alFinal = false } = {}) {
  const repo = repoQueAvanza({ 'a.md': 'uno\n', 'b.md': 'b\n' });
  const a0 = repo.arbol();
  const a1 = repo.cambiar({ 'a.md': 'dos\n' });
  const a2 = repo.cambiar({ 'b.md': 'formateado\n' });
  const f = fabrica()
    .prompt('Edita')
    .llamada('tu1', 'Edit', { file_path: 'a.md', old_string: 'uno', new_string: 'dos' })
    .resultado('tu1', 'ok')
    .llamada('tu2', 'Bash', { command: 'npm test' })
    .resultado('tu2', 'ok');
  const delBash = alFinal ? a1 : a2;
  const captura = [
    { evento: 'SessionStart', momento: enCaptura(0, 0), arbol: a0 },
    { evento: 'PreToolUse', toolUseId: 'tu1', momento: enCaptura(0, 2), arbol: a0 },
    { evento: 'PostToolUse', toolUseId: 'tu1', momento: enCaptura(0, 2), arbol: a1 },
    { evento: 'PreToolUse', toolUseId: 'tu2', momento: enCaptura(0, 4), arbol: delBash },
    { evento: 'PostToolUse', toolUseId: 'tu2', momento: enCaptura(0, 4), arbol: delBash },
    { evento: 'SessionEnd', momento: enCaptura(0, 6), arbol: a2 },
  ];
  return { f, captura, raiz: repo.raiz, arboles: [a0, a1, a2] };
}

// Un subagente que reescribe a.md y una accion del orquestador despues de el. `revierte` dice
// quien deshace el cambio: 'nadie', 'el-mismo' (con un Bash propio, despues de fallar un Edit
// sobre b.md que el orquestador repite con exito) o 'orquestador'.
export function sesionConSubagente({ revierte = 'nadie' } = {}) {
  const repo = repoQueAvanza({ 'a.md': 'a\n', 'b.md': 'b\n' });
  const a0 = repo.arbol();
  const a1 = repo.cambiar({ 'a.md': 'a2\n' });
  repo.cambiar({ 'a.md': 'a\n' });
  const a2 = repo.cambiar({ 'b.md': 'b2\n' });
  const ev = (evento, toolUseId, arbol) => ({ evento, toolUseId, arbol });
  const pasos = [];
  const delSubagente = [];
  if (revierte === 'el-mismo') {
    pasos.push({ id: 'su0', name: 'Edit', input: { file_path: 'b.md', old_string: 'zz',
      new_string: 'b2' }, salida: 'String to replace not found in file.', error: true });
    delSubagente.push(ev('PreToolUse', 'su0', a0), ev('PostToolUseFailure', 'su0', a0));
  }
  pasos.push({ id: 'su1', name: 'Write', input: { file_path: 'a.md', content: 'a2\n' } });
  delSubagente.push(ev('PreToolUse', 'su1', a0), ev('PostToolUse', 'su1', a1));
  if (revierte === 'el-mismo') {
    pasos.push({ id: 'su2', name: 'Bash', input: { command: 'git checkout -- a.md' } });
    delSubagente.push(ev('PreToolUse', 'su2', a1), ev('PostToolUse', 'su2', a0));
  }
  const finSubagente = revierte === 'el-mismo' ? a0 : a1;
  const [herramienta, entrada, antes, despues] = {
    nadie: ['Bash', { command: 'npm test' }, a1, a1],
    'el-mismo': ['Edit', { file_path: 'b.md', old_string: 'b', new_string: 'b2' }, a0, a2],
    orquestador: ['Bash', { command: 'git checkout -- a.md' }, a1, a0],
  }[revierte];
  const f = fabrica()
    .prompt('Delega')
    .llamada('tu1', 'Agent', { subagent_type: 'general-purpose', prompt: 'x' })
    .resultado('tu1', 'ok', { extra: { agentId: 'ab12' } })
    .pausa(60)
    .llamada('tu2', herramienta, entrada)
    .resultado('tu2', 'ok');
  const captura = [
    { evento: 'SessionStart', arbol: a0 },
    ev('PreToolUse', 'tu1', a0),
    ...delSubagente,
    ev('PostToolUse', 'tu1', finSubagente),
    ev('PreToolUse', 'tu2', antes),
    ev('PostToolUse', 'tu2', despues),
    { evento: 'SessionEnd', arbol: despues },
  ];
  const conSubagente = (carpeta) => subagente(carpeta, 'ab12', pasos);
  return { f, captura, raiz: repo.raiz, conSubagente };
}

// El caso de #219, medido en la sesion de #218: un script que lanza una excepcion antes de un
// `| grep`. El `&&` corta la cadena con codigo 1, Claude Code ve el grep al final y lo lee como
// «No matches found»: el resultado llega sin is_error, con la interpretacion en toolUseResult,
// como en Claude Code 2.1.282 y 2.1.287. Despues, el mismo comando corregido sale bien. Con
// `escribe`, el script alcanzo a cambiar a.md antes de la excepcion.
export const COMANDO_CON_GREP =
  'node t.mjs && node --test pruebas/curar.test.mjs 2>&1 | grep -E "^# (pass|fail)"';

export function sesionConGrepQueOcultaUnFallo({ escribe = false } = {}) {
  const repo = repoQueAvanza({ 'a.md': 'uno\n' });
  const a0 = repo.arbol();
  const a1 = escribe ? repo.cambiar({ 'a.md': 'a medias\n' }) : a0;
  const a2 = repo.cambiar({ 'a.md': 'dos\n' });
  const salida = 'file:///t.mjs:3\n    throw new Error("boom");\n\nError: boom\n';
  const bash = (stdout, extra = {}) => ({ stdout, stderr: '', interrupted: false,
    isImage: false, noOutputExpected: false, ...extra });
  const f = fabrica()
    .prompt('Corre las pruebas')
    .texto('Corro el script y las pruebas.')
    .llamada('tu1', 'Bash', { command: COMANDO_CON_GREP })
    .resultado('tu1', salida, {
      extra: bash(salida, { returnCodeInterpretation: 'No matches found' }) })
    .texto('Corrijo el script y repito.')
    .llamada('tu2', 'Bash', { command: COMANDO_CON_GREP })
    .resultado('tu2', '# pass 12\n# fail 0', { extra: bash('# pass 12\n# fail 0') });
  const ev = (evento, toolUseId, arbol) => ({ evento, toolUseId, arbol });
  const captura = [
    { evento: 'SessionStart', arbol: a0 },
    ev('PreToolUse', 'tu1', a0),
    ev('PostToolUse', 'tu1', a1),
    ev('PreToolUse', 'tu2', a1),
    ev('PostToolUse', 'tu2', a2),
    { evento: 'SessionEnd', arbol: a2 },
  ];
  return { f, captura, raiz: repo.raiz, arboles: [a0, a1, a2] };
}

// Una sesion para el motor (#222, fase 2), con una accion de cada modo: un Write y un Edit que se
// aplican, un efecto de hook entre los dos siguientes, un Bash que escribe c.md, un Read, una
// lectura externa (gh) y un efecto externo (git push). Con `powershell`, al final un comando de
// pwsh que escribe d.md: solo corre en el contenedor, que trae pwsh.
export function sesionParaElMotor({ powershell = false } = {}) {
  const repo = repoQueAvanza({ 'a.md': 'uno\n', 'b.md': 'b\n' });
  const a0 = repo.arbol();
  const a1 = repo.cambiar({ 'a.md': 'uno\ndos\n' });
  const a2 = repo.cambiar({ 'a.md': 'uno\ntres\n' });
  const a3 = repo.cambiar({ 'b.md': 'b formateado\n' });
  const a4 = repo.cambiar({ 'c.md': 'c\n' });
  const a5 = powershell ? repo.cambiar({ 'd.md': 'd\n' }) : a4;
  const f = fabrica()
    .prompt('Edita y verifica')
    .llamada('tu1', 'Write', { file_path: 'a.md', content: 'uno\ndos\n' })
    .resultado('tu1', 'ok')
    .llamada('tu2', 'Edit', { file_path: 'a.md', old_string: 'dos', new_string: 'tres' })
    .resultado('tu2', 'ok')
    .llamada('tu3', 'Bash', { command: "printf 'c\\n' > c.md" })
    .resultado('tu3', '')
    .llamada('tu4', 'Read', { file_path: 'a.md' })
    .resultado('tu4', '1\tuno\n2\ttres')
    .llamada('tu5', 'Bash', { command: 'gh issue view 1' })
    .resultado('tu5', 'titulo del issue')
    .llamada('tu6', 'Bash', { command: 'git push' })
    .resultado('tu6', '');
  if (powershell) {
    f.llamada('tu7', 'PowerShell', { command: "Set-Content -Path d.md -Value 'd'" })
      .resultado('tu7', '');
  }
  const ev = (evento, toolUseId, arbol, seg) => ({ evento, toolUseId, arbol,
    momento: enCaptura(0, seg) });
  const captura = [
    { evento: 'SessionStart', arbol: a0, momento: enCaptura(0, 0),
      head: git(['rev-parse', 'HEAD'], { cwd: repo.raiz }).trim() },
    ev('PreToolUse', 'tu1', a0, 2), ev('PostToolUse', 'tu1', a1, 2),
    ev('PreToolUse', 'tu2', a1, 4), ev('PostToolUse', 'tu2', a2, 4),
    ev('PreToolUse', 'tu3', a3, 6), ev('PostToolUse', 'tu3', a4, 6),
    ev('PreToolUse', 'tu4', null, 8), ev('PostToolUse', 'tu4', null, 8),
    ev('PreToolUse', 'tu5', a4, 10), ev('PostToolUse', 'tu5', a4, 10),
    ev('PreToolUse', 'tu6', a4, 12), ev('PostToolUse', 'tu6', a4, 12),
    ...(powershell ? [ev('PreToolUse', 'tu7', a4, 14), ev('PostToolUse', 'tu7', a5, 14)] : []),
    { evento: 'SessionEnd', arbol: a5, momento: enCaptura(0, 20) },
  ];
  return { f, captura, raiz: repo.raiz, arboles: [a0, a1, a2, a3, a4, a5] };
}

// La curada de la sesion para el motor, escrita como la deja el hook.
export function curadaParaElMotor(opciones) {
  const { f, captura, raiz } = sesionParaElMotor(opciones);
  const transcript = f.escribir(carpetaTemporal('sesion'));
  const [registros] = [...compilar({ transcript, captura, raizRepo: raiz }).actas.values()];
  const carpeta = path.join(carpetaTemporal('registros'), '10');
  fs.mkdirSync(carpeta);
  const cruda = path.join(carpeta, 'sesion-1.acta.cruda.jsonl');
  fs.writeFileSync(cruda, serializar(registros));
  const silencio = { log: () => {}, error: () => {} };
  if (curarYEscribir({ cruda, raizRepo: raiz, log: silencio }) !== 0) {
    throw new Error('la sesion para el motor no se cura');
  }
  const curada = cruda.replace('.cruda.', '.curada.');
  return { curada, registros: leerActa(curada), raiz };
}

// Una copia de la curada con un cambio a mano en una accion: el rojo sembrado del motor.
export function sembrar(registros, id, entrada) {
  return registros.map((r) => (r.id === id ? { ...r, entrada: { ...r.entrada, ...entrada } } : r));
}

// El marcador de paso de debt-triage (docs/protocolo-de-marcadores-de-paso.md).
export const marcaDePaso = (letra, fase = 2) =>
  `[sdlc-ia:step skill=debt-triage step=${letra} method-phase=${fase}]`;

// Una corrida de debt-triage que marca sus pasos (#222, fase 4): un Bash antes del primer
// marcador, las Phase 1 y 2 en el primer turno, y despues de la respuesta de la persona, la 2
// de nuevo (retoma) y la 3. La segunda marca va entre backticks, como el modelo suele escribirla.
export function sesionMarcada(opciones) {
  const marca = marcaDePaso;
  return fabrica(opciones)
    .prompt('<command-name>/sdlc-ia:debt-triage</command-name><command-args></command-args>')
    .llamada('tu0', 'Bash', { command: 'git status' })
    .resultado('tu0', 'limpio')
    .texto(`${marca(1)}\nBusco el analizador.`)
    .llamada('tu1', 'Glob', { pattern: '**/checkstyle.xml' })
    .resultado('tu1', 'checkstyle.xml')
    .texto(`\`${marca(2)}\``)
    .llamada('tu2', 'Bash', { command: 'mvn checkstyle:check' })
    .resultado('tu2', '3 reglas')
    .texto('Esta es la lista agrupada.')
    .prompt('Solo la regla MagicNumber')
    .texto(marca(2))
    .llamada('tu3', 'Read', { file_path: 'a.java' })
    .resultado('tu3', '1\tclass A {}')
    .texto(marca(3))
    .llamada('tu4', 'Read', { file_path: 'b.java' })
    .resultado('tu4', '1\tclass B {}');
}

// Una sesion para el visor (#222, fase 6) con cada caso que tiene que distinguir: una accion sin
// paso, pasos marcados por debt-triage con su SKILL.md real, una lectura externa (fixture), un
// intento fallido antes del Edit bueno, un efecto de hook, un rojo esperado y un git push con el
// permiso aprobado. Pasa por el compilador y la curacion de verdad; devuelve las rutas del acta.
export const SKILL_DEBT_TRIAGE = 'instrumentacion-java-ia/sdlc-ia/skills/debt-triage/SKILL.md';

export function sesionParaElVisor() {
  const raizMonorepo = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
  const repo = repoQueAvanza({ 'Tarifa.java': 'x * 1.19\n',
    [SKILL_DEBT_TRIAGE]: fs.readFileSync(path.join(raizMonorepo, SKILL_DEBT_TRIAGE), 'utf8') });
  const head = git(['rev-parse', 'HEAD'], { cwd: repo.raiz }).trim();
  const a0 = repo.arbol();
  const a1 = repo.cambiar({ 'Tarifa.java': 'x * IVA\n' });
  const a2 = repo.cambiar({ 'Tarifa.java': 'x * IVA; // formateado\n' });
  const marca = marcaDePaso;
  const push = { command: 'git push -u origin feat/10-deuda' };
  const f = fabrica()
    .prompt('<command-name>/sdlc-ia:debt-triage</command-name><command-args></command-args>')
    .llamada('tu0', 'Bash', { command: 'git status --short' })
    .resultado('tu0', '')
    .texto(`${marca(1)}\nBusco el analizador que ya corre en el repo.`)
    .llamada('tu1', 'Read', { file_path: 'Tarifa.java' })
    .resultado('tu1', '1\tx * 1.19')
    .texto(marca(2))
    .llamada('tu2', 'Bash', { command: 'gh issue list --label deuda' })
    .resultado('tu2', 'sin issues abiertos')
    .prompt('Solo la regla MagicNumber')
    .texto(`${marca(3)}\nLeo cada llamada antes de decidir.`)
    .llamada('tu3', 'Edit', { file_path: 'Tarifa.java', old_string: '* 1.20', new_string: '* IVA' })
    .resultado('tu3', '<tool_use_error>String to replace not found in file.</tool_use_error>',
      { error: true })
    .llamada('tu4', 'Edit', { file_path: 'Tarifa.java', old_string: '* 1.19', new_string: '* IVA' })
    .resultado('tu4', 'ok')
    .llamada('tu5', 'Bash', { command: './mvnw -q test  # rojo-esperado: ' +
      'la prueba nueva falla antes del arreglo' })
    .resultado('tu5', 'Tests run: 1, Failures: 1', { error: true })
    .llamada('tu6', 'Bash', push)
    .resultado('tu6', 'rama publicada');
  const ev = (evento, toolUseId, arbol, seg, extra = {}) => ({ evento, toolUseId, arbol,
    momento: enCaptura(0, seg), ...extra });
  const captura = [
    { evento: 'SessionStart', head, arbol: a0, momento: enCaptura(0, 0) },
    ev('PreToolUse', 'tu0', a0, 2), ev('PostToolUse', 'tu0', a0, 2),
    ev('PreToolUse', 'tu1', null, 5), ev('PostToolUse', 'tu1', null, 5),
    ev('PreToolUse', 'tu2', a0, 8), ev('PostToolUse', 'tu2', a0, 8),
    ev('PreToolUse', 'tu3', a0, 12), ev('PostToolUseFailure', 'tu3', a0, 12),
    ev('PreToolUse', 'tu4', a0, 14), ev('PostToolUse', 'tu4', a1, 14),
    ev('PreToolUse', 'tu5', a2, 16), ev('PostToolUseFailure', 'tu5', a2, 16),
    ev('PreToolUse', 'tu6', a2, 18, { herramienta: 'Bash', entrada: huellaDeEntrada(push) }),
    { evento: 'PermissionRequest', herramienta: 'Bash', entrada: huellaDeEntrada(push),
      modo: 'default', momento: enCaptura(0, 18) },
    ev('PostToolUse', 'tu6', a2, 18),
    { evento: 'SessionEnd', arbol: a2, momento: enCaptura(0, 20) },
  ];
  const transcript = f.escribir(carpetaTemporal('sesion'));
  const [registros] = [...compilar({ transcript, captura, raizRepo: repo.raiz }).actas.values()];
  const carpeta = path.join(repo.raiz, '.ia', 'registros', '10');
  fs.mkdirSync(carpeta, { recursive: true });
  const cruda = path.join(carpeta, 'sesion-1.acta.cruda.jsonl');
  fs.writeFileSync(cruda, serializar(registros));
  const silencio = { log: () => {}, error: () => {} };
  if (curarYEscribir({ cruda, raizRepo: repo.raiz, log: silencio }) !== 0) {
    throw new Error('la sesion para el visor no se cura');
  }
  return { raiz: repo.raiz, cruda, curada: cruda.replace('.cruda.', '.curada.') };
}

// Una sesion que trabaja el plan del issue #10 sin ninguna skill (#230): marca sus pasos con
// plan-de-issue, si `marcar`, y deja junto al acta el plan que guarda registrar-sesion.mjs. El
// repo trae su AGENTS.md, que es el procedimiento de una sesion sin skill.
export const PLAN_DEL_10 = '# Plan\n\n## Fase 1 · Catalogo de actividades\n\nTexto.\n\n' +
  '## Fase 2 · La ficha separa actividad y herramienta\n\n```\n## Fase 9 · un ejemplo\n```\n';

export const marcaDelPlan = (letra, fase = 3) =>
  `[sdlc-ia:step skill=plan-de-issue step=${letra} method-phase=${fase}]`;

export function sesionDelPlan({ marcar = true, conPlan = true } = {}) {
  const repo = repoQueAvanza({ 'AGENTS.md': '# Reglas\n', 'a.txt': 'uno\n' });
  const head = git(['rev-parse', 'HEAD'], { cwd: repo.raiz }).trim();
  const a0 = repo.arbol();
  const a1 = repo.cambiar({ 'a.txt': 'dos\n' });
  const f = fabrica()
    .prompt('ejecuta el plan del issue #10')
    .texto(marcar ? `${marcaDelPlan(1)}\nEmpiezo por el catalogo.` : 'Empiezo por el catalogo.')
    .llamada('tu0', 'Read', { file_path: 'a.txt' })
    .resultado('tu0', '1\tuno')
    .texto(marcar ? marcaDelPlan(2) : 'Sigo.')
    .llamada('tu1', 'Edit', { file_path: 'a.txt', old_string: 'uno', new_string: 'dos' })
    .resultado('tu1', 'ok');
  const captura = [
    { evento: 'SessionStart', head, arbol: a0, momento: enCaptura(0, 0) },
    { evento: 'PreToolUse', toolUseId: 'tu0', arbol: null, momento: enCaptura(0, 2) },
    { evento: 'PostToolUse', toolUseId: 'tu0', arbol: null, momento: enCaptura(0, 2) },
    { evento: 'PreToolUse', toolUseId: 'tu1', arbol: a0, momento: enCaptura(0, 4) },
    { evento: 'PostToolUse', toolUseId: 'tu1', arbol: a1, momento: enCaptura(0, 4) },
    { evento: 'SessionEnd', arbol: a1, momento: enCaptura(0, 6) },
  ];
  const transcript = f.escribir(carpetaTemporal('sesion'));
  const [registros] = [...compilar({ transcript, captura, raizRepo: repo.raiz }).actas.values()];
  const carpeta = path.join(repo.raiz, '.ia', 'registros', '10');
  fs.mkdirSync(carpeta, { recursive: true });
  const cruda = path.join(carpeta, 'sesion-1.acta.cruda.jsonl');
  fs.writeFileSync(cruda, serializar(registros));
  const silencio = { log: () => {}, error: () => {} };
  if (curarYEscribir({ cruda, raizRepo: repo.raiz, log: silencio }) !== 0) {
    throw new Error('la sesion del plan no se cura');
  }
  if (conPlan) {
    fs.writeFileSync(path.join(carpeta, 'plan-del-issue.json'), JSON.stringify({
      numero: 10, titulo: 'Actividad declarada', actualizado: '2026-10-04T12:00:00Z',
      sha256: 'x', cuerpo: PLAN_DEL_10 }));
  }
  return { raiz: repo.raiz, cruda, curada: cruda.replace('.cruda.', '.curada.') };
}
