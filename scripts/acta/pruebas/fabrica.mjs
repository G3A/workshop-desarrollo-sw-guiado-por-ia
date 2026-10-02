// Fabrica de transcripts SINTETICOS para las pruebas del acta. Nunca transcripts reales: traen
// rutas, contenido leido y posibles secretos.
//
// Reproduce la forma que escribe Claude Code 2.1.x (medida sobre sesiones de este repo, sin
// copiar su contenido): una linea por bloque, `user` con el prompt como texto o con tool_result,
// `assistant` con text o tool_use, y `toolUseResult` con el agentId de un subagente.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { git } from '../git.mjs';

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
