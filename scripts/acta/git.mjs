// Llamadas a git que usan la captura, el compilador y la curacion del acta.
//
// Las que la captura necesita en cada evento (git, raizDelRepo, head, arbolActual) viven en
// nucleo-captura.mjs, que no importa nada del repo, y aqui se reexportan (#222).
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { git } from './nucleo-captura.mjs';

export { arbolActual, git, head, raizDelRepo } from './nucleo-captura.mjs';

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

// El hash del blob de una ruta dentro de un arbol. Null si la ruta no existe en ese arbol.
export function blobEn(raiz, arbol, ruta) {
  const r = git(['rev-parse', '--verify', '--quiet', `${arbol}:${ruta}`], {
    cwd: raiz,
    permitirFallo: true,
  });
  return r ? r.trim() : null;
}

// El contenido de una ruta dentro de un arbol, como texto. Null si la ruta no esta.
export function blobTexto(raiz, arbol, ruta) {
  return git(['cat-file', 'blob', `${arbol}:${ruta}`], { cwd: raiz, permitirFallo: true });
}

// El arbol que resulta de poner `contenido` en `ruta` dentro de `arbol`. Lo escribe en el repo,
// como la captura escribe los suyos, desde un indice temporal: el real no se toca. El blob se
// guarda tal cual, sin filtros de fin de linea, porque `contenido` ya es contenido de un blob.
export function arbolCon(raiz, arbol, ruta, contenido) {
  const tmp = path.join(os.tmpdir(), `ia-acta-${process.pid}-${Date.now()}-${Math.random()}`);
  const env = { GIT_INDEX_FILE: tmp };
  try {
    git(['read-tree', arbol], { cwd: raiz, env });
    const blob = git(['hash-object', '-w', '--stdin'], { cwd: raiz, input: contenido }).trim();
    const previo = git(['ls-tree', arbol, '--', ruta], { cwd: raiz }).trim();
    const modo = previo ? previo.split(/\s/)[0] : '100644';
    git(['update-index', '--add', '--cacheinfo', `${modo},${blob},${ruta}`], { cwd: raiz, env });
    return git(['write-tree'], { cwd: raiz, env }).trim();
  } finally {
    fs.rmSync(tmp, { force: true });
  }
}

// Los hashes que el repo no tiene como objeto, en una sola llamada.
export function objetosQueFaltan(raiz, hashes) {
  if (!hashes.length) return [];
  const salida = git(['cat-file', '--batch-check'], { cwd: raiz, input: hashes.join('\n') + '\n' });
  return salida.split('\n').filter((l) => / missing$/.test(l)).map((l) => l.split(' ')[0]);
}

export function leerEnCommit(raiz, commit, ruta) {
  if (!commit) return null;
  return git(['show', `${commit}:${ruta}`], { cwd: raiz, permitirFallo: true });
}
