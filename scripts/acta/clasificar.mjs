// La clase de determinismo de cada accion (PC-13, ADR-0005).
//
//   pura             Edit, Write: se aplica y se compara el hash.
//   local            se ejecuta en el sandbox y se compara la salida.
//   externa_lectura  lee fuera de la maquina: se sirve el resultado grabado como fixture.
//   efecto_externo   escribe fuera de la maquina: nunca se ejecuta.
//
// Ante la duda, efecto_externo. Equivocarse hacia ahi cuesta una accion sin verificar; hacia el
// otro lado cuesta un `git push` re-ejecutado.

const PURAS = new Set(['Edit', 'Write', 'MultiEdit', 'NotebookEdit']);
const LOCALES = new Set(['Read', 'Grep', 'Glob', 'LS']);
const LECTURAS = new Set(['WebSearch', 'WebFetch', 'ToolSearch', 'Skill', 'Agent', 'Task']);

const alternativas = (lista) => lista.join('|');

const GH_RECURSOS = alternativas(['pr', 'issue', 'release', 'repo', 'project', 'label',
  'workflow', 'run']);
const GH_ESCRITURAS = alternativas(['create', 'edit', 'merge', 'close', 'reopen', 'delete',
  'comment', 'review', 'item-add', 'item-edit', 'run', 'cancel', 'rerun', 'upload']);

// Comandos de shell que escriben fuera de la maquina. Se revisan antes que las lecturas.
const SHELL_EXTERNO = [
  /\bgit\s+push\b/,
  new RegExp(`\\bgh\\s+(${GH_RECURSOS})\\s+(${GH_ESCRITURAS})\\b`),
  /\bgh\s+api\b[^|;&]*(-X|--method)\s*(POST|PUT|PATCH|DELETE)\b/,
  /\bgh\s+api\b[^|;&]*\s(-f|-F|--field|--raw-field|--input)\s/,
  /\bcurl\b[^|;&]*(-X|--request)\s*(POST|PUT|PATCH|DELETE)\b/,
  /\bcurl\b[^|;&]*\s(-d|--data\S*|-F|--form)\s/,
  /\b(npm|pnpm|yarn)\s+publish\b/,
  /\bdocker\s+push\b/,
  /\b(ssh|scp|rsync)\s/,
  /\baz\s+\S+\s+(create|update|delete)\b/,
  /\bInvoke-(WebRequest|RestMethod)\b[^|;&]*-Method\s+(Post|Put|Patch|Delete)\b/i,
];

// Comandos de shell que solo leen fuera de la maquina.
const SHELL_LECTURA = [
  /\bgit\s+(fetch|pull|clone|ls-remote)\b/,
  /\bgh\s+/,
  /\b(curl|wget)\b/,
  /\bInvoke-(WebRequest|RestMethod)\b/i,
  /\b(npm|pnpm|yarn)\s+(install|ci|view|info|outdated)\b/,
  /\bdocker\s+pull\b/,
];

// Verbos de herramientas MCP que solo leen. Cualquier otro verbo cuenta como escritura.
const VERBOS_LECTURA = alternativas(['get', 'list', 'query', 'read', 'search', 'buscar',
  'historial', 'contexto', 'info', 'stats', 'status', 'health', 'explain', 'describe', 'log',
  'logs', 'children', 'cycle', 'locks', 'connections', 'indexes', 'extensions']);
const VERBO_LECTURA = new RegExp(`(^|[_.-])(${VERBOS_LECTURA})([_.-]|$)`, 'i');

export function claseDeterminismo(herramienta, entrada = {}) {
  if (PURAS.has(herramienta)) return 'pura';
  if (LOCALES.has(herramienta)) return 'local';
  if (LECTURAS.has(herramienta)) return 'externa_lectura';
  if (herramienta === 'Bash' || herramienta === 'PowerShell') {
    const cmd = String(entrada.command || '');
    if (SHELL_EXTERNO.some((r) => r.test(cmd))) return 'efecto_externo';
    if (SHELL_LECTURA.some((r) => r.test(cmd))) return 'externa_lectura';
    return 'local';
  }
  if (herramienta === 'Artifact') {
    const accion = entrada.action || 'publish';
    return ['read', 'list', 'quickstart'].includes(accion) ? 'externa_lectura' : 'efecto_externo';
  }
  if (herramienta === 'ArtifactData') {
    return ['get', 'list', 'query', 'profiles'].includes(entrada.action)
      ? 'externa_lectura'
      : 'efecto_externo';
  }
  if (herramienta.startsWith('mcp__')) {
    const nombre = herramienta.split('__').slice(2).join('__');
    return VERBO_LECTURA.test(nombre) ? 'externa_lectura' : 'efecto_externo';
  }
  return 'efecto_externo';
}

// Las herramientas que no cambian el arbol. La captura no les toma el hash (cuesta unos 70 ms), y
// la curacion sabe que un fallo suyo no deja residuo aunque su arbol este en null.
export const SIN_ARBOL = new Set(['Read', 'Grep', 'Glob', 'LS', 'WebSearch', 'WebFetch',
  'ToolSearch', 'Skill', 'TodoWrite', 'AskUserQuestion']);

// Un rechazo de permiso deja un tool_result con error y uno de estos textos. Un hook que deniega
// NO es un rechazo de una persona: ese caso queda como accion fallida, sin intervencion.
const RECHAZO = [
  /doesn't want to proceed with this tool use/i,
  /tool use was rejected/i,
  /permission to use \S+ has been denied/i,
];

export function esRechazoDePermiso(texto) {
  return RECHAZO.some((r) => r.test(texto || ''));
}
