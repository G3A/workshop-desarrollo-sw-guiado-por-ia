// Sensor del espejo con el sandbox: node scripts/verificar-espejo.mjs (desde la raiz del repo)
//
// Compara base-conocimiento/ del monorepo contra la raiz de G3A/base-conocimiento-sandbox por
// hashes de blob, y falla cuando aparece una diferencia que el ADR-0003 no explica. Sobre esa
// comparacion de arboles hay dos afirmaciones de CONTENIDO que el ADR hace sobre archivos anclados
// a la raiz, y que este sensor tambien comprueba: el cuerpo de scripts/verificar-enlaces.mjs es
// identico salvo su cabecera, y los criterios de REVIEW.md --sus encabezados y los titulos en
// negrita de sus secciones numeradas-- son los mismos, aunque el resto del archivo difiera a
// proposito. Lo segundo entro en #162, despues de que tres vinetas derivaran sin que nada avisara.
//
// Nacio en #155: hasta entonces la lista de divergencias del ADR se mantenia a mano, y el documento
// admitia -- "esta lista envejece [...] y nada lo verifica a maquina". En #144 esa falta de sensor
// dejo pasar un hecho falso al plan ("el sandbox no tiene el sensor de enlaces") que salio de un
// grep sobre un clon 15 PR atras del remoto.
//
// La lista NO vive aca: sale del ADR, parseada. Si el ADR y los arboles discrepan, uno de los dos
// esta mal, y eso es justo la senal. Duplicarla en este archivo seria la segunda copia que se
// desincroniza, que es el defecto por el que el ADR-0002 ya habia descartado el claims-ledger.
//
// Ocho decisiones que no son de estilo:
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
// 8. Los criterios de REVIEW.md se comparan con severidad ASIMETRICA, que es la decision 4 llevada
//    a un archivo que difiere a proposito. Un criterio que esta aca y falta alla es rojo: lo agrego
//    alguien de este repositorio y su autor lo puede espejar. Al reves solo avisa: bloquear por un
//    criterio que agregaron alla frenaria a la siguiente PR de aca, que no toco nada.
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
// Los dos archivos de la lista "solo en el sandbox" sobre los que el ADR afirma algo del CONTENIDO,
// cada uno con su propia clase de afirmacion. El primero se compara byte a byte descontando la
// cabecera; el segundo NO --difiere a proposito en casi todo-- y solo se le comparan los titulos.
const ESPEJADO_SALVO_CABECERA = 'scripts/verificar-enlaces.mjs';
const CRITERIOS = 'REVIEW.md';
// Las seis secciones numeradas de REVIEW.md. Menos que esto es ceguera, no verde: si el parseo se
// rompe, los titulos salen vacios en los DOS lados, coinciden, y la comparacion pasa sin ver nada.
const MINIMO_SECCIONES = 6;

const barras = ruta => ruta.split(path.sep).join('/');
const dormir = ms => new Promise(resolver => setTimeout(resolver, ms));

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
    // Solo las vinetas de primer nivel son entradas. Una sub-vineta indentada bajo una razon es
    // Markdown valido y no viola ninguna de las tres reglas de escritura del ADR: tratarla como
    // entrada pondria el CI en rojo hablando del formato en vez de un problema real.
    if (!/^-\s/.test(linea)) continue;
    const m = linea.match(/^-\s+`([^`]+)`\s+(?:—|--)\s+(\S.*)$/);
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

// La cuarta lista no guarda rutas, guarda TITULOS en negrita: criterios de REVIEW.md que valen solo
// aca (los que hablan del plugin, del visor o del playbook, que alla no existen). Se escriben tal
// como aparecen en REVIEW.md para poder copiarlos y pegarlos sin traducir nada.
function entradasDeCriterios(bloque, nombre) {
  const entradas = [];
  for (const linea of bloque.split('\n')) {
    if (!/^-\s/.test(linea)) continue;
    const m = linea.match(/^-\s+\*\*(.+?)\*\*\s+(?:—|--)\s+(\S.*)$/);
    if (!m) {
      falla(`${ADR} (espejo:${nombre}): entrada ilegible -> ${linea.trim()}`);
      continue;
    }
    // La clave de esta lista es el titulo, no una ruta; mapaDeEntradas solo necesita una clave.
    entradas.push({ ruta: m[1].replace(/\s+/g, ' ').trim().normalize('NFC'), razon: m[2] });
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
  // Tambien puede quedar vacia, y por la misma razon que la anterior: hoy no hay ningun criterio
  // que valga solo aca. Existe desde el primer dia para que el primero que aparezca tenga donde
  // declararse, en vez de obligar a copiarlo al sandbox --donde no significa nada-- solo para
  // el CI.
  const criteriosSoloAca = mapaDeEntradas(
    entradasDeCriterios(
      bloqueDelAdr(texto, 'criterios-solo-en-el-monorepo'),
      'criterios-solo-en-el-monorepo',
    ),
    'criterios-solo-en-el-monorepo',
  );
  return { difieren, soloSandbox, pendientes, criteriosSoloAca };
}

// --- Los criterios de REVIEW.md --------------------------------------------------------------

// Devuelve, por cada seccion NUMERADA ("## N - Titulo"), su encabezado y la secuencia de titulos en
// negrita de sus vinetas. Tres decisiones de parseo que no son de estilo:
//
// a. La vineta se une en UNA linea y se colapsan los espacios antes de buscar la negrita. Este
//    archivo tiene un ancho maximo de 100, asi que hay titulos que se parten en dos lineas y el
//    `**` de cierre cae en la siguiente; mirando linea por linea esos salen "sin titulo" en vez de
//    compararse. Y el corte no cae en el mismo sitio en los dos repos, porque el texto que rodea al
//    titulo difiere a proposito: el falso positivo seria asimetrico y desconcertante.
// b. El patron NO se ancla al "- [ ]". La vineta de Flyway empieza con "*(Anadido: ...)*" en los
//    DOS repos: anclada, la seccion 4 quedaria sin titulos de los dos lados, las dos secuencias
//    saldrian vacias, coincidirian, y esa seccion dejaria de compararse EN SILENCIO.
// c. Solo las secciones numeradas. "Que no reportar" y "Como evoluciona esta lista" quedan fuera a
//    proposito: el ADR ya declara que el REVIEW.md del sandbox no menciona el plugin ni
//    playbook-sdlc-ia/vendor/, y eso vive justo en la primera.
function seccionesDeCriterios(texto, donde) {
  // Las dos mismas normalizaciones que listasDelAdr, y por las mismas dos razones: el \r es
  // terminador de linea en una regex de JS, y los dos lados entran por caminos distintos --un blob
  // local y el base64 de la API--, asi que un acento en NFD daria dos titulos identicos a la vista
  // que comparan distinto.
  const lineas = sinCodigo(texto.replace(/\r\n/g, '\n').normalize('NFC')).split('\n');
  const secciones = new Map();
  let actual = null;
  let vineta = null;

  const cerrar = () => {
    if (!vineta) return;
    const unida = vineta.join(' ').replace(/\s+/g, ' ').trim();
    const m = unida.match(/\*\*(.+?)\*\*/);
    if (!m) {
      falla(`${CRITERIOS} (${donde}): vineta sin titulo en negrita -> "${unida.slice(0, 70)}"`);
    } else {
      const titulo = m[1].trim();
      if (actual.titulos.includes(titulo)) {
        falla(
          `${CRITERIOS} (${donde}): "${titulo}" esta dos veces en la seccion ${actual.numero}.`,
        );
      }
      actual.titulos.push(titulo);
    }
    vineta = null;
  };

  for (const linea of lineas) {
    if (linea.startsWith('##')) {
      cerrar();
      actual = null;
      // Se aceptan el punto medio y el guion como separador: un separador tecleado distinto haria
      // desaparecer la seccion entera en silencio, que es peor que compararla y reportar la
      // diferencia del encabezado.
      const enc = linea.match(/^##\s+(\d+)\s+(?:·|-)\s+(.+?)\s*$/);
      if (!enc) continue;
      if (secciones.has(enc[1])) {
        falla(`${CRITERIOS} (${donde}): la seccion ${enc[1]} aparece dos veces.`);
      }
      actual = { numero: enc[1], encabezado: `${enc[1]} · ${enc[2].trim()}`, titulos: [] };
      secciones.set(enc[1], actual);
      continue;
    }
    if (!actual) continue;
    if (/^-\s/.test(linea)) {
      cerrar();
      vineta = [linea];
      continue;
    }
    // La vineta termina donde empieza la siguiente, donde acaba la seccion, o en la primera linea
    // en blanco: sin esto el parrafo suelto que sigue a una lista se pegaria a la ultima vineta y
    // una negrita suya se leeria como su titulo.
    if (!vineta) continue;
    if (linea.trim()) vineta.push(linea);
    else cerrar();
  }
  cerrar();

  if (secciones.size < MINIMO_SECCIONES) {
    abortar(
      `${CRITERIOS} (${donde}): se parsearon ${secciones.size} secciones numeradas, menos de` +
        ` ${MINIMO_SECCIONES}.`,
      'Un sensor que no ve las secciones no esta en verde: esta ciego. Ver #144.',
    );
  }
  for (const seccion of secciones.values()) {
    if (seccion.titulos.length) continue;
    abortar(
      `${CRITERIOS} (${donde}): la seccion ${seccion.numero} quedo sin ninguna vineta parseada.`,
      'Dos secciones vacias coinciden entre si, asi que esto pasaria en verde sin comparar nada.',
    );
  }
  return secciones;
}

// La severidad es ASIMETRICA, y es la misma razon de la decision 4 de la cabecera: lo que merece
// frenar una PR es la diferencia que ESTA PR causo. Un criterio que esta aca y falta alla lo agrego
// alguien de este repositorio y su autor lo puede espejar --rojo--. Uno que esta alla y falta aca
// lo agrego alguien del sandbox, y bloquear por eso castigaria a la siguiente PR de aca, que no
// toco nada --aviso--. La excepcion es el encabezado de una seccion que existe en los dos: no falta
// ningun criterio y no hay direccion que inferir, pero dejarlo en aviso vaciaria la mitad de lo que
// esta comprobacion promete, asi que es rojo y se arregla igualando el encabezado.
function compararCriterios(aqui, alla, soloAca, vistos) {
  const todas = [...new Set([...aqui.keys(), ...alla.keys()])];
  const numeros = todas.sort((a, b) => Number(a) - Number(b));
  for (const numero of numeros) {
    const aca = aqui.get(numero);
    const ya = alla.get(numero);
    if (!ya) {
      falla(
        `${CRITERIOS}: la seccion "${aca.encabezado}" existe aca y no en el sandbox;` +
          ' los criterios de las secciones numeradas son del equipo, no del repositorio.',
      );
      continue;
    }
    if (!aca) {
      avisa(`${CRITERIOS}: la seccion "${ya.encabezado}" existe en el sandbox y no aca.`);
      continue;
    }
    if (aca.encabezado !== ya.encabezado) {
      falla(
        `${CRITERIOS}: el encabezado de la seccion ${numero} difiere -- "${aca.encabezado}" aca` +
          ` y "${ya.encabezado}" en el sandbox.`,
      );
    }
    // Los criterios declarados como propios del monorepo salen de la secuencia esperada: si no, la
    // unica forma de poner el CI en verde seria copiarlos al sandbox, donde no significan nada.
    const esperados = [];
    for (const titulo of aca.titulos) {
      if (soloAca.has(titulo)) {
        vistos.add(titulo);
        continue;
      }
      esperados.push(titulo);
    }
    let faltan = false;
    for (const titulo of esperados) {
      if (ya.titulos.includes(titulo)) continue;
      falla(`${CRITERIOS} seccion ${numero}: "${titulo}" esta aca y falta en el sandbox.`);
      faltan = true;
    }
    for (const titulo of ya.titulos) {
      if (aca.titulos.includes(titulo)) continue;
      avisa(`${CRITERIOS} seccion ${numero}: "${titulo}" esta en el sandbox y falta aca.`);
      faltan = true;
    }
    // Solo cuando no falta ninguno Y las dos secuencias miden lo mismo. Si faltan, el orden todavia
    // no se puede comparar y decirlo seria ruido encima del hallazgo real; y un titulo repetido de
    // un solo lado deja las dos listas con los mismos titulos y distinto largo, que es un problema
    // de duplicado --ya reportado al parsear-- y no de orden. Lo destapo la siembra del duplicado:
    // la lectura no lo vio y habria salido un aviso espurio encima del rojo verdadero. Con los
    // mismos titulos en distinto orden las dos listas se leen distinto aunque digan lo mismo, y
    // comparar conjuntos en vez de secuencias no lo ve.
    if (faltan || esperados.length !== ya.titulos.length) continue;
    if (JSON.stringify(esperados) === JSON.stringify(ya.titulos)) continue;
    avisa(`${CRITERIOS} seccion ${numero}: los mismos criterios estan en distinto orden.`);
  }
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
      // El cuerpo se lee DENTRO del try, y no con un `return respuesta.json()` afuera: un timeout
      // que salta mientras baja el arbol, o un 200 que trae una pagina de mantenimiento en vez de
      // JSON, tiene que caer en el reintento y en el mensaje de "no pudo ver el otro arbol". Si
      // escapa de aca sale como una traza cruda, que es justo el caso para el que se escribio la
      // decision 5 de la cabecera.
      if (respuesta.ok) return await respuesta.json();
    } catch (error) {
      ultima = `no hubo respuesta (${error.name}: ${error.message})`;
      await dormir(2000);
      continue;
    }
    const restantes = respuesta.headers.get('x-ratelimit-remaining');
    const reintentarEn = Number(respuesta.headers.get('retry-after'));
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
    // Con espera, y respetando Retry-After. Un reintento inmediato contra un limite secundario de
    // GitHub vuelve a fallar milisegundos despues y encima consume cuota: no compra nada en el
    // unico escenario --el rate limit-- para el que el workflow cablea el token.
    await dormir(Math.min(reintentarEn > 0 ? reintentarEn : 3, 30) * 1000);
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
  if (!Array.isArray(json.tree)) {
    abortar(`La API respondio 200 pero sin arbol para ${SANDBOX}@${RAMA_SANDBOX}.`);
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
  // Un blob de mas de 1 MB vuelve con content vacio y encoding "none". Decodificarlo daria una
  // cadena vacia, y el sensor terminaria culpando a la cabecera del archivo por no encontrar su
  // primer `import` en vez de decir que no pudo bajar el blob.
  if (json.encoding !== 'base64' || !json.content) {
    abortar(
      `El blob ${sha.slice(0, 8)} de ${SANDBOX} no vino en base64 (encoding: ${json.encoding}).`,
      'La API no devuelve en linea los blobs de mas de 1 MB.',
    );
  }
  return Buffer.from(json.content, 'base64').toString('utf8');
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

  const { difieren, soloSandbox, pendientes, criteriosSoloAca } = listasDelAdr();
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
    // Estar en la lista explica una divergencia de CONTENIDO, que es lo unico que el ADR declara.
    // Sin esta linea, un `chmod +x` sobre cualquiera de los 9 archivos listados pasaria en silencio
    // --la entrada lo daria por explicado-- y la decision 1 de la cabecera quedaria en nada justo
    // sobre los archivos que mas se tocan.
    if (local.modo !== remoto.modo) {
      falla(
        `modo distinto: ${ruta} es ${local.modo} aca y ${remoto.modo} en el sandbox; el ADR solo` +
          ' declara para este archivo una divergencia de contenido.',
      );
    }
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
  const enlacesLocal = raizMonorepo.get(ESPEJADO_SALVO_CABECERA);
  if (enlacesSandbox && enlacesLocal) {
    // Del blob de HEAD (`cat-file`), no del arbol de trabajo: todo el resto de la comparacion mira
    // el commit que el reporte anuncia, y leer el disco aca inventaria una divergencia que ese
    // commit no tiene --o escondería una que si--. Se pasa el sha, no la ruta: `git show ref:ruta`
    // lleva dos puntos, que MSYS reescribe como si fuera una lista de rutas de Windows.
    const aca = cuerpoDe(git('cat-file', 'blob', enlacesLocal.sha));
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

  // La segunda afirmacion de contenido del ADR, y de otra clase que la anterior: REVIEW.md difiere
  // a proposito en casi todo su cuerpo --preambulo, prefijos, menciones al plugin-- pero sus
  // CRITERIOS son del equipo, no del repositorio, igual que los cuatro limites del permiso de
  // EXPERIMENTS.md. Hasta #162 nada lo comprobaba y tres vinetas llevaban meses derivando.
  const criteriosAlla = sandbox.get(CRITERIOS);
  const criteriosAca = raizMonorepo.get(CRITERIOS);
  if (!criteriosAca || !criteriosAlla) {
    // Rojo explicito, no un `if` que se salta la comprobacion entera: si REVIEW.md desaparece de un
    // lado, la unica senal seria el aviso de la entrada sobrante del ADR, que no bloquea, y este
    // sensor se apagaria sin que nadie se entere.
    const donde = criteriosAca ? 'el sandbox' : 'la raiz del monorepo';
    falla(`${CRITERIOS}: no existe en ${donde}, asi que no hay dos lados que comparar.`);
  } else {
    // Del blob de HEAD, no del disco, por la misma razon que el archivo de al lado: todo el resto
    // de la comparacion mira el commit que el reporte anuncia.
    const vistos = new Set();
    compararCriterios(
      seccionesDeCriterios(git('cat-file', 'blob', criteriosAca.sha), 'monorepo'),
      seccionesDeCriterios(await contenidoSandbox(criteriosAlla.sha), 'sandbox'),
      criteriosSoloAca,
      vistos,
    );
    sobrante(criteriosSoloAca, vistos, 'criterios-solo-en-el-monorepo');
  }

  // En un evento pull_request, actions/checkout deja HEAD desprendido sobre un merge commit
  // efimero: `--abbrev-ref` devuelve literalmente "HEAD" y ese SHA no lo puede traer nadie despues.
  // El ADR promete que este reporte sirve para reproducir un rojo, asi que en CI el nombre de la
  // rama y el SHA de su punta llegan cableados desde el workflow.
  const sha = process.env.ESPEJO_SHA || git('rev-parse', 'HEAD').trim();
  const ref =
    process.env.ESPEJO_REF ||
    process.env.GITHUB_HEAD_REF ||
    git('rev-parse', '--abbrev-ref', 'HEAD').trim();
  console.log(
    `Espejo: ${PREFIJO} en ${ref} (${sha.slice(0, 8)}), ${espejado.size} archivos` +
      ` <-> ${SANDBOX}@${RAMA_SANDBOX}, ${sandbox.size} archivos.`,
  );
  console.log(
    `ADR: ${difieren.size} difieren por contenido - ${soloSandbox.size} solo en el sandbox` +
      ` - ${pendientes.size} pendientes de limpieza` +
      ` - ${criteriosSoloAca.size} criterios solo en el monorepo.`,
  );
  console.log(`Criterios: ${CRITERIOS} comparado por encabezado y titulos de sus secciones.`);
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
