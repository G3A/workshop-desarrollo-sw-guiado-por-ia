// Pruebas del indice de una tarea (#222): node --test scripts/acta/pruebas/indexar-tarea.test.mjs
//
// Las actas salen del compilador sobre transcripts sinteticos (fabrica.mjs), con sus diffs
// contra un repo de prueba: el indice inverso se mide sobre los cambios que el compilador arma.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { compilar, serializar } from '../compilar-acta.mjs';
import { indexarTarea, indexarYEscribir } from '../indexar-tarea.mjs';
import { carpetaTemporal, enCaptura, fabrica, repoQueAvanza, sesionConHook } from './fabrica.mjs';

const silencio = { log: () => {}, error: () => {} };

function escribirActa(carpeta, f, opciones) {
  const transcript = f.escribir(carpetaTemporal('sesion'));
  const { actas } = compilar({ transcript, ...opciones });
  const [registros] = [...actas.values()];
  const nombre = `${registros[0].sesion}.acta.cruda.jsonl`;
  fs.writeFileSync(path.join(carpeta, nombre), serializar(registros));
  return nombre;
}

// Dos sesiones de la misma tarea, escritas en el orden inverso al de su inicio: la del hook
// (12:00) y una que reescribe a.md dos veces, un dia despues.
function tareaConDosSesiones() {
  const carpeta = path.join(carpetaTemporal('registros'), '10');
  fs.mkdirSync(carpeta);
  const repo = repoQueAvanza({ 'a.md': 'uno\n' });
  const b0 = repo.arbol();
  const b1 = repo.cambiar({ 'a.md': 'dos\n' });
  const b2 = repo.cambiar({ 'a.md': 'tres\n' });
  const otra = fabrica({ sesion: 'zeta', rama: 'fix/10-otra' })
    .pausa(24 * 60)
    .prompt('Otra vez')
    .llamada('tu1', 'Write', { file_path: 'a.md', content: 'dos\n' })
    .resultado('tu1', 'ok')
    .llamada('tu2', 'Edit', { file_path: 'a.md', old_string: 'dos', new_string: 'tres' })
    .resultado('tu2', 'ok');
  const ev = (evento, toolUseId, arbol) => ({ evento, toolUseId, arbol, momento: enCaptura(0, 0) });
  escribirActa(carpeta, otra, { raizRepo: repo.raiz, captura: [
    { evento: 'SessionStart', arbol: b0, momento: enCaptura(0, 0) },
    ev('PreToolUse', 'tu1', b0), ev('PostToolUse', 'tu1', b1),
    ev('PreToolUse', 'tu2', b1), ev('PostToolUse', 'tu2', b2),
  ] });
  const { f, captura, raiz } = sesionConHook();
  const nombre = escribirActa(carpeta, f, { captura, raizRepo: raiz });
  fs.writeFileSync(path.join(carpeta, nombre.replace('.cruda.', '.curada.')), '{}\n');
  return carpeta;
}

test('indice: actas en orden de inicio, con sus ramas, su curada y sin fases marcadas', () => {
  const indice = indexarTarea(tareaConDosSesiones(), { log: silencio });
  assert.equal(indice.tarea, '10');
  assert.deepEqual(indice.fases, []);
  assert.deepEqual(indice.actas.map((a) => [a.sesion, a.curada, a.fase]), [
    ['sesion-1', 'sesion-1.acta.curada.jsonl', null],
    ['zeta', null, null],
  ]);
  assert.deepEqual(indice.ramas, ['feat/10-demo', 'fix/10-otra']);
  assert.equal(indice.inicio, indice.actas[0].inicio);
  assert.equal(indice.fin, indice.actas[1].fin);
  assert.match(indice.actas[0].sha256, /^[0-9a-f]{64}$/);
});

test('indice inverso: cada archivo con las acciones que lo cambiaron, hooks incluidos', () => {
  const { archivos } = indexarTarea(tareaConDosSesiones(), { log: silencio });
  assert.deepEqual(Object.keys(archivos), ['a.md', 'b.md']);
  assert.deepEqual(archivos['a.md'], [
    { sesion: 'sesion-1', accion: 'a1', agente: 'orquestador' },
    { sesion: 'zeta', accion: 'a1', agente: 'orquestador' },
    { sesion: 'zeta', accion: 'a2', agente: 'orquestador' },
  ]);
  assert.deepEqual(archivos['b.md'],
    [{ sesion: 'sesion-1', accion: 'a3', agente: 'hook:sin-identificar' }]);
});

test('indice: determinista, byte a byte, y sin las actas que no se pueden leer', () => {
  const carpeta = tareaConDosSesiones();
  const destino = indexarYEscribir(carpeta, { log: silencio });
  const primero = fs.readFileSync(destino, 'utf8');
  fs.writeFileSync(path.join(carpeta, 'rota.acta.cruda.jsonl'), 'esto no es json\n');
  const avisos = [];
  indexarYEscribir(carpeta, { log: { log: () => {}, error: (m) => avisos.push(m) } });
  assert.equal(fs.readFileSync(destino, 'utf8'), primero);
  assert.match(avisos.join('\n'), /rota\.acta\.cruda\.jsonl no es JSONL/);
});
