// Pruebas de la exportacion a OCEL 2.0 y PROV-O (#222, fase 7; PC-15):
//   node --test $ACTA/pruebas/exportar-acta.test.mjs
//
// Sin perdida: cada escenario de la fabrica, crudo y curado, sale y vuelve byte a byte por los dos
// formatos. Y cada formato tiene la forma de su estandar: tipos declarados, relaciones que apuntan
// a objetos que existen, tiempos ISO 8601, nodos con tipos de PROV.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { compilar, serializar } from '../compilar-acta.mjs';
import { curarYEscribir } from '../curar-acta.mjs';
import { exportarOcel, exportarProv, importarOcel, importarProv } from '../exportar-acta.mjs';
import { leerActa } from '../validar-acta.mjs';
import { carpetaTemporal, fabrica, sesionConHook, sesionConSubagente, sesionMarcada,
  sesionParaElMotor } from './fabrica.mjs';

const silencio = { log: () => {}, error: () => {} };

// La cruda y, si sale, la curada de un escenario.
function actasDe({ f, captura = [], raiz = null, conSubagente = null }) {
  const carpeta = carpetaTemporal('sesion');
  const transcript = f.escribir(carpeta);
  if (conSubagente) conSubagente(transcript.replace(/\.jsonl$/, ''));
  const [cruda] = [...compilar({ transcript, captura, raizRepo: raiz }).actas.values()];
  const actas = [cruda];
  if (raiz) {
    const archivo = path.join(carpetaTemporal('registros'), 'sesion-1.acta.cruda.jsonl');
    fs.writeFileSync(archivo, serializar(cruda));
    if (curarYEscribir({ cruda: archivo, raizRepo: raiz, log: silencio }) === 0) {
      actas.push(leerActa(archivo.replace('.cruda.', '.curada.')));
    }
  }
  return actas;
}

const RECHAZO = "The user doesn't want to proceed with this tool use. The tool use was rejected.";

function escenarios() {
  return [
    ['hook', actasDe(sesionConHook())],
    ['subagente', actasDe(sesionConSubagente())],
    ['motor', actasDe(sesionParaElMotor())],
    ['marcada', actasDe({ f: sesionMarcada() })],
    ['intervenciones', actasDe({ f: fabrica()
      .prompt('<bash-input>git status</bash-input>')
      .prompt('<bash-stdout>limpio</bash-stdout><bash-stderr></bash-stderr>')
      .prompt('Borra')
      .llamada('tu1', 'Bash', { command: 'git push origin --delete x' })
      .resultado('tu1', RECHAZO, { error: true })
      .prompt('[Request interrupted by user]')
      .prompt('Mejor no') })],
  ];
}

test('sin perdida: cada acta, cruda y curada, vuelve byte a byte de OCEL y de PROV-O', () => {
  let n = 0;
  for (const [nombre, actas] of escenarios()) {
    for (const acta of actas) {
      const original = serializar(acta);
      assert.equal(serializar(importarOcel(exportarOcel(acta))), original, `OCEL, ${nombre}`);
      assert.equal(serializar(importarProv(exportarProv(acta))), original, `PROV-O, ${nombre}`);
      n++;
    }
  }
  assert.ok(n >= 8, `se esperaban crudas y curadas, hubo ${n} actas`);
});

test('OCEL 2.0: tipos declarados, relaciones a objetos que existen y tiempos ISO 8601', () => {
  for (const [nombre, actas] of escenarios()) {
    for (const acta of actas) {
      const ocel = exportarOcel(acta);
      const ids = new Set(ocel.objects.map((o) => o.id));
      const declarados = (tipos) => new Map(tipos.map((t) => [t.name,
        new Map(t.attributes.map((a) => [a.name, a.type]))]));
      const tiposObjeto = declarados(ocel.objectTypes);
      const tiposEvento = declarados(ocel.eventTypes);
      const tipoDe = (v) => (typeof v === 'boolean' ? 'boolean' : Number.isInteger(v) ? 'integer'
        : typeof v === 'number' ? 'float' : 'string');
      for (const [lista, tipos] of [[ocel.objects, tiposObjeto], [ocel.events, tiposEvento]]) {
        for (const x of lista) {
          assert.ok(tipos.has(x.type), `${nombre}: el tipo ${x.type} no esta declarado`);
          for (const a of x.attributes) {
            assert.equal(tipos.get(x.type).get(a.name), tipoDe(a.value), `${nombre}: ${a.name}`);
          }
          for (const r of x.relationships) {
            assert.ok(ids.has(r.objectId), `${nombre}: ${x.id} apunta a ${r.objectId}`);
            assert.match(r.qualifier, /^[a-z_]+$/);
          }
        }
      }
      for (const e of ocel.events) assert.ok(!Number.isNaN(Date.parse(e.time)), e.time);
      const acciones = acta.filter((r) => r.elemento === 'accion');
      assert.equal(ocel.events.filter((e) => e.id.startsWith('accion:')).length, acciones.length);
    }
  }
});

test('OCEL 2.0: la accion lleva su paso, su ejecutor y los archivos que modifica', () => {
  const [cruda] = actasDe(sesionParaElMotor());
  const ocel = exportarOcel(cruda);
  const edit = ocel.events.find((e) => e.type === 'Edit');
  const rel = edit.relationships.map((r) => `${r.qualifier}=${r.objectId}`).sort();
  assert.deepEqual(rel, ['ejecutor=agente:orquestador', 'modifica=archivo:a.md',
    'paso=paso:t1.p1']);
  assert.deepEqual(edit.attributes.filter((a) => a.name !== 'registro' && a.name !== 'orden'),
    [{ name: 'exito', value: true }, { name: 'claseDeterminismo', value: 'pura' }]);
  const prescrito = exportarOcel(actasDe({ f: sesionMarcada() })[0]).objects
    .find((o) => o.id === 'paso:t1.p2').relationships;
  assert.deepEqual(prescrito.map((r) => r.qualifier).sort(), ['pertenece_a', 'realiza']);
});

test('PROV-O: personas y agentes de software, en nombre de quien, y referencias que existen',
  () => {
  for (const [nombre, actas] of escenarios()) {
    for (const acta of actas) {
      const prov = exportarProv(acta);
      const nodos = new Map(prov['@graph'].map((n) => [n['@id'], n]));
      assert.equal(nodos.size, prov['@graph'].length, `${nombre}: ids repetidos`);
      const permitidos = new Set(['prov:Activity', 'prov:Entity', 'prov:Plan', 'prov:Bundle',
        'prov:Person', 'prov:SoftwareAgent']);
      for (const n of prov['@graph']) {
        assert.ok(permitidos.has(n['@type']), `${nombre}: ${n['@type']}`);
        const refs = JSON.stringify(n).match(/"@id":"[^"]+"/g).slice(1)
          .map((x) => x.slice(7, -1));
        for (const r of refs) assert.ok(nodos.has(r), `${nombre}: ${n['@id']} apunta a ${r}`);
      }
    }
  }
  const [cruda] = actasDe(sesionConSubagente());
  const nodos = exportarProv(cruda)['@graph'];
  const sub = nodos.find((n) => n['@id'].startsWith('agente:subagente:'));
  assert.equal(sub['@type'], 'prov:SoftwareAgent');
  assert.deepEqual(sub['prov:actedOnBehalfOf'], { '@id': 'agente:orquestador' });
  const [, curada] = actasDe(sesionParaElMotor());
  const grafo = exportarProv(curada)['@graph'];
  const bundle = grafo.find((n) => n['@id'] === 'acta:sesion-1.acta.curada');
  const cruda2 = grafo.find((n) => n['@id'] === 'acta:sesion-1.acta.cruda');
  assert.deepEqual([bundle['@type'], bundle['prov:wasDerivedFrom']['@id']],
    ['prov:Bundle', cruda2['@id']]);
  assert.match(cruda2['acta:sha256'], /^[0-9a-f]{64}$/);
});

test('determinista: la misma acta da la misma exportacion', () => {
  const [, curada] = actasDe(sesionParaElMotor());
  assert.equal(JSON.stringify(exportarOcel(curada)), JSON.stringify(exportarOcel(curada)));
  assert.equal(JSON.stringify(exportarProv(curada)), JSON.stringify(exportarProv(curada)));
});
