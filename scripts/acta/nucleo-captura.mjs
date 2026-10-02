// Lo unico que el hook de captura carga en cada evento (#222). No importa nada del repo, solo
// modulos de node, y por eso vive aparte de git.mjs y de clasificar.mjs, que lo reexportan.
//
// La razon es la sesion de #218: una accion dejo curar-acta.mjs con un error de sintaxis, y como
// capturar.mjs lo importaba, el hook no cargaba. Ningun evento se registro hasta que otra accion
// lo arreglo, y la cadena de arboles quedo cortada en a236-a239. Un registro que depende del
// codigo que registra pierde justo las acciones que rompen ese codigo. Este archivo y
// capturar.mjs son los dos que una sesion no puede dejar rotos sin perder la captura; el resto
// (compilar, curar, indexar) se carga al cerrar la sesion, y si no carga, solo falta el acta.
//
// Dos decisiones que no son de estilo:
//
// 1. Cada llamada BORRA GIT_DIR, GIT_WORK_TREE y GIT_INDEX_FILE del entorno heredado. Las pruebas
//    corren dentro del hook pre-push, y git exporta GIT_DIR a sus hooks: un `git init` en una
//    carpeta temporal con ese entorno escribe en el repo real. Ya paso con JGit en este monorepo.
// 2. El hash del arbol sale de un indice TEMPORAL, copiado del real para aprovechar su cache de
//    stat. Asi `git add -A` no toca lo que el usuario tiene en stage, y el hash es el del indice,
//    no el del arbol de trabajo: core.autocrlf no lo cambia entre Windows y Linux.
//
// Medido en este repo (599 archivos versionados): unos 70 ms por hash.
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

// Las herramientas que no cambian el arbol. La captura no les toma el hash (cuesta unos 70 ms), y
// la curacion sabe que un fallo suyo no deja residuo aunque su arbol este en null.
export const SIN_ARBOL = new Set(['Read', 'Grep', 'Glob', 'LS', 'WebSearch', 'WebFetch',
  'ToolSearch', 'Skill', 'TodoWrite', 'AskUserQuestion']);

function entornoLimpio(extra = {}) {
  const env = { ...process.env };
  delete env.GIT_DIR;
  delete env.GIT_WORK_TREE;
  delete env.GIT_INDEX_FILE;
  return { ...env, ...extra };
}

export function git(args, { cwd, env = {}, permitirFallo = false, input } = {}) {
  try {
    return execFileSync('git', args, {
      cwd,
      env: entornoLimpio(env),
      stdio: [input === undefined ? 'ignore' : 'pipe', 'pipe', 'pipe'],
      input,
      maxBuffer: 64 * 1024 * 1024,
    }).toString();
  } catch (e) {
    if (permitirFallo) return null;
    throw e;
  }
}

export function raizDelRepo(cwd) {
  const r = git(['rev-parse', '--show-toplevel'], { cwd, permitirFallo: true });
  return r ? r.trim() : null;
}

export function head(raiz) {
  const r = git(['rev-parse', 'HEAD'], { cwd: raiz, permitirFallo: true });
  return r ? r.trim() : null;
}

// El hash del arbol tal como esta ahora, sin tocar el indice real. Null si algo falla: la captura
// nunca bloquea la sesion, y un hash ausente se nota en el acta en vez de inventarse.
export function arbolActual(raiz) {
  const tmp = path.join(os.tmpdir(), `ia-acta-${process.pid}-${Date.now()}-${Math.random()}`);
  try {
    const rel = git(['rev-parse', '--git-path', 'index'], { cwd: raiz }).trim();
    const indiceReal = path.resolve(raiz, rel);
    if (fs.existsSync(indiceReal)) fs.copyFileSync(indiceReal, tmp);
    // .ia/ queda fuera aunque el repo no la ignore: la captura escribe ahi en cada accion, y
    // si entrara en el hash, el arbol cambiaria por el solo hecho de registrarlo. Si el repo ya
    // la ignora, el pathspec de exclusion NO se pasa: git lo trata como agregar una ruta
    // ignorada y termina con codigo 1.
    const ignorada = git(['check-ignore', '-q', '.ia'], { cwd: raiz, permitirFallo: true });
    const rutas = ignorada === null ? ['--', '.', ':(exclude).ia'] : [];
    git(['add', '-A', ...rutas], { cwd: raiz, env: { GIT_INDEX_FILE: tmp } });
    return git(['write-tree'], { cwd: raiz, env: { GIT_INDEX_FILE: tmp } }).trim();
  } catch {
    return null;
  } finally {
    fs.rmSync(tmp, { force: true });
  }
}
