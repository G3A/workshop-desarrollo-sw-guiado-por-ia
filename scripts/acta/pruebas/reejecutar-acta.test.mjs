// Pruebas del motor dentro de Docker (#222, fase 2):
//   node --test scripts/acta/pruebas/reejecutar-acta.test.mjs
//
// Corren la CLI como la corre una persona: construyen la imagen si falta (la primera vez tarda un
// par de minutos y necesita red) y re-ejecutan la curada en el contenedor. Sin Docker se saltan,
// como las de gitleaks: en el CI de GitHub siempre hay.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { serializar } from '../compilar-acta.mjs';
import { curadaParaElMotor, sembrar } from './fabrica.mjs';

const CLI = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', 'reejecutar-acta.mjs');
const hayDocker = spawnSync('docker', ['version', '--format', '{{.Server.Version}}']).status === 0;

function motor(curada, cwd) {
  const env = { ...process.env };
  delete env.GIT_DIR;
  delete env.GIT_WORK_TREE;
  delete env.GIT_INDEX_FILE;
  return spawnSync(process.execPath, [CLI, curada, '--tiempo', '120'], { cwd, env,
    encoding: 'utf8' });
}

test('motor en Docker: reproduce la curada hasta su arbol final, pwsh incluido', (t) => {
  if (!hayDocker) {
    t.skip('Docker no esta disponible');
    return;
  }
  const { curada, raiz } = curadaParaElMotor({ powershell: true });
  const r = motor(curada, raiz);
  assert.equal(r.status, 0, r.stdout + r.stderr);
  assert.match(r.stdout, /La curada llega a su arbol final/);
  const reporte = JSON.parse(fs.readFileSync(curada.replace('.curada.jsonl', '.reporte.json'),
    'utf8'));
  const ps = reporte.veredictos.find((v) => v.herramienta === 'PowerShell');
  assert.deepEqual([ps.modo, ps.veredicto], ['ejecutada', 'igual']);
  assert.equal(reporte.resumen.verificadas, 6);
  assert.match(reporte.imagen.id, /^sha256:[0-9a-f]{64}$/);
});

test('motor en Docker: una curada sembrada con una divergencia sale en rojo', (t) => {
  if (!hayDocker) {
    t.skip('Docker no esta disponible');
    return;
  }
  const { curada, registros, raiz } = curadaParaElMotor();
  fs.writeFileSync(curada, serializar(sembrar(registros, 'a3', { command: 'date > c.md' })));
  const r = motor(curada, raiz);
  assert.equal(r.status, 1, r.stdout + r.stderr);
  assert.match(r.stdout, /Primera divergencia: a3, deja el arbol/);
  assert.match(r.stdout, /NO llega a su arbol final/);
});
