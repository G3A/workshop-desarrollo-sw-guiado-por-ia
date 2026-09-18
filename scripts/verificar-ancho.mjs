// Sensor de ancho de linea: node scripts/verificar-ancho.mjs (desde la raiz del repo)
//
// Ninguna linea de prosa de un .md o un .mjs de los que este metodo mantiene pasa de 100
// caracteres. Nacio en #165, del paso K de la ronda de #162: el ancho es una convencion real de
// este repositorio --la prosa se envuelve cerca de 100 en los .md de la raiz, en docs/ y en los
// sensores-- y no la comprobaba ninguna maquina. En una sola ronda se rompio CUATRO veces; tres
// las cazo medir a mano y la cuarta la cazo la revision de codigo, ya con el PR abierto. Es la
// cuarta fila de la tabla de enrutamiento del metodo, la que el propio REVIEW.md nombra: un
// criterio que una maquina puede comprobar deja de ser texto y se vuelve sensor.
//
// Siete decisiones que no son de estilo:
//
// 1. Se mide en CARACTERES, con [...linea].length. `awk` cuenta bytes, asi que con acentos miente
//    --"migracion" con tilde pesa un byte mas de lo que ocupa-- y `linea.length` de JS cuenta
//    unidades UTF-16, que miente al reves con cualquier cosa fuera del plano basico. Las tres
//    respuestas se separan justo en el texto en espanol que llena este repositorio.
// 2. La lista de heredados vive ACA, como datos, y es la excepcion consciente al precedente del
//    ADR-0003. Alla la lista salio del script porque cada entrada llevaba una RAZON en prosa que
//    se habria separado de su dato; aca la entrada es una ruta y un numero, el sensor lo imprime
//    en cada corrida y lo verifica contra el archivo. No hay prosa que se desincronice.
// 3. Los heredados se CONGELAN, no se limpian: 25 archivos, 109 lineas. La razon es que 14 de los
//    25 viven bajo base-conocimiento/, que se espeja por hash de blob contra el sandbox, asi que
//    reescribirlos aca pondria el sensor del espejo en rojo hasta que un PR gemelo alla los
//    reescribiera igual. Una tarea de formato no justifica un cambio de dos repositorios.
// 4. La deuda solo se mueve en una direccion. Un heredado que empeora es ROJO; uno que mejora
//    avisa, para que su entrada baje en el mismo PR; y uno que sale de la lista ya no puede
//    volver a entrar, porque entonces cae en la regla general. Sin esto la lista es una amnistia
//    permanente en vez de un techo que baja.
// 5. Tres excepciones estructurales, y las tres salieron de MEDIR el repo, no de imaginarlas:
//    frontmatter YAML, fila de tabla, y linea con un token que por si solo ya no cabe. La tercera
//    se COMPRUEBA en vez de declararse, para que no haya una lista mas que mantener, y la
//    condicion va sobre el TOKEN: ver el comentario de inquebrantable(), donde esta escrito el
//    error que tenia y como se caza.
// 6. Un arbol sin archivos no es verde, es ceguera. Misma leccion de #144 que los otros dos
//    sensores ya tienen escrita, y aca importa igual: este sensor no sale a la red, pero un glob
//    mal escrito o una ruta mal resuelta lo dejarian midiendo cero archivos y saliendo en verde.
// 7. No se usa process.exit(), se usa process.exitCode. No hay sockets abiertos como en el sensor
//    del espejo, asi que el fallo de #155 no aplica, pero la forma se mantiene igual a proposito:
//    tres sensores que terminan distinto son tres cosas que recordar.
//
// Sin dependencias npm; usa el binario `git` del propio repositorio. Corre con cualquier
// node >= 18, en Windows y en el runner de CI. No sale a la red, asi que corre tambien en el
// pre-push, como el de enlaces.
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const RAIZ = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const TOPE = 100;
// Por debajo de esto el sensor no esta viendo el repositorio que cree: aborta en vez de comparar.
const MINIMO_ARCHIVOS = 40;

// Lo que queda fuera del alcance, y por que cada uno. Medir el repo entero daba 58 heredados sobre
// 126 archivos: una lista del tamano de la mitad del repositorio no es una excepcion, es la regla
// al reves. El alcance se acota a lo que este metodo mantiene y reescribe, que es donde la
// convencion de 100 se respeta de verdad --la mediana de lo que queda fuera esta entre 123 y 136,
// o sea dispersion real y no un tope mal calibrado--.
const FUERA = [
  // Salida de build de terceros: nadie de este repositorio la escribe ni la envuelve.
  'playbook-sdlc-ia/vendor/',
  'presentacion-validacion-workshop/',
  // El plugin se documenta en ingles y con su propia convencion de ancho; los SKILL.md y sus
  // references/ son 24 de los 58. Acotarlos es una decision suya, no de este sensor.
  'instrumentacion-java-ia/',
  // Material de trabajo, no documentacion mantenida: un plan aprobado se guarda como quedo, y las
  // notas de investigacion y de evaluacion se escriben una vez y no se reescriben.
  'base-conocimiento/docs/plans/',
  'base-conocimiento/docs/investigacion-',
  'base-conocimiento/eval-100-preguntas/',
];

// Archivos que ya excedian cuando este sensor nacio, con el maximo que tenian ese dia. Congelados:
// pueden mejorar --y entonces el sensor pide bajar el numero-- pero no empeorar. Medido en la punta
// de dev del 2026-09-18, con las cuatro excepciones estructurales ya aplicadas.
const HEREDADOS = new Map([
  ['base-conocimiento/docs/adrs/README.md', 228],
  ['base-conocimiento/AGENTS.md', 224],
  ['base-conocimiento/docs/adrs/adr-template.md', 155],
  ['docs/adrs/adr-template.md', 155],
  ['docs/adrs/0002-equivalencia-entre-el-monorepo-y-el-sandbox.md', 147],
  ['base-conocimiento/docs/design.md', 141],
  ['playbook-sdlc-ia/verificar-cobertura.mjs', 139],
  ['base-conocimiento/CLAUDE.md', 138],
  ['validacion-workshop/f2-preparar-proyecto.md', 137],
  ['manuales/procedimiento-liberacion/README.md', 136],
  ['validacion-workshop/f0-fundamentos.md', 136],
  ['base-conocimiento/docs/architecture.md', 133],
  ['base-conocimiento/docs/java.md', 130],
  ['base-conocimiento/README.md', 129],
  ['.github/pull_request_template.md', 120],
  ['base-conocimiento/docs/adrs/0008-umbral-de-relevancia-antes-de-sintesis.md', 116],
  ['base-conocimiento/docs/data-model.md', 114],
  ['manuales/manual-proceso-completo/README.md', 112],
  ['validacion-workshop/f1-preparar-maquina.md', 111],
  ['AGENTS.md', 102],
  ['base-conocimiento/docs/adrs/0009-bonsai-8b-integracion-pospuesta.md', 102],
  ['README.md', 102],
  ['scripts/verificar-enlaces.mjs', 102],
  ['base-conocimiento/docs/adrs/0011-vault-unificado.md', 101],
  ['base-conocimiento/docs/infrastructure.md', 101],
]);

const barras = ruta => ruta.split(path.sep).join('/');

// Corta la corrida con un motivo escrito. No es una linea larga: es que el sensor no pudo trabajar.
class Abortar extends Error {
  constructor(lineas) {
    super(lineas[0]);
    this.lineas = lineas;
  }
}
const abortar = (...lineas) => {
  throw new Abortar(lineas);
};

const fallas = [];
const avisos = [];
const falla = msg => fallas.push(msg);
const avisa = msg => avisos.push(msg);

function git(...args) {
  try {
    const opciones = { cwd: RAIZ, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 };
    return execFileSync('git', args, opciones);
  } catch (error) {
    abortar(`No se pudo correr "git ${args.join(' ')}" en ${barras(RAIZ)}: ${error.message}`);
  }
}

// Una linea es inevitablemente larga cuando UN SOLO TOKEN, con su sangria, ya no cabe: una URL o
// una ruta larga no se pueden envolver, envolverlas las rompe. Se comprueba en vez de declararse,
// para que no haya una quinta lista que mantener.
//
// La condicion es sobre el TOKEN, no sobre el resto de la linea. El primer intento preguntaba si
// la linea cabria al quitarle su token mas largo, y eso es cierto para casi cualquier linea de 101
// a 107 caracteres escrita con palabras normales: exceptuaba justo las que apenas se pasan, que
// son las que mas aparecen. Lo destapo sembrar prosa acentuada de 101 caracteres y verla pasar.
function inquebrantable(linea) {
  const tokens = linea.trim().split(/\s+/);
  if (!tokens.length) return false;
  const sangria = [...linea].length - [...linea.trimStart()].length;
  const largo = Math.max(...tokens.map(t => [...t].length));
  return sangria + largo > TOPE;
}

// El maximo de PROSA del archivo: el ancho de la linea mas larga que no cae en ninguna excepcion.
// Devuelve 0 cuando ninguna linea excede, que es el estado deseado.
function maximoDeProsa(ruta) {
  const lineas = fs.readFileSync(path.join(RAIZ, ruta), 'utf8').replace(/\r\n/g, '\n').split('\n');
  // Frontmatter YAML, solo si el archivo empieza con el marcador. El `description:` de cada
  // SKILL.md es una linea por construccion --llega a 1791 caracteres-- y envolverla cambiaria el
  // formato que Claude Code lee.
  const finFrontmatter = lineas[0] === '---' ? lineas.indexOf('---', 1) : -1;
  let max = 0;
  let peor = 0;
  lineas.forEach((linea, i) => {
    const ancho = [...linea].length;
    if (ancho <= TOPE) return;
    if (i <= finFrontmatter) return;
    // Una celda de tabla no se envuelve; el ADR-0003 ya tiene filas de 250.
    if (/^\s*\|/.test(linea)) return;
    if (inquebrantable(linea)) return;
    if (ancho <= max) return;
    max = ancho;
    peor = i + 1;
  });
  return { max, peor };
}

function verificar() {
  // Guarda estructural, igual que la de los otros dos sensores: si alguien baja este script una
  // carpeta, mide un arbol que no es el que cree y el resultado no significa nada.
  const raizGit = barras(path.resolve(git('rev-parse', '--show-toplevel').trim()));
  if (raizGit !== barras(RAIZ)) {
    abortar(
      'Este sensor tiene que vivir en <raiz-del-repo>/scripts/, y no es el caso:',
      `  raiz del repositorio git: ${raizGit}`,
      `  lo que el sensor mira:    ${barras(RAIZ)}`,
    );
  }

  const archivos = git('ls-files', '-z', '*.md', '*.mjs')
    .split('\0')
    .filter(Boolean)
    .map(r => r.normalize('NFC'))
    .filter(r => !FUERA.some(f => r.startsWith(f)));

  if (archivos.length < MINIMO_ARCHIVOS) {
    abortar(
      `Se midieron ${archivos.length} archivos, menos de ${MINIMO_ARCHIVOS}.`,
      'Un sensor que no ve archivos no esta en verde: esta ciego. Ver #144.',
    );
  }

  const vistos = new Set();
  let excedidos = 0;
  for (const ruta of archivos) {
    const { max, peor } = maximoDeProsa(ruta);
    const techo = HEREDADOS.get(ruta);
    if (techo === undefined) {
      if (!max) continue;
      excedidos += 1;
      falla(`${ruta}:${peor} tiene ${max} caracteres; el tope es ${TOPE}.`);
      continue;
    }
    vistos.add(ruta);
    if (max > techo) {
      excedidos += 1;
      falla(
        `${ruta}:${peor} tiene ${max} caracteres y su techo heredado es ${techo}.` +
          ' Un heredado puede mejorar, nunca empeorar.',
      );
      continue;
    }
    if (max === techo) continue;
    // Mejoro: se avisa para que la entrada baje en el mismo PR que la mejoro. Si no, el techo
    // queda mas alto que el archivo y la lista pasa a ser una amnistia en vez de un techo.
    const ahora = max ? `bajo a ${max}` : 'ya no excede el tope';
    avisa(
      `${ruta} ${ahora} y su entrada en HEREDADOS sigue en ${techo}:` +
        `${max ? ` bajala a ${max}` : ' sacalo de la lista'}.`,
    );
  }

  for (const ruta of HEREDADOS.keys()) {
    if (vistos.has(ruta)) continue;
    avisa(`HEREDADOS: "${ruta}" ya no existe o no se mide; su entrada sobra.`);
  }

  console.log(
    `Ancho: ${archivos.length} archivos .md y .mjs medidos contra un tope de ${TOPE} caracteres` +
      ` -- ${HEREDADOS.size} heredados congelados, ${excedidos} fuera de regla.`,
  );
}

try {
  verificar();
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
  console.error(`\nLineas de prosa sobre el tope (${fallas.length}):`);
  for (const f of fallas) console.error(`  - ${f}`);
  console.error('\nEnvuelvelas, o si el archivo es de terceros agregalo a FUERA.');
  process.exitCode = 1;
} else if (!process.exitCode) {
  console.log('\nNinguna linea de prosa pasa del tope, y ningun heredado empeoro.');
}
