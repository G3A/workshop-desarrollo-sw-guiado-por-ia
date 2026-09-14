// Sensor de coherencia del playbook: node playbook-sdlc-ia/verificar-cobertura.mjs
//
// Hace cumplir la "Regla de mantenimiento" del README: cuando una caja cambia de badge, cambian con
// ella el diagrama, su dato B(), su seccion en la pagina de la fase y los contadores del indice, del
// README y de la barra de esa fase. Antes de este archivo el chequeo se escribio dos veces en un
// scratchpad y se perdio las dos (#117).
//
// Sin dependencias: corre con cualquier node >= 18, en Windows y en el runner de CI.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const PB = path.dirname(fileURLToPath(import.meta.url));
const BADGES = ['skill', 'parcial', 'mano', 'hueco', 'fuera'];
const leer = f => fs.readFileSync(path.join(PB, f), 'utf8');
const fallas = [];
const falla = msg => fallas.push(msg);

const diag = leer('Playbook-sdlc-ia.html');

// Cajas con badge en el diagrama Mermaid: ID["texto"]:::clase
const nodos = {};
for (const m of diag.matchAll(/\b([A-Za-z][A-Za-z0-9_]*)\["[^"]*"\]:::(\w+)/g)) {
  if (BADGES.includes(m[2])) nodos[m[1]] = m[2];
}

// Paginas por fase (var P) y datos de cada caja: ID:B("titulo","badge","nota",P.fx,"seccion")
const P = {};
for (const m of diag.matchAll(/\b(\w+):"([^"]+\.html)"/g)) P[m[1]] = m[2];
const datos = {};
for (const m of diag.matchAll(/\b(\w+):B\("(?:[^"\\]|\\.)*","(\w*)","(?:[^"\\]|\\.)*",P\.(\w+),"(\w+)"\)/g)) {
  datos[m[1]] = { badge: m[2], pagina: P[m[3]], seccion: m[4] };
}

const total = Object.fromEntries(BADGES.map(b => [b, 0]));
const porPagina = {};
const paginas = {};
for (const [id, badge] of Object.entries(nodos)) {
  total[badge]++;
  const d = datos[id];
  if (!d) { falla(`${id}: tiene :::${badge} en el diagrama pero no tiene dato B(...)`); continue; }
  if (d.badge !== badge) falla(`${id}: el diagrama dice :::${badge} y B(...) dice "${d.badge}"`);
  if (!d.pagina) { falla(`${id}: B(...) apunta a una pagina que no esta en P`); continue; }
  porPagina[d.pagina] ??= Object.fromEntries(BADGES.map(b => [b, 0]));
  porPagina[d.pagina][badge]++;
  paginas[d.pagina] ??= leer(path.join('Playbook-Fases', d.pagina));
  const sec = paginas[d.pagina].match(new RegExp(
    `<section id="${d.seccion}" class="(\\w+)[^"]*">\\s*<span class="tag">[^<]*</span><span class="badge (\\w+)">`));
  if (!sec) { falla(`${id}: no hay <section id="${d.seccion}"> con tag y badge en ${d.pagina}`); continue; }
  if (sec[1] !== badge || sec[2] !== badge) {
    falla(`${id}: en ${d.pagina} la seccion tiene class="${sec[1]}" y badge "${sec[2]}"; el diagrama dice ${badge}`);
  }
}

// Barras .cobertura: <span class="k-skill"><b>43</b> · skill</span>
const barra = html => {
  const r = {};
  for (const m of html.matchAll(/<span class="k-(\w+)"><b>(\d+)<\/b>/g)) r[m[1]] = Number(m[2]);
  return r;
};
const comparar = (donde, tiene, esperado) => {
  for (const b of BADGES) {
    if ((tiene[b] || 0) !== esperado[b]) falla(`${donde}: ${b} = ${tiene[b] || 0}, el diagrama da ${esperado[b]}`);
  }
};
comparar('Playbook-Fases/index.html', barra(leer('Playbook-Fases/index.html')), total);
for (const [pagina, cuenta] of Object.entries(porPagina)) {
  const b = barra(paginas[pagina]);
  if (Object.keys(b).length) comparar(`Playbook-Fases/${pagina}`, b, cuenta);
}

const cajas = Object.keys(nodos).length;
const readme = leer('README.md').replace(/\r?\n/g, ' ');
const r = readme.match(/\*\*(\d+) skill · (\d+) parcial · (\d+) a mano · (\d+) hueco · (\d+) fuera de\s+alcance\*\*,\s+sobre (\d+) cajas/);
if (!r) falla('README.md: no se encontro la linea "Cobertura al momento de escribir esto"');
else {
  comparar('README.md', { skill: +r[1], parcial: +r[2], mano: +r[3], hueco: +r[4], fuera: +r[5] }, total);
  if (+r[6] !== cajas) falla(`README.md: dice ${r[6]} cajas, el diagrama tiene ${cajas}`);
}

console.log(`Playbook: ${cajas} cajas con badge — ${BADGES.map(b => `${total[b]} ${b}`).join(' · ')}`);
if (fallas.length) {
  console.log(`\n${fallas.length} incoherencia(s):`);
  for (const f of fallas) console.log(`  - ${f}`);
  console.log('\nRegla de mantenimiento: playbook-sdlc-ia/README.md.');
  process.exit(1);
}
console.log('Diagrama, datos, secciones y contadores coinciden.');
