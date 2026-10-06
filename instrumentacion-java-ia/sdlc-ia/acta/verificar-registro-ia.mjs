// Sensor del trailer Registro-IA (#222, fase 3; ADR-0006), desde la raiz del repo:
//
//   node $ACTA/verificar-registro-ia.mjs --rango <A..B> [--cuerpo-pr <archivo>]
//        [--sin-motor]
//
// Cada commit del rango que declara `Asistido-por-IA:` cita su acta con `Registro-IA:`: la ruta de
// la curada, o `ninguno: <motivo>`. Se exige declarar, igual que el pie de asistencia: un acta que
// no salio (una curacion detenida, una sesion sin captura) se dice, no se calla. Por cada acta
// citada, el sensor falla si:
//
// 1. La ruta no esta en el arbol de HEAD, o le falta su cruda.
// 2. La curada no es la que sale de su cruda (invariante I8): editada a mano, o derivada de otra
//    cruda. Antes desempaca <sesion>.acta.objetos.pack, que trae los arboles de la sesion.
// 3. El commit deja un archivo con un contenido que ningun arbol del acta tuvo: el acta no llega
//    al commit. Se compara archivo por archivo, no el arbol entero, porque un commit puede llevar
//    solo una parte de lo que habia en el arbol de trabajo. .ia/ queda fuera.
// 4. El motor (reejecutar-acta.mjs) no reproduce una accion aplicada (Edit, Write, hook, residuo)
//    o se detiene. Un comando ejecutado que diverge NO falla: se avisa. En el contenedor no hay
//    red, ni Maven, ni Docker, y esas divergencias son del entorno, no del registro (ADR-0006).
// 5. Con --cuerpo-pr, el cuerpo de la PR no repite cada Registro-IA de sus commits: el merge a dev
//    es por squash con ese cuerpo como mensaje, y lo que no este ahi se pierde.
//
// Codigos de salida: 0 en verde, 1 alguna falla, 2 uso incorrecto.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { ENVOLTORIOS } from './clasificar.mjs';
import { comprobarCurada } from './curar-acta.mjs';
import { git, raizDelRepo } from './git.mjs';
import { NoSePudo, reejecutar } from './reejecutar-acta.mjs';
import { leerActa } from './validar-acta.mjs';

const CON_IA = /^Asistido-por-IA: \S/m;
const EXENTO = /^(Merge |fixup!|squash!|amend!)/;
const NUL = String.fromCharCode(0);
const SUFIJO = '.acta.curada.jsonl';

// Los commits del rango, sin merges, con su mensaje.
function commitsDe(raiz, rango) {
  const salida = git(['log', '--no-merges', `--format=%H%x00%B%x00`, rango], { cwd: raiz });
  const partes = salida.split(NUL);
  const commits = [];
  for (let i = 0; i + 1 < partes.length; i += 2) {
    commits.push({ sha: partes[i].trim(), mensaje: partes[i + 1] });
  }
  return commits.filter((c) => c.sha && !EXENTO.test(c.mensaje));
}

export function citasDe(mensaje) {
  return [...mensaje.matchAll(/^Registro-IA:[ \t]*(.*)$/gm)].map((m) => m[1].trim());
}

// Los archivos que el commit cambia, fuera de .ia/, con su blob en el commit (null si lo borra).
function cambiosDelCommit(raiz, sha) {
  const nombres = git(['diff-tree', '-r', '--root', '--no-commit-id', '--name-only',
    '--no-renames', sha, '--', '.', ':(exclude).ia'], { cwd: raiz }).split('\n').filter(Boolean);
  return nombres.map((ruta) => ({ ruta, blob: blobEn(raiz, sha, ruta) }));
}

function blobEn(raiz, arbol, ruta) {
  const r = git(['rev-parse', '--verify', '--quiet', `${arbol}:${ruta}`], { cwd: raiz,
    permitirFallo: true });
  return r ? r.trim() : null;
}

// Los blobs de `rutas` en cada arbol de la cadena de la curada.
function contenidosDeLaCadena(raiz, registros, rutas) {
  const cabecera = registros.find((r) => r.elemento === 'acta');
  const arboles = new Set([cabecera.arbolBase, cabecera.arbolFinal].filter(Boolean));
  for (const a of registros) {
    if (a.elemento === 'accion' && a.arbolDespues && !ENVOLTORIOS.has(a.herramienta)) {
      arboles.add(a.arbolDespues);
    }
  }
  const vistos = new Map(rutas.map((r) => [r, new Set()]));
  for (const arbol of arboles) {
    const enArbol = new Map();
    const ls = git(['ls-tree', '-r', arbol, '--', ...rutas], { cwd: raiz, permitirFallo: true });
    for (const l of (ls || '').split('\n').filter(Boolean)) {
      const [meta, ruta] = l.split('\t');
      enArbol.set(ruta, meta.split(' ')[2]);
    }
    for (const r of rutas) vistos.get(r).add(enArbol.get(r) ?? null);
  }
  return vistos;
}

export function verificarRegistro({ raiz, rango, cuerpoPr = null, motor = true,
  log = console }) {
  const fallas = [];
  const avisos = [];
  const citas = new Map();
  for (const c of commitsDe(raiz, rango)) {
    if (!CON_IA.test(c.mensaje)) continue;
    const corto = c.sha.slice(0, 7);
    const valores = citasDe(c.mensaje);
    if (!valores.length) {
      fallas.push(`${corto} es asistido y no cita su acta: falta \`Registro-IA: <ruta>\` o ` +
        '`Registro-IA: ninguno: <motivo>`');
    }
    for (const v of valores) {
      if (/^ninguno:\s*\S/.test(v)) avisos.push(`${corto} no tiene acta: ${v.slice(8).trim()}`);
      else if (!v.endsWith(SUFIJO)) fallas.push(`${corto} cita «${v}», que no es una curada`);
      else citas.set(v, [...(citas.get(v) || []), c.sha]);
    }
  }

  for (const [ruta, shas] of citas) {
    const curada = path.join(raiz, ruta);
    if (!blobEn(raiz, 'HEAD', ruta) || !fs.existsSync(curada)) {
      fallas.push(`${shas.map((s) => s.slice(0, 7)).join(', ')} cita ${ruta}, que no esta en ` +
        'la PR: registra la sesion (registrar-sesion.mjs) y commitea su acta');
      continue;
    }
    const cruda = curada.slice(0, -SUFIJO.length) + '.acta.cruda.jsonl';
    if (!fs.existsSync(cruda)) {
      fallas.push(`${ruta} no tiene su cruda al lado`);
      continue;
    }
    const pack = curada.slice(0, -SUFIJO.length) + '.acta.objetos.pack';
    if (fs.existsSync(pack)) {
      git(['index-pack', '--stdin'], { cwd: raiz, input: fs.readFileSync(pack) });
    } else {
      avisos.push(`${ruta} no trae su pack de objetos: solo se verifica donde se capturo`);
    }
    const i8 = comprobarCurada({ cruda, curada, raizRepo: raiz });
    if (i8.length) {
      fallas.push(...i8.map((e) => `${ruta}: ${e}`));
      continue;
    }
    const registros = leerActa(curada);
    for (const sha of shas) {
      const cambios = cambiosDelCommit(raiz, sha);
      if (!cambios.length) continue;
      const vistos = contenidosDeLaCadena(raiz, registros, cambios.map((x) => x.ruta));
      for (const { ruta: archivo, blob } of cambios) {
        if (!vistos.get(archivo).has(blob)) {
          fallas.push(`${sha.slice(0, 7)} deja ${archivo} ${blob ? `en ${blob.slice(0, 7)}` :
            'borrado'}, y ningun arbol de ${path.basename(ruta)} lo tuvo asi: el acta no llega ` +
            'al commit');
        }
      }
    }
    if (!motor) continue;
    try {
      const r = reejecutar({ curada, raizRepo: raiz });
      const aplicadas = r.veredictos.filter((v) => v.modo === 'aplicada' &&
        v.veredicto === 'diverge');
      for (const v of aplicadas) {
        fallas.push(`${ruta}: el motor no reproduce ${v.accion}: ${v.motivo}`);
      }
      if (r.detenido) fallas.push(`${ruta}: el motor se detuvo: ${r.detenido.motivo}`);
      const ejecutadas = r.veredictos.filter((v) => v.modo === 'ejecutada' &&
        v.veredicto === 'diverge');
      if (ejecutadas.length) {
        avisos.push(`${ruta}: ${ejecutadas.length} comandos divergen en el contenedor ` +
          `(${ejecutadas.map((v) => v.accion).join(', ')}); no falla, ver ADR-0006`);
      }
      log.log(`${ruta}: ${r.resumen.verificadas} acciones verificadas de ${r.resumen.acciones}.`);
    } catch (e) {
      if (!(e instanceof NoSePudo)) throw e;
      fallas.push(`${ruta}: el motor no pudo re-ejecutarla: ${e.message}`);
    }
  }

  if (cuerpoPr !== null) {
    for (const ruta of citas.keys()) {
      if (!cuerpoPr.includes(`Registro-IA: ${ruta}`)) {
        fallas.push(`el cuerpo de la PR no repite \`Registro-IA: ${ruta}\`, y el ` +
          'squash lo perderia');
      }
    }
  }
  return { fallas, avisos, actas: citas.size };
}

function argumentos(argv) {
  const r = { motor: true };
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--rango') r.rango = argv[++i];
    else if (argv[i] === '--cuerpo-pr') r.cuerpoPr = argv[++i];
    else if (argv[i] === '--sin-motor') r.motor = false;
    else r.desconocido = argv[i];
  }
  return r;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const a = argumentos(process.argv.slice(2));
  const raiz = raizDelRepo(process.cwd());
  if (!a.rango || a.desconocido || !raiz || (a.cuerpoPr && !fs.existsSync(a.cuerpoPr))) {
    console.error('Uso: node $ACTA/verificar-registro-ia.mjs --rango <A..B> ' +
      '[--cuerpo-pr <archivo>] [--sin-motor]');
    process.exitCode = 2;
  } else {
    const cuerpoPr = a.cuerpoPr ? fs.readFileSync(a.cuerpoPr, 'utf8') : null;
    const { fallas, avisos, actas } = verificarRegistro({ raiz, rango: a.rango, cuerpoPr,
      motor: a.motor });
    for (const x of avisos) console.log(`aviso: ${x}`);
    for (const x of fallas) console.error(`FALLA: ${x}`);
    console.log(fallas.length ? `Registro de la IA: ${fallas.length} fallas.`
      : `Registro de la IA: ${actas} actas citadas, todas llegan a sus commits.`);
    process.exitCode = fallas.length ? 1 : 0;
  }
}
