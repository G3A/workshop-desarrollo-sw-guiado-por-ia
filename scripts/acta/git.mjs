// Llamadas a git que usan la captura y el compilador del acta.
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

function entornoLimpio(extra = {}) {
  const env = { ...process.env };
  delete env.GIT_DIR;
  delete env.GIT_WORK_TREE;
  delete env.GIT_INDEX_FILE;
  return { ...env, ...extra };
}

export function git(args, { cwd, env = {}, permitirFallo = false } = {}) {
  try {
    return execFileSync('git', args, {
      cwd,
      env: entornoLimpio(env),
      stdio: ['ignore', 'pipe', 'pipe'],
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

// Los cambios entre dos arboles, un elemento por archivo y en orden de ruta. Los dos arboles
// existen como objetos porque `write-tree` los escribio al capturar.
export function cambiosEntre(raiz, antes, despues) {
  if (!antes || !despues || antes === despues) return [];
  const nombres = git(['diff', '--name-only', '--no-renames', antes, despues], {
    cwd: raiz,
    permitirFallo: true,
  });
  if (nombres === null) return [];
  return nombres
    .split('\n')
    .filter(Boolean)
    .sort()
    .map((archivo) => ({
      archivo,
      diff: git(['diff', '--no-color', '--no-ext-diff', '--no-renames', antes, despues, '--',
        archivo], { cwd: raiz }),
    }));
}

// Commit y hash de un archivo tal como estaba en `commit`. Null si no existe en ese commit.
export function versionDe(raiz, commit, ruta) {
  if (!commit) return null;
  const blob = git(['rev-parse', `${commit}:${ruta}`], { cwd: raiz, permitirFallo: true });
  if (!blob) return null;
  const ultimo = git(['rev-list', '-1', commit, '--', ruta], { cwd: raiz, permitirFallo: true });
  return { ruta, commit: ultimo ? ultimo.trim() : null, hash: blob.trim() };
}

export function leerEnCommit(raiz, commit, ruta) {
  if (!commit) return null;
  return git(['show', `${commit}:${ruta}`], { cwd: raiz, permitirFallo: true });
}
