// Sensor de enlaces de la documentacion: node scripts/verificar-enlaces.mjs (desde base-conocimiento/)
//
// Falla si un enlace relativo de AGENTS.md, CLAUDE.md, COMPONENTS.md, README.md o docs/**/*.md
// apunta a un archivo que no existe, o si su ancla #... no coincide con ningun encabezado del
// archivo destino. En #120 aparecieron dos anclas rotas que nadie veia: architecture.md hacia F8
// del plan del proyecto (el titulo habia ganado un "completado") y java.md hacia una seccion de
// AGENTS.md que ya no existia. Un texto que pida revisarlas no las atrapa; esto si.
//
// Sin dependencias: corre con cualquier node >= 18, en Windows y en el runner de CI.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const RAIZ = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const RAICES = ['AGENTS.md', 'CLAUDE.md', 'COMPONENTS.md', 'README.md'];

function markdownsBajo(dir) {
  const salida = [];
  for (const entrada of fs.readdirSync(dir, { withFileTypes: true })) {
    const ruta = path.join(dir, entrada.name);
    if (entrada.isDirectory()) salida.push(...markdownsBajo(ruta));
    else if (entrada.name.endsWith('.md')) salida.push(ruta);
  }
  return salida;
}

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
function anclasDe(archivo) {
  const vistas = new Map();
  const anclas = new Set();
  for (const linea of sinCodigo(fs.readFileSync(archivo, 'utf8'), true).split('\n')) {
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

const archivos = [
  ...RAICES.map(f => path.join(RAIZ, f)).filter(f => fs.existsSync(f)),
  ...markdownsBajo(path.join(RAIZ, 'docs')),
];

const fallas = [];
const cacheAnclas = new Map();
let revisados = 0;

for (const archivo of archivos) {
  const lineas = sinCodigo(fs.readFileSync(archivo, 'utf8')).split('\n');
  lineas.forEach((linea, i) => {
    for (const m of linea.matchAll(/!?\[[^\]]*\]\(\s*<?([^)\s>]+)>?(?:\s+"[^"]*")?\s*\)/g)) {
      const destino = m[1];
      if (/^[a-z][a-z0-9+.-]*:/i.test(destino)) continue; // http:, https:, mailto:...
      revisados++;
      const donde = `${path.relative(RAIZ, archivo).replace(/\\/g, '/')}:${i + 1}`;
      const [ruta, ancla] = destino.split('#');
      const objetivo = ruta ? path.resolve(path.dirname(archivo), decodeURIComponent(ruta)) : archivo;
      if (!fs.existsSync(objetivo)) {
        fallas.push(`${donde}: ${destino} -> no existe ${path.relative(RAIZ, objetivo).replace(/\\/g, '/')}`);
        continue;
      }
      if (ancla && objetivo.endsWith('.md')) {
        if (!cacheAnclas.has(objetivo)) cacheAnclas.set(objetivo, anclasDe(objetivo));
        if (!cacheAnclas.get(objetivo).has(decodeURIComponent(ancla))) {
          fallas.push(`${donde}: ${destino} -> no hay encabezado con el ancla #${ancla}`);
        }
      }
    }
  });
}

if (fallas.length) {
  console.error(`Enlaces rotos (${fallas.length}):`);
  for (const f of fallas) console.error(`  ${f}`);
  process.exit(1);
}
console.log(`OK: ${revisados} enlaces relativos en ${archivos.length} archivos, todos resuelven.`);
