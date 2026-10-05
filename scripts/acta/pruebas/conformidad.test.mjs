// Pruebas de la conformidad del acta (#222, fase 4):
//   node --test scripts/acta/pruebas/conformidad.test.mjs
//
// De punta a punta con el instructivo REAL de debt-triage: el repo de prueba lleva una copia de su
// SKILL.md tal como esta en este commit, la huella lo registra por su blob, y la conformidad lo lee
// de ahi. Si la skill cambia sus fases, estas pruebas lo ven.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { compilar, serializar } from '../compilar-acta.mjs';
import { conformidad, pasosDelPlan, pasosPrescritos } from '../conformidad.mjs';
import { curar } from '../curar-acta.mjs';
import { git } from '../git.mjs';
import { carpetaTemporal, fabrica, marcaDePaso, PLAN_DEL_10, repoTemporal, sesionDelPlan,
  sesionMarcada } from './fabrica.mjs';
import { leerActa } from '../validar-acta.mjs';

const RUTA = 'instrumentacion-java-ia/sdlc-ia/skills/debt-triage/SKILL.md';
const RAIZ = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const INSTRUCTIVO = fs.readFileSync(path.join(RAIZ, RUTA), 'utf8');

function actaCon(f, { conInstructivo = true } = {}) {
  const raiz = repoTemporal(conInstructivo ? { [RUTA]: INSTRUCTIVO } : { 'a.md': 'a\n' });
  const head = git(['rev-parse', 'HEAD'], { cwd: raiz }).trim();
  const transcript = f.escribir(carpetaTemporal('sesion'));
  const { actas } = compilar({ transcript, raizRepo: raiz,
    captura: [{ evento: 'SessionStart', head, arbol: null }] });
  return { acta: [...actas.values()][0], raiz };
}

test('instructivo: las cinco fases de debt-triage, en orden y con su titulo', () => {
  assert.deepEqual(pasosPrescritos(INSTRUCTIVO).map((p) => [p.letra, p.orden]),
    [['1', 1], ['2', 2], ['3', 3], ['4', 4], ['5', 5]]);
  assert.equal(pasosPrescritos(INSTRUCTIVO)[1].titulo, 'Pull and group');
});

test('conformidad: lo ejecutado contra el instructivo de la huella, con lo omitido', () => {
  const { acta, raiz } = actaCon(sesionMarcada());
  const r = conformidad(acta, { raizRepo: raiz });
  assert.equal(r.estado, 'con datos');
  assert.equal(r.fase, 2);
  const [debt] = r.actividades;
  assert.equal(debt.skill, 'debt-triage');
  assert.equal(debt.instructivo.ruta, RUTA);
  assert.deepEqual(debt.ejecutados, ['1', '2', '3'], 'retomar la Phase 2 no la repite');
  assert.deepEqual(debt.omitidos, ['4', '5']);
  assert.deepEqual([debt.repetidos, debt.fueraDeOrden, debt.noPrescritos], [[], [], []]);
  assert.deepEqual(r.accionesSinPaso, ['a1'], 'el Bash de antes del primer marcador');
});

test('conformidad: volver a un paso es repetirlo y salir de orden; uno inventado se nombra', () => {
  const f = sesionMarcada();
  for (const [i, letra] of ['4', '5', '3', '5', '9'].entries()) {
    f.texto(marcaDePaso(letra)).llamada(`tu-${i}`, 'Bash',
      { command: `echo ${letra}` }).resultado(`tu-${i}`, 'ok');
  }
  const { acta, raiz } = actaCon(f);
  const [debt] = conformidad(acta, { raizRepo: raiz }).actividades;
  assert.deepEqual(debt.ejecutados, ['1', '2', '3', '4', '5', '3', '5', '9']);
  assert.deepEqual(debt.omitidos, []);
  assert.deepEqual(debt.repetidos, ['3', '5']);
  assert.deepEqual(debt.fueraDeOrden, [{ letra: '3', despuesDe: '5' }]);
  assert.deepEqual(debt.noPrescritos, ['9']);
});

test('conformidad: la curada conserva los pasos marcados y da la misma secuencia', () => {
  const { acta, raiz } = actaCon(sesionMarcada());
  const curada = curar(serializar(acta));
  const [debt] = conformidad(curada, { raizRepo: raiz }).actividades;
  assert.deepEqual(debt.ejecutados, ['1', '2', '3']);
});

test('sin datos: un acta sin marcadores no infiere pasos ni acciones sin paso', () => {
  const { acta, raiz } = actaCon(fabrica().prompt('Arregla').llamada('tu1', 'Bash',
    { command: 'npm test' }).resultado('tu1', 'ok'));
  const r = conformidad(acta, { raizRepo: raiz });
  assert.deepEqual([r.estado, r.actividades, r.accionesSinPaso], ['sin datos', [], null]);
});

test('sin instructivo: dice que ejecuto, y que no tiene contra que compararlo', () => {
  const { acta, raiz } = actaCon(sesionMarcada(), { conInstructivo: false });
  const [debt] = conformidad(acta, { raizRepo: raiz }).actividades;
  assert.deepEqual(debt.ejecutados, ['1', '2', '3']);
  assert.equal(debt.omitidos, null);
  assert.match(debt.motivo, /no registra su instructivo/);
});

test('plan de un issue: sus pasos son los encabezados numerados fuera de codigo', () => {
  assert.deepEqual(pasosDelPlan(PLAN_DEL_10), [
    { letra: '1', titulo: 'Catalogo de actividades', orden: 1 },
    { letra: '2', titulo: 'La ficha separa actividad y herramienta', orden: 2 },
  ]);
  assert.deepEqual(pasosDelPlan('## 5 · Actividad declarada').map((p) => p.letra), ['5']);
});

test('plan de un issue: el instructivo es el plan guardado, con su fecha y su sha256', () => {
  const { curada } = sesionDelPlan();
  const plan = JSON.parse(fs.readFileSync(path.join(path.dirname(curada), 'plan-del-issue.json')));
  const r = conformidad(leerActa(curada), { planDelIssue: plan });
  const [a] = r.actividades;
  assert.equal(a.skill, 'plan-de-issue');
  assert.deepEqual(a.instructivo,
    { ruta: 'issue #10', actualizado: plan.actualizado, sha256: 'x' });
  assert.deepEqual(a.ejecutados, ['1', '2']);
  assert.deepEqual(a.omitidos, []);
  const sinPlan = conformidad(leerActa(curada)).actividades[0];
  assert.equal(sinPlan.prescritos, null);
  assert.match(sinPlan.motivo, /no se guardo el plan del issue/);
});

// Las traducciones de proceso.json siguen a los SKILL.md (#230): cada skill del catalogo tiene la
// suya, con un paso por encabezado del instructivo, ni uno de mas ni de menos. requirement-to-spec
// lista sus fases en una tabla que la conformidad todavia no lee (frente 2 del #230).
test('proceso.json: cada skill traduce exactamente los pasos de su SKILL.md', () => {
  const proceso = JSON.parse(fs.readFileSync(path.join(RAIZ, 'scripts/acta/proceso.json'), 'utf8'));
  const skills = path.join(RAIZ, 'instrumentacion-java-ia/sdlc-ia/skills');
  const enDisco = fs.readdirSync(skills).sort();
  const delCatalogo = Object.keys(proceso.actividades)
    .filter((k) => !proceso.actividades[k].sinSkill).sort();
  assert.deepEqual(delCatalogo, enDisco, 'el catalogo nombra las skills del plugin');
  for (const skill of enDisco) {
    const letras = pasosPrescritos(fs.readFileSync(path.join(skills, skill, 'SKILL.md'), 'utf8'))
      .map((p) => p.letra);
    const traducidas = Object.keys(proceso.traducciones[skill] || {});
    assert.ok(traducidas.length, `${skill} no tiene traduccion de sus pasos`);
    if (skill === 'requirement-to-spec-java') {
      assert.deepEqual(letras, [], 'si ya se leen sus fases, esta excepcion sobra');
      continue;
    }
    assert.deepEqual(traducidas, letras, `${skill}: los pasos traducidos no son los del SKILL.md`);
  }
});
