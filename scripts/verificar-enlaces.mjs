// Sensor de enlaces de la documentacion: node scripts/verificar-enlaces.mjs (desde la raiz del repo)
//
// Falla si un enlace de cualquier .md del monorepo apunta a un archivo que no existe, o si su ancla
// #... no coincide con ningun encabezado del destino. Nacio en #120, donde atrapo dos anclas rotas
// que nadie veia. En #144 gano la raiz del repositorio: vivia en base-conocimiento/ y miraba cuatro
// archivos fijos, asi que REVIEW.md, EXPERIMENTS.md y .github/pull_request_template.md quedaban
// fuera de su alcance -- y el enlace roto de #121 vivia justo ahi. Un sensor que no mira donde
// ocurre el bug pasa en verde mientras el bug viaja.
//
// Tres decisiones que no son de estilo:
//
// 1. La lista de archivos sale de `git ls-files --cached --others --exclude-standard`: lo rastreado
//    Y lo nuevo sin `git add`, descontando lo que .gitignore ignora. No hay lista de exclusiones
//    que envejezca, y un .md recien escrito se revisa igual que uno commiteado.
// 2. Los destinos se resuelven contra ese mismo indice, no con fs.existsSync. existsSync ignora las
//    mayusculas en Windows y las respeta en el runner Linux, asi que un [x](Docs/java.md) pasaria
//    el pre-push local y rompia el CI. El indice es exacto en los dos sistemas, y de paso atrapa
//    el enlace a un archivo que se escribio pero no se agrego al repositorio.
// 3. Los enlaces absolutos al PROPIO repositorio tambien se revisan (#144). La plantilla de PR
//    enlaza REVIEW.md por URL absoluta a proposito -- una ruta relativa da 404 en el cuerpo
//    renderizado de una PR --, asi que saltarse todo http(s) dejaba sin sensor justo el enlace del
//    que trata este issue. Es comparacion de cadenas contra el indice: no sale a la red.
//
// Los .md de instrumentacion-java-ia/ entran en el barrido, pero son plantillas de un plugin que se
// leen desde OTRO repositorio (#140): un enlace de ejemplo como [x](pom.xml) es correcto alla y
// roto aca. En esos archivos los ejemplos de enlace van entre backticks, que este sensor ignora.
//
// Sin dependencias npm; usa el binario `git` del propio repositorio. Corre con cualquier node >= 18,
// en Windows y en el runner de CI.
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const RAIZ = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const RAMA_DE_INTEGRACION = 'dev';

const barras = ruta => ruta.split(path.sep).join('/');

function git(...args) {
  try {
    return execFileSync('git', args, { cwd: RAIZ, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
  } catch (error) {
    console.error(`No se pudo correr "git ${args.join(' ')}" en ${barras(RAIZ)}: ${error.message}`);
    process.exit(1);
  }
}

// Guarda estructural: el sensor tiene que estar mirando la raiz del repositorio git. Si alguien lo
// baja una carpeta, o lo copia a otro repo, el barrido se angosta y el sensor sale VERDE mirando
// menos -- que es exactamente la forma en que #144 dejo pasar el enlace roto de #121.
const raizGit = barras(path.resolve(git('rev-parse', '--show-toplevel').trim()));
if (raizGit !== barras(RAIZ)) {
  console.error('Este sensor tiene que vivir en <raiz-del-repo>/scripts/, y no es el caso:');
  console.error(`  raiz del repositorio git: ${raizGit}`);
  console.error(`  lo que el sensor mira:    ${barras(RAIZ)}`);
  process.exit(1);
}

// El indice: todo archivo del repositorio, no solo los .md. Los .md de aca salen las fuentes que se
// barren; el conjunto entero es contra lo que se resuelve cada destino.
const indice = new Set(
  git('ls-files', '-z', '--cached', '--others', '--exclude-standard').split('\0').filter(Boolean),
);
if (indice.size === 0) {
  console.error(`git no listo ningun archivo en ${barras(RAIZ)}.`);
  console.error('Un sensor que no ve nada no esta en verde: esta ciego. Ver #144.');
  process.exit(1);
}

// Los directorios no estan en el indice y hay enlaces que apuntan a uno ([...](docs/adrs/)).
const directorios = new Set();
for (const ruta of indice) {
  let dir = path.posix.dirname(ruta);
  while (dir !== '.' && !directorios.has(dir)) {
    directorios.add(dir);
    dir = path.posix.dirname(dir);
  }
}

// De git@github.com:G3A/repo.git y de https://github.com/G3A/repo.git sale el mismo G3A/repo. Sirve
// igual en el sandbox espejo, que tiene este archivo en la misma ruta y otro remoto.
//
// Opcional a proposito: una copia sin `origin` -- un `git init` local, o un clon cuyo remoto se
// llame `upstream` -- se queda sin el chequeo de enlaces absolutos, no sin sensor. Abortar ahi
// bloquearia todo `git push` por un error de git, no por un enlace roto.
function gitOpcional(...args) {
  try {
    const opciones = { cwd: RAIZ, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] };
    return execFileSync('git', args, opciones).trim();
  } catch {
    return null;
  }
}

const remoto = gitOpcional('remote', 'get-url', 'origin');
const propio = remoto && remoto.match(/github\.com[:/](.+?)(?:\.git)?\/?$/i);
const REPO_PROPIO = propio ? propio[1].toLowerCase() : null;

// blob/ y tree/ (un enlace a un directorio se escribe con tree/), con o sin www.
const URL_PROPIA = /^https?:\/\/(?:www\.)?github\.com\/(.+?)\/(?:blob|tree)\/(.+)$/i;

// Quita los bloques de codigo cercados y, salvo que se pida conservarlo, el codigo en linea, sin
// tocar los saltos de linea para que los numeros de linea sigan valiendo: un [x](y) dentro de
// backticks no es un enlace. Los encabezados lo conservan: GitHub mantiene ese texto en el id.
function sinCodigo(texto, conservarEnLinea = false) {
  let enBloque = false;
  return texto
    .split('\n')
    .map(linea => {
      if (/^\s*(```|~~~)/.test(linea)) {
        enBloque = !enBloque;
        return '';
      }
      if (enBloque) return '';
      return conservarEnLinea ? linea : linea.replace(/`[^`]*`/g, '');
    })
    .join('\n');
}

// Regla de GitHub para el id de un encabezado: minusculas, fuera todo lo que no sea letra, numero,
// espacio, guion o guion bajo, espacios a guiones; los repetidos llevan -1, -2...
function anclasDe(rutaRepo) {
  const vistas = new Map();
  const anclas = new Set();
  const contenido = leer(rutaRepo);
  if (contenido === null) return anclas;
  for (const linea of sinCodigo(contenido, true).split('\n')) {
    const encabezado = linea.match(/^#{1,6}\s+(.*?)\s*#*\s*$/);
    if (encabezado) {
      const texto = encabezado[1].replace(/\[([^\]]*)\]\([^)]*\)/g, '$1').replace(/[*`]/g, '');
      const base = texto.toLowerCase().replace(/[^\p{L}\p{N}\p{M} _-]/gu, '').replace(/ /g, '-');
      const n = vistas.get(base) ?? 0;
      vistas.set(base, n + 1);
      anclas.add(n === 0 ? base : `${base}-${n}`);
    }
    for (const html of linea.matchAll(/<a\s+(?:name|id)="([^"]+)"/g)) anclas.add(html[1]);
  }
  return anclas;
}

const fallas = [];

// Un archivo del indice puede no estar en disco: rastreado y borrado con `rm` en vez de `git rm`, o
// un sparse-checkout. Se reporta, no se ignora en silencio.
function leer(rutaRepo) {
  try {
    return fs.readFileSync(path.join(RAIZ, rutaRepo), 'utf8');
  } catch (error) {
    fallas.push(`${rutaRepo}: el repositorio lo lista pero no se pudo leer (${error.code})`);
    return null;
  }
}

// Un destino puede traer ?plain=1 antes del ancla, y un % suelto rompe decodeURIComponent.
function partir(destino) {
  const corte = destino.indexOf('#');
  const ruta = corte === -1 ? destino : destino.slice(0, corte);
  const ancla = corte === -1 ? '' : destino.slice(corte + 1);
  return [decodificar(ruta.split('?')[0]), decodificar(ancla)];
}

function decodificar(texto) {
  try {
    return decodeURIComponent(texto);
  } catch {
    return texto;
  }
}

// Un enlace a un directorio suele llevar barra final ([...](docs/adrs/)) y normalize() la conserva;
// el indice no la lleva.
const sinBarraFinal = ruta => (ruta.length > 1 ? ruta.replace(/\/+$/, '') : ruta);

const existe = rutaRepo => indice.has(rutaRepo) || directorios.has(sinBarraFinal(rutaRepo));

function revisarAncla(donde, destino, rutaRepo, ancla, cache) {
  if (!ancla || !rutaRepo.endsWith('.md')) return;
  if (!cache.has(rutaRepo)) cache.set(rutaRepo, anclasDe(rutaRepo));
  if (!cache.get(rutaRepo).has(ancla)) {
    fallas.push(`${donde}: ${destino} -> no hay encabezado con el ancla #${ancla}`);
  }
}

const archivos = [...indice].filter(f => f.endsWith('.md')).sort();
const cacheAnclas = new Map();
let relativos = 0;
let absolutos = 0;

for (const archivo of archivos) {
  const contenido = leer(archivo);
  if (contenido === null) continue;
  sinCodigo(contenido).split('\n').forEach((linea, i) => {
    for (const m of linea.matchAll(/!?\[[^\]]*\]\(\s*<?([^)\s>]+)>?(?:\s+"[^"]*")?\s*\)/g)) {
      const destino = m[1];
      const donde = `${archivo}:${i + 1}`;

      if (/^[a-z][a-z0-9+.-]*:/i.test(destino)) {
        // http:, https:, mailto: de otros dominios no son asunto de este sensor; los del propio
        // repositorio si (#144).
        const propia = REPO_PROPIO && destino.match(URL_PROPIA);
        if (!propia || propia[1].toLowerCase() !== REPO_PROPIO) continue;
        absolutos++;
        const resto = propia[2];
        const corte = resto.indexOf('/');
        if (corte === -1) {
          fallas.push(`${donde}: ${destino} -> le falta la ruta despues de la rama`);
          continue;
        }
        const ref = resto.slice(0, corte);
        const [ruta, ancla] = partir(resto.slice(corte + 1));
        if (ref !== RAMA_DE_INTEGRACION && !/^[0-9a-f]{40}$/.test(ref)) {
          fallas.push(
            `${donde}: ${destino} -> apunta a "${ref}"; usa ${RAMA_DE_INTEGRACION} o un SHA completo`,
          );
        }
        if (!existe(ruta)) {
          fallas.push(`${donde}: ${destino} -> no existe ${ruta} en este repositorio`);
          continue;
        }
        revisarAncla(donde, destino, ruta, ancla, cacheAnclas);
        continue;
      }

      relativos++;
      const [ruta, ancla] = partir(destino);
      if (!ruta) {
        revisarAncla(donde, destino, archivo, ancla, cacheAnclas);
        continue;
      }
      // Un enlace que empieza por "/" no apunta a la raiz del repositorio: GitHub lo resuelve desde
      // la raiz del SITIO, asi que [x](/docs/a.md) va a github.com/docs/a.md y da 404.
      if (ruta.startsWith('/')) {
        fallas.push(
          `${donde}: ${destino} -> empieza por "/", que GitHub resuelve desde la raiz del sitio`,
        );
        continue;
      }
      const objetivo = path.posix.normalize(path.posix.join(path.posix.dirname(archivo), ruta));
      if (objetivo.startsWith('..')) {
        fallas.push(`${donde}: ${destino} -> sale del repositorio`);
        continue;
      }
      if (!existe(objetivo)) {
        fallas.push(`${donde}: ${destino} -> no existe ${objetivo}`);
        continue;
      }
      revisarAncla(donde, destino, objetivo, ancla, cacheAnclas);
    }
  });
}

if (fallas.length) {
  console.error(`Enlaces rotos (${fallas.length}):`);
  for (const f of fallas) console.error(`  ${f}`);
  process.exit(1);
}
console.log(
  `OK: ${relativos} enlaces relativos y ${absolutos} al propio repositorio` +
    ` en ${archivos.length} archivos, todos resuelven.`,
);
