// Fabrica de transcripts SINTETICOS para las pruebas del acta. Nunca transcripts reales: traen
// rutas, contenido leido y posibles secretos.
//
// Reproduce la forma que escribe Claude Code 2.1.x (medida sobre sesiones de este repo, sin
// copiar su contenido): una linea por bloque, `user` con el prompt como texto o con tool_result,
// `assistant` con text o tool_use, y `toolUseResult` con el agentId de un subagente.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { arbolActual, git } from '../git.mjs';

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
      { type: 'tool_result', tool_use_id: p.id, content: p.salida || 'ok' }] } });
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
