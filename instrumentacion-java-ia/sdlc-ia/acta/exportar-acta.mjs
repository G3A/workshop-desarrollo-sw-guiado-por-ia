// Exportacion del acta a OCEL 2.0 y a W3C PROV-O (#222, fase 7; PC-15), desde la raiz del repo:
//
//   node $ACTA/exportar-acta.mjs <acta> --formato ocel|prov [--salida <archivo>]
//
// OCEL 2.0 (JSON, https://www.ocel-standard.org): cada accion es un evento cuyo tipo es su
// herramienta, y cada intervencion es un evento `intervencion`. Tarea, sesion, acta, turno, paso,
// paso prescrito, decision, agente, archivo y verificacion negativa son objetos. Las relaciones
// siguen la correspondencia del modelo conceptual (seccion 6): evento-objeto con calificador
// (`paso`, `ejecutor`, `modifica`, `motivada_por`, `afecta`) y objeto-objeto (`realiza`,
// `en_nombre_de`, `pertenece_a`, `contribuye_a`, `registra`).
//
// PROV-O (JSON-LD): sesion, turno, paso, accion e intervencion son prov:Activity anidadas con
// prov:wasInformedBy; los agentes, prov:Person o prov:SoftwareAgent con prov:actedOnBehalfOf; el
// paso prescrito, un prov:Plan por prov:qualifiedAssociation; cada archivo que una accion cambio,
// una prov:Entity generada por ella; la decision, una prov:Entity que influye en la accion; el
// acta, un prov:Bundle, y la curada prov:wasDerivedFrom su cruda.
//
// Sin perdida: ningun estandar tiene donde poner todos los campos del acta (los diffs, los
// intentos previos, la huella). Cada nodo que viene de un elemento del acta lleva ese elemento
// exacto en `registro` y su posicion en `orden`, y `importarOcel` / `importarProv` reconstruyen el
// acta byte a byte. Los atributos nativos (tiempo, herramienta, exito, clase) son los que leen las
// herramientas de mineria de procesos; el registro es lo que garantiza que no se pierde nada.
//
// DETERMINISTA: la misma acta da el mismo archivo. Codigos de salida: 0 escrito, 2 uso incorrecto.
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { leerActa } from './validar-acta.mjs';

const EPOCA = '1970-01-01T00:00:00Z';
const VOCABULARIO = 'https://github.com/G3A/workshop-desarrollo-sw-guiado-por-ia/blob/dev/docs/' +
  'modelo-conceptual-registro-ia.md#';

const cabeceraDe = (registros) => registros.find((r) => r.elemento === 'acta');
const nombreDelActa = (c) => `${c.sesion}.acta.${c.derivadaDe ? 'curada' : 'cruda'}`;
const archivosDe = (a) => [...new Set((a.cambios || []).map((c) => c.archivo))].sort();

// --- OCEL 2.0 ----------------------------------------------------------------------------------

export function exportarOcel(registros) {
  const c = cabeceraDe(registros);
  const objetos = new Map();
  const eventos = [];
  const objeto = (id, type, atributos = [], relaciones = []) => {
    if (!objetos.has(id)) objetos.set(id, { id, type, attributes: [], relationships: [] });
    const o = objetos.get(id);
    o.attributes.push(...atributos.map(([name, value]) => ({ name, time: EPOCA, value })));
    o.relationships.push(...relaciones.map(([objectId, qualifier]) => ({ objectId, qualifier })));
    return id;
  };
  const conRegistro = (r, i) => [['registro', JSON.stringify(r)], ['orden', i]];

  const tarea = objeto(`tarea:${c.tarea}`, 'tarea', [['numero', c.tarea]]);
  const sesion = objeto(`sesion:${c.sesion}`, 'sesion', [], [[tarea, 'contribuye_a']]);
  registros.forEach((r, i) => {
    const sello = conRegistro(r, i);
    if (r.elemento === 'acta') {
      objeto(`acta:${nombreDelActa(r)}`, 'acta', sello, [[sesion, 'registra']]);
    } else if (r.elemento === 'agente') {
      objeto(`agente:${r.id}`, 'agente', [['tipo', r.tipo], ['rol', r.rol], ...sello],
        r.actuoEnNombreDe ? [[`agente:${r.actuoEnNombreDe}`, 'en_nombre_de']] : []);
    } else if (r.elemento === 'turno') {
      objeto(`turno:${r.id}`, 'turno', sello, [[sesion, 'pertenece_a']]);
    } else if (r.elemento === 'paso') {
      const rel = [[`turno:${r.turno}`, 'pertenece_a']];
      if (r.pasoPrescrito) {
        const p = `prescrito:${r.pasoPrescrito.skill}:${r.pasoPrescrito.letra}`;
        objeto(p, 'paso_prescrito', [['skill', r.pasoPrescrito.skill],
          ['letra', r.pasoPrescrito.letra]]);
        rel.push([p, 'realiza']);
      }
      objeto(`paso:${r.id}`, 'paso', [['procedencia', r.procedencia], ...sello], rel);
    } else if (r.elemento === 'decision') {
      objeto(`decision:${r.id}`, 'decision', sello, [[`turno:${r.turno}`, 'pertenece_a']]);
    } else if (r.elemento === 'verificacion_negativa') {
      objeto(`verificacion:${r.id}`, 'verificacion_negativa', sello,
        [[`agente:${r.agente}`, 'ejecutor']]);
    } else if (r.elemento === 'accion') {
      const rel = [[`paso:${r.paso}`, 'paso'], [`agente:${r.agente}`, 'ejecutor']];
      for (const f of archivosDe(r)) rel.push([objeto(`archivo:${f}`, 'archivo'), 'modifica']);
      for (const d of registros.filter((x) => x.elemento === 'decision' &&
        x.motiva.includes(r.id))) rel.push([`decision:${d.id}`, 'motivada_por']);
      eventos.push({ id: `accion:${r.id}`, type: r.herramienta,
        time: r.momento || c.inicio || EPOCA,
        attributes: [['exito', r.exito], ['claseDeterminismo', r.claseDeterminismo], ...sello]
          .filter(([, v]) => v !== null && v !== undefined)
          .map(([name, value]) => ({ name, value })),
        relationships: rel.map(([objectId, qualifier]) => ({ objectId, qualifier })) });
    } else if (r.elemento === 'intervencion') {
      const rel = [[`paso:${r.paso}`, 'afecta'], [`agente:${r.agente}`, 'ejecutor']];
      eventos.push({ id: `intervencion:${r.id}`, type: 'intervencion', time: r.momento ||
        c.inicio || EPOCA, attributes: [['tipo', r.tipo], ...sello]
        .map(([name, value]) => ({ name, value })),
      relationships: rel.map(([objectId, qualifier]) => ({ objectId, qualifier })) });
    }
  });

  const tipoDe = (v) => (typeof v === 'boolean' ? 'boolean' : Number.isInteger(v) ? 'integer'
    : typeof v === 'number' ? 'float' : 'string');
  const declarar = (lista) => {
    const tipos = new Map();
    for (const x of lista) {
      if (!tipos.has(x.type)) tipos.set(x.type, new Map());
      for (const a of x.attributes) tipos.get(x.type).set(a.name, tipoDe(a.value));
    }
    return [...tipos].sort(([a], [b]) => (a < b ? -1 : 1)).map(([name, attrs]) => ({ name,
      attributes: [...attrs].sort(([a], [b]) => (a < b ? -1 : 1))
        .map(([n, type]) => ({ name: n, type })) }));
  };
  const lista = [...objetos.values()].sort((a, b) => (a.id < b.id ? -1 : 1));
  return { objectTypes: declarar(lista), eventTypes: declarar(eventos), objects: lista,
    events: eventos };
}

export function importarOcel(ocel) {
  const conRegistro = [...ocel.objects, ...ocel.events].map((x) => {
    const valor = (n) => x.attributes.find((a) => a.name === n)?.value;
    return { registro: valor('registro'), orden: valor('orden') };
  }).filter((x) => x.registro !== undefined);
  return conRegistro.sort((a, b) => a.orden - b.orden).map((x) => JSON.parse(x.registro));
}

// --- PROV-O (JSON-LD) --------------------------------------------------------------------------

export function exportarProv(registros) {
  const c = cabeceraDe(registros);
  const grafo = [];
  const nodo = (n) => {
    grafo.push(n);
    return n['@id'];
  };
  const sello = (r, i) => ({ 'acta:registro': JSON.stringify(r), 'acta:orden': i });
  const sesion = nodo({ '@id': `sesion:${c.sesion}`, '@type': 'prov:Activity',
    'acta:tarea': c.tarea });
  registros.forEach((r, i) => {
    const s = sello(r, i);
    const id = `${r.elemento}:${r.id ?? nombreDelActa(r)}`;
    if (r.elemento === 'acta') {
      nodo({ '@id': `acta:${nombreDelActa(r)}`, '@type': 'prov:Bundle', ...s,
        ...(r.derivadaDe ? { 'prov:wasDerivedFrom': { '@id': `acta:${c.sesion}.acta.cruda` } }
          : {}),
        ...(r.inicio ? { 'prov:startedAtTime': r.inicio } : {}),
        ...(r.fin ? { 'prov:endedAtTime': r.fin } : {}) });
      // La cruda es otro archivo: entra como un Bundle sin registro, con el sha256 de sus bytes,
      // para que el grafo no apunte afuera y diga exactamente de que deriva.
      if (r.derivadaDe) {
        nodo({ '@id': `acta:${c.sesion}.acta.cruda`, '@type': 'prov:Bundle',
          'acta:sha256': r.derivadaDe.sha256 });
      }
    } else if (r.elemento === 'agente') {
      nodo({ '@id': id, '@type': r.tipo === 'persona' ? 'prov:Person' : 'prov:SoftwareAgent',
        ...s, ...(r.actuoEnNombreDe ? { 'prov:actedOnBehalfOf':
          { '@id': `agente:${r.actuoEnNombreDe}` } } : {}) });
    } else if (r.elemento === 'turno') {
      nodo({ '@id': id, '@type': 'prov:Activity', ...s, 'prov:wasInformedBy': { '@id': sesion },
        ...(r.momento ? { 'prov:startedAtTime': r.momento } : {}) });
    } else if (r.elemento === 'paso') {
      const plan = r.pasoPrescrito
        ? nodo({ '@id': `plan:${r.pasoPrescrito.skill}:${r.pasoPrescrito.letra}:${r.id}`,
          '@type': 'prov:Plan' }) : null;
      nodo({ '@id': id, '@type': 'prov:Activity', ...s,
        'prov:wasInformedBy': { '@id': `turno:${r.turno}` },
        ...(plan ? { 'prov:qualifiedAssociation': { '@type': 'prov:Association',
          'prov:agent': { '@id': 'agente:orquestador' }, 'prov:hadPlan': { '@id': plan } } }
          : {}) });
    } else if (r.elemento === 'decision') {
      nodo({ '@id': id, '@type': 'prov:Entity', ...s });
    } else if (r.elemento === 'verificacion_negativa') {
      nodo({ '@id': id, '@type': 'prov:Entity', ...s,
        'prov:wasAttributedTo': { '@id': `agente:${r.agente}` } });
    } else if (r.elemento === 'accion') {
      const influyen = registros.filter((x) => x.elemento === 'decision' &&
        x.motiva.includes(r.id)).map((d) => ({ '@id': `decision:${d.id}` }));
      for (const f of archivosDe(r)) {
        nodo({ '@id': `archivo:${f}@${r.id}`, '@type': 'prov:Entity', 'acta:archivo': f,
          'prov:wasGeneratedBy': { '@id': id } });
      }
      nodo({ '@id': id, '@type': 'prov:Activity', ...s,
        'prov:wasInformedBy': { '@id': `paso:${r.paso}` },
        'prov:wasAssociatedWith': { '@id': `agente:${r.agente}` },
        ...(r.momento ? { 'prov:startedAtTime': r.momento } : {}),
        ...(influyen.length ? { 'prov:wasInfluencedBy': influyen } : {}) });
    } else if (r.elemento === 'intervencion') {
      nodo({ '@id': id, '@type': 'prov:Activity', ...s,
        'prov:wasInformedBy': { '@id': `paso:${r.paso}` },
        'prov:wasAssociatedWith': { '@id': `agente:${r.agente}` },
        ...(r.momento ? { 'prov:startedAtTime': r.momento } : {}) });
    }
  });
  return {
    '@context': { prov: 'http://www.w3.org/ns/prov#', acta: VOCABULARIO,
      'prov:startedAtTime': { '@type': 'http://www.w3.org/2001/XMLSchema#dateTime' },
      'prov:endedAtTime': { '@type': 'http://www.w3.org/2001/XMLSchema#dateTime' } },
    '@graph': grafo,
  };
}

export function importarProv(prov) {
  return prov['@graph'].filter((n) => n['acta:registro'] !== undefined)
    .sort((a, b) => a['acta:orden'] - b['acta:orden'])
    .map((n) => JSON.parse(n['acta:registro']));
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  const opcion = (n) => (args.includes(n) ? args[args.indexOf(n) + 1] : undefined);
  const [acta] = args;
  const formato = opcion('--formato');
  if (!acta || !fs.existsSync(acta) || !['ocel', 'prov'].includes(formato)) {
    console.error('Uso: node $ACTA/exportar-acta.mjs <acta> --formato ocel|prov ' +
      '[--salida <archivo>]');
    process.exitCode = 2;
  } else {
    const registros = leerActa(acta);
    const salida = JSON.stringify(formato === 'ocel' ? exportarOcel(registros)
      : exportarProv(registros), null, 2) + '\n';
    if (opcion('--salida')) fs.writeFileSync(opcion('--salida'), salida);
    else process.stdout.write(salida);
  }
}
