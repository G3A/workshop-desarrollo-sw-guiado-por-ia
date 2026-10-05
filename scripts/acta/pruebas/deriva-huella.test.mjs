// Pruebas de la deriva de la huella (#222, fase 3; PC-14):
//   node --test scripts/acta/pruebas/deriva-huella.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { derivaHuella } from '../deriva-huella.mjs';
import { git } from '../git.mjs';
import { repoTemporal } from './fabrica.mjs';

const PLUGIN = 'instrumentacion-java-ia/sdlc-ia/.claude-plugin/plugin.json';
const SKILL = 'instrumentacion-java-ia/sdlc-ia/skills/demo/SKILL.md';
const DOCKERFILE = 'scripts/acta/motor/Dockerfile';
const BASE = `node@sha256:${'ab'.repeat(32)}`;

test('deriva: dice que cambio desde el HEAD base del acta, y que no se puede saber', () => {
  const raiz = repoTemporal({ [SKILL]: '# v1\n', [PLUGIN]: '{"version":"1.0.0"}',
    [DOCKERFILE]: `FROM ${BASE}\n` });
  const head = git(['rev-parse', 'HEAD'], { cwd: raiz }).trim();
  const blob = (ruta) => git(['rev-parse', `HEAD:${ruta}`], { cwd: raiz }).trim();
  const acta = [{ elemento: 'acta', sesion: 's', tarea: '10', headBase: head, huella: {
    claudeCode: ['2.1.282', '2.1.287'], modelos: ['claude-a'], plugin: '1.0.0',
    documentos: [{ ruta: SKILL, commit: head, hash: blob(SKILL) }],
    imagen: { dockerfile: { ruta: DOCKERFILE, commit: head, hash: blob(DOCKERFILE) }, base: BASE },
  } }];
  fs.writeFileSync(path.join(raiz, SKILL), '# v2\n');
  fs.writeFileSync(path.join(raiz, PLUGIN), '{"version":"1.1.0"}');
  git(['commit', '-q', '-am', 'avanza'], { cwd: raiz });

  const d = derivaHuella(acta, { raiz, modelo: 'claude-b', claudeCode: '2.1.287' });
  assert.equal(d.documentos[0].cambio, true);
  assert.deepEqual([d.plugin.antes, d.plugin.ahora, d.plugin.cambio], ['1.0.0', '1.1.0', true]);
  assert.equal(d.imagen.dockerfile.cambio, false);
  assert.equal(d.imagen.base.cambio, false);
  assert.equal(d.claudeCode.cambio, false, 'la de ahora esta entre las de la sesion');
  assert.equal(d.modelo.cambio, true);
  const sinDatos = derivaHuella(acta, { raiz, claudeCode: null });
  assert.deepEqual([sinDatos.modelo.cambio, sinDatos.claudeCode.cambio], [null, null],
    'sin el modelo ni la version de ahora, no se afirma nada');
});
