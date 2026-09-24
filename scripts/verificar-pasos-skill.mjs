// Sensor de citas a pasos de skill: node scripts/verificar-pasos-skill.mjs (desde la raiz del repo)
//
// El visor dibuja por separado etapas que una sola skill corre por dentro -- «Planificar el issue»
// lanza github-plan-build y ahi pasan ejecutar tareas, verificar, commitear, pushear y abrir la PR
// --, y para explicarlo cita los pasos de la skill por su nombre: «esto es el paso G de la skill»,
// «su paso H», «su Fase 5». Esas citas son el acoplamiento mas caro del repositorio y hasta #193
// eran el unico que se sostenia a mano: si la skill renombra un paso o mueve el commit de la G a la
// H, el visor sigue afirmando lo viejo y todo pasa en verde -- JSON.parse, el detector de solapes
// del diagrama y los otros cuatro sensores no miran dentro de esas frases.
//
// Cuatro decisiones que no son de estilo:
//
// 1. Las letras se buscan tambien en references/. Los encabezados del SKILL.md de github-plan-build
//    son Phase 0..4; los Step A..K viven en references/build-loop.md y build-loop-execute.md. Un
//    sensor que solo mirara el SKILL.md daria rojo sobre citas correctas.
// 2. Una cita de fase solo cuenta si viene con posesivo: «su Fase 5», «su fase 2». Sin esa marca,
//    «Fase 5» es una de las siete fases del metodo -- las dos numeraciones se escriben igual y
//    significan cosas distintas, y la del metodo aparece en la prosa de casi todos los nodos.
// 3. La skill de una cita es la que el MISMO nodo nombra, por su slash o por su nombre suelto. Un
//    nodo que cita «el paso G» sin nombrar ninguna skill es un defecto de documentacion por si
//    mismo -- manda al lector a buscar en nueve skills --, asi que tambien da rojo.
// 4. Se revisa ademas que todo /sdlc-ia:<algo> del catalogo exista como skill. Es el caso que este
//    sensor tiene que atrapar mas seguido: renombrar una skill y dejar el visor citando la vieja.
//
// Lo que este sensor NO cubre: que el ORDEN dibujado coincida con el orden real de la skill.
// Comprobar que la letra existe es barato y ataja la referencia muerta; comprobar la secuencia
// pediria un modelo del flujo de la skill, y eso es otro sensor.
//
// Sin dependencias: corre con cualquier node >= 18, en Windows y en el runner de CI.
import fs from 'node:fs';
import path from 'node:path';

const CATALOGO = 'proceso-operacional-con-ia/comandos.json';
const SKILLS = 'instrumentacion-java-ia/sdlc-ia/skills';

const fallas = [];
const falla = (msg) => fallas.push(msg);

// Encabezados de una skill: los Step <letra> y Phase <n> de su SKILL.md y de sus references/*.md.
function encabezadosDe(dir) {
  const archivos = [path.join(dir, 'SKILL.md')];
  const refs = path.join(dir, 'references');
  if (fs.existsSync(refs)) {
    for (const f of fs.readdirSync(refs).sort()) {
      if (f.endsWith('.md')) archivos.push(path.join(refs, f));
    }
  }
  const pasos = new Set();
  const fases = new Set();
  for (const archivo of archivos) {
    if (!fs.existsSync(archivo)) continue;
    const txt = fs.readFileSync(archivo, 'utf8');
    for (const m of txt.matchAll(/^#{2,4}\s*`?Step\s+([A-L])\b/gm)) pasos.add(m[1]);
    for (const m of txt.matchAll(/^#{2,4}\s*`?Phase\s+(\d+)\b/gm)) fases.add(m[1]);
  }
  return { pasos, fases };
}

if (!fs.existsSync(CATALOGO)) {
  console.error(`No encuentro ${CATALOGO}. Corre el sensor desde la raiz del repositorio.`);
  process.exit(1);
}
if (!fs.existsSync(SKILLS)) {
  console.error(`No encuentro ${SKILLS}. Corre el sensor desde la raiz del repositorio.`);
  process.exit(1);
}

const catalogo = JSON.parse(fs.readFileSync(CATALOGO, 'utf8'));
const skills = {};
for (const nombre of fs.readdirSync(SKILLS).sort()) {
  const dir = path.join(SKILLS, nombre);
  if (fs.statSync(dir).isDirectory()) skills[nombre] = encabezadosDe(dir);
}

let citasPaso = 0;
let citasFase = 0;
let slashes = 0;

for (const [id, nodo] of Object.entries(catalogo.nodos || {})) {
  const texto = JSON.stringify(nodo);

  // Skills que este nodo nombra: por su slash, por un /sdlc-ia:<algo> en la prosa, o sueltas.
  const nombradas = new Set();
  for (const m of texto.matchAll(/\/sdlc-ia:([a-z0-9-]+)/g)) {
    slashes++;
    if (skills[m[1]]) nombradas.add(m[1]);
    else falla(`[${id}] cita /sdlc-ia:${m[1]}, que no existe en ${SKILLS}/`);
  }
  for (const nombre of Object.keys(skills)) {
    if (texto.includes(nombre)) nombradas.add(nombre);
  }

  const pasos = [...texto.matchAll(/\bpaso\s+([A-L])\b/g)].map((m) => m[1]);
  const fases = [...texto.matchAll(/\bsu\s+[Ff]ase\s+(\d+)\b/g)].map((m) => m[1]);
  citasPaso += pasos.length;
  citasFase += fases.length;

  if ((pasos.length || fases.length) && nombradas.size === 0) {
    const cita = pasos.length ? `paso ${pasos[0]}` : `fase ${fases[0]}`;
    falla(`[${id}] cita «${cita}» sin nombrar ninguna skill: no hay contra que verificarlo`);
    continue;
  }

  for (const letra of new Set(pasos)) {
    const duenas = [...nombradas].filter((n) => skills[n].pasos.has(letra));
    if (duenas.length === 0) {
      const donde = [...nombradas].join(', ');
      falla(`[${id}] cita «paso ${letra}» y ninguna de las skills que nombra (${donde}) lo tiene`);
    }
  }
  for (const numero of new Set(fases)) {
    const duenas = [...nombradas].filter((n) => skills[n].fases.has(numero));
    if (duenas.length === 0) {
      const donde = [...nombradas].join(', ');
      falla(`[${id}] cita «su fase ${numero}» y ninguna skill que nombra (${donde}) la tiene`);
    }
  }
}

const cuantas = Object.keys(skills).length;
console.log(
  `Pasos de skill: ${citasPaso} citas de paso, ${citasFase} de fase y ${slashes} slash en ` +
    `${CATALOGO}, contra ${cuantas} skills.`
);

if (fallas.length) {
  console.error(`\n${fallas.length} cita(s) que ya no existen en la skill:\n`);
  for (const f of fallas) console.error(`  - ${f}`);
  console.error('\nCorrige la prosa del visor, o el encabezado de la skill si el que se movio fue');
  console.error('el paso.');
  process.exit(1);
}

console.log('Cada paso citado existe en la skill que el nodo nombra.');
