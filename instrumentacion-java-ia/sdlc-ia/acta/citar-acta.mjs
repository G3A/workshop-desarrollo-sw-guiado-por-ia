// Hook commit-msg que cita el acta de la sesion (#222, fase 3; ADR-0006):
//   node $ACTA/citar-acta.mjs <archivo-del-mensaje>
//
// Si el commit lo hace Claude Code (CLAUDE_CODE_SESSION_ID en el entorno, que Claude Code exporta
// a sus comandos desde 2.1.x) y el mensaje declara `Asistido-por-IA:` sin `Registro-IA:`, agrega
// debajo de esa linea:
//
//   Registro-IA: .ia/registros/<tarea>/<sesion>.acta.curada.jsonl
//
// La tarea sale de la rama actual, con la misma regla que el compilador. El acta todavia no
// existe en ese momento: la escribe `registrar-sesion.mjs`, y el sensor del CI exige que este en
// la PR. Un commit asistido hecho fuera de Claude Code escribe el trailer a mano, con la ruta o con
// `Registro-IA: ninguno: <motivo>`.
//
// Nunca bloquea el commit: si algo falla, deja el mensaje como estaba y lo dice. Lo que exige el
// trailer es el sensor, no este hook.
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import { tareaDeRama } from './compilar-acta.mjs';
import { git, raizDelRepo } from './git.mjs';

const CON_IA = /^Asistido-por-IA: \S.*$/m;
const REGISTRO = /^Registro-IA:/m;

export function citar(mensaje, { sesion, rama }) {
  if (!sesion || !CON_IA.test(mensaje) || REGISTRO.test(mensaje)) return mensaje;
  const ruta = `.ia/registros/${tareaDeRama(rama)}/${sesion}.acta.curada.jsonl`;
  return mensaje.replace(CON_IA, (linea) => `${linea}\nRegistro-IA: ${ruta}`);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const archivo = process.argv[2];
  try {
    const raiz = raizDelRepo(process.cwd());
    const rama = git(['rev-parse', '--abbrev-ref', 'HEAD'], { cwd: raiz }).trim();
    const mensaje = fs.readFileSync(archivo, 'utf8');
    const nuevo = citar(mensaje, { sesion: process.env.CLAUDE_CODE_SESSION_ID, rama });
    if (nuevo !== mensaje) fs.writeFileSync(archivo, nuevo);
  } catch (e) {
    console.error(`citar-acta: el mensaje queda como estaba (${e.message})`);
  }
}
