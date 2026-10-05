// El modelo de vista del visor del acta (#222, fase 6): de los registros a lo que lee una persona.
//
// Funcion pura. Recibe la curada y, si los hay, la cruda, el reporte del motor, la conformidad, el
// indice de la tarea, la deriva de la huella y la configuracion del proceso
// (scripts/acta/proceso.json), y devuelve todo lo que el visor muestra, ya redactado.
//
// Tres reglas que no son de estilo (docs/jerarquia-proceso-actividad-tarea.md, ADR-0005):
//
// 1. LENGUAJE COMUN. Ningun texto visible lleva un id del acta (t2.p2, a10), un codigo de pregunta
//    de competencia ni un valor de enumeracion: «Turno 2», «Paso 3 de 5», «Accion 9». Los ids
//    viven solo en `registro`, el bloque que muestra el archivo tal cual. Una prueba lo exige.
// 2. LOS TRES EJES, como en la norma: el trabajo (proceso, actividad, tarea, paso, accion, con
//    quien responde en cada nivel), la documentacion (manual, procedimiento, instructivo,
//    registro) y el
//    tiempo (las fases, con su gate). Lo que la configuracion no dice queda como un campo a
//    completar, nunca inventado.
// 3. NUNCA AFIRMA LO QUE NADIE COMPROBO. Sin reporte del motor, cada accion dice «registrada, sin
//    verificar»; lo que salio del fixture dice «tomada del registro»; nada que no se re-ejecuto
//    dice «re-ejecutada» (PC-13).
import { ENVOLTORIOS } from './clasificar.mjs';

const MESES = [
  'enero',
  'febrero',
  'marzo',
  'abril',
  'mayo',
  'junio',
  'julio',
  'agosto',
  'septiembre',
  'octubre',
  'noviembre',
  'diciembre',
];

const corto = (s, n) => {
  const t = String(s ?? '')
    .replace(/\s+/g, ' ')
    .trim();
  return t.length > n ? `${t.slice(0, n - 1)}…` : t;
};
// «1 acción», «2 acciones»: un auditor lee el plural mal puesto como descuido.
export const plural = (n, uno, varios) => `${n} ${n === 1 ? uno : varios}`;
const ruta = (p) =>
  String(p ?? '')
    .replace(/\\/g, '/')
    .replace(/^(\.\/)+/, '');
const hora = (iso) => (iso ? iso.slice(11, 16) : '');
const dia = (iso) => {
  if (!iso) return '';
  const [a, m, d] = iso.slice(0, 10).split('-').map(Number);
  return `${d} de ${MESES[m - 1]} de ${a}`;
};

export function etiquetaDeAgente(id, agentes = []) {
  if (id === 'orquestador') return 'orquestador IA';
  if (id === 'usuario') return 'la persona';
  if (String(id).startsWith('hook:')) return 'un hook';
  if (String(id).startsWith('subagente:')) {
    const a = agentes.find((x) => x.id === id);
    const tipo = String(a?.rol || '').replace(/^subagente:/, '');
    return tipo ? `subagente IA (${tipo})` : 'subagente IA';
  }
  return 'otro agente';
}

const archivosDe = (a) => [...new Set((a.cambios || []).map((c) => ruta(c.archivo)))];

// Que hizo la accion, dicho para una persona, y el texto literal que la acompana.
export function describirAccion(a) {
  const e = a.entrada || {};
  const archivo = ruta(e.file_path || e.notebook_path);
  switch (a.herramienta) {
    case 'Edit':
      return {
        texto: `Edita ${archivo}`,
        codigo: `«${corto(e.old_string, 50)}» → «${corto(e.new_string, 50)}»`,
      };
    case 'MultiEdit':
      return { texto: `Edita ${archivo} en varios lugares`, codigo: '' };
    case 'Write':
      return { texto: `Escribe ${archivo}`, codigo: '' };
    case 'Read':
      return { texto: `Lee ${archivo}`, codigo: '' };
    case 'Bash':
    case 'PowerShell':
      return {
        texto: 'Ejecuta un comando',
        codigo: corto(String(e.command || '').split('\n')[0], 110),
      };
    case 'Grep':
    case 'Glob':
      return { texto: 'Busca en los archivos', codigo: corto(e.pattern, 80) };
    case 'hook':
      return {
        texto: `Cambio automático en ${archivosDe(a).join(', ') || 'los archivos'}`,
        codigo: '',
        nota: 'lo hizo un hook, no la IA',
      };
    case 'residuo':
      return {
        texto: `Lo que dejó un intento fallido en ${archivosDe(a).join(', ')}`,
        codigo: '',
        nota: 'el intento falló, pero cambió los archivos',
      };
    case 'Agent':
    case 'Task':
      return { texto: 'Delega a un subagente', codigo: corto(e.description || e.prompt, 90) };
    case 'Skill':
      return { texto: `Invoca la skill ${e.skill || ''}`.trim(), codigo: '' };
    case 'WebSearch':
    case 'WebFetch':
      return { texto: 'Consulta la web', codigo: corto(e.query || e.url, 90) };
    default:
      return {
        texto: a.herramienta?.startsWith('mcp__')
          ? `Usa la herramienta externa ${a.herramienta.split('__').pop()}`
          : `Usa ${a.herramienta}`,
        codigo: '',
      };
  }
}

// El estado de una accion segun el reporte del motor, en palabras.
export function estadoDe(a, v) {
  if (!v)
    return {
      clase: 'sin',
      texto: 'registrada, sin verificar',
      detalle: 'No hay registro de ' + 'verificación: nadie volvió a ejecutar esta acción.',
    };
  const motivo = String(v.motivo || '');
  const codigo = /codigo (\d+)/.exec(motivo);
  const explicaDiverge = /deja el arbol/.test(motivo)
    ? 'Lo que dejó en los archivos no es lo que dice el registro.'
    : codigo
      ? `Terminó con error (código ${codigo[1]}), y en la sesión había terminado bien.`
      : /no se aplica|texto a reemplazar/.test(motivo)
        ? 'El cambio no se pudo aplicar sobre los archivos de antes.'
        : /sin terminar/.test(motivo)
          ? 'No terminó en el tiempo permitido.'
          : 'No coincide con el registro.';
  const m = v.modo;
  if (m === 'fixture')
    return {
      clase: 'sin',
      texto: '○ tomada del registro · sin verificar',
      detalle:
        'Lee fuera de la máquina: su resultado se tomó del registro y no se volvió a ejecutar.',
    };
  if (m === 'omitida') {
    if (a.claseDeterminismo === 'efecto_externo')
      return {
        clase: 'fuera',
        texto: '○ no reproducible: sale de la máquina',
        detalle:
          'Escribe fuera de la máquina ' +
          '(publicar, comentar, desplegar): nunca se vuelve a ejecutar.',
      };
    if (ENVOLTORIOS.has(a.herramienta))
      return {
        clase: 'fuera',
        texto: '○ delega: sus acciones se ' + 'verifican una por una',
        detalle: 'Las acciones del subagente aparecen y se verifican por separado.',
      };
    return {
      clase: 'fuera',
      texto: '○ no reproducible',
      detalle: `No se volvió a ejecutar: ${motivo}.`,
    };
  }
  const verbo = m === 'ejecutada' ? 're-ejecutada' : m === 'aplicada' ? 'aplicada' : 'comparada';
  const como =
    m === 'ejecutada'
      ? 'Se volvió a ejecutar en un contenedor aislado, sin la IA.'
      : m === 'aplicada'
        ? 'Se volvió a aplicar el cambio sobre los archivos de antes, sin la IA.'
        : 'Se comparó lo que leyó con el archivo de ese momento.';
  if (v.veredicto === 'igual')
    return { clase: 'ok', texto: `✓ ${verbo} · coincide`, detalle: `${como} Coincide.` };
  if (v.veredicto === 'equivalente')
    return {
      clase: 'casi',
      texto: `≈ ${verbo} · mismo resultado, otra salida`,
      detalle: `${como} Dejó lo mismo en los archivos, con una salida distinta.`,
    };
  if (v.veredicto === 'diverge')
    return {
      clase: 'no',
      texto: `✗ ${verbo} · no coincide`,
      detalle: `${como} No coincide: ${explicaDiverge}`,
    };
  return {
    clase: 'sin',
    texto: `○ ${verbo} · no se pudo verificar`,
    detalle: `${como} No se pudo verificar: la herramienta no está en el contenedor.`,
  };
}

const INTERVENCION = {
  permiso_aprobado: 'La persona aprobó el permiso',
  permiso_rechazado: 'La persona rechazó el permiso',
  interrupcion: 'La persona interrumpió',
  comando_usuario: 'La persona ejecutó un comando',
  correccion: 'La persona corrigió el rumbo',
};

// Lo que el archivo dice de la accion, para el bloque «Asi esta en el archivo del acta»: el unico
// lugar con ids. Los textos largos se recortan; el archivo completo es la fuente.
function registroCrudo(r) {
  const o = {};
  for (const [k, val] of Object.entries(r)) {
    if (k === 'cambios') o.cambios = (val || []).map((c) => c.archivo);
    else if (typeof val === 'string') o[k] = corto(val, 160);
    else o[k] = val;
  }
  return JSON.stringify(o, null, 1);
}

// El procedimiento de la sesion, tal como lo registra la huella (#230): el SKILL.md de la skill
// que ejecuto la actividad o, sin skill, el AGENTS.md del repositorio.
export function documentoDelProcedimiento(cab, clave, esPlan) {
  const docs = cab.huella?.documentos || [];
  const suyo = (d) => d.ruta.endsWith(`/skills/${clave}/SKILL.md`);
  if (clave && !esPlan) return docs.find(suyo) || null;
  return docs.find((d) => d.ruta === 'AGENTS.md') || null;
}

export function construirVista({
  curada,
  cruda = null,
  reporte = null,
  conformidad = null,
  indice = null,
  deriva = null,
  proceso = {},
  tarea = {},
  procedimiento = {},
  commits = [],
  nombreActa = 'acta',
}) {
  const de = (el) => curada.filter((r) => r.elemento === el);
  const cab = curada.find((r) => r.elemento === 'acta');
  const agentes = de('agente');
  const veredictos = new Map((reporte?.veredictos || []).map((v) => [v.accion, v]));
  const linea = new Map(curada.map((r, i) => [r.id, i + 1]));
  const actividad = conformidad?.actividades?.[0] || null;
  const prescritos = actividad?.prescritos || [];
  const traducciones = proceso.traducciones?.[actividad?.skill] || {};
  // La actividad es la del catalogo de proceso.json; la skill es la herramienta que la ejecuta
  // (#230). Sale de los pasos marcados o, sin marcas, de la primera skill invocada.
  const clave = actividad?.skill || (cab.actividades?.[0] || '').split(':').pop() || null;
  const delCatalogo = clave ? proceso.actividades?.[clave] || null : null;
  const esPlan = Boolean(delCatalogo?.sinSkill);
  const docProcedimiento = documentoDelProcedimiento(cab, clave, esPlan);
  const nombreProcedimiento = docProcedimiento
    ? docProcedimiento.ruta === 'AGENTS.md'
      ? 'AGENTS.md del repositorio'
      : `${clave} · SKILL.md`
    : null;

  const pasoVista = (p) => {
    if (p.procedencia !== 'marcado')
      return {
        etiqueta: 'Sin paso marcado',
        literal: '',
        traduccion: '',
        marcado: false,
        corto: 'sin paso',
      };
    const pres = prescritos.find((x) => x.letra === p.pasoPrescrito.letra);
    const k = pres ? pres.orden : p.pasoPrescrito.letra;
    return {
      etiqueta: pres ? `Paso ${k} de ${prescritos.length}` : `Paso ${k}`,
      literal: pres?.titulo || '',
      traduccion: traducciones[p.pasoPrescrito.letra] || '',
      marcado: true,
      corto: `Paso ${k}`,
    };
  };

  const fallidos = new Map(
    (cruda || []).filter((r) => r.elemento === 'accion').map((r) => [r.id, r]),
  );
  const acciones = [];
  const turnos = de('turno').map((t, ti) => {
    const pasos = de('paso')
      .filter((p) => p.turno === t.id)
      .map((p) => {
        const pv = pasoVista(p);
        const decision = de('decision').find(
          (d) =>
            d.turno === t.id && d.motiva.some((m) => curada.find((x) => x.id === m)?.paso === p.id),
        );
        const filas = [];
        for (const a of de('accion').filter((x) => x.paso === p.id)) {
          for (const idPrevio of a.intentosPrevios || []) {
            const f = fallidos.get(idPrevio);
            if (f)
              filas.push({
                tipo: 'intento',
                texto: `Intento fallido: ${describirAccion(f).texto}`,
                codigo: describirAccion(f).codigo,
                agente: etiquetaDeAgente(f.agente, agentes),
                nota: 'queda en el registro completo, fuera de la secuencia',
              });
          }
          const d = describirAccion(a);
          const est = estadoDe(a, reporte ? veredictos.get(a.id) : null);
          const vista = {
            orden: acciones.length + 1,
            texto: d.texto,
            codigo: d.codigo,
            nota: d.nota || '',
            agente: etiquetaDeAgente(a.agente, agentes),
            estado: est,
            turno: ti + 1,
            paso: pv,
            momento: a.momento ? hora(a.momento) : '',
            registro: registroCrudo(a),
            registroLinea: linea.get(a.id) || null,
            salidaMotor: veredictos.get(a.id)?.salida
              ? corto(veredictos.get(a.id).salida, 400)
              : '',
          };
          acciones.push(vista);
          filas.push({ tipo: 'accion', indice: vista.orden - 1 });
        }
        for (const i of de('intervencion').filter((x) => x.paso === p.id)) {
          filas.push({
            tipo: 'intervencion',
            texto: INTERVENCION[i.tipo] || 'La persona intervino',
            detalle: i.texto ? `«${corto(i.texto, 140)}»` : '',
            agente: 'la persona',
          });
        }
        return {
          ...pv,
          decision: decision ? corto(decision.texto, 400) : '',
          filas,
          n: filas.filter((f) => f.tipo === 'accion').length,
        };
      });
    const quien =
      t.origen === 'comando'
        ? `la persona invoca ${corto(t.prompt, 60)}`
        : t.origen === 'comando_usuario'
          ? 'la persona ejecuta un comando'
          : t.prompt
            ? `la persona pide «${corto(t.prompt, 90)}»`
            : 'sin pedido de la persona';
    return { numero: ti + 1, quien, pasos };
  });

  const porModo = (m) => (reporte?.veredictos || []).filter((v) => v.modo === m);
  const ejec = porModo('ejecutada');
  const verificacion = reporte
    ? {
        hay: true,
        fecha: '',
        grupos: [
          {
            titulo: plural(ejec.length, 're-ejecutada', 're-ejecutadas'),
            detalle:
              `${
                ejec.filter((v) => ['igual', 'equivalente'].includes(v.veredicto)).length
              } coinciden · ${ejec.filter((v) => v.veredicto === 'diverge').length} no · ` +
              `${ejec.filter((v) => v.veredicto === 'no_verificable').length} sin verificar`,
            peso: ejec.length,
          },
          {
            titulo: plural(porModo('aplicada').length, 'aplicada', 'aplicadas'),
            detalle: 'el cambio se volvió a aplicar',
            peso: porModo('aplicada').length,
          },
          {
            titulo: plural(porModo('comparada').length, 'comparada', 'comparadas'),
            detalle: 'lo leído contra el archivo',
            peso: porModo('comparada').length,
          },
          {
            titulo: plural(
              porModo('fixture').length,
              'tomada del registro',
              'tomadas del registro',
            ),
            detalle: 'leen fuera de la máquina · sin verificar',
            peso: porModo('fixture').length,
          },
          {
            titulo: plural(porModo('omitida').length, 'no reproducible', 'no reproducibles'),
            detalle: 'salen de la máquina o no dejan salida',
            peso: porModo('omitida').length,
          },
        ],
        llega: reporte.llegaAlArbolFinal,
      }
    : { hay: false, grupos: [] };

  const fase = cab.fase ?? delCatalogo?.fase ?? null;
  const fases = (proceso.fases || []).map((f) => ({ ...f, actual: f.numero === fase }));

  const conformidadVista =
    actividad && actividad.prescritos
      ? {
          pasos: actividad.prescritos.map((p) => {
            const veces = actividad.ejecutados.filter((l) => l === p.letra).length;
            return {
              literal: p.titulo,
              traduccion: traducciones[p.letra] || '',
              estado: veces === 0 ? 'no se hizo' : veces === 1 ? 'hecho' : `hecho, ${veces} veces`,
            };
          }),
          notas: [
            ...(actividad.repetidos.length
              ? [
                  `Se volvió a ${plural(actividad.repetidos.length, 'paso', 'pasos')} ` +
                    'después de pasar por otro.',
                ]
              : []),
            ...(actividad.fueraDeOrden.length
              ? [
                  `${plural(actividad.fueraDeOrden.length, 'paso llegó', 'pasos llegaron')} ` +
                    'fuera de orden.',
                ]
              : []),
            ...(actividad.noPrescritos.length
              ? ['La skill marcó pasos que el instructivo no tiene.']
              : []),
            ...((conformidad.accionesSinPaso || []).length
              ? [
                  `${plural(conformidad.accionesSinPaso.length, 'acción quedó',
                    'acciones quedaron')} fuera de todo paso.`,
                ]
              : []),
          ],
        }
      : {
          pasos: [],
          notas: [
            conformidad?.estado === 'sin datos'
              ? 'Sin datos: la skill no marcó sus pasos, y el registro no los adivina.'
              : 'El registro no trae el instructivo con el que comparar.',
          ],
        };

  // Los documentos de la huella que hoy tienen otra version. El procedimiento solo figura como
  // cambiado si es el de la actividad; un SKILL.md que la sesion apenas leyo se nombra por su
  // ruta, sin llamarlo «el procedimiento».
  const cambiados = (deriva?.documentos || []).filter((d) => d.cambio === true);
  const rutaProcedimiento = docProcedimiento?.ruta;
  const procedimientoCambio = cambiados.some((d) => d.ruta === rutaProcedimiento);
  const nombreDoc = (r) => ruta(r).split('/').slice(-2).join('/');
  const momentos = [cab.inicio, cab.fin].filter(Boolean);
  const herramienta = esPlan
    ? 'sin skill: el agente siguió el plan del issue'
    : clave
      ? `ejecutada con la skill ${clave}` +
        (cab.huella?.plugin ? ` del plugin sdlc-ia ${cab.huella.plugin}` : '')
      : '';
  const vista = {
    titulo: tarea.titulo
      ? `${tarea.titulo}`
      : cab.tarea === 'sin-tarea'
        ? 'Sesión sin tarea'
        : `Issue #${cab.tarea}`,
    trabajo: {
      proceso: {
        nombre: proceso.proceso || '[proceso sin configurar]',
        dueno: proceso.dueno
          ? `dueño: ${proceso.dueno}`
          : 'dueño: [sin configurar en proceso.json]',
      },
      actividad: {
        nombre: delCatalogo?.nombre || (clave ? `Skill ${clave}` : '[sin actividad declarada]'),
        rol:
          `rol: ${delCatalogo?.rol || 'orquestador IA'}` + (herramienta ? ` · ${herramienta}` : ''),
        herramienta,
      },
      tarea: {
        nombre: cab.tarea === 'sin-tarea' ? 'Sin tarea' : `Issue #${cab.tarea}`,
        asignado: tarea.asignado
          ? `asignada a ${tarea.asignado}`
          : 'asignada a: [sin asignar en GitHub]',
        cierre: 'termina con el merge',
      },
    },
    documentacion: {
      manual: proceso.manual || '[manual sin configurar]',
      procedimiento: docProcedimiento
        ? {
            nombre: nombreProcedimiento,
            version: procedimiento.fecha
              ? `versión del ${dia(procedimiento.fecha)}`
              : 'versión del HEAD base',
            cambio: procedimientoCambio,
          }
        : { nombre: '[el registro no trae el procedimiento]', version: '', cambio: false },
      instructivo: !actividad?.prescritos
        ? '[sin instructivo]'
        : esPlan
          ? `Plan del ${actividad.instructivo.ruta} · sus ${actividad.prescritos.length} pasos` +
            (actividad.instructivo.actualizado
              ? ` · versión del ${dia(actividad.instructivo.actualizado)}`
              : '')
          : `${actividad.skill} · sus ${actividad.prescritos.length} pasos`,
      instructivoEstado: actividad?.prescritos
        ? `se siguieron ${new Set(actividad.ejecutados).size} de ${actividad.prescritos.length}`
        : '',
      registro: nombreActa,
      commits: commits.length
        ? `la cita el commit ${commits.join(', ')}`
        : 'ningún commit la cita todavía',
    },
    fases,
    faseActual: fase,
    sesion: {
      etiqueta: momentos.length
        ? `Sesión del ${dia(cab.inicio)}, ${hora(cab.inicio)} a ${hora(cab.fin)} (UTC)`
        : 'Sesión',
      turnos: turnos.length,
    },
    verificacion,
    turnos,
    acciones,
    conformidad: conformidadVista,
    versiones: {
      vigentes: [
        nombreProcedimiento
          ? `procedimiento ${nombreProcedimiento}` +
            (procedimiento.fecha ? ` del ${dia(procedimiento.fecha)}` : '')
          : null,
        cab.huella?.plugin ? `plugin ${cab.huella.plugin}` : null,
        cab.huella?.claudeCode?.length ? `Claude Code ${cab.huella.claudeCode.join(', ')}` : null,
        cab.huella?.modelos?.length ? `modelo ${cab.huella.modelos.join(', ')}` : null,
      ]
        .filter(Boolean)
        .join(' · '),
      cambio: !cambiados.length
        ? ''
        : procedimientoCambio
          ? 'Hoy hay una versión más nueva del procedimiento. Una diferencia al verificar ' +
            'puede venir de ahí y no de un error.'
          : 'Hoy hay una versión más nueva de ' +
            `${cambiados.map((d) => nombreDoc(d.ruta)).join(', ')}, que la sesión consultó.`,
    },
    pestanas: construirPestanas({ curada, indice, cab, agentes }),
  };
  return vista;
}

function construirPestanas({ curada, indice, cab, agentes }) {
  const acciones = curada.filter((r) => r.elemento === 'accion');
  const sesiones = (
    indice?.actas || [
      { sesion: cab.sesion, inicio: cab.inicio, fin: cab.fin, ramas: cab.ramas, curada: true },
    ]
  ).map((a) => ({
    etiqueta: `Sesión del ${dia(a.inicio)}, ${hora(a.inicio)} a ${hora(a.fin)} (UTC)`,
    detalle: `${(a.ramas || []).join(', ')}${a.curada ? '' : ' · sin curada'}`,
    esta: a.sesion === cab.sesion,
  }));
  const archivos = indice?.archivos
    ? Object.entries(indice.archivos).map(([r, lista]) => ({
        ruta: r,
        veces: lista.length,
        quienes: [...new Set(lista.map((x) => etiquetaDeAgente(x.agente, agentes)))].join(', '),
      }))
    : [...new Set(acciones.flatMap((a) => (a.cambios || []).map((c) => c.archivo)))]
        .sort()
        .map((r) => {
          const las = acciones.filter((a) => (a.cambios || []).some((c) => c.archivo === r));
          return {
            ruta: r,
            veces: las.length,
            quienes: [...new Set(las.map((a) => etiquetaDeAgente(a.agente, agentes)))].join(', '),
          };
        });
  const quien = agentes.map((g) => ({
    nombre: etiquetaDeAgente(g.id, agentes),
    tipo:
      g.tipo === 'persona'
        ? 'persona'
        : g.tipo === 'ia'
          ? 'inteligencia artificial'
          : 'automatismo',
    enNombreDe: g.actuoEnNombreDe
      ? `en nombre de ${etiquetaDeAgente(g.actuoEnNombreDe, agentes)}`
      : '',
    integracion:
      g.integracion === 'integrado'
        ? 'su trabajo llegó al resultado'
        : g.integracion === 'descartado'
          ? 'su trabajo se descartó'
          : '',
    acciones: acciones.filter((a) => a.agente === g.id).length,
  }));
  const controles = curada
    .filter((r) => r.elemento === 'verificacion_negativa')
    .map((v) => ({
      comando: corto(String(v.comando || '').replace(/#\s*rojo-esperado:.*$/m, ''), 110),
      demuestra: v.rojoEsperado,
      resultado:
        v.enRojo === true
          ? 'falló, como se esperaba'
          : v.enRojo === false
            ? 'no falló: el control no demostró nada'
            : 'sin resultado',
      ok: v.enRojo === true,
    }));
  return { sesiones, archivos, quien, controles };
}

// Todos los textos visibles de una vista, sin el bloque del registro: lo que la prueba de
// lenguaje comun revisa.
export function textosVisibles(v) {
  const salida = [];
  const recorrer = (x, clave) => {
    if (clave === 'registro' || clave === 'registroLinea') return;
    if (typeof x === 'string') salida.push(x);
    else if (Array.isArray(x)) x.forEach((y) => recorrer(y, null));
    else if (x && typeof x === 'object') for (const [k, y] of Object.entries(x)) recorrer(y, k);
  };
  recorrer(v, null);
  return salida;
}
