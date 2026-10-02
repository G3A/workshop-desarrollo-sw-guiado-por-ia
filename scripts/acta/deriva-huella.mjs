// Que cambio en la huella de un acta desde que se produjo (#222, fase 3; PC-14), desde la raiz:
//
//   node scripts/acta/deriva-huella.mjs <acta> [--hasta <commit>] [--modelo <id>]
//        [--claude-code <version>]
//
// Compara la huella del acta (en su HEAD base) con el estado en `--hasta` (HEAD por defecto):
//
//   documentos   cada instructivo de la huella: el blob de entonces contra el de ahora.
//   plugin       la version de sdlc-ia en el plugin.json.
//   imagen       el blob del Dockerfile del motor y el digest de su imagen de partida.
//   claudeCode   las versiones que el transcript registro contra la que se pasa, o la que
//                responde `claude --version` si esta en el PATH.
//   modelo       los modelos del acta contra el que se pasa con --modelo. Desde aqui no hay
//                forma de saber que modelo corre la proxima sesion: sin --modelo queda en null.
//
// Cada entrada lleva `cambio`: true, false, o null cuando falta uno de los dos lados. Escribe JSON
// en stdout. Codigos de salida: 0 escrito, 2 uso incorrecto.
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { git, raizDelRepo } from './git.mjs';
import { leerActa } from './validar-acta.mjs';

const PLUGIN = 'instrumentacion-java-ia/sdlc-ia/.claude-plugin/plugin.json';
const DOCKERFILE = 'scripts/acta/motor/Dockerfile';

const comparar = (antes, ahora) => ({ antes, ahora,
  cambio: antes === null || ahora === null ? null : JSON.stringify(antes) !==
    JSON.stringify(ahora) });

// Una sesion puede pasar por varias versiones de Claude Code o varios modelos: cambio es que el
// de ahora no este entre ellos.
const enLista = (antes, ahora) => ({ antes: antes?.length ? antes : null, ahora,
  cambio: !antes?.length || ahora === null ? null : !antes.includes(ahora) });

function blob(raiz, commit, ruta) {
  const r = git(['rev-parse', '--verify', '--quiet', `${commit}:${ruta}`], { cwd: raiz,
    permitirFallo: true });
  return r ? r.trim() : null;
}

function texto(raiz, commit, ruta) {
  return git(['show', `${commit}:${ruta}`], { cwd: raiz, permitirFallo: true });
}

function versionClaudeCode() {
  const r = spawnSync('claude', ['--version'], { encoding: 'utf8', timeout: 20000 });
  const m = /(\d+\.\d+\.\d+)/.exec(r.stdout || '');
  return m ? m[1] : null;
}

export function derivaHuella(registros, { raiz, hasta = 'HEAD', modelo = null,
  claudeCode = undefined }) {
  const cabecera = registros.find((r) => r.elemento === 'acta');
  const h = cabecera.huella || {};
  const commit = git(['rev-parse', hasta], { cwd: raiz }).trim();
  let plugin = null;
  try {
    plugin = JSON.parse(texto(raiz, commit, PLUGIN) || 'null')?.version ?? null;
  } catch { /* un plugin.json roto cuenta como ausente */ }
  const desde = /^FROM\s+(\S+@sha256:[0-9a-f]{64})/m.exec(texto(raiz, commit, DOCKERFILE) || '');
  const cc = claudeCode === undefined ? versionClaudeCode() : claudeCode;
  return {
    sesion: cabecera.sesion,
    tarea: cabecera.tarea,
    desde: cabecera.headBase,
    hasta: commit,
    documentos: (h.documentos || []).map((d) => ({ ruta: d.ruta,
      ...comparar(d.hash, blob(raiz, commit, d.ruta)) })),
    plugin: comparar(h.plugin ?? null, plugin),
    imagen: {
      dockerfile: comparar(h.imagen?.dockerfile?.hash ?? null, blob(raiz, commit, DOCKERFILE)),
      base: comparar(h.imagen?.base ?? null, desde ? desde[1] : null),
    },
    claudeCode: enLista(h.claudeCode, cc),
    modelo: enLista(h.modelos, modelo),
  };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  const opcion = (n) => {
    const i = args.indexOf(n);
    return i === -1 ? undefined : args[i + 1];
  };
  const acta = args[0];
  const raiz = raizDelRepo(process.cwd());
  if (!acta || !fs.existsSync(acta) || !raiz) {
    console.error('Uso: node scripts/acta/deriva-huella.mjs <acta> [--hasta <commit>] ' +
      '[--modelo <id>] [--claude-code <version>]');
    process.exitCode = 2;
  } else {
    const r = derivaHuella(leerActa(acta), { raiz, hasta: opcion('--hasta') || 'HEAD',
      modelo: opcion('--modelo') || null, claudeCode: opcion('--claude-code') });
    process.stdout.write(JSON.stringify(r, null, 2) + '\n');
  }
}
