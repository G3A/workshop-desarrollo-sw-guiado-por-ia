// Motor de re-ejecucion del acta (#222, fase 2; ADR-0005 punto 4), desde la raiz del repo:
//
//   node $ACTA/reejecutar-acta.mjs <acta.curada.jsonl> [--reporte <archivo>]
//        [--imagen <imagen>] [--tiempo <segundos por accion>]
//
// Re-ejecuta la curada en un contenedor, sin el modelo, y escribe el reporte junto a ella como
// <sesion>.acta.reporte.json: un veredicto por accion, la primera divergencia y si llega al arbol
// final. El ejecutor que corre adentro es ejecutar-acta.mjs; lo que decide cada veredicto esta
// escrito ahi.
//
// Cuatro decisiones que no son de estilo:
//
// 1. El repo entra como un PACK de git con el HEAD base y todos los arboles del acta, que la
//    captura escribio como objetos. Sin volumenes del host con escritura: la unica carpeta
//    montada trae el pack, la curada y los scripts del acta, en solo lectura.
// 2. --network none, --read-only, usuario no root y limites de memoria, CPU y procesos. El
//    arbol de trabajo vive en un tmpfs. No se monta el socket de Docker: lo que levanta
//    infraestructura falla adentro y el reporte lo dice. La opcion --con-infra del ADR queda
//    para cuando haga falta.
// 3. La imagen se construye desde motor/Dockerfile, con la base fijada por digest, y se etiqueta
//    con el hash del Dockerfile. Es el unico paso con red. El reporte guarda el id de la imagen
//    construida y si el Dockerfile es el que la huella del acta registro en su HEAD base.
// 4. La curada no se valida aqui contra su cruda (eso es `curar-acta.mjs --comprobar`, I8): el
//    motor responde si lo que dice la curada se reproduce, no de donde salio.
//
// Codigos de salida: 0 llega a su arbol final sin divergencias, 1 diverge, 2 uso incorrecto,
// 3 no se pudo re-ejecutar (sin Docker, la imagen no se construye o faltan objetos en el repo).
import { execFileSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { git, objetosQueFaltan, raizDelRepo } from './git.mjs';
import { leerActa, validarActa } from './validar-acta.mjs';

const AQUI = path.dirname(fileURLToPath(import.meta.url));
const DOCKERFILE = path.join(AQUI, 'motor', 'Dockerfile');
const SUFIJO_CURADA = '.acta.curada.jsonl';

export class NoSePudo extends Error {}

function docker(args, opciones = {}) {
  return spawnSync('docker', args, { encoding: 'utf8', maxBuffer: 256 * 1024 * 1024,
    ...opciones });
}

// La imagen del motor: la que se pasa, o la que se construye desde motor/Dockerfile.
export function imagenDelMotor(imagen = null) {
  const v = docker(['version', '--format', '{{.Server.Version}}']);
  if (v.error || v.status !== 0) throw new NoSePudo('no hay Docker, o su servidor no responde');
  const hashDockerfile = git(['hash-object', DOCKERFILE], { cwd: AQUI }).trim();
  let ref = imagen;
  if (!ref) {
    ref = `acta-motor:${hashDockerfile.slice(0, 12)}`;
    if (docker(['image', 'inspect', ref]).status !== 0) {
      const b = docker(['build', '-q', '-t', ref, path.dirname(DOCKERFILE)]);
      if (b.status !== 0) throw new NoSePudo(`la imagen no se construye: ${b.stderr.trim()}`);
    }
  }
  const id = docker(['image', 'inspect', '--format', '{{.Id}}', ref]);
  if (id.status !== 0) throw new NoSePudo(`no existe la imagen ${ref}`);
  return { ref, id: id.stdout.trim(), dockerfile: imagen ? null : hashDockerfile };
}

// El pack con el HEAD base y todos los arboles de la curada, escrito en `destino`.
export function empaquetar(raiz, registros, destino) {
  const cabecera = registros.find((r) => r.elemento === 'acta');
  const arboles = new Set([cabecera.arbolBase, cabecera.arbolFinal]);
  for (const a of registros) {
    if (a.elemento === 'accion') arboles.add(a.arbolAntes).add(a.arbolDespues);
  }
  const objetos = [cabecera.headBase, ...[...arboles].filter(Boolean).sort()];
  if (!cabecera.headBase) throw new NoSePudo('el acta no tiene HEAD base');
  const faltan = objetosQueFaltan(raiz, objetos);
  if (faltan.length) throw new NoSePudo(`faltan en el repo los objetos ${faltan.join(', ')}`);
  const env = { ...process.env };
  delete env.GIT_DIR;
  delete env.GIT_WORK_TREE;
  delete env.GIT_INDEX_FILE;
  const fd = fs.openSync(destino, 'w');
  try {
    execFileSync('git', ['pack-objects', '--revs', '--stdout', '-q'], { cwd: raiz, env,
      input: objetos.join('\n') + '\n', stdio: ['pipe', fd, 'pipe'] });
  } finally {
    fs.closeSync(fd);
  }
}

export function reejecutar({ curada, raizRepo, imagen = null, tiempo = 600 }) {
  const registros = leerActa(curada);
  const cabecera = registros.find((r) => r.elemento === 'acta');
  if (!cabecera?.derivadaDe) throw new NoSePudo(`${curada} no es una curada: no trae derivadaDe`);
  const errores = validarActa(registros);
  if (errores.length) {
    throw new NoSePudo(`la curada rompe el modelo: ${errores.map((e) => e.mensaje).join('; ')}`);
  }
  const motor = imagenDelMotor(imagen);
  // realpath: Docker Desktop no siempre resuelve los nombres cortos 8.3 de Windows.
  const entrada = fs.realpathSync.native(fs.mkdtempSync(path.join(os.tmpdir(), 'acta-motor-')));
  const nombre = `acta-motor-${process.pid}-${Date.now()}`;
  // mkdtemp la crea con 0700, y en Linux el usuario del contenedor no es el del host.
  fs.chmodSync(entrada, 0o755);
  try {
    empaquetar(raizRepo, registros, path.join(entrada, 'objetos.pack'));
    fs.copyFileSync(curada, path.join(entrada, 'acta.curada.jsonl'));
    fs.mkdirSync(path.join(entrada, 'acta'));
    for (const f of fs.readdirSync(AQUI).filter((n) => n.endsWith('.mjs'))) {
      fs.copyFileSync(path.join(AQUI, f), path.join(entrada, 'acta', f));
    }
    const r = docker(['run', '--rm', '--name', nombre, '--network', 'none', '--read-only',
      '--user', 'node', '--memory', '4g', '--cpus', '2', '--pids-limit', '512',
      '--tmpfs', '/trabajo:rw,exec,size=4g,mode=1777', '--tmpfs', '/tmp:rw,exec,size=1g',
      '-e', 'HOME=/trabajo', '-v', `${entrada}:/entrada:ro`, motor.ref,
      'node', '/entrada/acta/ejecutar-acta.mjs', '/entrada/acta.curada.jsonl',
      '--repo', '/trabajo/repo', '--pack', '/entrada/objetos.pack', '--tiempo', String(tiempo)],
    { timeout: (tiempo * Math.max(1, registros.length) + 300) * 1000 });
    if (r.error) {
      docker(['kill', nombre]);
      throw new NoSePudo(`el contenedor no termino: ${r.error.message}`);
    }
    let reporte;
    try {
      reporte = JSON.parse(r.stdout);
    } catch {
      throw new NoSePudo(`el contenedor no devolvio un reporte (codigo ${r.status}): ` +
        `${r.stderr.trim().slice(0, 2000)}`);
    }
    const enLaHuella = cabecera.huella?.imagen?.dockerfile?.hash ?? null;
    reporte.imagen = {
      ref: motor.ref,
      id: motor.id,
      dockerfile: motor.dockerfile,
      coincideConLaHuella: enLaHuella && motor.dockerfile ? enLaHuella === motor.dockerfile : null,
    };
    return reporte;
  } finally {
    fs.rmSync(entrada, { recursive: true, force: true });
  }
}

function argumentos(argv) {
  const r = { tiempo: 600 };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--reporte') r.reporte = argv[++i];
    else if (a === '--imagen') r.imagen = argv[++i];
    else if (a === '--tiempo') r.tiempo = Number(argv[++i]);
    else if (!r.curada && !a.startsWith('--')) r.curada = a;
    else r.desconocido = a;
  }
  return r;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const a = argumentos(process.argv.slice(2));
  if (!a.curada || a.desconocido || !a.curada.endsWith(SUFIJO_CURADA) ||
    !fs.existsSync(a.curada) || !(a.tiempo > 0)) {
    console.error('Uso: node $ACTA/reejecutar-acta.mjs <acta.curada.jsonl> ' +
      '[--reporte <archivo>] [--imagen <imagen>] [--tiempo <segundos por accion>]');
    process.exitCode = 2;
  } else {
    try {
      const reporte = reejecutar({ curada: a.curada, raizRepo: raizDelRepo(process.cwd()),
        imagen: a.imagen, tiempo: a.tiempo });
      const destino = a.reporte || a.curada.slice(0, -SUFIJO_CURADA.length) +
        '.acta.reporte.json';
      fs.writeFileSync(destino, JSON.stringify(reporte, null, 2) + '\n');
      const { resumen: s, primeraDivergencia: p } = reporte;
      console.log(`${s.acciones} acciones: ${s.verificadas} verificadas (${s.divergentes} ` +
        `divergen), ${s.desdeFixture} desde el fixture, ${s.omitidas} omitidas, ` +
        `${s.sinVerificar} ejecutadas sin poder verificar.`);
      if (p) console.log(`Primera divergencia: ${p.accion}, ${p.motivo}.`);
      console.log(reporte.llegaAlArbolFinal ? 'La curada llega a su arbol final.'
        : 'La curada NO llega a su arbol final sin divergencias.');
      console.log(`Reporte: ${destino}`);
      process.exitCode = reporte.llegaAlArbolFinal ? 0 : 1;
    } catch (e) {
      if (!(e instanceof NoSePudo)) throw e;
      console.error(`No se pudo re-ejecutar: ${e.message}`);
      process.exitCode = 3;
    }
  }
}
