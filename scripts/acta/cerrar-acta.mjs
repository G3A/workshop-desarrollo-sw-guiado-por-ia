// Lo que pasa con cada acta cruda recien escrita, al cerrar la sesion (capturar.mjs) o al
// registrarla a mitad de sesion (registrar-sesion.mjs): se cura, se empacan sus objetos y se
// reescribe el indice de su tarea.
//
// El pack de objetos (#222, fase 3). Los arboles del acta los escribio la captura en el repo de
// quien trabajo, con `write-tree`, y ningun commit los alcanza: un clon no los tiene. Sin ellos,
// el CI no puede verificar la curada (I8) ni el motor armar su repo. <sesion>.acta.objetos.pack
// lleva todos los arboles de la cruda y de la curada, con sus blobs, menos lo que ya alcanza el
// HEAD base. Se versiona junto al acta; el sensor del CI lo desempaca antes de verificar.
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { compilarYEscribir } from './compilar-acta.mjs';
import { curarYEscribir } from './curar-acta.mjs';
import { git, objetosQueFaltan } from './git.mjs';
import { indexarYEscribir } from './indexar-tarea.mjs';
import { leerActa } from './validar-acta.mjs';

const SUFIJO_CRUDA = '.acta.cruda.jsonl';

export const rutaHermana = (cruda, sufijo) => cruda.slice(0, -SUFIJO_CRUDA.length) + sufijo;

// Escribe el pack de objetos de la cruda (y de su curada, si existe). Devuelve su ruta, o null si
// el acta no tiene HEAD base o le faltan arboles en el repo.
export function empacarObjetos({ cruda, raizRepo }) {
  const curada = rutaHermana(cruda, '.acta.curada.jsonl');
  const registros = [...leerActa(cruda), ...(fs.existsSync(curada) ? leerActa(curada) : [])];
  const cabecera = registros.find((r) => r.elemento === 'acta');
  const arboles = new Set();
  for (const r of registros) {
    for (const k of ['arbolBase', 'arbolFinal', 'arbolAntes', 'arbolDespues']) {
      if (r[k]) arboles.add(r[k]);
    }
  }
  const lista = [...arboles].sort();
  if (!cabecera?.headBase || objetosQueFaltan(raizRepo, [cabecera.headBase, ...lista]).length) {
    return null;
  }
  // La resta se hace a mano: `rev-list --objects <arbol> --not <commit>` no descuenta los objetos
  // de un arbol pasado explicito aunque el commit los alcance. Medido en esta sesion: el pack
  // llevaba el PDF del corpus y mermaid.min.js, 8,7 MB que el HEAD base ya tenia.
  const objetos = (args, input) => new Set(git(['rev-list', '--objects', ...args],
    { cwd: raizRepo, input }).split('\n').filter(Boolean).map((l) => l.split(' ')[0]));
  const yaEstan = objetos([cabecera.headBase]);
  const nuevos = [...objetos(['--stdin'], lista.join('\n') + '\n')]
    .filter((o) => !yaEstan.has(o)).sort();
  const destino = rutaHermana(cruda, '.acta.objetos.pack');
  const env = { ...process.env };
  delete env.GIT_DIR;
  delete env.GIT_WORK_TREE;
  delete env.GIT_INDEX_FILE;
  const fd = fs.openSync(destino, 'w');
  try {
    execFileSync('git', ['pack-objects', '--stdout', '-q'], { cwd: raizRepo, env,
      // Sin objetos nuevos (todo lo alcanza el HEAD base), el pack sale vacio, que es valido; una
      // linea en blanco, no: pack-objects la rechaza como un id roto.
      input: nuevos.map((o) => `${o}\n`).join(''), stdio: ['pipe', fd, 'pipe'] });
  } finally {
    fs.closeSync(fd);
  }
  return destino;
}

// Cura, empaca e indexa las crudas escritas. Una curada de un cierre anterior de la misma sesion
// se borra antes: si esta curacion no sale, no puede quedar una curada vieja junto a una cruda
// nueva (#218).
export function cerrarActas({ escritas, raizRepo, log }) {
  for (const cruda of escritas) {
    fs.rmSync(rutaHermana(cruda, '.acta.curada.jsonl'), { force: true });
    const curacion = curarYEscribir({ cruda, raizRepo, log });
    if (curacion !== 0) {
      const tarea = path.basename(path.dirname(cruda));
      log.error(`la curacion de la tarea ${tarea} termino con codigo ${curacion}; la cruda ` +
        'quedo escrita');
    }
    if (!empacarObjetos({ cruda, raizRepo })) {
      log.error(`no se empacaron los objetos de ${path.basename(cruda)}: le falta el HEAD base ` +
        'o algun arbol en el repo');
    }
  }
  // El indice de cada tarea, despues de curar: dice si cada acta tiene curada (#222).
  for (const tarea of new Set(escritas.map((c) => path.dirname(c)))) {
    indexarYEscribir(tarea, { log });
  }
}

// Compila, cura, empaca e indexa. Devuelve el codigo de la compilacion y las crudas escritas.
export function compilarYCerrar({ transcript, captura, raizRepo, log, carpetaSesion = null,
  verificar = true }) {
  const escritas = [];
  const codigo = compilarYEscribir({ transcript, captura, salida: path.join(raizRepo, '.ia',
    'registros'), raizRepo, log, escritas, carpetaSesion, verificar });
  cerrarActas({ escritas, raizRepo, log });
  return { codigo, escritas };
}
