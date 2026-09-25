// Sensor visor <-> playbook: node scripts/verificar-visor-playbook.mjs (desde la raiz del repo)
//
// La regla dura del monorepo ata tres cosas -- visor, playbook y skills coinciden -- y hasta #203
// solo dos tenian quien las vigilara: verificar-pasos-skill.mjs (visor <-> skills) y
// verificar-cobertura.mjs (coherencia interna del playbook). La tercera se fue dos veces en la
// misma semana: el playbook tenia las dos mitades de «Costos y privacidad» y el visor solo una
// (#191), y el playbook no modelaba la entrega entera mientras el visor ya tenia cinco pasos de
// ese tramo (#199). Las dos se encontraron leyendo, de casualidad, trabajando en otra cosa.
//
// No hay biyeccion que exigir: el visor tiene pasos y el playbook cajas, con granularidad distinta
// a proposito. Lo que si es exacto es lo que una pieza AFIRMA de la otra, y eso es lo que se mide.
//
// Tres comprobaciones, y cuatro decisiones que no son de estilo:
//
// 1. CODIGOS EN EL DIAGRAMA. Las cajas del playbook citan nodos del visor en su etiqueta («A MANO ·
//    visor fd2», «visor f0, G1», «visor bo2 y G3»). Cada codigo tiene que existir como nodo o como
//    evento del visor.
// 2. FUENTES DE LAS PAGINAS DE FASE. Cada seccion cierra con «Fuente: visor, nodo «Titulo»
//    (codigo)». Ahi se comprueban las dos mitades: que el codigo exista, y que el TITULO citado sea
//    el mismo que el `name=` del nodo en el .bpmn. Es la comprobacion mas afilada del sensor: un
//    nodo renombrado deja la cita muerta sin que nada mas se entere.
// 3. SKILLS. El conjunto de skills nombradas en el visor y en el playbook tiene que ser el mismo.
//    Esta es la que habria atrapado a impact-metrics e instrument-github-repo, que existian en el
//    plugin y el visor no nombraba (#186, #187).
//
// 4. UNA CITA SE RECONOCE POR COMO TERMINA, no por parecerse a un codigo. «visor fd2"» y «visor f0,
//    G1"» son citas; «el visor paso a paso», «el visor o un manual», «visor no determinista» son
//    prosa. La diferencia exacta es el delimitador: una cita termina en comilla, coma, punto y
//    coma, dos puntos, corchete, «<» o « +», nunca en espacio seguido de palabra. Sin esa regla, el
//    sensor pide que existan nodos llamados «paso», «o» y «no».
//
// Lo que NO cubre: que el playbook modele todos los tramos del visor. Un tramo que ninguna caja
// nombra no tiene citas, y una cita que no existe no se puede verificar -- eso se ve leyendo, que
// es como aparecio #199. Este sensor evita la deriva de lo que YA esta escrito, no la omision.
//
// Sin dependencias: corre con cualquier node >= 18, en Windows y en el runner de CI.
import fs from 'node:fs';
import path from 'node:path';

const VISOR = 'proceso-operacional-con-ia';
const PLAYBOOK = 'playbook-sdlc-ia';
const SKILLS = 'instrumentacion-java-ia/sdlc-ia/skills';

const fallas = [];
const falla = (msg) => fallas.push(msg);

for (const ruta of [`${VISOR}/comandos.json`, `${PLAYBOOK}/Playbook-sdlc-ia.html`, SKILLS]) {
  if (!fs.existsSync(ruta)) {
    console.error(`No encuentro ${ruta}. Corre el sensor desde la raiz del repositorio.`);
    process.exit(1);
  }
}

const catalogo = JSON.parse(fs.readFileSync(`${VISOR}/comandos.json`, 'utf8'));
const ids = new Set([...Object.keys(catalogo.nodos || {}), ...Object.keys(catalogo.eventos || {})]);

// El titulo de un nodo vive en el name= del .bpmn, no en comandos.json.
const bpmn = fs.readFileSync(`${VISOR}/${VISOR}.bpmn`, 'utf8');
const titulos = new Map();
const NOMBRE = /<bpmn:[a-zA-Z]+ id="([^"]+)"[^>]*name="([^"]*)"/g;
for (const m of bpmn.matchAll(NOMBRE)) titulos.set(m[1], m[2]);

const diagrama = fs.readFileSync(`${PLAYBOOK}/Playbook-sdlc-ia.html`, 'utf8');

// --- 1. Codigos citados en las etiquetas del diagrama ------------------------------------------
const COD = '[A-Za-z][A-Za-z0-9]{0,3}';
// El delimitador final es lo que separa una cita de la prosa; ver la decision 4 de la cabecera.
const FIN = '(?=["\'`,;:.\\]<]| \\+|$)';
const CITA = new RegExp(`[Vv]isor,? ((?:${COD})(?:(?:, | y )${COD})*)${FIN}`, 'g');
let citas = 0;
for (const m of diagrama.matchAll(CITA)) {
  for (const id of m[1].split(/, | y /)) {
    citas += 1;
    if (!ids.has(id)) falla(`el diagrama del playbook cita «visor ${id}» y ese nodo no existe`);
  }
}

// --- 2. Fuentes de las paginas de fase: codigo Y titulo ----------------------------------------
const FUENTE = /<b>Fuente:<\/b>[^<]*visor[^<]*«([^»]+)»[^<]*<code>([A-Za-z0-9_]{1,5})<\/code>/g;
const paginas = path.join(PLAYBOOK, 'Playbook-Fases');
let fuentes = 0;
for (const archivo of fs.readdirSync(paginas).filter((n) => n.endsWith('.html')).sort()) {
  const html = fs.readFileSync(path.join(paginas, archivo), 'utf8');
  for (const [, titulo, id] of html.matchAll(FUENTE)) {
    fuentes += 1;
    if (!ids.has(id)) {
      falla(`${archivo}: la fuente «${titulo}» cita el nodo ${id}, que no existe`);
      continue;
    }
    const real = titulos.get(id);
    if (real && real !== titulo) {
      falla(`${archivo}: cita «${titulo}» (${id}) y el .bpmn lo llama «${real}»`);
    }
  }
}

// --- 3. Skills nombradas de los dos lados -------------------------------------------------------
const esCarpeta = (n) => fs.statSync(path.join(SKILLS, n)).isDirectory();
const skills = fs.readdirSync(SKILLS).filter(esCarpeta);
const textoVisor = JSON.stringify(catalogo);
// Con `includes` a secas, renombrar una skill a algo que CONTENGA el nombre viejo -- de
// impact-metrics a impact-metrics-v2 -- deja el sensor en verde sobre una mencion que ya no existe.
// Los guiones entran en la frontera porque los nombres de las skills los llevan.
const BORDE = '[A-Za-z0-9-]';
const nombra = (texto, skill) =>
  new RegExp(`(?<!${BORDE})${skill}(?!${BORDE})`).test(texto);
const soloVisor = skills.filter((s) => nombra(textoVisor, s) && !nombra(diagrama, s));
const soloPlaybook = skills.filter((s) => nombra(diagrama, s) && !nombra(textoVisor, s));
for (const s of soloVisor) falla(`la skill ${s} la nombra el visor y el playbook no`);
for (const s of soloPlaybook) falla(`la skill ${s} la nombra el playbook y el visor no`);

const nombradas = skills.filter((s) => nombra(textoVisor, s) || nombra(diagrama, s)).length;
console.log(
  `Visor y playbook: ${citas} codigos citados, ${fuentes} fuentes con titulo y ` +
    `${nombradas} de ${skills.length} skills nombradas en las dos piezas.`
);

if (fallas.length) {
  const cuantas = fallas.length;
  console.error(`\n${cuantas} afirmacion(es) de una pieza sobre la otra que ya no son ciertas:\n`);
  for (const f of fallas) console.error(`  - ${f}`);
  console.error('\nRegla dura: visor, playbook y skills coinciden. Ver AGENTS.md.');
  process.exit(1);
}

console.log('Cada cita del playbook apunta a un nodo que existe y lo llama por su titulo.');
