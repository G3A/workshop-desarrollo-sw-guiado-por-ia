// Visor del acta (#222, fase 6; diseno E5), desde la raiz del repo:
//
//   node scripts/acta/visor-acta.mjs <acta.curada.jsonl> [--reporte <reporte.json>]
//        [--asignado <persona>] [--sin-red] [--salida <archivo.html>]
//
// Escribe un HTML autocontenido —sin red, sin fuentes externas— que muestra la sesion como
// evidencia para una auditoria: la ficha de trazabilidad con los tres ejes de
// docs/jerarquia-proceso-actividad-tarea.md, el alcance de la verificacion, la tarea paso a paso y
// las vistas de la tarea. Lo que se muestra y como se dice esta en vista-acta.mjs.
//
// Lo que junta, ademas de la curada: su cruda (los intentos fallidos), el reporte del motor
// (<sesion>.acta.reporte.json, si existe), la conformidad con el instructivo, el indice de la
// tarea, la deriva de la huella, la fecha del procedimiento, los commits que citan el acta con
// Registro-IA, la configuracion del proceso (scripts/acta/proceso.json) y, salvo con --sin-red, el
// titulo y la persona asignada del issue en GitHub (`gh issue view`).
//
// Todo el contenido del acta entra al HTML como DATOS (JSON con `<` escapado) y la pagina lo pinta
// con textContent: un comando o una salida grabada nunca se interpretan como HTML.
//
// Codigos de salida: 0 escrito, 2 uso incorrecto.
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { conformidad } from './conformidad.mjs';
import { derivaHuella } from './deriva-huella.mjs';
import { git, raizDelRepo } from './git.mjs';
import { leerActa } from './validar-acta.mjs';
import { construirVista, documentoDelProcedimiento } from './vista-acta.mjs';

const AQUI = path.dirname(fileURLToPath(import.meta.url));
// La hoja de estilo y el script de la pagina viven aparte, para leerlos y editarlos como lo que
// son; el generador los copia dentro del HTML, que sigue siendo un solo archivo sin red.
const CSS = fs.readFileSync(path.join(AQUI, 'visor', 'visor.css'), 'utf8');
const JS = fs.readFileSync(path.join(AQUI, 'visor', 'visor.js'), 'utf8');
const SUFIJO = '.acta.curada.jsonl';

function issueDeGitHub(numero) {
  const r = spawnSync('gh', ['issue', 'view', numero, '--json', 'title,assignees'], {
    encoding: 'utf8',
    timeout: 20000,
  });
  if (r.status !== 0) return {};
  try {
    const j = JSON.parse(r.stdout);
    return {
      titulo: j.title,
      asignado: (j.assignees || []).map((a) => a.login).join(', ') || null,
    };
  } catch {
    return {};
  }
}

// Junta las entradas del visor. Nada de esto es obligatorio salvo la curada: lo que falta se
// muestra como falta.
export function recolectar({
  curada,
  raizRepo = null,
  reporte = null,
  asignado = null,
  sinRed = false,
}) {
  const registros = leerActa(curada);
  const cab = registros.find((r) => r.elemento === 'acta');
  const hermana = (sufijo) => curada.slice(0, -SUFIJO.length) + sufijo;
  const leerSi = (f, fn) => (f && fs.existsSync(f) ? fn(f) : null);
  const cruda = leerSi(hermana('.acta.cruda.jsonl'), leerActa);
  const rutaReporte = reporte || hermana('.acta.reporte.json');
  const rep = leerSi(rutaReporte, (f) => JSON.parse(fs.readFileSync(f, 'utf8')));
  const indice = leerSi(path.join(path.dirname(curada), 'indice.json'), (f) =>
    JSON.parse(fs.readFileSync(f, 'utf8')),
  );
  const proceso = JSON.parse(fs.readFileSync(path.join(AQUI, 'proceso.json'), 'utf8'));
  // El plan del issue que registrar-sesion.mjs guardo junto al acta (#230).
  const planDelIssue = leerSi(path.join(path.dirname(curada), 'plan-del-issue.json'), (f) =>
    JSON.parse(fs.readFileSync(f, 'utf8')),
  );
  const conf = conformidad(registros, { raizRepo, planDelIssue });
  let deriva = null;
  let procedimiento = {};
  let commits = [];
  if (raizRepo) {
    try {
      deriva = derivaHuella(registros, { raiz: raizRepo, claudeCode: null, modelo: null });
    } catch {
      /* sin repo legible, sin deriva */
    }
    // La fecha del procedimiento: el SKILL.md de la actividad o, sin skill, el AGENTS.md.
    const clave = conf.actividades?.[0]?.skill || (cab.actividades?.[0] || '').split(':').pop();
    const doc = documentoDelProcedimiento(cab, clave || null,
      Boolean(proceso.actividades?.[clave]?.sinSkill));
    if (doc?.commit) {
      const f = git(['show', '-s', '--format=%cs', doc.commit], {
        cwd: raizRepo,
        permitirFallo: true,
      });
      if (f) procedimiento = { fecha: f.trim() };
    }
    const relativa = path.relative(raizRepo, path.resolve(curada)).replace(/\\/g, '/');
    const log = git(['log', '--all', '--format=%h', '-F', `--grep=Registro-IA: ${relativa}`], {
      cwd: raizRepo,
      permitirFallo: true,
    });
    commits = (log || '').split('\n').filter(Boolean).slice(0, 5);
  }
  let tarea = {};
  if (!sinRed && /^\d+$/.test(cab.tarea)) tarea = issueDeGitHub(cab.tarea);
  if (asignado) tarea.asignado = asignado;
  return {
    curada: registros,
    cruda,
    reporte: rep,
    conformidad: conf,
    indice,
    deriva,
    proceso,
    tarea,
    procedimiento,
    commits,
    nombreActa: path.basename(curada),
  };
}

export function generarHtml(vista) {
  const datos = JSON.stringify(vista).replace(/</g, '\\u003c');
  return `<!doctype html>
<html lang="es">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Registro de la IA · ${escaparHtml(vista.titulo)}</title>
<style>${CSS}</style>
</head>
<body>
<header class="cabecera">
<div>
<div class="sobre">Registro de la IA · evidencia para auditoría</div>
<h1 id="titulo"></h1>
</div>
<div class="botones">
<button type="button" id="btn-intentos" aria-pressed="true" class="b-intentos">
Intentos fallidos: visibles</button>
<button type="button" id="btn-reproducir" class="b-sec">Reproducir paso a paso</button>
<button type="button" id="btn-exportar" class="b-pri">Exportar ficha</button>
</div>
</header>
<div class="leyenda" aria-label="De dónde sale cada dato">
<b>De dónde sale cada dato</b>
<span><i class="c-ejec"></i> Registro de ejecución · lo que hizo la sesión</span>
<span><i class="c-comp"></i> Registro completo · también los intentos fallidos</span>
<span><i class="c-verif"></i> Registro de verificación · se volvió a ejecutar sin la IA</span>
<span><i class="c-doc"></i> Procedimiento e instructivo · en la versión vigente al trabajar</span>
</div>
<section class="ficha" aria-label="Ficha de trazabilidad">
<div class="ficha-t">
<b>Ficha de trazabilidad · acción seleccionada</b>
<span>Cada nivel del trabajo, con quién responde, y el documento que lo describe justo
debajo</span>
</div>
<div class="ficha-g" id="ficha"></div>
</section>
<nav class="pestanas" aria-label="Vistas de la tarea" id="pestanas"></nav>
<main id="contenido"></main>
<script type="application/json" id="datos">${datos}</script>
<script>${JS}</script>
</body>
</html>
`;
}

function escaparHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  const opcion = (n) => (args.includes(n) ? args[args.indexOf(n) + 1] : undefined);
  const [curada] = args;
  if (!curada || !curada.endsWith(SUFIJO) || !fs.existsSync(curada)) {
    console.error(
      'Uso: node scripts/acta/visor-acta.mjs <acta.curada.jsonl> [--reporte <reporte.json>] ' +
        '[--asignado <persona>] [--sin-red] [--salida <archivo.html>]',
    );
    process.exitCode = 2;
  } else {
    const entradas = recolectar({
      curada,
      raizRepo: raizDelRepo(process.cwd()),
      reporte: opcion('--reporte'),
      asignado: opcion('--asignado'),
      sinRed: args.includes('--sin-red'),
    });
    const destino = opcion('--salida') || curada.slice(0, -SUFIJO.length) + '.acta.visor.html';
    fs.writeFileSync(destino, generarHtml(construirVista(entradas)));
    console.log(`Visor del acta: ${destino}`);
  }
}
