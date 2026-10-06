// Pruebas del trailer Registro-IA y su sensor (#222, fase 3; ADR-0006):
//   node --test $ACTA/pruebas/verificar-registro-ia.test.mjs
//
// La sesion para el motor se registra dentro de su repo de prueba, sus cambios y su acta se
// commitean con el trailer, y el sensor corre sobre un CLON: ahi no estan los arboles que la
// captura escribio, y solo los trae el pack de objetos, como en el CI. El motor queda fuera
// (--sin-motor): su corrida en Docker la prueban reejecutar-acta.test.mjs y el CI de cada PR.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { compilarYCerrar } from '../cerrar-acta.mjs';
import { citar } from '../citar-acta.mjs';
import { compilar } from '../compilar-acta.mjs';
import { identidadDelEntorno } from '../normalizar.mjs';
import { cortarEnCurso, guardarPlanDelIssue } from '../registrar-sesion.mjs';
import { git } from '../git.mjs';
import { verificarRegistro } from '../verificar-registro-ia.mjs';
import { carpetaTemporal, fabrica, sesionParaElMotor } from './fabrica.mjs';

const silencio = { log: () => {}, error: () => {} };
const ACTA = '.ia/registros/10/sesion-1.acta.curada.jsonl';
const pie = (registro = `Registro-IA: ${ACTA}`) =>
  `\n\nAsistido-por-IA: claude-prueba\n${registro}\n`;

function commitear(raiz, mensaje, archivos = ['-A']) {
  git(['add', ...archivos], { cwd: raiz });
  git(['commit', '-q', '--allow-empty', '-m', mensaje], { cwd: raiz });
}

// El repo de la sesion con dos commits asistidos: el del trabajo y el del acta. Devuelve un clon,
// el rango de la PR y el texto de un cuerpo de PR que repite el trailer.
function prConActa() {
  const { f, captura, raiz } = sesionParaElMotor();
  const base = git(['rev-parse', 'HEAD'], { cwd: raiz }).trim();
  const carpeta = carpetaTemporal('sesion');
  const transcript = f.escribir(carpeta);
  const archivoCaptura = path.join(carpeta, 'captura.jsonl');
  fs.writeFileSync(archivoCaptura, captura.map((c) => JSON.stringify(c)).join('\n') + '\n');
  const { codigo } = compilarYCerrar({ transcript, captura: archivoCaptura, raizRepo: raiz,
    log: silencio, verificar: false });
  assert.equal(codigo, 0);
  assert.ok(fs.existsSync(path.join(raiz, ACTA.replace('curada.jsonl', 'objetos.pack'))));
  commitear(raiz, `Trabajo de la sesion${pie()}`, ['a.md', 'b.md', 'c.md']);
  commitear(raiz, `Registro de la sesion${pie()}`, ['-f', '.ia/registros/10']);
  const clon = path.join(carpetaTemporal('clon'), 'repo');
  git(['clone', '-q', '-c', 'core.autocrlf=false', raiz, clon]);
  git(['config', 'user.email', 'prueba@example.com'], { cwd: clon });
  git(['config', 'user.name', 'Prueba'], { cwd: clon });
  git(['config', 'core.autocrlf', 'false'], { cwd: clon });
  return { clon, rango: `${base}..HEAD` };
}

const verificar = (clon, rango, extra = {}) =>
  verificarRegistro({ raiz: clon, rango, motor: false, log: silencio, ...extra });

test('registro: el acta llega a su commit, verificada en un clon con su pack de objetos', () => {
  const { clon, rango } = prConActa();
  const r = verificar(clon, rango, { cuerpoPr: `Cuerpo${pie()}` });
  assert.deepEqual(r.fallas, []);
  assert.equal(r.actas, 1);
});

test('registro: un commit asistido sin acta la declara con «ninguno»', () => {
  const { clon, rango } = prConActa();
  commitear(clon, `Sin acta${pie('Registro-IA: ninguno: la sesion no tuvo captura')}`);
  const r = verificar(clon, rango);
  assert.deepEqual(r.fallas, []);
  assert.ok(r.avisos.some((a) => /no tiene acta: la sesion no tuvo captura/.test(a)));
});

// --- Rojos sembrados (REVIEW.md, seccion 3) ----------------------------------------------------

test('rojo sembrado: una curada editada a mano', () => {
  const { clon, rango } = prConActa();
  const curada = path.join(clon, ACTA);
  fs.writeFileSync(curada, fs.readFileSync(curada, 'utf8').replace('"tres"', '"cuatro"'));
  commitear(clon, 'Una curada retocada\n\nSin-IA:\n', ['-f', ACTA]);
  const { fallas } = verificar(clon, rango);
  assert.ok(fallas.some((x) => /no es la que sale de su cruda/.test(x)), fallas.join('\n'));
});

test('rojo sembrado: un commit cuyo contenido ningun arbol del acta tuvo', () => {
  const { clon, rango } = prConActa();
  fs.writeFileSync(path.join(clon, 'a.md'), 'escrito por nadie\n');
  commitear(clon, `Un cambio sin registro${pie()}`, ['a.md']);
  const { fallas } = verificar(clon, rango);
  assert.deepEqual(fallas.length, 1, fallas.join('\n'));
  assert.match(fallas[0], /deja a\.md en \w+, y ningun arbol .* lo tuvo asi: el acta no llega/);
});

test('rojo sembrado: un trailer que apunta a un acta inexistente', () => {
  const { clon, rango } = prConActa();
  commitear(clon, `Otro${pie('Registro-IA: .ia/registros/10/otra.acta.curada.jsonl')}`);
  const { fallas } = verificar(clon, rango);
  assert.ok(fallas.some((x) => /otra\.acta\.curada\.jsonl, que no esta en la PR/.test(x)),
    fallas.join('\n'));
});

test('rojo sembrado: un commit asistido que no declara su acta', () => {
  const { clon, rango } = prConActa();
  commitear(clon, 'Olvido el trailer\n\nAsistido-por-IA: claude-prueba\n');
  const { fallas } = verificar(clon, rango);
  assert.ok(fallas.some((x) => /es asistido y no cita su acta/.test(x)), fallas.join('\n'));
});

test('rojo sembrado: el cuerpo de la PR no repite el trailer y el squash lo perderia', () => {
  const { clon, rango } = prConActa();
  const { fallas } = verificar(clon, rango, { cuerpoPr: 'Un cuerpo sin trailers' });
  assert.deepEqual(fallas, [`el cuerpo de la PR no repite \`Registro-IA: ${ACTA}\`, y el ` +
    'squash lo perderia']);
});

test('citar: agrega el trailer debajo del pie, una sola vez, y solo dentro de Claude Code', () => {
  const mensaje = 'Titulo\n\nCuerpo\n\nAsistido-por-IA: claude-opus-5-5\nCo-Authored-By: X\n';
  const citado = citar(mensaje, { sesion: 'abc', rama: 'feat/222-algo' });
  assert.equal(citado, 'Titulo\n\nCuerpo\n\nAsistido-por-IA: claude-opus-5-5\n' +
    'Registro-IA: .ia/registros/222/abc.acta.curada.jsonl\nCo-Authored-By: X\n');
  assert.equal(citar(citado, { sesion: 'abc', rama: 'feat/222-algo' }), citado);
  assert.equal(citar(mensaje, { sesion: undefined, rama: 'dev' }), mensaje);
  assert.equal(citar('Titulo\n\nSin-IA:\n', { sesion: 'abc', rama: 'dev' }), 'Titulo\n\nSin-IA:\n');
  assert.match(citar(mensaje, { sesion: 'abc', rama: 'dev' }), /registros\/sin-tarea\/abc/);
});

test('registrar: corta la llamada en curso, la que corre el registro, y su captura', () => {
  const f = fabrica()
    .prompt('Registra')
    .llamada('tu1', 'Bash', { command: 'npm test' })
    .resultado('tu1', 'ok')
    .llamada('tu2', 'Bash', { command: 'node $ACTA/registrar-sesion.mjs' });
  const carpeta = carpetaTemporal('sesion');
  const transcript = f.escribir(carpeta);
  const captura = path.join(carpeta, 'captura.jsonl');
  fs.writeFileSync(captura, ['tu1', 'tu1', 'tu2'].map((id, i) => JSON.stringify({
    evento: i === 1 ? 'PostToolUse' : 'PreToolUse', toolUseId: id })).join('\n') + '\n');
  const r = cortarEnCurso({ transcript, captura, destino: carpetaTemporal('corte') });
  assert.equal(r.enCurso, 'tu2');
  const acta = [...compilar({ transcript: r.transcript }).actas.values()][0];
  assert.deepEqual(acta.filter((x) => x.elemento === 'accion').map((a) => a.toolUseId), ['tu1']);
  assert.equal(fs.readFileSync(r.captura, 'utf8').trim().split('\n').length, 2);
});

test('redaccion: el valor de una variable con nombre de secreto no entra al acta', () => {
  const f = fabrica().prompt('Mira el entorno')
    .llamada('tu1', 'Bash', { command: 'env' })
    .resultado('tu1', 'CLAUDE_PID=27644\nCLAUDE_CODE_MESSAGING_TOKEN=b3925485842450ae\n' +
      'GH_TOKEN=ghp_abc API_KEY=xyz');
  const acta = [...compilar({ transcript: f.escribir(carpetaTemporal('s')) }).actas.values()][0];
  const [accion] = acta.filter((x) => x.elemento === 'accion');
  assert.equal(accion.resultado, 'CLAUDE_PID=27644\nCLAUDE_CODE_MESSAGING_TOKEN=<redactado>\n' +
    'GH_TOKEN=<redactado> API_KEY=<redactado>');
});

test('redaccion: la identidad de quien trabajo sale del acta, no del contenido de archivos', () => {
  const identidad = identidadDelEntorno({ email: 'ana@example.com', usuario: 'ana.perez',
    dominio: 'EMPRESA', casa: 'AnaPerezGomez' });
  const f = fabrica().prompt('Lista y edita')
    .llamada('tu1', 'Bash', { command: 'ls -la ~/x && git log -1' })
    .resultado('tu1', 'drwx 1 EMPRESA+ana.perez 0 a\nC:/Users/ANAPER~1/x\n' +
      'Author: Ana <ana@example.com>')
    .llamada('tu2', 'Write', { file_path: 'a.md', content: 'contacto: ana@example.com\n' })
    .resultado('tu2', 'ok')
    .llamada('tu3', 'Bash', { command: 'git reset HEAD~1' })
    .resultado('tu3', 'ok');
  const acta = [...compilar({ transcript: f.escribir(carpetaTemporal('s')), identidad })
    .actas.values()][0];
  const [ls, escribir, reset] = acta.filter((x) => x.elemento === 'accion');
  assert.equal(ls.resultado, 'drwx 1 <usuario> 0 a\n~/x\nAuthor: Ana <<email>>');
  assert.equal(escribir.entrada.content, 'contacto: ana@example.com\n',
    'el contenido del archivo lo publica el commit; redactarlo romperia el motor');
  assert.equal(reset.entrada.command, 'git reset HEAD~1');
});

test('registrar: guarda el plan del issue con su fecha y el sha256 de su texto (#230)', () => {
  const carpeta = carpetaTemporal('plan');
  const leerIssue = (n) => ({ number: Number(n), title: 'Un plan',
    updatedAt: '2026-10-04T12:00:00Z',
    body: '## Fase 1 · Algo\r\n' });
  const destino = guardarPlanDelIssue({ carpeta, tarea: '10', leerIssue });
  const plan = JSON.parse(fs.readFileSync(destino, 'utf8'));
  assert.equal(plan.cuerpo, '## Fase 1 · Algo\n', 'los saltos de linea quedan como en git');
  assert.equal(plan.sha256,
    crypto.createHash('sha256').update('## Fase 1 · Algo\n').digest('hex'));
  assert.equal(plan.actualizado, '2026-10-04T12:00:00Z');
  assert.equal(guardarPlanDelIssue({ carpeta, tarea: 'sin-tarea', leerIssue }), null);
  assert.equal(guardarPlanDelIssue({ carpeta, tarea: '11', leerIssue: () => null }), null);
});
