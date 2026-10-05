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
import { bucles, conformidad, pasosDeLaSkill, pasosDelPlan, pasosPrescritos }
  from '../conformidad.mjs';
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

function debtTriageCon(letras) {
  const f = sesionMarcada();
  for (const [i, letra] of letras.entries()) {
    f.texto(marcaDePaso(letra)).llamada(`tu-${i}`, 'Bash',
      { command: `echo ${letra}` }).resultado(`tu-${i}`, 'ok');
  }
  const { acta, raiz } = actaCon(f);
  return conformidad(acta, { raizRepo: raiz }).actividades[0];
}

test('conformidad: volver a un paso no declarado es repetirlo y salir de orden', () => {
  const debt = debtTriageCon(['4', '2', '5', '9']);
  assert.deepEqual(debt.ejecutados, ['1', '2', '3', '4', '2', '5', '9']);
  assert.deepEqual(debt.omitidos, []);
  assert.deepEqual(debt.repetidos, ['2']);
  assert.deepEqual(debt.fueraDeOrden, [{ letra: '2', despuesDe: '4' }]);
  assert.deepEqual(debt.iteraciones, []);
  assert.deepEqual(debt.noPrescritos, ['9'], 'un paso inventado se nombra');
});

// #235: debt-triage declara su regreso de la Phase 5 a la 3, cuando falla una fila del gate.
test('conformidad: la vuelta declarada de la Phase 5 a la 3 es una iteracion', () => {
  assert.deepEqual(bucles(INSTRUCTIVO), [{ desde: '5', hacia: '3' }]);
  const debt = debtTriageCon(['4', '5', '3', '4', '5']);
  assert.deepEqual(debt.ejecutados, ['1', '2', '3', '4', '5', '3', '4', '5']);
  assert.deepEqual(debt.omitidos, []);
  assert.deepEqual(debt.repetidos, []);
  assert.deepEqual(debt.fueraDeOrden, []);
  assert.deepEqual(debt.iteraciones, [{ desde: '5', hacia: '3', veces: 1 }]);
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

// Las nueve skills marcan sus pasos (#230, frente 1) y proceso.json las sigue: cada skill del
// plugin esta en el catalogo, su SKILL.md tiene pasos que la conformidad lee, el marcador declara
// la fase del metodo que dice el catalogo, y la traduccion cubre esos pasos, ni uno mas ni menos.
test('proceso.json y los SKILL.md: marcador, fase del metodo y traduccion de cada paso', () => {
  const proceso = JSON.parse(fs.readFileSync(path.join(RAIZ, 'scripts/acta/proceso.json'), 'utf8'));
  const skills = path.join(RAIZ, 'instrumentacion-java-ia/sdlc-ia/skills');
  const enDisco = fs.readdirSync(skills).sort();
  const delCatalogo = Object.keys(proceso.actividades)
    .filter((k) => !proceso.actividades[k].sinSkill).sort();
  assert.deepEqual(delCatalogo, enDisco, 'el catalogo nombra las skills del plugin');
  for (const skill of enDisco) {
    const texto = fs.readFileSync(path.join(skills, skill, 'SKILL.md'), 'utf8');
    const leerReferencia = (archivo) => {
      const r = path.join(skills, skill, 'references', archivo);
      return fs.existsSync(r) ? fs.readFileSync(r, 'utf8') : null;
    };
    const { pasos, faltan } = pasosDeLaSkill(texto, leerReferencia);
    assert.deepEqual(faltan, [], `${skill}: su SKILL.md nombra references/ que no existen`);
    const letras = pasos.map((p) => p.letra);
    assert.ok(letras.length, `${skill}: la conformidad no lee ningun paso de su SKILL.md`);
    const fase = proceso.actividades[skill].fase;
    const marca = new RegExp(`\\[sdlc-ia:step skill=${skill} step=\\w+ method-phase=${fase}\\]`);
    assert.match(texto, marca,
      `${skill}: el SKILL.md no trae su marcador con la fase ${fase} del metodo`);
    assert.deepEqual(Object.keys(proceso.traducciones[skill] || {}), letras,
      `${skill}: los pasos traducidos no son los del SKILL.md`);
  }
});

// github-plan-build (#230, frente 2): sus Phase 0 a 4 en el SKILL.md y sus Step A a K en los
// references/, con el SKILL.md y los references reales en el repo de la sesion.
const SKILL_GPB = 'instrumentacion-java-ia/sdlc-ia/skills/github-plan-build';
const REFS_GPB = ['build-loop.md', 'build-loop-execute.md'];

function actaDeGithubPlanBuild(letras, { conReferencias = true } = {}) {
  const archivos = { [`${SKILL_GPB}/SKILL.md`]:
    fs.readFileSync(path.join(RAIZ, SKILL_GPB, 'SKILL.md'), 'utf8') };
  if (conReferencias) {
    for (const r of REFS_GPB) {
      archivos[`${SKILL_GPB}/references/${r}`] =
        fs.readFileSync(path.join(RAIZ, SKILL_GPB, 'references', r), 'utf8');
    }
  }
  const f = fabrica().prompt('<command-name>/sdlc-ia:github-plan-build</command-name>' +
    '<command-args>10</command-args>');
  for (const [i, letra] of letras.entries()) {
    f.texto(`[sdlc-ia:step skill=github-plan-build step=${letra} method-phase=3]`)
      .llamada(`tu-${i}`, 'Bash', { command: `echo ${letra}` }).resultado(`tu-${i}`, 'ok');
  }
  const raiz = repoTemporal(archivos);
  const head = git(['rev-parse', 'HEAD'], { cwd: raiz }).trim();
  const transcript = f.escribir(carpetaTemporal('sesion'));
  const { actas } = compilar({ transcript, raizRepo: raiz,
    captura: [{ evento: 'SessionStart', head, arbol: null }] });
  const acta = [...actas.values()][0];
  return { acta, actividad: conformidad(acta, { raizRepo: raiz }).actividades[0] };
}

const FASES = ['0', '1', '2', '3', '4'];

test('references: los Step A..K de github-plan-build, anidados bajo la Phase 4 y en orden', () => {
  const { acta, actividad } = actaDeGithubPlanBuild(FASES);
  assert.ok(acta[0].huella.documentos.some((d) => d.ruta.endsWith('references/build-loop.md')),
    'la huella registra los references aunque la sesion no los leyo');
  assert.deepEqual(actividad.prescritos.map((p) => p.letra),
    [...FASES, ...'ABCDEFGHIJK']);
  assert.deepEqual(actividad.prescritos.filter((p) => p.dentroDe === '4').map((p) => p.archivo),
    [...Array(5).fill('build-loop.md'), ...Array(6).fill('build-loop-execute.md')]);
  assert.deepEqual(actividad.omitidos, [...'ABCDEFGHIJK']);
});

test('references: la vuelta declarada de I a G es una iteracion, no un paso repetido', () => {
  const secuencia = [...FASES, ...'ABCDEFGHI', ...'GHI', ...'GHI', 'J', 'K'];
  const { actividad } = actaDeGithubPlanBuild(secuencia);
  assert.deepEqual(actividad.omitidos, []);
  assert.deepEqual(actividad.repetidos, []);
  assert.deepEqual(actividad.fueraDeOrden, []);
  assert.deepEqual(actividad.iteraciones, [{ desde: 'I', hacia: 'G', veces: 2 }]);
});

test('references: un regreso no declarado sigue siendo repetido y fuera de orden', () => {
  const { actividad } = actaDeGithubPlanBuild([...FASES, ...'ABCDEF', 'B', 'G']);
  assert.deepEqual(actividad.repetidos, ['B']);
  assert.deepEqual(actividad.fueraDeOrden, [{ letra: 'B', despuesDe: 'F' }]);
  assert.deepEqual(actividad.iteraciones, []);
  assert.deepEqual(bucles(fs.readFileSync(path.join(RAIZ, SKILL_GPB, 'SKILL.md'), 'utf8')),
    [{ desde: 'I', hacia: 'G' }]);
});

test('references: si faltan en el repo, se dice cuales y no se inventan pasos', () => {
  const { actividad } = actaDeGithubPlanBuild(FASES, { conReferencias: false });
  assert.deepEqual(actividad.prescritos.map((p) => p.letra), FASES);
  assert.deepEqual(actividad.referenciasFaltantes, REFS_GPB);
});
