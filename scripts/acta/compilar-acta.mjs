// Compilador del acta cruda (#216, ADR-0005), desde la raiz del repo:
//
//   node scripts/acta/compilar-acta.mjs --transcript <sesion.jsonl>
//        [--captura <captura.jsonl>] [--salida <carpeta>] [--sin-verificar-secretos]
//
// Toma el transcript principal de Claude Code y los de sus subagentes
// (<sesion>/subagents/agent-<id>.jsonl) y escribe, por cada tarea que la sesion toco,
// <salida>/<tarea>/<sesion>.acta.cruda.jsonl con los elementos del modelo conceptual
// (docs/modelo-conceptual-registro-ia.md): acta, agentes, turnos, pasos, decisiones, acciones e
// intervenciones, una linea JSON por elemento.
//
// Cinco decisiones que no son de estilo:
//
// 1. DETERMINISTA. La misma entrada produce el mismo archivo, byte a byte: ids secuenciales, orden
//    estable, claves en orden fijo, sin la hora de la compilacion. Lo exige la re-ejecucion, que
//    compara hashes, y lo verifica una prueba.
// 2. Los PASOS salen de los marcadores que la skill escribe en su texto (#222, fase 4;
//    docs/protocolo-de-marcadores-de-paso.md). Cada marcador abre un paso `marcado` en el turno;
//    lo que el turno hizo antes del primero queda en un paso `ausente`, el primero del turno.
//    Sin marcadores, el paso es el turno completo, con procedencia `ausente` (invariante I5).
//    No se infiere: una heuristica meteria no determinismo (PC-04).
// 3. Una sesion que cambia de rama se parte: un acta por tarea (I2). La tarea sale de la rama
//    (`<tipo>/<N>-slug`); en dev, main o una rama sin numero es `sin-tarea`.
// 4. El acta no se escribe si rompe una invariante, ni si gitleaks encuentra un secreto. Un acta
//    a medias o con una credencial es peor que ninguna: la primera miente y la segunda filtra.
// 5. Las rutas se normalizan: la raiz del repo pasa a "." y la carpeta del usuario a "~".
//
// La fase la declara la skill en cada marcador. El acta lleva la fase cuando todos sus pasos
// marcados declaran la misma; si declaran varias, queda en null y se avisa.
//
// El exito sale del `is_error` del resultado, salvo cuando Claude Code reinterpreto el codigo de
// salida (#219): un comando que termina en `| grep` y sale con 1 queda como «No matches found»,
// sin `is_error`, aunque el 1 viniera de un eslabon anterior que fallo. Ni el transcript ni el
// payload de PostToolUse traen el codigo crudo, solo esa interpretacion. La accion queda con
// `exito = null`, porque no se sabe, y la interpretacion en `codigoReinterpretado`. Leer la
// salida para decidir (buscar `Error:`) seria una heuristica, y el modelo las prohibe.
//
// Codigos de salida: 0 todo escrito, 1 un acta rompe una invariante, 2 uso incorrecto,
// 3 gitleaks encontro un secreto, 4 no hay gitleaks para verificar.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { ENVOLTORIOS, MODOS_CON_PERSONA, SIN_PERMISO, claseDeterminismo, esRechazoDePermiso,
  textoDelRechazo } from './clasificar.mjs';
import { identidadDelEntorno, normalizador } from './normalizar.mjs';
import { validarActa } from './validar-acta.mjs';
import { blobEn, cambiosEntre, git, leerEnCommit, raizDelRepo, versionDe } from './git.mjs';

const RAMA_CON_TAREA = /^[a-z]+\/(\d+)-/;
const RUTA_PLUGIN = 'instrumentacion-java-ia/sdlc-ia';
const RUTA_DOCKERFILE = 'scripts/acta/motor/Dockerfile';
const DOC_DE_SKILL = /(^|\/)skills\/[^/]+\/(SKILL\.md|references\/[^/]+\.md)$/;
const PROFUNDIDAD_MAXIMA = 5;

// El marcador de paso (docs/protocolo-de-marcadores-de-paso.md): una linea sola en el texto de la
// skill, con o sin backticks alrededor. `step` es el numero de un `Phase N` o la letra de un
// `Step X` del instructivo; `method-phase`, la fase del playbook, de 0 a 6.
const MARCADOR = new RegExp('^`?\\[sdlc-ia:step skill=([a-z0-9][a-z0-9-]*) ' +
  'step=([0-9]+|[A-Z]) method-phase=([0-6])\\]`?[ \\t]*$', 'gm');

// Un marcador dentro de un bloque de codigo (```) no cuenta: es un ejemplo, no un paso (#230).
export const BLOQUE_DE_CODIGO = /^```[\s\S]*?^```/gm;

export function marcadoresEn(texto) {
  return [...String(texto ?? '').replace(BLOQUE_DE_CODIGO, '').matchAll(MARCADOR)]
    .map((m) => ({ skill: m[1], letra: m[2], fase: Number(m[3]) }));
}

// La actividad que declara el agente cuando trabaja el plan de un issue sin una skill (#230). La
// regla esta en AGENTS.md; su instructivo es el plan del issue, que registrar-sesion.mjs guarda.
export const PLAN_DE_ISSUE = 'plan-de-issue';

const sinMarcadores = (texto) => String(texto ?? '').replace(MARCADOR, '').trim();

export function tareaDeRama(rama) {
  const m = RAMA_CON_TAREA.exec(rama || '');
  return m ? m[1] : 'sin-tarea';
}

function leerJsonl(archivo) {
  const ilegibles = { n: 0 };
  const lineas = fs.readFileSync(archivo, 'utf8').split('\n').filter((l) => l.trim());
  const registros = [];
  for (const l of lineas) {
    try {
      registros.push(JSON.parse(l));
    } catch {
      ilegibles.n++;
    }
  }
  return { registros, ilegibles: ilegibles.n };
}

function textoDe(contenido) {
  if (typeof contenido === 'string') return contenido;
  if (!Array.isArray(contenido)) return '';
  return contenido
    .map((b) => (b.type === 'text' ? b.text : b.type === 'image' ? '[imagen]' : ''))
    .filter(Boolean)
    .join('\n');
}

const entre = (texto, etiqueta) => {
  const m = new RegExp(`<${etiqueta}>([\\s\\S]*?)</${etiqueta}>`).exec(texto);
  return m ? m[1] : null;
};

// El compilador como funcion pura sobre sus entradas. `raizRepo` es opcional: sin ella no hay
// diffs ni versiones de documentos, pero el resto del acta se arma igual.
export function compilar({ transcript, captura = [], raizRepo = null, carpetaSesion = null,
  identidad = {} }) {
  const avisos = [];
  const { registros: lineas, ilegibles } = leerJsonl(transcript);
  if (ilegibles) avisos.push(`${ilegibles} lineas del transcript no son JSON y se ignoraron`);
  const primera = lineas.find((l) => l.sessionId && l.cwd) || {};
  const sesion = primera.sessionId || path.basename(transcript, '.jsonl');
  const norm = normalizador(primera.cwd || null, { identidad });
  const carpeta = carpetaSesion || transcript.replace(/\.jsonl$/, '');

  const capturaPorUso = new Map();
  let inicioSesion = null;
  for (const c of captura) {
    if (c.evento === 'SessionStart' && !inicioSesion) inicioSesion = c;
    if (!c.toolUseId) continue;
    const previo = capturaPorUso.get(c.toolUseId) || {};
    if (c.evento === 'PreToolUse') previo.antes = c.arbol || null;
    else previo.despues = c.arbol || null;
    capturaPorUso.set(c.toolUseId, previo);
  }
  const headBase = inicioSesion?.head || null;

  // Si la captura vio el PreToolUse de la accion (#218). `false` dice que la herramienta no llego
  // a correr: un Edit que no paso la validacion, o un Write que freno el clasificador, no
  // disparan ningun hook. Solo se afirma con captura desde antes de la accion, y nunca para un
  // comando con !, que no pasa por los hooks: en esos casos es null, no se sabe. Tampoco se
  // afirma de una accion que no fallo: ver «Eventos perdidos», mas abajo.
  const inicioCaptura = captura.map((c) => c.momento).filter(Boolean).sort()[0] || null;
  const capturada = (uso, agenteId, momento) => {
    if (capturaPorUso.get(uso.id)?.antes !== undefined) return true;
    if (agenteId === 'usuario' || !inicioCaptura || !momento) return null;
    return momento >= inicioCaptura ? false : null;
  };

  const agentes = new Map();
  const agente = (id, tipo, rol, actuoEnNombreDe = null) => {
    if (!agentes.has(id)) {
      agentes.set(id, { elemento: 'agente', id, tipo, rol, actuoEnNombreDe, integracion: null });
    }
    return id;
  };
  agente('orquestador', 'ia', 'orquestador');

  const turnos = [];
  const pasos = [];
  const acciones = [];
  const decisiones = [];
  const intervenciones = [];
  const accionPorUso = new Map();
  const cont = { a: 0, d: 0, i: 0 };
  const versiones = new Set();
  const modelos = new Set();
  const skills = new Set();

  let turno = null;
  let paso = null;
  let comandoPendiente = null;
  let trasInterrupcion = false;

  const nuevoTurno = (linea, prompt, origen) => {
    const n = turnos.length + 1;
    turno = {
      elemento: 'turno',
      id: `t${n}`,
      sesion,
      numero: n,
      origen,
      prompt: prompt === null ? null : norm.texto(prompt),
      rama: linea.gitBranch || null,
      tarea: tareaDeRama(linea.gitBranch),
      momento: linea.timestamp || null,
    };
    turnos.push(turno);
    paso = {
      elemento: 'paso',
      id: `${turno.id}.p1`,
      turno: turno.id,
      procedencia: 'ausente',
      pasoPrescrito: null,
      faseDeclarada: null,
    };
    pasos.push(paso);
  };

  // Un marcador abre un paso marcado. El mismo paso marcado dos veces seguidas en un turno es uno
  // solo; un paso ausente todavia sin acciones se convierte en vez de quedar vacio.
  const pasoConAcciones = new Set();
  const marcarPaso = (linea, m) => {
    if (!turno) nuevoTurno(linea, null, 'sin_prompt');
    const prescrito = { skill: m.skill, letra: m.letra };
    if (paso.procedencia === 'marcado' && paso.pasoPrescrito.skill === m.skill &&
      paso.pasoPrescrito.letra === m.letra) return;
    if (paso.procedencia === 'ausente' && !pasoConAcciones.has(paso.id)) {
      paso.procedencia = 'marcado';
      paso.pasoPrescrito = prescrito;
      paso.faseDeclarada = m.fase;
      return;
    }
    const n = pasos.filter((p) => p.turno === turno.id).length + 1;
    paso = { elemento: 'paso', id: `${turno.id}.p${n}`, turno: turno.id, procedencia: 'marcado',
      pasoPrescrito: prescrito, faseDeclarada: m.fase };
    pasos.push(paso);
  };

  const nuevaIntervencion = (tipo, linea, accion = null, { texto = null } = {}) => {
    if (!paso) {
      avisos.push(`una intervencion ${tipo} llego antes del primer turno y se ignoro`);
      return;
    }
    agente('usuario', 'persona', 'usuario');
    intervenciones.push({
      elemento: 'intervencion',
      id: `i${++cont.i}`,
      tipo,
      paso: accion ? accion.paso : paso.id,
      accion: accion ? accion.id : null,
      agente: 'usuario',
      texto: texto === null ? null : norm.texto(texto),
      momento: linea.timestamp || null,
    });
  };

  const nuevaAccion = ({ linea, uso, pasoId, agenteId, lanzadaPor = null }) => {
    const entrada = uso.input || {};
    const cap = capturaPorUso.get(uso.id) || {};
    const antes = cap.antes || null;
    const despues = cap.despues || null;
    const accion = {
      elemento: 'accion',
      id: `a${++cont.a}`,
      paso: pasoId,
      agente: agenteId,
      lanzadaPor,
      herramienta: uso.name,
      toolUseId: uso.id,
      entrada: norm.profundo(entrada),
      claseDeterminismo: claseDeterminismo(uso.name, entrada),
      segundoPlano: entrada.run_in_background === true,
      directorio: linea.cwd ? norm.texto(linea.cwd) : null,
      momento: linea.timestamp || null,
      exito: null,
      resultado: null,
      resultadoCompleto: null,
      resultadoDiferido: null,
      error: null,
      codigoReinterpretado: null,
      capturada: capturada(uso, agenteId, linea.timestamp),
      arbolAntes: antes,
      arbolDespues: despues,
      cambios: raizRepo ? cambiosEntre(raizRepo, antes, despues).map(norm.profundo) : [],
      subagente: null,
      tareaFondo: null,
    };
    acciones.push(accion);
    pasoConAcciones.add(pasoId);
    accionPorUso.set(uso.id, accion);
    if (uso.name === 'Skill' && entrada.skill) skills.add(String(entrada.skill));
    return accion;
  };

  const registrarResultado = (linea, bloque) => {
    const accion = accionPorUso.get(bloque.tool_use_id);
    if (!accion) {
      avisos.push(`un resultado apunta a ${bloque.tool_use_id}, que no tiene llamada`);
      return;
    }
    const texto = norm.texto(textoDe(bloque.content));
    const extra = linea.toolUseResult;
    const interpretacion = bloque.is_error !== true && typeof extra?.returnCodeInterpretation ===
      'string' && extra.returnCodeInterpretation.trim() ? extra.returnCodeInterpretation : null;
    accion.exito = interpretacion !== null ? null : bloque.is_error !== true;
    accion.codigoReinterpretado = interpretacion;
    accion.resultado = texto;
    accion.error = bloque.is_error === true ? texto : null;
    const archivo = /tool-results[\\/]([\w.-]+)/.exec(texto);
    if (archivo && carpeta) {
      const ruta = path.join(carpeta, 'tool-results', archivo[1]);
      if (fs.existsSync(ruta)) accion.resultadoCompleto = norm.texto(fs.readFileSync(ruta, 'utf8'));
    }
    if (extra && typeof extra === 'object') {
      if (extra.agentId) accion.subagente = String(extra.agentId);
      if (extra.backgroundTaskId) accion.tareaFondo = String(extra.backgroundTaskId);
    }
    // Un rechazo (#222, fase 5): el de una pregunta o un plan es una correccion, no un permiso.
    // El de un permiso es permiso_rechazado y, si la persona escribio como seguir, tambien una
    // correccion con su texto. El «haven't granted it yet» de una sesion sin persona (claude -p)
    // no es ninguna de las dos.
    if (bloque.is_error === true && esRechazoDePermiso(texto)) {
      const dicho = textoDelRechazo(texto);
      if (SIN_PERMISO.has(accion.herramienta)) {
        nuevaIntervencion('correccion', linea, accion, { texto: dicho });
      } else {
        nuevaIntervencion('permiso_rechazado', linea, accion);
        if (dicho !== null) nuevaIntervencion('correccion', linea, accion, { texto: dicho });
      }
    }
  };

  // Decisiones: el texto del asistente que precede a una o mas llamadas (PC-07). El texto que no
  // va seguido de ninguna llamada es la respuesta final al usuario y no es una decision.
  const lector = (turnoDe) => {
    let pendiente = '';
    let actual = null;
    return {
      reiniciar() {
        pendiente = '';
        actual = null;
      },
      texto(t) {
        const limpio = sinMarcadores(t);
        if (!limpio) return;
        if (actual) actual = null;
        pendiente += (pendiente ? '\n' : '') + limpio;
      },
      llamada(accion) {
        if (pendiente.trim()) {
          actual = {
            elemento: 'decision',
            id: `d${++cont.d}`,
            turno: turnoDe(),
            texto: norm.texto(pendiente.trim()),
            motiva: [accion.id],
            momento: accion.momento,
          };
          decisiones.push(actual);
          pendiente = '';
        } else if (actual) {
          actual.motiva.push(accion.id);
        }
      },
    };
  };

  const decisionesPrincipal = lector(() => turno.id);

  const ramasDelTurno = new Map();

  for (const linea of lineas) {
    if (linea.isSidechain) continue;
    if (linea.version) versiones.add(linea.version);
    if (turno && linea.gitBranch) {
      const vistas = ramasDelTurno.get(turno) || [];
      if (!vistas.includes(linea.gitBranch)) vistas.push(linea.gitBranch);
      ramasDelTurno.set(turno, vistas);
    }
    if (linea.type === 'assistant') {
      const modelo = linea.message?.model;
      if (modelo && modelo !== '<synthetic>') modelos.add(modelo);
      for (const b of linea.message?.content || []) {
        if (b.type === 'text') {
          decisionesPrincipal.texto(b.text);
          // Un marcador cuenta solo si su skill se invoco antes en la sesion (#222, fase 6): un
          // texto que EXPLICA el protocolo, con un marcador de ejemplo en su propia linea, no es
          // la skill marcando un paso. Paso en la sesion que construyo el visor. El del plan de
          // un issue no tiene skill: cuenta si el turno es de una tarea con numero (#230).
          for (const m of marcadoresEn(b.text)) {
            const delPlan = m.skill === PLAN_DE_ISSUE && turno &&
              tareaDeRama(linea.gitBranch || turno.rama) !== 'sin-tarea';
            if (delPlan || [...skills].some((x) => x.split(':').pop() === m.skill)) {
              marcarPaso(linea, m);
            }
            else {
              avisos.push(`un marcador de ${m.skill} aparecio sin que la skill se invocara ` +
                'antes; ' +
                'no cuenta como paso');
            }
          }
        }
        if (b.type !== 'tool_use') continue;
        if (!turno) nuevoTurno(linea, null, 'sin_prompt');
        const accion = nuevaAccion({ linea, uso: b, pasoId: paso.id, agenteId: 'orquestador' });
        decisionesPrincipal.llamada(accion);
      }
      continue;
    }
    if (linea.type !== 'user' || linea.isMeta) continue;
    const contenido = linea.message?.content;
    if (Array.isArray(contenido) && contenido.some((b) => b.type === 'tool_result')) {
      for (const b of contenido) if (b.type === 'tool_result') registrarResultado(linea, b);
      continue;
    }
    const texto = textoDe(contenido);
    if (!texto.trim()) continue;
    if (texto.includes('<local-command-caveat>') || texto.includes('<local-command-stdout>')) {
      continue;
    }
    if (texto.includes('<bash-stdout>') || texto.includes('<bash-stderr>')) {
      if (comandoPendiente) {
        const salida = entre(texto, 'bash-stdout') || '';
        const errores = entre(texto, 'bash-stderr') || '';
        comandoPendiente.resultado = norm.texto(salida);
        comandoPendiente.error = errores.trim() ? norm.texto(errores) : null;
        comandoPendiente.exito = !errores.trim();
        comandoPendiente = null;
      }
      continue;
    }
    const comando = entre(texto, 'bash-input');
    if (comando !== null) {
      decisionesPrincipal.reiniciar();
      nuevoTurno(linea, `!${comando}`, 'comando_usuario');
      agente('usuario', 'persona', 'usuario');
      const uso = { id: `usuario-${turno.id}`, name: 'Bash', input: { command: comando } };
      comandoPendiente = nuevaAccion({ linea, uso, pasoId: paso.id, agenteId: 'usuario' });
      nuevaIntervencion('comando_usuario', linea, comandoPendiente);
      trasInterrupcion = false;
      continue;
    }
    if (texto.includes('[Request interrupted by user')) {
      nuevaIntervencion('interrupcion', linea);
      trasInterrupcion = true;
      continue;
    }
    if (texto.includes('<task-notification>')) {
      const idTarea = entre(texto, 'task-id');
      const idUso = entre(texto, 'tool-use-id');
      const accion = acciones.find(
        (a) => (idTarea && a.tareaFondo === idTarea) || (idUso && a.toolUseId === idUso),
      );
      if (accion) {
        accion.resultadoDiferido = { momento: linea.timestamp || null, texto: norm.texto(texto) };
      } else {
        avisos.push(`una notificacion de tarea en segundo plano no tiene accion (${idTarea})`);
      }
      continue;
    }
    decisionesPrincipal.reiniciar();
    const nombreComando = entre(texto, 'command-name');
    if (nombreComando !== null) {
      const args = entre(texto, 'command-args') || '';
      nuevoTurno(linea, `${nombreComando.trim()} ${args.trim()}`.trim(), 'comando');
      const skill = nombreComando.trim().replace(/^\//, '');
      if (skill.includes(':')) skills.add(skill);
    } else {
      nuevoTurno(linea, texto, 'prompt');
    }
    // El prompt que sigue a una interrupcion es la persona diciendo como seguir (#222, fase 5).
    if (trasInterrupcion) {
      nuevaIntervencion('correccion', linea, null, { texto: turno.prompt });
      trasInterrupcion = false;
    }
  }

  // Un turno suele empezar en dev y crear su rama de trabajo a mitad de camino. Su tarea es la de
  // la PRIMERA rama con numero que toca, no la de la rama en la que llego el prompt: si no, todo
  // el trabajo de un issue cae en `sin-tarea`. Paso con la sesion que construyo este compilador.
  for (const [t, vistas] of ramasDelTurno) {
    const conTarea = vistas.filter((r) => tareaDeRama(r) !== 'sin-tarea');
    if (conTarea.length === 0) continue;
    t.rama = conTarea[0];
    t.tarea = tareaDeRama(conTarea[0]);
    const otras = [...new Set(conTarea.map(tareaDeRama))].filter((x) => x !== t.tarea);
    if (otras.length) {
      avisos.push(`el turno ${t.id} toca las tareas ${[t.tarea, ...otras].join(', ')}; ` +
        `queda en la ${t.tarea}`);
    }
  }

  // Subagentes: sus acciones van al paso de la llamada que los lanzo (I3), con su propio agente,
  // que actua en nombre de quien hizo la llamada (PC-11).
  const expandir = (lanzadora, profundidad) => {
    const archivo = path.join(carpeta, 'subagents', `agent-${lanzadora.subagente}.jsonl`);
    const tipo = lanzadora.entrada.subagent_type || 'general-purpose';
    const idAgente = agente(`subagente:${lanzadora.subagente}`, 'ia', `subagente:${tipo}`,
      lanzadora.agente);
    if (!fs.existsSync(archivo)) {
      avisos.push(`falta el transcript del subagente ${lanzadora.subagente}`);
      return;
    }
    const turnoDeLanzadora = pasos.find((p) => p.id === lanzadora.paso).turno;
    const lectorSub = lector(() => turnoDeLanzadora);
    const { registros } = leerJsonl(archivo);
    const lanzadas = [];
    for (const linea of registros) {
      if (linea.type === 'assistant') {
        const modelo = linea.message?.model;
        if (modelo && modelo !== '<synthetic>') modelos.add(modelo);
        for (const b of linea.message?.content || []) {
          if (b.type === 'text') lectorSub.texto(b.text);
          if (b.type !== 'tool_use') continue;
          const accion = nuevaAccion({
            linea, uso: b, pasoId: lanzadora.paso, agenteId: idAgente, lanzadaPor: lanzadora.id,
          });
          lectorSub.llamada(accion);
        }
      } else if (linea.type === 'user' && Array.isArray(linea.message?.content)) {
        for (const b of linea.message.content) {
          if (b.type === 'tool_result') registrarResultado(linea, b);
        }
      }
    }
    for (const a of acciones) {
      if (a.lanzadaPor === lanzadora.id && a.subagente) lanzadas.push(a);
    }
    for (const a of lanzadas) {
      if (profundidad < PROFUNDIDAD_MAXIMA) expandir(a, profundidad + 1);
      else avisos.push(`el subagente ${a.subagente} supera la profundidad maxima`);
    }
  };
  for (const a of acciones.filter((x) => x.subagente && x.agente === 'orquestador')) {
    expandir(a, 1);
  }

  // Permisos aprobados (#222, fase 5). PermissionRequest no trae tool_use_id: se empareja con el
  // ultimo PreToolUse anterior de la misma herramienta y la misma entrada (por su hash). Si la
  // accion corrio despues, la persona aprobo; si la rechazo, ya quedo como permiso_rechazado. Un
  // pedido en modo auto lo resolvio el clasificador, no una persona, y no se registra. Lo que no
  // se puede afirmar es que ningun otro hook haya respondido el pedido antes que la persona.
  const emparejados = new Set();
  captura.forEach((c, i) => {
    if (c.evento !== 'PermissionRequest') return;
    const pre = captura.slice(0, i).reverse().find((p) => p.evento === 'PreToolUse' &&
      p.herramienta === c.herramienta && p.entrada === c.entrada && !emparejados.has(p));
    if (!pre) {
      avisos.push(`un pedido de permiso de ${c.herramienta} (${c.momento}) no tiene su PreToolUse`);
      return;
    }
    emparejados.add(pre);
    const accion = accionPorUso.get(pre.toolUseId);
    if (!accion) return;
    if (!MODOS_CON_PERSONA.has(c.modo)) {
      avisos.push(`el permiso de ${accion.id} se resolvio en modo ${c.modo}: no lo decidio una ` +
        'persona');
      return;
    }
    const sinPersona = /haven't granted it yet/.test(accion.error || '');
    const corrio = accion.exito !== null || accion.codigoReinterpretado !== null;
    if (corrio && !sinPersona && !esRechazoDePermiso(accion.error)) {
      nuevaIntervencion('permiso_aprobado', { timestamp: c.momento }, accion);
    }
  });

  // Eventos perdidos (#222). Una accion que termino sin fallar corrio, y si la captura no tiene su
  // PreToolUse o su PostToolUse, los perdio: en la sesion de #218 el hook no cargaba porque una
  // accion habia roto un modulo que importaba. Esa accion queda con `capturada` en null, no en
  // false, que diria que no corrio y le quitaria el residuo en la curacion. Se avisa: la cadena
  // de arboles va a tener un hueco, y la curacion se detendra en el.
  const perdidas = [];
  for (const a of acciones) {
    const cap = capturaPorUso.get(a.toolUseId);
    const termino = a.exito !== null || a.codigoReinterpretado !== null;
    if (a.capturada === false && termino && a.exito !== false) {
      a.capturada = null;
      perdidas.push(a.id);
    } else if (a.capturada === true && termino && cap.despues === undefined) {
      perdidas.push(a.id);
    }
  }
  if (perdidas.length) {
    avisos.push(`la captura perdio eventos de ${perdidas.length} acciones que corrieron ` +
      `(${perdidas.join(', ')}): el hook no corrio o no cargo en ese tramo`);
  }

  // Efectos entre acciones (#218): un hook como format-on-edit corre en paralelo con la captura,
  // y lo que escribe puede quedar entre el `despues` de una accion y el `antes` de la siguiente.
  // Se recorre la captura en el orden en que se escribio, y cada cambio del arbol sin ninguna
  // accion abierta entra como accion derivada `hook`. No se infiere que hook fue, ni si fue una
  // persona editando a mano: el agente es `hook:sin-identificar`, y el nombre lo admite.
  let visto = null;
  const abiertas = new Set();
  for (const c of captura) {
    if (!c.arbol) continue;
    const accion = c.toolUseId ? accionPorUso.get(c.toolUseId) || null : null;
    const esPunto = c.evento === 'PreToolUse' || c.evento === 'SessionStart' ||
      c.evento === 'SessionEnd';
    if (esPunto && visto && abiertas.size === 0 && c.arbol !== visto.arbol) {
      const paso = (visto.accion || accion)?.paso;
      if (paso) {
        acciones.push({
          elemento: 'accion',
          id: `a${++cont.a}`,
          paso,
          agente: agente('hook:sin-identificar', 'automatismo', 'hook:sin-identificar'),
          lanzadaPor: null,
          herramienta: 'hook',
          toolUseId: null,
          entrada: null,
          claseDeterminismo: 'pura',
          segundoPlano: false,
          directorio: null,
          momento: visto.momento || null,
          exito: true,
          resultado: null,
          resultadoCompleto: null,
          resultadoDiferido: null,
          error: null,
          codigoReinterpretado: null,
          capturada: null,
          arbolAntes: visto.arbol,
          arbolDespues: c.arbol,
          cambios: raizRepo ? cambiosEntre(raizRepo, visto.arbol, c.arbol).map(norm.profundo) : [],
          subagente: null,
          tareaFondo: null,
          despuesDe: visto.accion?.id || null,
          antesDe: accion?.id || null,
        });
      } else {
        avisos.push(`el arbol cambio entre ${visto.momento} y ${c.momento}, sin acciones cerca`);
      }
    }
    // Solo abre la ventana una accion que el transcript tiene y que termino: un PreToolUse sin
    // llamada en el transcript, o el de una accion interrumpida, nunca recibe su Post, y dejarlo
    // abierto apagaba la deteccion el resto de la sesion. Paso en vivo en la sesion de #218. Una
    // accion con el codigo reinterpretado tambien termino, aunque su exito sea null (#219).
    const termino = accion && (accion.exito !== null || accion.codigoReinterpretado !== null);
    if (c.evento === 'PreToolUse' && termino &&
      !ENVOLTORIOS.has(accion.herramienta)) {
      abiertas.add(c.toolUseId);
    } else {
      abiertas.delete(c.toolUseId);
    }
    visto = { arbol: c.arbol, momento: c.momento, accion };
  }

  // Integracion de cada subagente (invariante I3, PC-11): `descartado` si todos los archivos que
  // cambio estan en el arbol final como estaban antes de su primer cambio; `integrado` si alguno
  // no, o si no cambio el arbol (por vacuidad: no le falta nada al arbol final). Null cuando
  // cambio el arbol y no se puede comparar, sin repo o sin arbol final. Se compara por blob.
  const arbolFinal = [...captura].reverse().find((c) => c.arbol)?.arbol || null;
  for (const ag of agentes.values()) {
    if (!ag.id.startsWith('subagente:')) continue;
    const conCambios = acciones.filter((a) => a.agente === ag.id && a.arbolAntes &&
      a.arbolDespues && a.arbolAntes !== a.arbolDespues);
    if (conCambios.length === 0) {
      ag.integracion = 'integrado';
      continue;
    }
    const antesDeEl = new Map();
    for (const a of conCambios) {
      for (const c of a.cambios) {
        if (!antesDeEl.has(c.archivo)) antesDeEl.set(c.archivo, a.arbolAntes);
      }
    }
    if (!raizRepo || !arbolFinal || antesDeEl.size === 0) continue;
    const revertido = [...antesDeEl].every(([archivo, arbol]) =>
      blobEn(raizRepo, arbolFinal, archivo) === blobEn(raizRepo, arbol, archivo));
    ag.integracion = revertido ? 'descartado' : 'integrado';
  }

  const huella = {
    claudeCode: [...versiones].sort(),
    modelos: [...modelos].sort(),
    plugin: versionDelPlugin(raizRepo, headBase),
    documentos: documentosUsados(raizRepo, headBase, skills, acciones),
    imagen: imagenDelMotor(raizRepo, headBase),
  };

  const finSesion = [...captura].reverse().find((c) => c.evento === 'SessionEnd' && c.arbol);
  return { actas: separarPorTarea({ sesion, turnos, pasos, acciones, decisiones, avisos,
    intervenciones, agentes, huella, headBase, inicioSesion,
    arbolFinal: finSesion?.arbol || null }), avisos };
}

function versionDelPlugin(raizRepo, commit) {
  if (!raizRepo || !commit) return null;
  const texto = leerEnCommit(raizRepo, commit, `${RUTA_PLUGIN}/.claude-plugin/plugin.json`);
  if (!texto) return null;
  try {
    return JSON.parse(texto).version || null;
  } catch {
    return null;
  }
}

// La imagen con la que el motor re-ejecuta esta acta (#222, fase 2): el Dockerfile tal como
// estaba en el HEAD base y el digest de su imagen de partida. El id de la imagen construida lo
// guarda el reporte del motor, que es quien la construye.
function imagenDelMotor(raizRepo, commit) {
  if (!raizRepo || !commit) return null;
  const dockerfile = versionDe(raizRepo, commit, RUTA_DOCKERFILE);
  if (!dockerfile) return null;
  const desde = /^FROM\s+(\S+@sha256:[0-9a-f]{64})/m.exec(leerEnCommit(raizRepo, commit,
    RUTA_DOCKERFILE) || '');
  return { dockerfile, base: desde ? desde[1] : null };
}

// AGENTS.md y CLAUDE.md de la raiz son el procedimiento de toda sesion: Claude Code los carga al
// arrancar, haya o no una skill. Se registran siempre en la version del HEAD base (#230).
const PROCEDIMIENTO_DEL_REPO = ['AGENTS.md', 'CLAUDE.md'];

function documentosUsados(raizRepo, commit, skills, acciones) {
  if (!raizRepo || !commit) return [];
  const rutas = new Set(PROCEDIMIENTO_DEL_REPO);
  for (const s of skills) {
    const nombre = s.split(':').pop();
    rutas.add(`${RUTA_PLUGIN}/skills/${nombre}/SKILL.md`);
  }
  for (const a of acciones) {
    if (a.herramienta !== 'Read' || !a.entrada.file_path) continue;
    const ruta = String(a.entrada.file_path).replace(/\\/g, '/').replace(/^\.\//, '');
    if (DOC_DE_SKILL.test(ruta) && !ruta.startsWith('~')) rutas.add(ruta);
  }
  return [...rutas].sort().map((r) => versionDe(raizRepo, commit, r)).filter(Boolean);
}

function porMomento(lista) {
  return lista
    .map((x, i) => ({ x, i }))
    .sort((p, q) => {
      const a = p.x.momento || '';
      const b = q.x.momento || '';
      return a < b ? -1 : a > b ? 1 : p.i - q.i;
    })
    .map((p) => p.x);
}

function separarPorTarea(s) {
  const tareas = [...new Set(s.turnos.map((t) => t.tarea))].sort();
  // El arbol de SessionEnd va solo en el acta de la ultima accion con arbol (#218): las demas
  // tareas dejaron de actuar antes, y su arbol final no es ese.
  const tareaDe = (a) => {
    const paso = s.pasos.find((p) => p.id === a.paso);
    return s.turnos.find((t) => t.id === paso?.turno)?.tarea;
  };
  const ultimaConArbol = porMomento(s.acciones.filter((a) => a.arbolDespues)).pop();
  const tareaFinal = ultimaConArbol ? tareaDe(ultimaConArbol) : null;
  const actas = new Map();
  for (const tarea of tareas) {
    const turnos = s.turnos.filter((t) => t.tarea === tarea);
    const idsTurno = new Set(turnos.map((t) => t.id));
    const pasos = s.pasos.filter((p) => idsTurno.has(p.turno));
    const idsPaso = new Set(pasos.map((p) => p.id));
    const acciones = porMomento(s.acciones.filter((a) => idsPaso.has(a.paso)));
    const decisiones = s.decisiones.filter((d) => idsTurno.has(d.turno));
    const intervenciones = porMomento(s.intervenciones.filter((i) => idsPaso.has(i.paso)));
    const usados = new Set(['orquestador', ...acciones.map((a) => a.agente),
      ...intervenciones.map((i) => i.agente)]);
    const agentes = [...s.agentes.values()].filter((a) => usados.has(a.id));
    const momentos = [...turnos, ...acciones].map((x) => x.momento).filter(Boolean).sort();
    const primeraConArbol = acciones.find((a) => a.arbolAntes);
    const actividades = new Set();
    for (const a of acciones) {
      if (a.herramienta === 'Skill' && a.entrada.skill) actividades.add(String(a.entrada.skill));
    }
    for (const t of turnos) {
      const comando = t.origen === 'comando' ? t.prompt.split(/\s/)[0].replace(/^\//, '') : '';
      if (comando.includes(':')) actividades.add(comando);
    }
    const fases = [...new Set(pasos.map((p) => p.faseDeclarada).filter((x) => x !== null))].sort();
    if (fases.length > 1) {
      s.avisos.push(`el acta de la tarea ${tarea} declara las fases ${fases.join(', ')}; ` +
        'queda sin fase');
    }
    const cabecera = {
      elemento: 'acta',
      version: 1,
      sesion: s.sesion,
      tarea,
      ramas: [...new Set(turnos.map((t) => t.rama).filter(Boolean))].sort(),
      proceso: 'sdlc-ia',
      actividades: [...actividades].sort(),
      fase: fases.length === 1 ? fases[0] : null,
      headBase: s.headBase,
      arbolBase: primeraConArbol ? primeraConArbol.arbolAntes : s.inicioSesion?.arbol || null,
      arbolFinal: tarea === tareaFinal ? s.arbolFinal : null,
      inicio: momentos[0] || null,
      fin: momentos[momentos.length - 1] || null,
      huella: s.huella,
    };
    actas.set(tarea, [cabecera, ...agentes, ...turnos, ...pasos, ...decisiones, ...acciones,
      ...intervenciones]);
  }
  return actas;
}

// Quien compila es quien trabajo: el hook corre en su maquina. Su cuenta, su dominio, su carpeta
// y su email de git se redactan del acta (#222, fase 3).
function identidadDeQuienCompila(raizRepo) {
  let usuario = process.env.USERNAME || null;
  try {
    usuario = usuario || os.userInfo().username;
  } catch { /* sin cuenta legible, sin redaccion de cuenta */ }
  const email = raizRepo ? git(['config', 'user.email'], { cwd: raizRepo,
    permitirFallo: true }) : null;
  return identidadDelEntorno({ email: email ? email.trim() : null, usuario,
    dominio: process.env.USERDOMAIN || null, casa: path.basename(os.homedir()) });
}

export function serializar(registros) {
  return registros.map((r) => JSON.stringify(r)).join('\n') + '\n';
}

// gitleaks sobre el acta antes de dejarla en su lugar.
// Devuelve 'limpia', 'secreto' o 'sin-gitleaks'.
export function verificarSecretos(archivo) {
  try {
    execFileSync('gitleaks', ['dir', archivo, '--no-banner', '--redact', '--exit-code', '1'], {
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    return 'limpia';
  } catch (e) {
    if (e.code === 'ENOENT') return 'sin-gitleaks';
    return 'secreto';
  }
}

export function leerCaptura(archivo) {
  if (!archivo || !fs.existsSync(archivo)) return [];
  return leerJsonl(archivo).registros;
}

// Compila y escribe. La usan la CLI y el hook SessionEnd. Devuelve el codigo de salida, y deja
// en `escritas` la ruta de cada acta que escribio: el hook las cura despues.
export function compilarYEscribir({
  transcript, captura, salida, raizRepo = null, verificar = true, log = console, escritas = [],
  carpetaSesion = null,
}) {
  const { actas, avisos } = compilar({ transcript, captura: leerCaptura(captura), raizRepo,
    carpetaSesion, identidad: identidadDeQuienCompila(raizRepo) });
  for (const a of avisos) log.error(`aviso: ${a}`);
  let codigo = 0;
  for (const [tarea, registros] of actas) {
    const errores = validarActa(registros);
    if (errores.length) {
      for (const e of errores) log.error(`tarea ${tarea}, ${e.invariante}: ${e.mensaje}`);
      log.error(`El acta de la tarea ${tarea} rompe el modelo y no se escribio.`);
      codigo = Math.max(codigo, 1);
      continue;
    }
    const sesion = registros[0].sesion;
    const destino = path.join(salida, tarea, `${sesion}.acta.cruda.jsonl`);
    fs.mkdirSync(path.dirname(destino), { recursive: true });
    const tmp = path.join(os.tmpdir(), `acta-${sesion}-${tarea}-${process.pid}.jsonl`);
    fs.writeFileSync(tmp, serializar(registros));
    const estado = verificar ? verificarSecretos(tmp) : 'limpia';
    if (estado !== 'limpia') {
      fs.rmSync(tmp, { force: true });
      if (estado === 'secreto') {
        log.error(`gitleaks encontro un posible secreto en el acta de la tarea ${tarea}: no se ` +
          'escribio. Corre gitleaks sobre el transcript para ver cual.');
        codigo = Math.max(codigo, 3);
      } else {
        log.error('No hay gitleaks en el PATH: el acta no se escribe sin verificar secretos.');
        codigo = Math.max(codigo, 4);
      }
      continue;
    }
    fs.copyFileSync(tmp, destino);
    fs.rmSync(tmp, { force: true });
    escritas.push(destino);
    log.log(`Acta de la tarea ${tarea}: ${destino}`);
  }
  return codigo;
}

function argumentos(argv) {
  const r = { verificar: true };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--transcript') r.transcript = argv[++i];
    else if (a === '--captura') r.captura = argv[++i];
    else if (a === '--salida') r.salida = argv[++i];
    else if (a === '--sin-verificar-secretos') r.verificar = false;
    else r.desconocido = a;
  }
  return r;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const a = argumentos(process.argv.slice(2));
  if (!a.transcript || a.desconocido || !fs.existsSync(a.transcript)) {
    console.error('Uso: node scripts/acta/compilar-acta.mjs --transcript <sesion.jsonl> ' +
      '[--captura <captura.jsonl>] [--salida <carpeta>] [--sin-verificar-secretos]');
    process.exitCode = 2;
  } else {
    const raiz = raizDelRepo(process.cwd()) || process.cwd();
    const sesion = path.basename(a.transcript, '.jsonl');
    const salida = a.salida || path.join(raiz, '.ia', 'registros');
    const captura = a.captura || path.join(raiz, '.ia', 'captura', `${sesion}.jsonl`);
    process.exitCode = compilarYEscribir({ transcript: a.transcript, captura, salida,
      raizRepo: raizDelRepo(process.cwd()), verificar: a.verificar });
  }
}
