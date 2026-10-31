#!/usr/bin/env node
// Sirve el LLM y los embeddings con llama.cpp nativo, fuera de Docker (ADR-0017).
//
//   node scripts/llama-cpp.mjs instalar   baja y verifica el build fijado de llama.cpp (Windows)
//   node scripts/llama-cpp.mjs arrancar   levanta las dos instancias y espera a que respondan
//   node scripts/llama-cpp.mjs detener    detiene las dos instancias
//   node scripts/llama-cpp.mjs estado     dice si cada una corre y si responde
//
// Lo invoca el Makefile (make up, make down, make llm-*), que le pasa por entorno lo que decide
// con nvidia-smi: cuantas capas van a la GPU en cada instancia y si el build es CUDA o CPU. A mano
// funciona igual, con los defaults de abajo.
//
// Son dos procesos y no uno porque llama-server sirve un solo modelo por instancia, y el de
// embeddings necesita --embedding y otro reparto de la GPU: en la T600 de 4 GB el LLM va a la
// tarjeta y bge-m3 a la CPU (umbral KB_VRAM_EMBEDDINGS_GPU del Makefile).
//
// En Linux y macOS no se instala nada: se usa el llama-server del PATH, o el de LLAMA_SERVER.
// Los builds publicados por llama.cpp para esos sistemas no traen CUDA, asi que ahi conviene el
// paquete del sistema o compilarlo.

import { createHash } from 'node:crypto';
import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';

const env = process.env;
const DATOS = path.resolve(env.KB_DATA_DIR || './.data');
const BUILD = env.KB_LLAMA_CPP_BUILD || 'b11960';
const VARIANTE = env.KB_LLAMA_CPP_VARIANTE || 'cuda';
const HOST = env.KB_LLAMA_HOST || '127.0.0.1';
const CARPETA_BUILD = path.join(DATOS, 'llama.cpp', BUILD);
const CARPETA_RUN = path.join(DATOS, 'llama.cpp', 'run');
const CARPETA_LOGS = path.join(DATOS, 'llama.cpp', 'logs');
const ES_WINDOWS = process.platform === 'win32';

// b11960 es el build de la version estable v0.8.0 (28/10/2026). Los hashes salen del campo
// digest de la API de releases de GitHub; un build distinto se baja sin verificar, con aviso.
const HASHES = {
  b11960: {
    'llama-b11960-bin-win-cuda-12.4-x64.zip':
      '8d9d819261de10cc2f03921b8b79c779c5c458e21edb60c170ee30a7d462d684',
    'cudart-llama-bin-win-cuda-12.4-x64.zip':
      '8c79a9b226de4b3cacfd1f83d24f962d0773be79f1e7b75c6af4ded7e32ae1d6',
    'llama-b11960-bin-win-cpu-x64.zip':
      '73f962f82850d244db47eaacb9d913637027b82399124324298576d58c5f4287',
  },
};

const entero = (valor, porDefecto) => {
  const n = Number.parseInt(valor ?? '', 10);
  return Number.isNaN(n) ? porDefecto : n;
};

// El contexto de llama-server se reparte entre los slots: -c 8192 con -np 2 deja 4096 por
// consulta, que es lo que asume kb.llm.contexto-tokens. Por eso se multiplica aqui.
const CONTEXTO_POR_SLOT = entero(env.KB_LLM_CONTEXTO_TOKENS, 4096);
const PARALELO = entero(env.KB_LLM_PARALELO, 2);

// Con 0 capas el build CUDA igual abre un contexto en la tarjeta y le quita VRAM al LLM: -dev
// none lo deja del todo en la CPU.
const capasEnGpu = (capas) => (capas > 0 ? ['-ngl', String(capas)] : ['-ngl', '0', '-dev', 'none']);

const INSTANCIAS = [
  {
    nombre: 'llm',
    puerto: entero(env.KB_LLM_PORT, 8081),
    modelo: path.resolve(DATOS, env.KB_LLM_GGUF || 'models/llm/granite-4.1-3b-Q3_K_M.gguf'),
    argumentos: () => [
      '--alias', env.KB_LLM_MODELO || 'granite-4.1-3b-Q3_K_M',
      ...capasEnGpu(entero(env.KB_LLM_GPU_CAPAS, 99)),
      '-c', String(CONTEXTO_POR_SLOT * PARALELO),
      '-np', String(PARALELO),
    ],
  },
  {
    nombre: 'embeddings',
    puerto: entero(env.KB_EMBEDDINGS_PORT, 8082),
    modelo: path.resolve(DATOS, env.KB_EMBEDDINGS_GGUF || 'models/embeddings/bge-m3-FP16.gguf'),
    // bge-m3 agrupa por el token CLS. El lote fisico (-ub) tiene que cubrir la entrada entera:
    // un embedding no se parte en varios lotes, y con el default (512) un chunk largo falla.
    argumentos: () => [
      '--alias', env.KB_EMBEDDINGS_MODELO || 'bge-m3',
      '--embedding',
      '--pooling', 'cls',
      ...capasEnGpu(entero(env.KB_EMBEDDINGS_GPU_CAPAS, 0)),
      '-c', '8192',
      '-b', '8192',
      '-ub', '8192',
      '-np', '1',
    ],
  },
];

function fallar(mensaje) {
  console.error(`ERROR: ${mensaje}`);
  process.exit(1);
}

function ejecutable() {
  if (env.LLAMA_SERVER) return env.LLAMA_SERVER;
  if (ES_WINDOWS) return path.join(CARPETA_BUILD, 'llama-server.exe');
  return 'llama-server';
}

function archivosDelBuild() {
  if (VARIANTE === 'cpu') return [`llama-${BUILD}-bin-win-cpu-x64.zip`];
  return [`llama-${BUILD}-bin-win-cuda-12.4-x64.zip`, 'cudart-llama-bin-win-cuda-12.4-x64.zip'];
}

function hashDe(archivo) {
  return createHash('sha256').update(fs.readFileSync(archivo)).digest('hex');
}

async function descargar(nombre, destino) {
  const url = `https://github.com/ggml-org/llama.cpp/releases/download/${BUILD}/${nombre}`;
  console.log(`  bajando ${nombre} ...`);
  const respuesta = await fetch(url);
  if (!respuesta.ok) fallar(`${url} respondio ${respuesta.status}`);
  const hash = createHash('sha256');
  const temporal = `${destino}.tmp`;
  await pipeline(
    Readable.fromWeb(respuesta.body),
    async function* (fuente) {
      for await (const trozo of fuente) {
        hash.update(trozo);
        yield trozo;
      }
    },
    fs.createWriteStream(temporal),
  );
  const calculado = hash.digest('hex');
  const esperado = HASHES[BUILD]?.[nombre];
  if (!esperado) {
    console.log(`  AVISO: ${BUILD} no tiene hash fijado en este script; ${nombre} no se verifica.`);
  } else if (calculado !== esperado) {
    fs.rmSync(temporal, { force: true });
    fallar(`hash de ${nombre} no coincide.\n  esperado:  ${esperado}\n  calculado: ${calculado}`);
  }
  fs.renameSync(temporal, destino);
}

async function instalar() {
  if (!ES_WINDOWS) {
    console.log('Fuera de Windows no se instala nada: se usa el llama-server del PATH, o la ruta');
    console.log('que pongas en LLAMA_SERVER. Ver docs/infrastructure.md.');
    return;
  }
  if (fs.existsSync(ejecutable())) {
    console.log(`llama.cpp ${BUILD} ya esta en ${CARPETA_BUILD}, no se descarga de nuevo.`);
    return;
  }
  console.log(`Instalando llama.cpp ${BUILD} (${VARIANTE}) en ${CARPETA_BUILD}`);
  const descargas = path.join(DATOS, 'llama.cpp', 'descargas');
  fs.mkdirSync(descargas, { recursive: true });
  fs.mkdirSync(CARPETA_BUILD, { recursive: true });
  // El tar de Windows (bsdtar, en System32) abre zip; el GNU tar que trae Git en el PATH no.
  const tar = path.join(env.SystemRoot || 'C:\\Windows', 'System32', 'tar.exe');
  for (const nombre of archivosDelBuild()) {
    const zip = path.join(descargas, nombre);
    // Un zip que ya estaba (una descarga anterior, o puesto a mano) se verifica igual.
    const esperado = HASHES[BUILD]?.[nombre];
    if (fs.existsSync(zip) && esperado && hashDe(zip) !== esperado) fs.rmSync(zip);
    if (!fs.existsSync(zip)) await descargar(nombre, zip);
    const r = spawnSync(tar, ['-xf', zip, '-C', CARPETA_BUILD], { stdio: 'inherit' });
    if (r.status !== 0) fallar(`no se pudo descomprimir ${zip}`);
  }
  if (!fs.existsSync(ejecutable())) fallar(`el build no trae ${ejecutable()}`);
  console.log('llama.cpp listo y verificado.');
}

const archivoPid = (instancia) => path.join(CARPETA_RUN, `${instancia.nombre}.pid`);

function leerPid(instancia) {
  try {
    return entero(fs.readFileSync(archivoPid(instancia), 'utf8').trim(), 0);
  } catch {
    return 0;
  }
}

// Un pid guardado puede haberlo reusado otro proceso despues de un reinicio de Windows: se
// confirma por el nombre del ejecutable antes de darlo por nuestro, y sobre todo antes de matarlo.
function esLlamaServer(pid) {
  if (!pid) return false;
  if (ES_WINDOWS) {
    const r = spawnSync('tasklist', ['/FI', `PID eq ${pid}`, '/FO', 'CSV', '/NH'], {
      encoding: 'utf8',
    });
    return /llama-server/i.test(r.stdout || '');
  }
  const r = spawnSync('ps', ['-p', String(pid), '-o', 'comm='], { encoding: 'utf8' });
  return /llama-server/.test(r.stdout || '');
}

async function responde(instancia) {
  try {
    const r = await fetch(`http://${HOST}:${instancia.puerto}/health`, {
      signal: AbortSignal.timeout(2000),
    });
    return r.status;
  } catch {
    return 0;
  }
}

function colaDelLog(instancia, lineas = 25) {
  try {
    const texto = fs.readFileSync(path.join(CARPETA_LOGS, `${instancia.nombre}.log`), 'utf8');
    return texto.trimEnd().split(/\r?\n/).slice(-lineas).join('\n');
  } catch {
    return '(sin log)';
  }
}

async function arrancarUna(instancia) {
  const pid = leerPid(instancia);
  if (esLlamaServer(pid)) {
    console.log(`  ${instancia.nombre}: ya corre (pid ${pid}, puerto ${instancia.puerto})`);
    return;
  }
  if ((await responde(instancia)) !== 0) {
    fallar(`el puerto ${instancia.puerto} ya lo usa otro proceso. Detenlo o cambia ` +
      `KB_${instancia.nombre.toUpperCase()}_PORT.`);
  }
  if (!fs.existsSync(instancia.modelo)) {
    fallar(`falta el modelo ${instancia.modelo}. Se baja una sola vez con: make pull-models`);
  }
  const exe = ejecutable();
  if (ES_WINDOWS && !fs.existsSync(exe)) {
    fallar(`falta llama.cpp en ${CARPETA_BUILD}. Se instala con: make llm-instalar`);
  }
  fs.mkdirSync(CARPETA_RUN, { recursive: true });
  fs.mkdirSync(CARPETA_LOGS, { recursive: true });
  const rutaLog = path.join(CARPETA_LOGS, `${instancia.nombre}.log`);
  fs.rmSync(rutaLog, { force: true });
  const args = ['-m', instancia.modelo, '--host', HOST, '--port', String(instancia.puerto),
    '--log-colors', 'off', ...instancia.argumentos()];
  const nuevo = ES_WINDOWS ? lanzarEnWindows(exe, [...args, '--log-file', rutaLog])
    : lanzar(exe, args, rutaLog);
  fs.writeFileSync(archivoPid(instancia), String(nuevo));
  console.log(`  ${instancia.nombre}: arrancando (pid ${nuevo}, puerto ${instancia.puerto})`);
}

// detached + unref: el proceso sobrevive a make.
function lanzar(exe, args, rutaLog) {
  const log = fs.openSync(rutaLog, 'w');
  const hijo = spawn(exe, args, { detached: true, stdio: ['ignore', log, log] });
  hijo.on('error', (e) => fallar(`no se pudo ejecutar ${exe}: ${e.message}`));
  hijo.unref();
  return hijo.pid;
}

// En Windows un spawn de node le pasa al hijo TODOS los handles heredables que node recibio, y
// el make.exe de 32 bits le deja varios: llama-server se quedaba con la salida de make abierta y
// cualquier `make up | tee` (o el tail de un agente) esperaba para siempre. Start-Process sin
// redireccion lanza por ShellExecute, que no hereda handles; el log va por --log-file.
function lanzarEnWindows(exe, args) {
  const comillas = (a) => (/[\s"]/.test(a) ? `"${a.replace(/"/g, '\\"')}"` : a);
  const literal = (s) => `'${s.replace(/'/g, "''")}'`;
  const script = `$p = Start-Process -FilePath ${literal(exe)} ` +
    `-ArgumentList ${literal(args.map(comillas).join(' '))} -WindowStyle Hidden -PassThru; $p.Id`;
  const r = spawnSync('powershell', ['-NoProfile', '-NonInteractive', '-EncodedCommand',
    Buffer.from(script, 'utf16le').toString('base64')], { encoding: 'utf8' });
  const pid = entero((r.stdout || '').trim(), 0);
  if (r.status !== 0 || !pid) fallar(`no se pudo ejecutar ${exe}: ${(r.stderr || '').trim()}`);
  return pid;
}

async function esperarUna(instancia, segundos = 300) {
  const limite = Date.now() + segundos * 1000;
  while (Date.now() < limite) {
    if ((await responde(instancia)) === 200) {
      console.log(`  ${instancia.nombre}: listo en http://${HOST}:${instancia.puerto}`);
      return;
    }
    if (!esLlamaServer(leerPid(instancia))) {
      fallar(`${instancia.nombre} termino antes de responder. Ultimas lineas del log:\n` +
        colaDelLog(instancia));
    }
    await new Promise((r) => setTimeout(r, 1000));
  }
  fallar(`${instancia.nombre} no respondio en ${segundos} s. Ultimas lineas del log:\n` +
    colaDelLog(instancia));
}

async function arrancar() {
  console.log('llama.cpp:');
  for (const instancia of INSTANCIAS) await arrancarUna(instancia);
  for (const instancia of INSTANCIAS) await esperarUna(instancia);
}

function detener() {
  console.log('llama.cpp:');
  for (const instancia of INSTANCIAS) {
    const pid = leerPid(instancia);
    if (!esLlamaServer(pid)) {
      console.log(`  ${instancia.nombre}: no estaba corriendo`);
    } else if (ES_WINDOWS) {
      spawnSync('taskkill', ['/PID', String(pid), '/T', '/F'], { stdio: 'ignore' });
      console.log(`  ${instancia.nombre}: detenido (pid ${pid})`);
    } else {
      process.kill(pid, 'SIGTERM');
      console.log(`  ${instancia.nombre}: detenido (pid ${pid})`);
    }
    fs.rmSync(archivoPid(instancia), { force: true });
  }
}

async function estado() {
  console.log(`llama.cpp ${BUILD} (${ejecutable()}):`);
  let todoBien = true;
  for (const instancia of INSTANCIAS) {
    const pid = leerPid(instancia);
    const corre = esLlamaServer(pid);
    const codigo = await responde(instancia);
    const salud =
      codigo === 200 ? 'responde' : codigo === 503 ? 'cargando el modelo' : 'no responde';
    todoBien &&= codigo === 200;
    console.log(`  ${instancia.nombre.padEnd(10)} ${corre ? `pid ${pid}` : 'sin proceso'}, ` +
      `puerto ${instancia.puerto}: ${salud}`);
    console.log(`  ${''.padEnd(10)} ${instancia.modelo}`);
  }
  console.log(`  logs: ${CARPETA_LOGS}`);
  if (!todoBien) process.exitCode = 1;
}

const ORDENES = { instalar, arrancar, detener, estado };
const orden = ORDENES[process.argv[2]];
if (!orden) fallar('uso: node scripts/llama-cpp.mjs <instalar|arrancar|detener|estado>');
await orden();
