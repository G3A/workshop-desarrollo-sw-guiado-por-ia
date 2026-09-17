// Sensor del espejo con el sandbox: node scripts/verificar-espejo.mjs (desde la raiz del repo)
//
// Compara base-conocimiento/ del monorepo contra la raiz de G3A/base-conocimiento-sandbox por
// hashes de blob, y falla cuando aparece una diferencia que el ADR-0003 no explica. Nacio en #155:
// hasta entonces la lista de divergencias del ADR se mantenia a mano, y el propio documento lo
// admitia -- "esta lista envejece [...] y nada lo verifica a maquina". En #144 esa falta de sensor
// dejo pasar un hecho falso al plan ("el sandbox no tiene el sensor de enlaces") que salio de un
// grep sobre un clon 15 PR atras del remoto.
//
// La lista NO vive aca: sale del ADR, parseada. Si el ADR y los arboles discrepan, uno de los dos
// esta mal, y eso es justo la senal. Duplicarla en este archivo seria la segunda copia que se
// desincroniza, que es el defecto por el que el ADR-0002 ya habia descartado el claims-ledger.
//
// Siete decisiones que no son de estilo:
//
// 1. La clave de comparacion es (sha, mode), no solo sha. Con sha solo, un `chmod +x` de un lado
//    sale VERDE con los dos arboles realmente distintos: mismo blob, uno arranca y el otro da
//    "Permission denied". Hoy los dos arboles son enteramente 100644, asi que el primer 100755 que
//    aparezca va a ser justamente una divergencia nueva.
// 2. `git ls-tree -r -z`. Sin -z, con core.quotePath en su valor por defecto, git cita y escapa en
//    octal: un nombre con enie sale como "docs/dise\303\261o.md" y nunca coincide con el UTF-8 que
//    devuelve la API. El precedente esta en verificar-enlaces.mjs, que ya usa ls-files -z.
// 3. El prefijo se quita con startsWith + slice, NUNCA con un reemplazo global. Hoy existe
//    base-conocimiento/docs/plans/plan-base-conocimiento.md, que un replaceAll convertiria en
//    docs/plans/plan-.md -- un rojo duro sobre un archivo perfectamente espejado.
// 4. Solo la diferencia NO explicada bloquea. Una entrada del ADR que sobra, o un residuo que
//    desaparece del sandbox, avisan. El sandbox es un repositorio que este no controla: sin esa
//    distincion, limpiar un residuo alla pondria en rojo la siguiente PR de aca, que no toco nada.
// 5. Sale a la red, a diferencia de los otros dos sensores del monorepo. Por eso corre solo en CI y
//    no en el pre-push: un hook que sale a la red bloquea `git push` cuando falla el wifi. Un fallo
//    de red se reporta con su propio motivo, nunca como si fuera una divergencia -- la accion del
//    humano es distinta en cada caso.
// 6. Un arbol vacio no es verde, es ceguera. Misma leccion de #144 que verificar-enlaces.mjs ya
//    tiene escrita para su indice.
// 7. Despues del primer fetch nadie llama a process.exit(). Con un socket de undici abierto,
//    en Windows libuv aborta con "Assertion failed: !(handle->flags & UV_HANDLE_CLOSING)" y el
//    codigo de salida pasa a 127 en vez de 1. Se usa process.exitCode y se deja que el proceso
//    termine solo. En Linux no se nota, que es justo lo que lo vuelve peligroso.
//
// El sandbox es publico, asi que el fetch funciona sin token (60 peticiones/hora por IP). En CI hay
// que cablear `env: GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}` en el paso: en Actions ese secreto
// NO esta en el entorno por si solo, y sin el las corridas salen anonimas desde IPs compartidas de
// runners. Sirve de sobra para leer un repo publico; el dia que el sandbox deje de serlo, la API
// respondera 404 y este sensor lo dira con ese motivo.
//
// API de trees: https://docs.github.com/en/rest/git/trees#get-a-tree -- `recursive=1` devuelve el
// arbol entero (hoy 366 entradas, tope 100.000) y ese endpoint NO pagina: si `truncated` viene en
// true hay que recorrerlo nivel por nivel. El `sha` que devuelve es el mismo blob SHA-1 que da
// `git ls-tree`, que es lo que hace comparable un lado con el otro.
//
// Sin dependencias npm; usa el binario `git` del propio repositorio y el fetch global de node.
// Corre con cualquier node >= 18, en Windows y en el runner de CI.
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const RAIZ = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const ADR = 'docs/adrs/0003-el-adr-como-fuente-de-datos-del-sensor-de-espejo.md';
const PREFIJO = 'base-conocimiento/';
const SANDBOX = 'G3A/base-conocimiento-sandbox';
const RAMA_SANDBOX = 'dev';
const MODOS = new Set(['100644', '100755']);
// Por debajo de esto el sensor no esta viendo el repositorio que cree: aborta en vez de comparar.
const MINIMO_ENTRADAS = 100;
// El unico archivo de la lista "solo en el sandbox" sobre el que el ADR afirma algo del contenido.
const ESPEJADO_SALVO_CABECERA = 'scripts/verificar-enlaces.mjs';

const barras = ruta => ruta.split(path.sep).join('/');

// Corta la corrida con un motivo escrito. No es una divergencia: es que el sensor no pudo trabajar.
class Abortar extends Error {
  constructor(lineas) {
    super(lineas[0]);
    this.lineas = lineas;
  }
}
const abortar = (...lineas) => {
  throw new Abortar(lineas);
};

function git(...args) {
  try {
    const opciones = { cwd: RAIZ, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 };
    return execFileSync('git', args, opciones);
  } catch (error) {
    abortar(`No se pudo correr "git ${args.join(' ')}" en ${barras(RAIZ)}: ${error.message}`);
  }
}

const fallas = [];
const avisos = [];
const falla = msg => fallas.push(msg);
const avisa = msg => avisos.push(msg);

// Quita los bloques de codigo cercados sin tocar los saltos de linea, para que los numeros de linea
// sigan valiendo. Misma funcion que verificar-enlaces.mjs, y por la misma razon: el ADR documenta
// su propia sintaxis de marcadores dentro de un bloque cercado, y sin esto el ejemplo se parsearia
// como si fuera la lista real -- o su marcador de apertura cerraria la region verdadera.
function sinCodigo(texto) {
  let enBloque = false;
  return texto
    .split('\n')
    .map(linea => {
      if (/^\s*(```|~~~)/.test(linea)) {
        enBloque = !enBloque;
        return '';
      }
      return enBloque ? '' : linea;
    })
    .join('\n');
}

// --- El ADR como fuente de datos -------------------------------------------------------------

function bloqueDelAdr(texto, nombre) {
  const abre = `<!-- espejo:${nombre} -->`;
  const cierra = `<!-- /espejo:${nombre} -->`;
  const desde = texto.indexOf(abre);
  const hasta = texto.indexOf(cierra);
  if (desde === -1 || hasta === -1 || hasta < desde) {
    abortar(
      `${ADR}: falta el marcador "espejo:${nombre}" (o su cierre).`,
      'Un sensor que no encuentra su fuente de datos no esta en verde: esta ciego.',
    );
  }
  return texto.slice(desde + abre.length, hasta);
}

// Vinetas "- `ruta` — razon", donde la razon puede envolverse: la entrada termina donde empieza la
// siguiente. Se aceptan el guion largo y el doble guion, porque un guion tecleado a mano que el
// parser no reconociera convertiria la entrada en una linea que desaparece en silencio.
function entradasDeLista(bloque, nombre) {
  const entradas = [];
  for (const linea of bloque.split('\n')) {
    if (!/^\s*-\s/.test(linea)) continue;
    const m = linea.match(/^\s*-\s+`([^`]+)`\s+(?:—|--)\s+(\S.*)$/);
    if (!m) {
      falla(`${ADR} (espejo:${nombre}): entrada ilegible -> ${linea.trim()}`);
      continue;
    }
    entradas.push({ ruta: m[1].normalize('NFC'), razon: m[2] });
  }
  return entradas;
}

// La tabla de "difieren por contenido" agrupa varios archivos bajo un mismo motivo, asi que la
// entrada es cada ruta entre backticks de su primera columna. Aplanarla a una ruta por linea
// duplicaria el motivo, que es justo lo que el ADR-0002 evito al descartar el claims-ledger.
function entradasDeTabla(bloque, nombre) {
  const entradas = [];
  for (const linea of bloque.split('\n')) {
    if (!/^\s*\|/.test(linea)) continue;
    if (/^\s*\|[\s|:-]*$/.test(linea)) continue;
    const celda = linea.split('|')[1] ?? '';
    const rutas = [...celda.matchAll(/`([^`]+)`/g)].map(m => m[1].normalize('NFC'));
    if (!rutas.length) continue;
    const razon = (linea.split('|')[2] ?? '').trim();
    for (const ruta of rutas) entradas.push({ ruta, razon });
  }
  if (!entradas.length) {
    falla(`${ADR} (espejo:${nombre}): la tabla no tiene ninguna ruta entre backticks.`);
  }
  return entradas;
}

function mapaDeEntradas(entradas, nombre) {
  const mapa = new Map();
  for (const entrada of entradas) {
    if (mapa.has(entrada.ruta)) {
      falla(`${ADR} (espejo:${nombre}): "${entrada.ruta}" esta dos veces en la misma lista.`);
      continue;
    }
    mapa.set(entrada.ruta, entrada);
  }
  return mapa;
}

function listasDelAdr() {
  // El \r\n se normaliza ANTES de parsear, y no es cosmetico: en una regex de JS el \r es
  // terminador de linea, asi que `.` no lo matchea y un `$` al final del patron nunca cierra. Con
  // core.autocrlf en true -- lo normal en Windows -- toda vineta del ADR quedaba "ilegible" en la
  // maquina de quien lo edita mientras el runner Linux salia en verde. Es el bug de #144 al reves:
  // verde donde ocurre el cambio, rojo donde nadie mira.
  const crudo = fs.readFileSync(path.join(RAIZ, ADR), 'utf8').replace(/\r\n/g, '\n');
  const texto = sinCodigo(crudo);
  const difieren = mapaDeEntradas(
    entradasDeTabla(bloqueDelAdr(texto, 'difieren-por-contenido'), 'difieren-por-contenido'),
    'difieren-por-contenido',
  );
  const soloSandbox = mapaDeEntradas(
    entradasDeLista(bloqueDelAdr(texto, 'solo-en-el-sandbox'), 'solo-en-el-sandbox'),
    'solo-en-el-sandbox',
  );
  // Esta lista puede quedar vacia: vaciarse es su estado final deseado. Lo que seria un error es
  // que faltara el marcador, y de eso ya se encarga bloqueDelAdr.
  const pendientes = mapaDeEntradas(
    entradasDeLista(bloqueDelAdr(texto, 'pendientes-de-limpieza'), 'pendientes-de-limpieza'),
    'pendientes-de-limpieza',
  );
  for (const ruta of pendientes.keys()) {
    if (soloSandbox.has(ruta)) {
      falla(`${ADR}: "${ruta}" esta en solo-en-el-sandbox y en pendientes-de-limpieza a la vez.`);
    }
  }
  return { difieren, soloSandbox, pendientes };
}

// --- Los dos arboles -------------------------------------------------------------------------

// Salida de `ls-tree -r -z`: "<modo> SP <tipo> SP <sha> TAB <ruta>" por entrada, terminada en NUL.
function arbolLocal() {
  const arbol = new Map();
  for (const entrada of git('ls-tree', '-r', '-z', 'HEAD').split('\0')) {
    if (!entrada) continue;
    const corte = entrada.indexOf('\t');
    const [modo, tipo, sha] = entrada.slice(0, corte).split(' ');
    arbol.set(entrada.slice(corte + 1).normalize('NFC'), { modo, tipo, sha });
  }
  return arbol;
}

async function pedir(url) {
  const cabeceras = { 'User-Agent': 'verificar-espejo', Accept: 'application/vnd.github+json' };
  const token = process.env.GITHUB_TOKEN || process.env.GH_TOKEN;
  if (token) cabeceras.Authorization = `Bearer ${token}`;
  let ultima = '';
  for (let intento = 1; intento <= 2; intento++) {
    let respuesta;
    try {
      respuesta = await fetch(url, { headers: cabeceras, signal: AbortSignal.timeout(20_000) });
    } catch (error) {
      ultima = `no hubo respuesta (${error.name}: ${error.message})`;
      continue;
    }
    if (respuesta.ok) return respuesta.json();
    const restantes = respuesta.headers.get('x-ratelimit-remaining');
    // El cuerpo se descarta explicitamente: una respuesta sin leer deja el socket ocupado.
    await respuesta.body?.cancel();
    ultima = `HTTP ${respuesta.status}`;
    if (respuesta.status === 403 || respuesta.status === 429) {
      ultima += restantes === '0' ? ' -- se agoto el rate limit de la API' : ' -- acceso denegado';
    } else if (respuesta.status === 404) {
      ultima += ` -- no existe ${SANDBOX} o su rama ${RAMA_SANDBOX}, o el repo dejo de ser publico`;
    } else if (respuesta.status === 401) {
      ultima += ' -- el token no sirve';
    }
    // Solo un 5xx o un 429 merecen el segundo intento; un 404 va a dar 404 de nuevo.
    if (respuesta.status < 500 && respuesta.status !== 429) break;
  }
  abortar(
    `No se pudo alcanzar ${SANDBOX}@${RAMA_SANDBOX}: ${ultima}`,
    'Esto NO es una divergencia: es que el sensor no pudo ver el otro arbol.',
    token ? '' : 'Corriendo sin token: la API anonima permite 60 peticiones por hora.',
  );
}

async function arbolSandbox() {
  const url = `https://api.github.com/repos/${SANDBOX}/git/trees/${RAMA_SANDBOX}?recursive=1`;
  const json = await pedir(url);
  if (json.truncated) {
    abortar(
      `El arbol de ${SANDBOX} vino truncado por la API.`,
      'Ese endpoint no pagina: hay que recorrerlo nivel por nivel. Ver #155.',
    );
  }
  const arbol = new Map();
  for (const entrada of json.tree) {
    if (entrada.type !== 'blob') continue;
    const ruta = entrada.path.normalize('NFC');
    arbol.set(ruta, { modo: entrada.mode, tipo: 'blob', sha: entrada.sha });
  }
  return arbol;
}

async function contenidoSandbox(sha) {
  const json = await pedir(`https://api.github.com/repos/${SANDBOX}/git/blobs/${sha}`);
  return Buffer.from(json.content, json.encoding === 'base64' ? 'base64' : 'utf8').toString('utf8');
}

// --- Comparar --------------------------------------------------------------------------------

async function comparar() {
  // Guarda estructural, igual que la de verificar-enlaces.mjs: el sensor tiene que estar mirando la
  // raiz del repositorio git. Si alguien lo baja una carpeta, o lo copia al sandbox, compara contra
  // un arbol que no es el que cree y el resultado no significa nada.
  const raizGit = barras(path.resolve(git('rev-parse', '--show-toplevel').trim()));
  if (raizGit !== barras(RAIZ)) {
    abortar(
      'Este sensor tiene que vivir en <raiz-del-repo>/scripts/, y no es el caso:',
      `  raiz del repositorio git: ${raizGit}`,
      `  lo que el sensor mira:    ${barras(RAIZ)}`,
    );
  }

  const { difieren, soloSandbox, pendientes } = listasDelAdr();
  const raizMonorepo = arbolLocal();
  const sandbox = await arbolSandbox();

  // El subarbol espejado, con el prefijo quitado para que las rutas sean comparables con las del
  // sandbox, que las tiene respecto de SU raiz.
  const espejado = new Map();
  for (const [ruta, dato] of raizMonorepo) {
    if (!ruta.startsWith(PREFIJO)) continue;
    espejado.set(ruta.slice(PREFIJO.length), dato);
  }

  const arboles = [
    ['monorepo', espejado],
    ['sandbox', sandbox],
  ];
  for (const [nombre, arbol] of arboles) {
    if (arbol.size >= MINIMO_ENTRADAS) continue;
    abortar(
      `El arbol del ${nombre} trae ${arbol.size} entradas, menos de ${MINIMO_ENTRADAS}.`,
      'Un sensor que no ve nada no esta en verde: esta ciego. Ver #144.',
    );
  }
  for (const [nombre, arbol] of arboles) {
    for (const [ruta, dato] of arbol) {
      if (dato.tipo !== 'blob' || !MODOS.has(dato.modo)) {
        falla(`${nombre}: ${ruta} tiene modo ${dato.modo} (${dato.tipo}); no es un archivo comun.`);
      }
    }
  }

  // .gitattributes primero: lleva `* text=auto eol=lf`, asi que una divergencia suya cambiaria los
  // blobs de familias enteras de archivos de golpe. Decirlo vale mas que escupir decenas de lineas
  // de "difiere sin explicacion" que esconden la causa.
  const gaLocal = espejado.get('.gitattributes');
  const gaSandbox = sandbox.get('.gitattributes');
  if (gaLocal && gaSandbox && gaLocal.sha !== gaSandbox.sha && !difieren.has('.gitattributes')) {
    abortar(
      '.gitattributes difiere entre los dos repos y el ADR no lo declara.',
      'Lleva `* text=auto eol=lf`: mientras difiera, la comparacion de blobs no vale.',
    );
  }

  const explicadas = { difieren: new Set(), soloSandbox: new Set(), pendientes: new Set() };

  for (const [ruta, local] of espejado) {
    const remoto = sandbox.get(ruta);
    if (!remoto) {
      falla(`falta espejar: ${ruta} existe en ${PREFIJO} y no en el sandbox.`);
      continue;
    }
    if (local.sha === remoto.sha && local.modo === remoto.modo) continue;
    if (!difieren.has(ruta)) {
      const igual = local.sha === remoto.sha;
      const motivo = igual ? `modo ${local.modo} vs ${remoto.modo}` : 'contenido';
      falla(`difiere sin explicacion (${motivo}): ${ruta} -- no esta en difieren-por-contenido`);
      continue;
    }
    explicadas.difieren.add(ruta);
  }

  for (const ruta of sandbox.keys()) {
    if (espejado.has(ruta)) continue;
    if (soloSandbox.has(ruta)) {
      explicadas.soloSandbox.add(ruta);
      // La regla que separa esta lista de la de residuos, y que el ADR-0003 vuelve comprobable: una
      // divergencia "por la forma del repositorio" tiene su equivalente en la raiz del monorepo.
      if (!raizMonorepo.has(ruta)) {
        falla(
          `mal clasificado: ${ruta} esta en solo-en-el-sandbox pero no existe en la raiz del` +
            ' monorepo, asi que no es una divergencia por la forma del repositorio.',
        );
      }
      continue;
    }
    if (pendientes.has(ruta)) {
      explicadas.pendientes.add(ruta);
      if (raizMonorepo.has(ruta)) {
        falla(
          `mal clasificado: ${ruta} esta en espejo:pendientes-de-limpieza pero existe en la raiz` +
            ' del monorepo, asi que no es un residuo de corrida.',
        );
      }
      continue;
    }
    falla(`solo en el sandbox y sin explicacion: ${ruta} -- no esta en ninguna lista del ADR.`);
  }

  // Entradas que sobran: avisan, no bloquean. El sandbox es un repositorio que este no controla, y
  // lo que merece frenar una PR es la diferencia no explicada, no la explicacion que sobra.
  const sobrante = (mapa, vistas, nombre) => {
    for (const ruta of mapa.keys()) {
      if (vistas.has(ruta)) continue;
      avisa(`${ADR} (espejo:${nombre}): "${ruta}" ya no corresponde a ninguna diferencia real.`);
    }
  };
  sobrante(difieren, explicadas.difieren, 'difieren-por-contenido');
  sobrante(soloSandbox, explicadas.soloSandbox, 'solo-en-el-sandbox');
  sobrante(pendientes, explicadas.pendientes, 'pendientes-de-limpieza');

  // El unico archivo de la lista sobre el que el ADR afirma algo del CONTENIDO: los dos repos
  // tienen el mismo cuerpo de script y difieren solo en la cabecera de comentarios. Sin esta
  // comprobacion esa afirmacion seria la unica del ADR que nada verifica, y los dos sensores de
  // enlaces podrian separarse en silencio. El cuerpo empieza en el primer `import`.
  const cuerpoDe = texto => {
    const lineas = texto.replace(/\r\n/g, '\n').split('\n');
    const inicio = lineas.findIndex(l => l.startsWith('import '));
    return inicio === -1 ? null : lineas.slice(inicio).join('\n');
  };
  const enlacesSandbox = sandbox.get(ESPEJADO_SALVO_CABECERA);
  if (enlacesSandbox && raizMonorepo.has(ESPEJADO_SALVO_CABECERA)) {
    const aca = cuerpoDe(fs.readFileSync(path.join(RAIZ, ESPEJADO_SALVO_CABECERA), 'utf8'));
    const alla = cuerpoDe(await contenidoSandbox(enlacesSandbox.sha));
    if (aca === null || alla === null) {
      falla(`${ESPEJADO_SALVO_CABECERA}: no se hallo el primer "import" para separar la cabecera.`);
    } else if (aca !== alla) {
      falla(
        `${ESPEJADO_SALVO_CABECERA}: el CUERPO difiere entre los dos repos, y el ADR dice que es` +
          ' identico y que solo difiere la cabecera de comentarios.',
      );
    }
  }

  const sha = git('rev-parse', 'HEAD').trim();
  const ref = git('rev-parse', '--abbrev-ref', 'HEAD').trim();
  console.log(
    `Espejo: ${PREFIJO} en ${ref} (${sha.slice(0, 8)}), ${espejado.size} archivos` +
      ` <-> ${SANDBOX}@${RAMA_SANDBOX}, ${sandbox.size} archivos.`,
  );
  console.log(
    `ADR: ${difieren.size} difieren por contenido - ${soloSandbox.size} solo en el sandbox` +
      ` - ${pendientes.size} pendientes de limpieza.`,
  );
}

try {
  await comparar();
} catch (error) {
  if (!(error instanceof Abortar)) throw error;
  for (const linea of error.lineas) if (linea) console.error(linea);
  process.exitCode = 1;
}

if (avisos.length) {
  console.log(`\n${avisos.length} aviso(s) -- no bloquean:`);
  for (const a of avisos) console.log(`  - ${a}`);
}

if (fallas.length) {
  console.error(`\nDivergencias que el ADR no explica (${fallas.length}):`);
  for (const f of fallas) console.error(`  - ${f}`);
  console.error(`\nCada una va anotada en ${ADR} en este mismo PR, o espejada al sandbox.`);
  process.exitCode = 1;
} else if (!process.exitCode) {
  console.log('\nCada diferencia entre los dos arboles tiene su entrada en el ADR.');
}
