// Pruebas del visor del acta (#222, fase 6; diseno E5):
//   node --test scripts/acta/pruebas/visor-acta.test.mjs
//
// Dos partes. El modelo de vista sobre una sesion sintetica que pasa por el compilador y la
// curacion de verdad, con el SKILL.md real de debt-triage: lenguaje comun, los tres ejes y que
// nunca afirma lo que nadie comprobo. Y el HTML en Chromium con Playwright, si esta instalado
// (en esta maquina vive en base-conocimiento/eval-100-preguntas); sin el, esa parte se salta.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { generarHtml, recolectar } from '../visor-acta.mjs';
import { construirVista, textosVisibles } from '../vista-acta.mjs';
import { carpetaTemporal, sesionParaElVisor } from './fabrica.mjs';

const RAIZ = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..');

// Un reporte del motor sintetico con un modo por accion, como lo escribe reejecutar-acta.mjs.
function reporteDe(curada) {
  const acciones = fs
    .readFileSync(curada, 'utf8')
    .trim()
    .split('\n')
    .map(JSON.parse)
    .filter((r) => r.elemento === 'accion');
  const modo = { Bash: 'ejecutada', Read: 'comparada', Edit: 'aplicada', hook: 'aplicada' };
  return {
    llegaAlArbolFinal: false,
    veredictos: acciones.map((a) => {
      if (a.claseDeterminismo === 'externa_lectura')
        return { accion: a.id, modo: 'fixture', veredicto: 'no_verificable' };
      if (a.claseDeterminismo === 'efecto_externo')
        return { accion: a.id, modo: 'omitida', veredicto: 'no_verificable' };
      const m = modo[a.herramienta];
      return m === 'ejecutada'
        ? {
            accion: a.id,
            modo: m,
            veredicto: 'diverge',
            motivo: 'sale con codigo 1, y en la sesion salio bien',
            salida: 'network is unreachable',
          }
        : { accion: a.id, modo: m, veredicto: 'igual' };
    }),
  };
}

function vistaDeLaSesion({ conReporte = true } = {}) {
  const s = sesionParaElVisor();
  if (conReporte) {
    fs.writeFileSync(
      s.curada.replace('.curada.jsonl', '.reporte.json'),
      JSON.stringify(reporteDe(s.curada)),
    );
  }
  const entradas = recolectar({
    curada: s.curada,
    raizRepo: s.raiz,
    sinRed: true,
    asignado: 'una-persona',
  });
  return { ...s, entradas, vista: construirVista(entradas) };
}

const IDS = /\b(t\d+(\.p\d+)?|a\d+(\.[rh])?|i\d+|d\d+|PC-\d+)\b/;
const ENUMS = /hook:sin-identificar|permiso_aprobado|externa_lectura|efecto_externo|procedencia/;

test('lenguaje comun: ningun texto visible lleva un id del acta ni un valor de enumeracion', () => {
  const { vista } = vistaDeLaSesion();
  for (const t of textosVisibles(vista)) {
    assert.doesNotMatch(t, IDS, `id en el texto visible: «${t}»`);
    assert.doesNotMatch(t, ENUMS, `enumeracion en el texto visible: «${t}»`);
  }
  assert.match(vista.acciones[0].registro, /"id": "a1"/, 'los ids viven en el registro tal cual');
});

test('tres ejes: trabajo con responsables, documentacion emparejada y fase en el tiempo', () => {
  const { vista } = vistaDeLaSesion();
  assert.equal(vista.trabajo.proceso.nombre, 'Desarrollo de software asistido por IA');
  assert.match(vista.trabajo.proceso.dueno, /sin configurar en proceso\.json/);
  assert.equal(vista.trabajo.tarea.nombre, 'Issue #10');
  assert.equal(vista.trabajo.tarea.asignado, 'asignada a una-persona');
  assert.equal(vista.trabajo.actividad.nombre, 'Skill debt-triage');
  assert.equal(vista.documentacion.procedimiento.nombre, 'debt-triage · SKILL.md');
  assert.equal(vista.documentacion.instructivo, 'debt-triage · sus 5 pasos');
  assert.equal(vista.documentacion.instructivoEstado, 'se siguieron 3 de 5');
  assert.equal(vista.faseActual, 2);
  assert.equal(vista.fases.find((f) => f.actual).nombre, 'Preparación del terreno');
  assert.match(vista.sesion.etiqueta, /\(UTC\)$/);
});

test('los pasos llevan el texto literal del instructivo y la traduccion de proceso.json', () => {
  const { vista } = vistaDeLaSesion();
  const pasos = vista.turnos.flatMap((t) => t.pasos);
  assert.deepEqual(
    pasos.map((p) => p.etiqueta),
    ['Sin paso marcado', 'Paso 1 de 5', 'Paso 2 de 5', 'Paso 3 de 5'],
  );
  const tercero = pasos[3];
  assert.equal(tercero.literal, 'Triage each group');
  assert.equal(tercero.traduccion, 'Triar cada grupo');
  assert.equal(tercero.decision, 'Leo cada llamada antes de decidir.');
  assert.deepEqual(
    vista.conformidad.pasos.map((p) => p.estado),
    ['hecho', 'hecho', 'hecho', 'no se hizo', 'no se hizo'],
  );
});

test('nunca afirma lo que nadie comprobo: modos, fixture y efecto externo dichos como son', () => {
  const { vista } = vistaDeLaSesion();
  const por = (re) => vista.acciones.find((a) => re.test(a.codigo || a.texto));
  assert.equal(por(/gh issue list/).estado.texto, '○ tomada del registro · sin verificar');
  assert.equal(por(/git push/).estado.texto, '○ no reproducible: sale de la máquina');
  assert.equal(por(/git status/).estado.texto, '✗ re-ejecutada · no coincide');
  assert.match(por(/git status/).estado.detalle, /Terminó con error \(código 1\)/);
  assert.equal(por(/Lee Tarifa/).estado.texto, '✓ comparada · coincide');
  assert.equal(por(/«\* 1\.19»/).estado.texto, '✓ aplicada · coincide');
  for (const a of vista.acciones.filter(
    (x) => x.estado.clase === 'sin' || x.estado.clase === 'fuera',
  )) {
    assert.doesNotMatch(a.estado.texto, /re-ejecutada|ejecutó/);
  }
  assert.deepEqual(
    vista.verificacion.grupos.map((g) => g.titulo),
    ['1 re-ejecutada', '2 aplicadas', '1 comparada', '1 tomada del registro', '1 no reproducible'],
  );
});

test('sin reporte del motor, todo dice «registrada, sin verificar»', () => {
  const { vista } = vistaDeLaSesion({ conReporte: false });
  assert.equal(vista.verificacion.hay, false);
  assert.ok(vista.acciones.every((a) => a.estado.texto === 'registrada, sin verificar'));
});

test('intentos fallidos, intervenciones, hooks y pruebas que debian fallar, en palabras', () => {
  const { vista } = vistaDeLaSesion();
  const filas = vista.turnos.flatMap((t) => t.pasos).flatMap((p) => p.filas);
  const intento = filas.find((f) => f.tipo === 'intento');
  assert.equal(intento.texto, 'Intento fallido: Edita Tarifa.java');
  assert.equal(intento.nota, 'queda en el registro completo, fuera de la secuencia');
  assert.equal(filas.find((f) => f.tipo === 'intervencion').texto, 'La persona aprobó el permiso');
  const hook = vista.acciones.find((a) => a.agente === 'un hook');
  assert.equal(hook.nota, 'lo hizo un hook, no la IA');
  assert.deepEqual(
    vista.pestanas.controles.map((c) => [c.demuestra, c.resultado]),
    [['la prueba nueva falla antes del arreglo', 'falló, como se esperaba']],
  );
  assert.deepEqual(
    vista.pestanas.archivos.map((a) => a.ruta),
    ['Tarifa.java'],
  );
});

test('el HTML no deja que el contenido del acta se interprete como HTML', () => {
  const { entradas } = vistaDeLaSesion();
  const maliciosa = entradas.curada.map((r) =>
    r.elemento === 'accion' && r.herramienta === 'Bash' && r.claseDeterminismo === 'local'
      ? { ...r, entrada: { command: '</script><script>alert(1)</script>' } }
      : r,
  );
  const html = generarHtml(construirVista({ ...entradas, curada: maliciosa }));
  assert.ok(!html.includes('</script><script>alert(1)'), 'el cierre de script quedo crudo');
  const datos = /<script type="application\/json" id="datos">([\s\S]*?)<\/script>/.exec(html)[1];
  assert.match(JSON.parse(datos).acciones[0].codigo, /<\/script><script>alert\(1\)/);
});

// --- En Chromium -------------------------------------------------------------------------------

function cargarPlaywright() {
  for (const base of [RAIZ, path.join(RAIZ, 'base-conocimiento', 'eval-100-preguntas')]) {
    try {
      return createRequire(path.join(base, 'package.json'))('playwright');
    } catch {
      /* sigue con la proxima */
    }
  }
  return null;
}

test('en Chromium: ficha, seleccion, intentos, pestanas y ningun id a la vista', async (t) => {
  const playwright = cargarPlaywright();
  if (!playwright) {
    t.skip('Playwright no esta instalado');
    return;
  }
  const { vista } = vistaDeLaSesion();
  const archivo = path.join(carpetaTemporal('visor'), 'visor.html');
  fs.writeFileSync(archivo, generarHtml(vista));
  let navegador;
  try {
    navegador = await playwright.chromium.launch();
  } catch (e) {
    t.skip(`Chromium no arranca: ${e.message.split('\n')[0]}`);
    return;
  }
  try {
    const pagina = await navegador.newPage({ viewport: { width: 1440, height: 1000 } });
    const errores = [];
    pagina.on('pageerror', (e) => errores.push(e.message));
    await pagina.goto(pathToFileURL(archivo).href);
    await pagina.waitForSelector('#ficha .nivel');
    assert.equal(
      await pagina.locator('#ficha .nivel').count(),
      9,
      'cinco niveles del trabajo y cuatro de la documentación',
    );
    assert.match(
      await pagina.locator('#ficha').innerText(),
      /Proceso · qué transformamos[\s\S]*Registro · la evidencia/,
    );

    // La primera divergencia queda seleccionada; elegir otra accion cambia la ficha.
    assert.match(await pagina.locator('#ficha').innerText(), /Acción 1 · qué se hizo/);
    await pagina.locator('details:has(.fila[data-orden="4"]) summary').click();
    await pagina.locator('.fila[data-orden="4"]').click();
    assert.match(
      await pagina.locator('#ficha').innerText(),
      /Paso 3 de 5 · Triage each group[\s\S]*Acción 4 · qué se hizo[\s\S]*Edita Tarifa\.java/,
    );

    // Ningun id a la vista fuera del bloque del registro.
    const visible = await pagina.evaluate(() => {
      const r = document.getElementById('registro');
      if (r) r.remove();
      return document.body.innerText;
    });
    assert.doesNotMatch(visible, IDS);
    await pagina.reload();
    await pagina.waitForSelector('#ficha .nivel');

    // Los intentos fallidos se ocultan y se muestran.
    await pagina.locator('details:has(.fila.intento) summary').click();
    assert.equal(await pagina.locator('.fila.intento').isVisible(), true);
    await pagina.locator('#btn-intentos').click();
    assert.equal(await pagina.locator('.fila.intento').isVisible(), false);

    // La pestana de los controles.
    await pagina.getByRole('tab', { name: /Pruebas que debían fallar/ }).click();
    assert.match(await pagina.locator('main').innerText(), /falló, como se esperaba/);
    assert.deepEqual(errores, []);
  } finally {
    await navegador.close();
  }
});

test('control documental: nombra el documento que cambió; procedimiento es solo el suyo', () => {
  const { entradas } = vistaDeLaSesion();
  const otro = { ruta: 'x/skills/otra-skill/SKILL.md', cambio: true };
  const propio = { ruta: entradas.conformidad.actividades[0].instructivo.ruta, cambio: true };
  const consultado = construirVista({ ...entradas, deriva: { documentos: [otro] } });
  assert.equal(consultado.documentacion.procedimiento.cambio, false);
  assert.equal(consultado.versiones.cambio,
    'Hoy hay una versión más nueva de otra-skill/SKILL.md, que la sesión consultó.');
  const delProcedimiento = construirVista({ ...entradas, deriva: { documentos: [propio] } });
  assert.equal(delProcedimiento.documentacion.procedimiento.cambio, true);
  assert.match(delProcedimiento.versiones.cambio,
    /^Hoy hay una versión más nueva del procedimiento/);
});
