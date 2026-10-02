// El ejecutor del motor de re-ejecucion (#222, fase 2; ADR-0005 punto 4). Corre DENTRO del
// contenedor que arma reejecutar-acta.mjs, sobre una copia del repo:
//
//   node ejecutar-acta.mjs <acta.curada.jsonl> --repo <carpeta> [--pack <objetos.pack>]
//        [--tiempo <segundos por accion>]
//
// Con --pack, arma el repo en <carpeta> desde ese pack: el HEAD base del acta en su rama, y el
// arbol base en el arbol de trabajo. Despues ejecuta la curada accion por accion, sin el modelo,
// y escribe el reporte en stdout, como JSON.
//
// Un veredicto por accion (modelo conceptual, Veredicto.valor), con el modo en que se obtuvo:
//
//   aplicada   Edit, Write, MultiEdit: se aplican sobre el archivo. Un `hook` o un `residuo`: se
//              aplica su diff con `git apply`. Se compara el arbol: igual o diverge.
//   ejecutada  Bash con bash, PowerShell con pwsh, en su directorio. Si el arbol coincide y salio
//              con 0, `igual` si la salida normalizada es la grabada y `equivalente` si no. Si no,
//              diverge. Un comando que no existe en la imagen (127) queda no_verificable.
//   comparada  Read: cada linea numerada del resultado grabado contra el archivo. Las que difieren
//              dejan `equivalente`, con cuantas: la normalizacion de rutas del acta y el recorte
//              de la herramienta cambian el texto grabado. Un archivo que no esta, diverge.
//   fixture    externa_lectura: no se ejecuta; se toma el resultado y el arbol del registro.
//              Siempre no_verificable: el reporte nunca la cuenta como ejecutada (PC-13).
//   omitida    efecto_externo (invariante 9), envoltorios de subagente, lecturas sin salida
//              comparable y lo que motivoNoReejecutable() descarta, como un cmdlet de Windows.
//              Siempre no_verificable.
//
// Tras cada accion, si el arbol no es el que el registro dice, el motor lo RESTAURA desde el
// registro y sigue: asi el reporte trae todas las divergencias, no solo la primera, y cada
// veredicto se mide desde el arbol que la accion tuvo en la sesion. Si no lo puede restaurar, se
// detiene, y el resto queda no_verificable.
//
// Codigos de salida: 0 la curada llega a su arbol final sin divergencias, 1 diverge o se detuvo,
// 2 uso incorrecto.
import crypto from 'node:crypto';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { ENVOLTORIOS, SIN_ARBOL, motivoNoReejecutable } from './clasificar.mjs';
import { arbolActual, git } from './nucleo-captura.mjs';
import { aplicar, rutaEnRepo } from './verificar-arbol.mjs';

const EDICIONES = new Set(['Edit', 'Write', 'MultiEdit']);
const IDENTIDAD = [['user.name', 'Motor del acta'], ['user.email', 'motor@acta.invalid']];

// El repo de la re-ejecucion, desde el pack que arma reejecutar-acta.mjs: el HEAD base en su
// rama y el arbol base en el arbol de trabajo. Devuelve si quedo en el arbol base.
export function prepararRepo({ raiz, pack, cabecera }) {
  fs.mkdirSync(raiz, { recursive: true });
  git(['init', '-q'], { cwd: raiz });
  for (const [clave, valor] of [['core.autocrlf', 'false'], ['commit.gpgsign', 'false'],
    ...IDENTIDAD]) {
    git(['config', clave, valor], { cwd: raiz });
  }
  git(['index-pack', '--stdin'], { cwd: raiz, input: fs.readFileSync(pack) });
  const rama = cabecera.ramas?.[0] || 'acta';
  git(['update-ref', `refs/heads/${rama}`, cabecera.headBase], { cwd: raiz });
  git(['symbolic-ref', 'HEAD', `refs/heads/${rama}`], { cwd: raiz });
  git(['read-tree', '-u', '--reset', 'HEAD'], { cwd: raiz });
  return restaurar(raiz, cabecera.arbolBase);
}

// Deja el arbol de trabajo en `arbol` y el indice en HEAD, como queda despues de editar sin
// stagear. Los archivos ignorados no se tocan: no cuentan en el hash. Devuelve si lo logro.
export function restaurar(raiz, arbol) {
  git(['clean', '-fdq'], { cwd: raiz });
  git(['read-tree', '-u', '--reset', arbol], { cwd: raiz });
  if (git(['rev-parse', '--verify', '--quiet', 'HEAD'], { cwd: raiz, permitirFallo: true })) {
    git(['read-tree', 'HEAD'], { cwd: raiz });
  }
  return arbolActual(raiz) === arbol;
}

export function normalizarSalida(texto, raiz) {
  let s = String(texto ?? '').replace(/\x1b\[[0-9;]*[A-Za-z]/g, '').replace(/\r/g, '');
  if (raiz) s = s.split(raiz).join('.');
  return s.split('\n').map((l) => l.trimEnd()).join('\n').trim();
}

export function correrComando({ herramienta, comando, cwd, tiempo, env }) {
  const [exe, args] = herramienta === 'PowerShell'
    ? ['pwsh', ['-NoProfile', '-NonInteractive', '-Command', comando]]
    : ['bash', ['-c', comando]];
  const r = spawnSync(exe, args, { cwd, env, encoding: 'utf8', timeout: tiempo * 1000,
    killSignal: 'SIGKILL', maxBuffer: 64 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'] });
  if (r.error?.code === 'ENOENT') return { codigo: 127, salida: `${exe}: no esta en la imagen` };
  const salida = [r.stdout, r.stderr].filter(Boolean).join('\n');
  if (r.error?.code === 'ETIMEDOUT') return { codigo: null, agotado: true, salida };
  return { codigo: r.status ?? null, salida };
}

const resultadoDe = (a) => a.resultadoCompleto ?? a.resultado ?? '';

function directorioDe(raiz, a) {
  const d = String(a.directorio ?? '.').replace(/\\/g, '/').replace(/\/+$/, '');
  if (d === '.') return raiz;
  const ruta = rutaEnRepo(d);
  return ruta === null ? null : path.join(raiz, ruta);
}

// Lo que el ejecutor hace con una accion: su modo, su veredicto y por que.
function paso(a, ctx) {
  const omitida = (motivo) => ({ modo: 'omitida', veredicto: 'no_verificable', motivo });
  if (ENVOLTORIOS.has(a.herramienta)) {
    return omitida('envuelve a un subagente, cuyas acciones se re-ejecutan una por una');
  }
  if (a.claseDeterminismo === 'efecto_externo') {
    return omitida('escribe fuera de la maquina: nunca se ejecuta (invariante 9)');
  }
  if (a.claseDeterminismo === 'externa_lectura') {
    return { modo: 'fixture', veredicto: 'no_verificable',
      motivo: 'lee fuera de la maquina: su resultado y su arbol salen del registro' };
  }
  if (a.herramienta === 'Read') return comparar(a, ctx);
  if (SIN_ARBOL.has(a.herramienta)) return omitida('no cambia el arbol y su salida no se compara');
  if (EDICIONES.has(a.herramienta)) return conArbol(a, ctx, editar(a, ctx));
  if (a.herramienta === 'hook' || a.herramienta === 'residuo') {
    return conArbol(a, ctx, aplicarDiff(a, ctx));
  }
  if (a.herramienta === 'Bash' || a.herramienta === 'PowerShell') return ejecutar1(a, ctx);
  return omitida(`el motor no sabe ejecutar ${a.herramienta}`);
}

// El veredicto de una accion aplicada: diverge si no se aplico o no deja el arbol del registro.
function conArbol(a, ctx, fallo) {
  if (fallo) return { modo: 'aplicada', veredicto: 'diverge', motivo: fallo };
  const obtenido = arbolActual(ctx.raiz);
  if (obtenido !== a.arbolDespues) {
    return { modo: 'aplicada', veredicto: 'diverge', arbolObtenido: obtenido,
      motivo: `deja el arbol ${obtenido} y el registro dice ${a.arbolDespues}` };
  }
  return { modo: 'aplicada', veredicto: 'igual' };
}

function editar(a, { raiz }) {
  const ruta = rutaEnRepo(a.entrada?.file_path);
  if (ruta === null) return null;
  const archivo = path.join(raiz, ruta);
  const antes = fs.existsSync(archivo) ? fs.readFileSync(archivo, 'utf8') : null;
  const nuevo = aplicar(a, antes);
  if (nuevo === null) return `no se aplica sobre ${ruta}: el texto a reemplazar no esta`;
  fs.mkdirSync(path.dirname(archivo), { recursive: true });
  fs.writeFileSync(archivo, nuevo);
  return null;
}

function aplicarDiff(a, { raiz }) {
  const diff = (a.cambios || []).map((c) => c.diff).join('');
  if (!diff) return 'no trae diff';
  const r = git(['apply', '--whitespace=nowarn', '-'], { cwd: raiz, input: diff,
    permitirFallo: true });
  return r === null ? 'su diff no se aplica sobre el arbol de antes' : null;
}

function comparar(a, { raiz }) {
  const ruta = rutaEnRepo(a.entrada?.file_path);
  if (ruta === null) {
    return { modo: 'omitida', veredicto: 'no_verificable', motivo: 'lee fuera del repo' };
  }
  const grabadas = [...String(resultadoDe(a)).matchAll(/^\s*(\d+)\t(.*)$/gm)];
  if (!grabadas.length) {
    return { modo: 'omitida', veredicto: 'no_verificable',
      motivo: 'el resultado grabado no trae lineas numeradas' };
  }
  const archivo = path.join(raiz, ruta);
  if (!fs.existsSync(archivo)) {
    return { modo: 'comparada', veredicto: 'diverge', motivo: `${ruta} no existe` };
  }
  const lineas = fs.readFileSync(archivo, 'utf8').replace(/\r/g, '').split('\n');
  const distintas = grabadas.filter(([, n, t]) =>
    (lineas[Number(n) - 1] ?? '').trimEnd() !== t.replace(/\r$/, '').trimEnd()).length;
  if (distintas === 0) return { modo: 'comparada', veredicto: 'igual' };
  return { modo: 'comparada', veredicto: 'equivalente', motivo: `${distintas} de ` +
    `${grabadas.length} lineas difieren del resultado grabado` };
}

function ejecutar1(a, ctx) {
  const motivo = motivoNoReejecutable(a.herramienta, a.entrada || {});
  if (motivo) return { modo: 'omitida', veredicto: 'no_verificable', motivo };
  const cwd = directorioDe(ctx.raiz, a);
  if (cwd === null || !fs.existsSync(cwd)) {
    return { modo: 'omitida', veredicto: 'no_verificable',
      motivo: `corrio en ${a.directorio}, fuera del repo o en una carpeta que no esta` };
  }
  const r = ctx.correr({ herramienta: a.herramienta, comando: String(a.entrada?.command ?? ''),
    cwd, tiempo: ctx.tiempo, env: ctx.env });
  const base = { modo: 'ejecutada', codigo: r.codigo };
  if (r.codigo === 127) {
    return { ...base, veredicto: 'no_verificable',
      motivo: 'usa un comando que no esta en la imagen (codigo 127)' };
  }
  // La salida de un comando que diverge va al reporte, recortada: es lo primero que se mira.
  const salida = normalizarSalida(r.salida, ctx.raiz);
  const diverge = (motivo, extra = {}) => ({ ...base, veredicto: 'diverge', motivo, ...extra,
    salida: salida.slice(-4000) });
  if (r.agotado) return diverge(`paso de ${ctx.tiempo} s sin terminar`);
  const obtenido = arbolActual(ctx.raiz);
  if (obtenido !== a.arbolDespues) {
    return diverge(`deja el arbol ${obtenido} y el registro dice ${a.arbolDespues}`,
      { arbolObtenido: obtenido });
  }
  if (r.codigo !== 0) return diverge(`sale con codigo ${r.codigo}, y en la sesion salio bien`);
  return salida === normalizarSalida(resultadoDe(a)) ? { ...base, veredicto: 'igual' }
    : { ...base, veredicto: 'equivalente', motivo: 'deja el mismo arbol con otra salida' };
}

// Ejecuta la curada sobre un repo que ya esta en su arbol base. Devuelve el reporte.
export function ejecutar({ registros, raiz, tiempo = 600, correr = correrComando, env = null }) {
  const cabecera = registros.find((r) => r.elemento === 'acta');
  const acciones = registros.filter((r) => r.elemento === 'accion');
  const temporal = path.join(path.dirname(raiz), 'tmp-acta');
  fs.mkdirSync(temporal, { recursive: true });
  const ctx = { raiz, tiempo, correr,
    env: env || { ...process.env, TEMP: temporal, TMP: temporal, TMPDIR: temporal } };
  delete ctx.env.GIT_DIR;
  delete ctx.env.GIT_WORK_TREE;
  delete ctx.env.GIT_INDEX_FILE;

  const veredictos = [];
  let primeraDivergencia = null;
  let detenido = arbolActual(raiz) === cabecera.arbolBase ? null
    : { accion: null, motivo: `el repo no arranca en el arbol base ${cabecera.arbolBase}` };
  for (const a of acciones) {
    const fila = { accion: a.id, herramienta: a.herramienta, clase: a.claseDeterminismo };
    if (detenido) {
      veredictos.push({ ...fila, modo: 'omitida', veredicto: 'no_verificable',
        motivo: 'el motor se detuvo antes' });
      continue;
    }
    if (a.arbolAntes && !ENVOLTORIOS.has(a.herramienta) && arbolActual(raiz) !== a.arbolAntes &&
      !restaurar(raiz, a.arbolAntes)) {
      detenido = { accion: a.id, motivo: `no se pudo restaurar su arbol de antes ${a.arbolAntes}` };
      veredictos.push({ ...fila, modo: 'omitida', veredicto: 'no_verificable',
        motivo: detenido.motivo });
      continue;
    }
    const r = { ...fila, ...paso(a, ctx) };
    veredictos.push(r);
    if (r.veredicto === 'diverge' && !primeraDivergencia) {
      primeraDivergencia = { accion: a.id, motivo: r.motivo };
    }
    if (a.arbolDespues && !ENVOLTORIOS.has(a.herramienta) && arbolActual(raiz) !==
      a.arbolDespues && !restaurar(raiz, a.arbolDespues)) {
      detenido = { accion: a.id, motivo: `no se pudo restaurar su arbol de despues ` +
        `${a.arbolDespues}` };
    }
  }

  const ultima = [...acciones].reverse().find((a) => a.arbolDespues &&
    !ENVOLTORIOS.has(a.herramienta));
  const esperado = cabecera.arbolFinal || ultima?.arbolDespues || cabecera.arbolBase;
  const obtenido = arbolActual(raiz);
  const cuenta = (f) => veredictos.filter(f).length;
  const verificable = (v) => ['aplicada', 'ejecutada', 'comparada'].includes(v.modo) &&
    v.veredicto !== 'no_verificable';
  return {
    elemento: 'reporte_motor',
    version: 1,
    sesion: cabecera.sesion,
    tarea: cabecera.tarea,
    headBase: cabecera.headBase,
    arbolBase: cabecera.arbolBase,
    arbolFinalEsperado: esperado,
    arbolFinalObtenido: obtenido,
    llegaAlArbolFinal: !detenido && !primeraDivergencia && obtenido === esperado,
    primeraDivergencia,
    detenido,
    resumen: {
      acciones: veredictos.length,
      verificadas: cuenta(verificable),
      divergentes: cuenta((v) => v.veredicto === 'diverge'),
      desdeFixture: cuenta((v) => v.modo === 'fixture'),
      omitidas: cuenta((v) => v.modo === 'omitida'),
      sinVerificar: cuenta((v) => v.modo === 'ejecutada' && v.veredicto === 'no_verificable'),
    },
    veredictos,
  };
}

function argumentos(argv) {
  const r = { tiempo: 600 };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--repo') r.repo = argv[++i];
    else if (a === '--pack') r.pack = argv[++i];
    else if (a === '--tiempo') r.tiempo = Number(argv[++i]);
    else if (!r.curada && !a.startsWith('--')) r.curada = a;
    else r.desconocido = a;
  }
  return r;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const a = argumentos(process.argv.slice(2));
  if (!a.curada || !a.repo || a.desconocido || !fs.existsSync(a.curada) ||
    !(a.tiempo > 0) || (a.pack && !fs.existsSync(a.pack))) {
    console.error('Uso: node ejecutar-acta.mjs <acta.curada.jsonl> --repo <carpeta> ' +
      '[--pack <objetos.pack>] [--tiempo <segundos por accion>]');
    process.exitCode = 2;
  } else {
    const texto = fs.readFileSync(a.curada, 'utf8');
    const registros = texto.split('\n').filter(Boolean).map((l) => JSON.parse(l));
    const cabecera = registros.find((r) => r.elemento === 'acta');
    if (a.pack) prepararRepo({ raiz: a.repo, pack: a.pack, cabecera });
    const reporte = ejecutar({ registros, raiz: path.resolve(a.repo), tiempo: a.tiempo });
    reporte.sha256Curada = crypto.createHash('sha256').update(texto).digest('hex');
    process.stdout.write(JSON.stringify(reporte, null, 2) + '\n');
    process.exitCode = reporte.llegaAlArbolFinal ? 0 : 1;
  }
}
