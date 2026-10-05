// Validacion del arbol final de la curada (#218, ADR-0005 punto 3, cuarta regla): antes de
// emitirla, se aplican en memoria sus acciones puras y se comprueba que lleguen al arbol final.
//
// La curacion (curar-acta.mjs) es pura sobre los bytes de la cruda; esto no, porque aplicar un
// Edit exige leer el blob del arbol de antes. Solo lee objetos por su hash, que no cambian: la
// curada sigue siendo funcion de la cruda y de esos objetos. Sin repo, o sin uno de los arboles,
// no se verifica nada y la curacion se DETIENE: no hay curada sin verificar.
//
// Tres comprobaciones:
//
// 1. Todos los arboles del acta estan en el repo como objetos.
// 2. Cada Edit, Write o MultiEdit que cambio el arbol se aplica sobre el blob de su arbol de antes.
//    Si no aplica (el old_string no esta), se DETIENE. Si aplica y no da su arbol de despues, un
//    hook escribio dentro de su ventana: format-on-edit corre en paralelo con la captura. La
//    accion se PARTE: ella queda de su arbol de antes al que da sola, y entra una accion `hook`
//    (`<id>.h`) desde ahi hasta su arbol de despues, con su diff. Ese arbol intermedio se
//    escribe en el repo, como la captura escribe los suyos.
// 3. Si la cabecera trae `arbolFinal` (el de SessionEnd, solo en la ultima acta de la sesion), la
//    cadena de arboles de la curada termina en el. La continuidad entre eslabones ya la exige la
//    curacion.
//
// Medido sobre las dos sesiones de este repo con captura: 66 Edit y Write reproducen su arbol
// byte a byte, con core.autocrlf=true, porque se aplican sobre el blob y no sobre el archivo.
import { ENVOLTORIOS } from './clasificar.mjs';
import { arbolCon, blobTexto, cambiosEntre, objetosQueFaltan } from './git.mjs';

const APLICABLES = new Set(['Edit', 'Write', 'MultiEdit']);
const AGENTE_HOOK = {
  elemento: 'agente',
  id: 'hook:sin-identificar',
  tipo: 'automatismo',
  rol: 'hook:sin-identificar',
  actuoEnNombreDe: null,
  integracion: null,
};

// La ruta del archivo de una accion relativa al repo, o null si apunta fuera de el. El
// compilador ya cambio la raiz del repo por "." y la carpeta del usuario por "~".
export function rutaEnRepo(archivo) {
  const r = String(archivo ?? '').replace(/\\/g, '/').replace(/^(\.\/)+/, '');
  if (!r || r === '.' || r.startsWith('~') || r.startsWith('/') || /^[A-Za-z]:/.test(r)) {
    return null;
  }
  return r.split('/').includes('..') ? null : r;
}

// El contenido que deja la accion sobre `contenido`, o null si no se puede aplicar.
export function aplicar(accion, contenido) {
  const e = accion.entrada || {};
  if (accion.herramienta === 'Write') return typeof e.content === 'string' ? e.content : null;
  if (contenido === null) return null;
  const cambios = accion.herramienta === 'MultiEdit' ? e.edits || [] : [e];
  let texto = contenido;
  for (const c of cambios) {
    if (typeof c.old_string !== 'string' || !texto.includes(c.old_string)) return null;
    texto = c.replace_all
      ? texto.split(c.old_string).join(c.new_string)
      : texto.replace(c.old_string, () => c.new_string);
  }
  return texto;
}

// Devuelve la curada verificada (con las acciones partidas) y los motivos para detenerse.
export function verificarArbol(registros, raizRepo) {
  if (!raizRepo) return { registros, motivos: ['sin el repo no se puede verificar el arbol'] };
  const cabecera = registros.find((r) => r.elemento === 'acta');
  const acciones = registros.filter((r) => r.elemento === 'accion');
  const hashes = new Set([cabecera.arbolBase, cabecera.arbolFinal,
    ...acciones.flatMap((a) => [a.arbolAntes, a.arbolDespues])].filter(Boolean));
  const faltan = objetosQueFaltan(raizRepo, [...hashes].sort());
  if (faltan.length) {
    return { registros, motivos: [`faltan en el repo los arboles ${faltan.join(', ')}`] };
  }

  const motivos = [];
  const salida = [];
  for (const a of registros) {
    salida.push(a);
    if (a.elemento !== 'accion' || !APLICABLES.has(a.herramienta) || !a.arbolAntes ||
      !a.arbolDespues || a.arbolAntes === a.arbolDespues) continue;
    const intermedio = arbolPropio(a, raizRepo);
    if (intermedio === null) {
      motivos.push(`la accion ${a.id} (${a.herramienta}) no se aplica sobre su arbol de antes`);
      continue;
    }
    if (intermedio === a.arbolDespues) continue;
    salida[salida.length - 1] = { ...a, arbolDespues: intermedio,
      cambios: cambiosEntre(raizRepo, a.arbolAntes, intermedio) };
    salida.push({
      elemento: 'accion',
      id: `${a.id}.h`,
      paso: a.paso,
      agente: AGENTE_HOOK.id,
      lanzadaPor: null,
      herramienta: 'hook',
      claseDeterminismo: 'pura',
      momento: a.momento,
      exito: true,
      arbolAntes: intermedio,
      arbolDespues: a.arbolDespues,
      cambios: cambiosEntre(raizRepo, intermedio, a.arbolDespues),
      parteDe: a.id,
      despuesDe: a.id,
      antesDe: null,
      intentosPrevios: [],
    });
  }

  if (cabecera.arbolFinal) {
    const ultima = [...salida].reverse().find((a) => a.elemento === 'accion' && a.arbolDespues &&
      !ENVOLTORIOS.has(a.herramienta));
    const fin = ultima ? ultima.arbolDespues : cabecera.arbolBase;
    if (fin !== cabecera.arbolFinal) {
      motivos.push(`la curada termina en el arbol ${fin} y la sesion en ${cabecera.arbolFinal}`);
    }
  }

  const conHook = salida.some((a) => a.agente === AGENTE_HOOK.id);
  if (conHook && !salida.some((r) => r.elemento === 'agente' && r.id === AGENTE_HOOK.id)) {
    const ultimoAgente = salida.findLastIndex((r) => r.elemento === 'agente');
    salida.splice(ultimoAgente + 1, 0, AGENTE_HOOK);
  }
  return { registros: salida, motivos };
}

// El arbol que deja la accion sola sobre su arbol de antes, o null si no se puede aplicar. Un
// archivo fuera del repo, o que no esta en ninguno de los dos arboles (ignorado por git), no
// cambia el arbol: lo que haya cambiado en esa ventana no es de la accion.
function arbolPropio(a, raizRepo) {
  const ruta = rutaEnRepo(a.entrada?.file_path);
  if (ruta === null) return a.arbolAntes;
  const antes = blobTexto(raizRepo, a.arbolAntes, ruta);
  if (antes === null && blobTexto(raizRepo, a.arbolDespues, ruta) === null) return a.arbolAntes;
  const nuevo = aplicar(a, antes);
  return nuevo === null ? null : arbolCon(raizRepo, a.arbolAntes, ruta, nuevo);
}
