// Registra el acta de la sesion EN CURSO, para commitearla (#222, fase 3), desde la raiz del repo:
//
//   node scripts/acta/registrar-sesion.mjs [--sesion <id>] [--sin-stage]
//
// El hook compila el acta al cerrar la sesion, pero el commit que la cita se hace antes, durante
// la sesion. Este script compila lo que va de ella, la cura, empaca sus objetos, reescribe el
// indice y deja en stage, con `git add -f`, los archivos del acta de la tarea de la rama actual:
// la cruda, la curada, el pack de objetos y el indice. .ia/ sigue en .gitignore a proposito: asi
// un `git add -A` no publica todas las sesiones, solo la que se registra.
//
// La sesion es la de CLAUDE_CODE_SESSION_ID, que Claude Code exporta a los comandos (verificado
// con 2.1.287), o la de --sesion. El transcript se busca en ~/.claude/projects/*/<id>.jsonl.
//
// La llamada en curso, la que corre este script, todavia no tiene resultado: el transcript y la
// captura se cortan justo antes de ella. Sin el corte, la curada se detendria en una accion sin
// arbol de despues. Al cerrar la sesion, el hook vuelve a compilar el acta entera; la que se
// commiteo queda como estaba hasta el proximo registro.
//
// Codigos de salida: 0 registrada, 1 la compilacion o la curacion no salieron, 2 uso incorrecto.
import { spawnSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { compilarYCerrar, rutaHermana } from './cerrar-acta.mjs';
import { leerCaptura, tareaDeRama } from './compilar-acta.mjs';
import { git, raizDelRepo } from './git.mjs';

export function buscarTranscript(sesion, proyectos = path.join(os.homedir(), '.claude',
  'projects')) {
  if (!fs.existsSync(proyectos)) return null;
  for (const d of fs.readdirSync(proyectos).sort()) {
    const t = path.join(proyectos, d, `${sesion}.jsonl`);
    if (fs.existsSync(t)) return t;
  }
  return null;
}

// Copia el transcript y la captura hasta antes de la ultima llamada sin resultado. Devuelve las
// rutas de las copias y el id de la llamada cortada (null si no habia ninguna).
export function cortarEnCurso({ transcript, captura, destino }) {
  const lineas = fs.readFileSync(transcript, 'utf8').split('\n');
  const conResultado = new Set();
  for (const l of lineas) {
    if (!l.includes('tool_result')) continue;
    try {
      for (const b of JSON.parse(l).message?.content || []) {
        if (b.type === 'tool_result') conResultado.add(b.tool_use_id);
      }
    } catch { /* una linea rota la ignora el compilador */ }
  }
  let corte = lineas.length;
  let enCurso = null;
  // La ULTIMA llamada sin resultado: una interrumpida mas atras tambien puede no tenerlo, y cortar
  // ahi perderia el resto de la sesion.
  lineas.forEach((l, i) => {
    if (!l.includes('tool_use')) return;
    try {
      const o = JSON.parse(l);
      if (o.isSidechain || o.type !== 'assistant') return;
      const uso = (o.message?.content || []).find((b) => b.type === 'tool_use' &&
        !conResultado.has(b.id));
      if (uso) {
        enCurso = uso.id;
        corte = i;
      }
    } catch { /* idem */ }
  });
  fs.mkdirSync(destino, { recursive: true });
  const t = path.join(destino, path.basename(transcript));
  fs.writeFileSync(t, lineas.slice(0, corte).join('\n') + '\n');
  const eventos = leerCaptura(captura);
  const hasta = enCurso ? eventos.findIndex((e) => e.toolUseId === enCurso) : -1;
  const c = path.join(destino, 'captura.jsonl');
  fs.writeFileSync(c, (hasta === -1 ? eventos : eventos.slice(0, hasta))
    .map((e) => JSON.stringify(e)).join('\n') + '\n');
  return { transcript: t, captura: c, enCurso };
}

// El plan del issue de la tarea, tal como estaba al registrar (#230): es el instructivo de trabajo
// de una sesion que trabaja el issue sin una skill. Se guarda junto al acta con la fecha de su
// ultima edicion y el sha256 de su texto, y se versiona con ella. Sin red o sin issue, no se
// guarda y la conformidad lo dice.
export function leerIssueDeGitHub(numero) {
  const campos = 'number,title,body,updatedAt';
  const r = spawnSync('gh', ['issue', 'view', String(numero), '--json', campos],
    { encoding: 'utf8', timeout: 20000 });
  if (r.status !== 0) return null;
  try {
    return JSON.parse(r.stdout);
  } catch {
    return null;
  }
}

export function guardarPlanDelIssue({ carpeta, tarea, leerIssue = leerIssueDeGitHub }) {
  if (!/^\d+$/.test(tarea)) return null;
  const issue = leerIssue(tarea);
  if (!issue?.body) return null;
  const cuerpo = issue.body.replace(/\r\n/g, '\n');
  const plan = { numero: Number(tarea), titulo: issue.title, actualizado: issue.updatedAt,
    sha256: crypto.createHash('sha256').update(cuerpo).digest('hex'), cuerpo };
  const destino = path.join(carpeta, 'plan-del-issue.json');
  fs.writeFileSync(destino, JSON.stringify(plan, null, 2) + '\n');
  return destino;
}

export function registrar({ sesion, raizRepo, stage = true, log = console, proyectos,
  leerIssue = leerIssueDeGitHub }) {
  const transcript = buscarTranscript(sesion, proyectos);
  if (!transcript) {
    log.error(`No encuentro el transcript de la sesion ${sesion} en ~/.claude/projects.`);
    return { codigo: 1, archivos: [] };
  }
  const destino = fs.mkdtempSync(path.join(os.tmpdir(), 'acta-registro-'));
  try {
    const cortado = cortarEnCurso({ transcript, destino,
      captura: path.join(raizRepo, '.ia', 'captura', `${sesion}.jsonl`) });
    const { codigo, escritas } = compilarYCerrar({ transcript: cortado.transcript,
      captura: cortado.captura, raizRepo, log, carpetaSesion: transcript.replace(/\.jsonl$/, '') });
    const rama = git(['rev-parse', '--abbrev-ref', 'HEAD'], { cwd: raizRepo }).trim();
    const tarea = tareaDeRama(rama);
    const cruda = escritas.find((e) => path.basename(path.dirname(e)) === tarea);
    if (!cruda) {
      log.error(`La sesion no escribio un acta para la tarea ${tarea} (rama ${rama}).`);
      return { codigo: 1, archivos: [] };
    }
    if (!guardarPlanDelIssue({ carpeta: path.dirname(cruda), tarea, leerIssue })) {
      log.error(`No se guardo el plan del issue #${tarea}: sin red, sin gh o sin texto.`);
    }
    const archivos = [cruda, rutaHermana(cruda, '.acta.curada.jsonl'),
      rutaHermana(cruda, '.acta.objetos.pack'), path.join(path.dirname(cruda), 'indice.json'),
      path.join(path.dirname(cruda), 'plan-del-issue.json')]
      .filter((f) => fs.existsSync(f)).map((f) => path.relative(raizRepo, f).replace(/\\/g, '/'));
    const curada = archivos.some((f) => f.endsWith('.acta.curada.jsonl'));
    if (stage) git(['add', '-f', '--', ...archivos], { cwd: raizRepo });
    for (const f of archivos) log.log(`${stage ? 'En stage' : 'Escrito'}: ${f}`);
    if (!curada) log.error('La curada no salio: el commit no puede citarla. Mira los avisos.');
    return { codigo: codigo === 0 && curada ? 0 : 1, archivos };
  } finally {
    fs.rmSync(destino, { recursive: true, force: true });
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  const i = args.indexOf('--sesion');
  const sesion = i === -1 ? process.env.CLAUDE_CODE_SESSION_ID : args[i + 1];
  const conocidos = new Set(['--sesion', '--sin-stage', sesion]);
  const raizRepo = raizDelRepo(process.cwd());
  if (!sesion || !raizRepo || args.some((a) => !conocidos.has(a))) {
    console.error('Uso: node scripts/acta/registrar-sesion.mjs [--sesion <id>] [--sin-stage]');
    console.error('Sin --sesion, usa CLAUDE_CODE_SESSION_ID: corre dentro de Claude Code.');
    process.exitCode = 2;
  } else {
    process.exitCode = registrar({ sesion, raizRepo, stage: !args.includes('--sin-stage') })
      .codigo;
  }
}
