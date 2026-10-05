(function () {
  var V = JSON.parse(document.getElementById('datos').textContent);
  var sel = Math.max(
    0,
    V.acciones.findIndex(function (a) {
      return a.estado.clase === 'no';
    }),
  );
  var pestana = 'sesion';
  var temporizador = null;
  function el(tag, attrs, hijos) {
    var n = document.createElement(tag);
    if (attrs)
      Object.keys(attrs).forEach(function (k) {
        if (k === 'class') n.className = attrs[k];
        else if (k === 'text') n.textContent = attrs[k];
        else if (k.slice(0, 2) === 'on') n.addEventListener(k.slice(2), attrs[k]);
        else n.setAttribute(k, attrs[k]);
      });
    (hijos || []).forEach(function (h) {
      if (h) n.appendChild(typeof h === 'string' ? document.createTextNode(h) : h);
    });
    return n;
  }
  function nivel(clase, pequeno, grande, debajo, mono) {
    return el('div', { class: 'nivel ' + clase }, [
      el('small', { text: pequeno }),
      el('b', { text: grande, class: mono ? 'mono' : '' }),
      el('span', { text: debajo }),
    ]);
  }
  function pasoTexto(p) {
    return p.marcado ? p.etiqueta + ' · ' + p.literal : p.etiqueta;
  }
  function ficha() {
    var f = document.getElementById('ficha');
    f.textContent = '';
    var a = V.acciones[sel];
    if (!a) {
      f.appendChild(el('p', { class: 'vacio', text: 'La sesión no tiene acciones.' }));
      return;
    }
    var t = V.trabajo,
      d = V.documentacion;
    f.appendChild(
      el('span', { class: 'eje', style: 'color:var(--azul2)', text: 'Eje del trabajo' }),
    );
    f.appendChild(nivel('t', 'Proceso · qué transformamos', t.proceso.nombre, t.proceso.dueno));
    f.appendChild(nivel('t', 'Actividad · bloque de trabajo', t.actividad.nombre, t.actividad.rol));
    f.appendChild(
      nivel(
        't',
        'Tarea · alguien dice «terminé»',
        t.tarea.nombre,
        t.tarea.asignado + ' · ' + t.tarea.cierre,
      ),
    );
    f.appendChild(
      nivel(
        't sel',
        'Paso · en qué orden',
        pasoTexto(a.paso),
        a.paso.traduccion ? '«' + a.paso.traduccion + '» · turno ' + a.turno : 'turno ' + a.turno,
      ),
    );
    var acc = nivel(
      't acc' + (a.estado.clase === 'ok' ? ' ok' : ''),
      'Acción ' + a.orden + ' · qué se hizo',
      a.texto,
      'la ejecutó: ' + a.agente,
    );
    if (a.codigo) acc.insertBefore(el('span', { class: 'codigo', text: a.codigo }), acc.lastChild);
    f.appendChild(acc);
    f.appendChild(
      el('span', { class: 'eje', style: 'color:var(--teal)', text: 'Eje de la documentación' }),
    );
    f.appendChild(nivel('d', 'Manual · el porqué del sistema', d.manual, ''));
    f.appendChild(
      nivel(
        'd',
        'Procedimiento · qué y quién',
        d.procedimiento.nombre,
        d.procedimiento.version + (d.procedimiento.cambio ? ' · hoy hay una más nueva' : ''),
      ),
    );
    f.appendChild(
      nivel(
        'd span2',
        'Instructivo de trabajo · cómo, paso a paso',
        d.instructivo,
        d.instructivoEstado,
      ),
    );
    f.appendChild(
      nivel(
        'd',
        'Registro · la evidencia',
        'Acta de la sesión' + (a.registroLinea ? ' · línea ' + a.registroLinea : ''),
        d.commits,
      ),
    );
  }
  function pestanas() {
    var n = document.getElementById('pestanas');
    n.textContent = '';
    var P = V.pestanas;
    [
      ['sesion', 'Esta sesión, paso a paso'],
      ['sesiones', 'Sesiones de la tarea · ' + P.sesiones.length],
      ['archivos', 'Archivos que cambiaron y quién · ' + P.archivos.length],
      ['quien', 'Quién hizo cada cosa'],
      ['controles', 'Pruebas que debían fallar · ' + P.controles.length],
    ].forEach(function (p) {
      n.appendChild(
        el('button', {
          type: 'button',
          role: 'tab',
          'aria-selected': String(pestana === p[0]),
          text: p[1],
          onclick: function () {
            pestana = p[0];
            pestanas();
            contenido();
          },
        }),
      );
    });
  }
  function tiempo() {
    var s = el('section', { class: 'tiempo', 'aria-label': 'Eje del tiempo' });
    s.appendChild(
      el('div', { class: 'rotulo' }, [
        el('b', { text: 'Eje del tiempo · fases del ciclo de vida' }),
        el('span', {
          text: 'Las fases cortan el tiempo y cierran con un gate; adentro, la sesión en el orden en que ocurrió',
        }),
      ]),
    );
    var g = el('div', { class: 'fases' });
    g.style.gridTemplateColumns = V.fases
      .map(function (f) {
        return f.actual ? '3fr' : '1fr';
      })
      .join(' ');
    V.fases.forEach(function (f) {
      g.appendChild(
        el('div', {
          class: 'fase' + (f.actual ? ' actual' : ''),
          text:
            f.numero +
            ' · ' +
            f.nombre +
            (f.actual
              ? ' · gate: ' + (f.gate || 'sin definir en la configuración del proceso')
              : ''),
        }),
      );
    });
    s.appendChild(g);
    if (V.faseActual === null || V.faseActual === undefined)
      s.appendChild(
        el('p', {
          class: 'nota',
          text: 'La skill no declaró una fase: la sesión no se ubica en el ciclo de vida.',
        }),
      );
    var c = el('div', { class: 'tira-c' });
    c.appendChild(
      el('div', {
        class: 'nota',
        text:
          V.sesion.etiqueta +
          ' · ' +
          V.acciones.length +
          ' acciones · el ancho es la cantidad, no el tiempo',
      }),
    );
    var tira = el('div', { class: 'tira' }),
      grupos = [];
    V.acciones.forEach(function (a, i) {
      var k = a.turno + '|' + a.paso.corto,
        u = grupos[grupos.length - 1];
      if (u && u.k === k) {
        u.n++;
        u.fin = i;
      } else grupos.push({ k: k, n: 1, ini: i, fin: i, a: a });
    });
    grupos.forEach(function (g) {
      var dentro = sel >= g.ini && sel <= g.fin;
      var seg = el('button', {
        type: 'button',
        class: 'seg' + (g.a.paso.marcado ? '' : ' sinpaso') + (dentro ? ' sel' : ''),
        title: 'Turno ' + g.a.turno + ' · ' + pasoTexto(g.a.paso),
        text: g.a.paso.corto + (dentro ? ' · ' + g.n + (g.n === 1 ? ' acción' : ' acciones') : ''),
        onclick: function () {
          elegir(g.ini);
        },
      });
      seg.style.flex = String(g.n);
      tira.appendChild(seg);
    });
    var cab = el('div', { class: 'cabezal' });
    cab.style.left = ((sel + 0.5) / Math.max(1, V.acciones.length)) * 100 + '%';
    tira.appendChild(cab);
    c.appendChild(tira);
    s.appendChild(c);
    return s;
  }
  function verificacion() {
    var c = el('section', { class: 'caja', 'aria-label': 'Alcance de la verificación' });
    c.appendChild(
      el('div', { class: 'caja-t' }, [
        el('b', { text: 'Alcance de la verificación' }),
        el('span', { class: 'fuente f-verif', text: 'registro de verificación' }),
      ]),
    );
    if (!V.verificacion.hay) {
      c.appendChild(
        el('p', {
          class: 'vacio',
          text: 'No hay registro de verificación: ninguna acción se volvió a ejecutar. Cada una dice «registrada, sin verificar».',
        }),
      );
      return c;
    }
    var colores = ['#5b7be0', '#3e9c74', '#2f8a82', '#c9cdd4', '#e4e6ea'],
      barra = el('div', { class: 'barra' }),
      gr = el('div', { class: 'grupos' });
    V.verificacion.grupos.forEach(function (g, i) {
      var s = el('div');
      s.style.flex = String(g.peso);
      s.style.background = colores[i];
      barra.appendChild(s);
      gr.appendChild(el('span', null, [el('b', { text: g.titulo }), el('br'), g.detalle]));
    });
    c.appendChild(barra);
    c.appendChild(gr);
    c.appendChild(
      el('p', {
        class: 'nota',
        text: 'Sin registro de verificación, cada acción diría «registrada, sin verificar»: el visor nunca afirma lo que nadie comprobó.',
      }),
    );
    return c;
  }
  function filaAccion(i) {
    var a = V.acciones[i];
    return el(
      'button',
      {
        type: 'button',
        class: 'fila' + (i === sel ? ' sel' : '') + (a.estado.clase === 'fuera' ? ' fuera' : ''),
        'data-orden': String(a.orden),
        onclick: function () {
          elegir(i);
        },
      },
      [
        el('span', { text: String(a.orden) }),
        el('span', null, [
          a.texto,
          a.codigo ? el('span', { class: 'codigo', text: ' · ' + a.codigo }) : null,
          a.nota ? el('span', { class: 'codigo', text: ' — ' + a.nota }) : null,
        ]),
        el('span', { text: a.agente }),
        el('span', { class: 'e-' + a.estado.clase, text: a.estado.texto }),
      ],
    );
  }
  function sesion() {
    var izq = el('div', { class: 'izq' });
    izq.appendChild(verificacion());
    izq.appendChild(
      el('div', { class: 'caja-t' }, [
        el('b', { text: 'La tarea paso a paso' }),
        el('span', { class: 'fuente f-ejec', text: 'registro de ejecución' }),
      ]),
    );
    var a = V.acciones[sel];
    V.turnos.forEach(function (t) {
      izq.appendChild(el('div', { class: 'turno-t', text: 'Turno ' + t.numero + ' · ' + t.quien }));
      t.pasos.forEach(function (p) {
        var tieneSel = p.filas.some(function (f) {
          return f.tipo === 'accion' && f.indice === sel;
        });
        var d = el('details', { class: 'paso' + (tieneSel ? ' sel' : '') });
        if (tieneSel) d.open = true;
        d.appendChild(
          el('summary', null, [
            el('b', { text: pasoTexto(p) }),
            p.traduccion ? el('span', { class: 'codigo', text: '«' + p.traduccion + '»' }) : null,
            p.marcado ? el('span', { class: 'chip', text: 'lo marcó la skill' }) : null,
            el('span', { class: 'nota', text: p.n + (p.n === 1 ? ' acción' : ' acciones') }),
          ]),
        );
        if (p.decision)
          d.appendChild(
            el('div', { class: 'porque' }, [
              el('small', { text: 'Por qué' }),
              el('span', { text: '«' + p.decision + '»' }),
            ]),
          );
        var filas = el('div', { class: 'filas', role: 'list' });
        p.filas.forEach(function (f) {
          if (f.tipo === 'accion') filas.appendChild(filaAccion(f.indice));
          else if (f.tipo === 'intento')
            filas.appendChild(
              el('div', { class: 'fila intento' }, [
                el('span', { text: '—' }),
                el('span', null, [
                  f.texto,
                  f.codigo ? el('span', { class: 'codigo', text: ' · ' + f.codigo }) : null,
                ]),
                el('span', { text: f.agente }),
                el('span', { text: f.nota }),
              ]),
            );
          else
            filas.appendChild(
              el('div', { class: 'fila interv' }, [
                el('span', { text: '—' }),
                el('span', null, [el('b', { text: f.texto }), f.detalle ? ' ' + f.detalle : '']),
                el('span', { text: f.agente }),
                el('span', { text: 'intervención humana' }),
              ]),
            );
        });
        d.appendChild(filas);
        izq.appendChild(d);
      });
    });
    var ev = el('aside', { 'aria-label': 'Evidencia de la acción seleccionada' });
    if (a) {
      ev.appendChild(el('b', { text: 'Evidencia · acción ' + a.orden }));
      var v = el('div', { class: 'ev verif' + (a.estado.clase === 'ok' ? ' ok' : '') }, [
        el('div', { class: 'ev-t' }, [
          el('b', { text: 'Verificación independiente' }),
          el('span', { class: 'fuente f-verif', text: 'registro de verificación' }),
        ]),
        el('span', { text: a.estado.detalle }),
      ]);
      if (a.salidaMotor) v.appendChild(el('pre', { text: a.salidaMotor }));
      ev.appendChild(v);
      var c = el('div', { class: 'ev conf' }, [
        el('div', { class: 'ev-t' }, [
          el('b', { text: 'Conformidad con el instructivo' }),
          el('span', { class: 'fuente f-doc', text: 'instructivo vigente' }),
        ]),
      ]);
      if (V.conformidad.pasos.length) {
        var ol = el('ol');
        V.conformidad.pasos.forEach(function (p) {
          ol.appendChild(
            el('li', {
              text:
                p.literal + (p.traduccion ? ' («' + p.traduccion + '»)' : '') + ' · ' + p.estado,
            }),
          );
        });
        c.appendChild(ol);
      }
      V.conformidad.notas.forEach(function (n) {
        c.appendChild(el('span', { text: n }));
      });
      ev.appendChild(c);
      var ve = el('div', { class: 'ev vers' }, [
        el('b', {
          style: 'font-size:11px;letter-spacing:.08em;text-transform:uppercase;color:var(--ambar)',
          text: 'Control de la información documentada',
        }),
        el('span', {
          text: 'Vigentes al trabajar: ' + (V.versiones.vigentes || 'sin datos') + '.',
        }),
      ]);
      if (V.versiones.cambio) ve.appendChild(el('span', { text: V.versiones.cambio }));
      ve.appendChild(
        el('small', { style: 'color:var(--suave)', text: 'ISO 9001, cláusula 7.5.3' }),
      );
      ev.appendChild(ve);
      ev.appendChild(
        el('div', { class: 'ev' }, [
          el('div', { class: 'ev-t' }, [
            el('b', { text: 'Así está en el archivo del acta' }),
            el('span', { class: 'fuente f-ejec', text: 'registro de ejecución' }),
          ]),
          el('pre', { id: 'registro', text: a.registro }),
        ]),
      );
    }
    ev.appendChild(
      el('div', { class: 'ev' }, [
        el('b', {
          style: 'font-size:11px;letter-spacing:.08em;text-transform:uppercase',
          text: 'Vocabulario según la norma',
        }),
        tabla([
          ['ISO 12207 / 29110', 'proceso → actividad → tarea'],
          ['SPEM / RUP', 'fase → actividad → tarea → paso'],
          ['ISO 9001', 'procedimiento → instructivo → registro'],
        ]),
      ]),
    );
    var cuerpo = el('div', { class: 'cuerpo' });
    cuerpo.appendChild(izq);
    cuerpo.appendChild(ev);
    return cuerpo;
  }
  function tabla(filas, cab) {
    var t = el('table');
    if (cab)
      t.appendChild(
        el(
          'tr',
          null,
          cab.map(function (h) {
            return el('th', { text: h });
          }),
        ),
      );
    filas.forEach(function (f) {
      t.appendChild(
        el(
          'tr',
          null,
          f.map(function (x) {
            return el('td', { text: String(x) });
          }),
        ),
      );
    });
    return t;
  }
  function vistaTarea() {
    var P = V.pestanas,
      c = el('div', { class: 'izq' }),
      caja = el('div', { class: 'caja' });
    if (pestana === 'sesiones') {
      caja.appendChild(
        el('div', { class: 'caja-t' }, [
          el('b', { text: 'Sesiones de la tarea' }),
          el('span', { class: 'fuente f-ejec', text: 'índice de la tarea' }),
        ]),
      );
      caja.appendChild(
        tabla(
          P.sesiones.map(function (s) {
            return [s.etiqueta + (s.esta ? ' · esta' : ''), s.detalle];
          }),
          ['Sesión', 'Rama'],
        ),
      );
    }
    if (pestana === 'archivos') {
      caja.appendChild(
        el('div', { class: 'caja-t' }, [
          el('b', { text: 'Archivos que cambiaron y quién' }),
          el('span', { class: 'fuente f-comp', text: 'registro completo' }),
        ]),
      );
      caja.appendChild(
        P.archivos.length
          ? tabla(
              P.archivos.map(function (a) {
                return [a.ruta, a.veces, a.quienes];
              }),
              ['Archivo', 'Acciones que lo cambiaron', 'Quién'],
            )
          : el('p', { class: 'vacio', text: 'La sesión no cambió archivos.' }),
      );
    }
    if (pestana === 'quien') {
      caja.appendChild(
        el('div', { class: 'caja-t' }, [
          el('b', { text: 'Quién hizo cada cosa' }),
          el('span', { class: 'fuente f-ejec', text: 'registro de ejecución' }),
        ]),
      );
      caja.appendChild(
        tabla(
          P.quien.map(function (q) {
            return [q.nombre, q.tipo, q.enNombreDe, q.integracion, q.acciones];
          }),
          ['Quién', 'Tipo', 'En nombre de', 'Resultado', 'Acciones'],
        ),
      );
    }
    if (pestana === 'controles') {
      caja.appendChild(
        el('div', { class: 'caja-t' }, [
          el('b', { text: 'Pruebas que debían fallar' }),
          el('span', { class: 'fuente f-ejec', text: 'registro de ejecución' }),
        ]),
      );
      caja.appendChild(
        P.controles.length
          ? tabla(
              P.controles.map(function (x) {
                return [x.comando, x.demuestra, x.resultado];
              }),
              ['Prueba', 'Qué demuestra', 'Resultado'],
            )
          : el('p', { class: 'vacio', text: 'La sesión no declaró pruebas que debían fallar.' }),
      );
    }
    c.appendChild(caja);
    return c;
  }
  function contenido() {
    var m = document.getElementById('contenido');
    m.textContent = '';
    m.appendChild(tiempo());
    m.appendChild(pestana === 'sesion' ? sesion() : vistaTarea());
  }
  function elegir(i) {
    sel = i;
    ficha();
    contenido();
  }
  document.getElementById('titulo').textContent = V.titulo;
  document.getElementById('btn-intentos').addEventListener('click', function () {
    var b = this,
      ver = b.getAttribute('aria-pressed') !== 'true';
    b.setAttribute('aria-pressed', String(ver));
    b.textContent = ver ? 'Intentos fallidos: visibles' : 'Intentos fallidos: ocultos';
    document.body.classList.toggle('sin-intentos', !ver);
  });
  document.getElementById('btn-reproducir').addEventListener('click', function () {
    var b = this;
    if (temporizador) {
      clearInterval(temporizador);
      temporizador = null;
      b.textContent = 'Reproducir paso a paso';
      return;
    }
    b.textContent = 'Detener';
    pestana = 'sesion';
    pestanas();
    elegir(0);
    temporizador = setInterval(function () {
      if (sel >= V.acciones.length - 1) {
        clearInterval(temporizador);
        temporizador = null;
        b.textContent = 'Reproducir paso a paso';
        return;
      }
      elegir(sel + 1);
    }, 1200);
  });
  document.getElementById('btn-exportar').addEventListener('click', function () {
    var a = V.acciones[sel];
    if (!a) return;
    var t = V.trabajo,
      d = V.documentacion;
    var texto = [
      'Ficha de trazabilidad · ' + V.titulo,
      '',
      'Proceso: ' + t.proceso.nombre + ' (' + t.proceso.dueno + ')',
      'Fase: ' + (V.faseActual === null ? 'sin declarar' : V.faseActual),
      'Actividad: ' + t.actividad.nombre + ' (' + t.actividad.rol + ')',
      'Tarea: ' + t.tarea.nombre + ' (' + t.tarea.asignado + ')',
      'Paso: ' + pasoTexto(a.paso),
      'Acción ' +
        a.orden +
        ': ' +
        a.texto +
        (a.codigo ? ' · ' + a.codigo : '') +
        ' (' +
        a.agente +
        ')',
      'Verificación: ' + a.estado.detalle,
      '',
      'Manual: ' + d.manual,
      'Procedimiento: ' + d.procedimiento.nombre + ' · ' + d.procedimiento.version,
      'Instructivo: ' + d.instructivo + ' · ' + d.instructivoEstado,
      'Registro: ' +
        d.registro +
        (a.registroLinea ? ', línea ' + a.registroLinea : '') +
        ' · ' +
        d.commits,
    ].join('\n');
    var url = URL.createObjectURL(new Blob([texto], { type: 'text/plain;charset=utf-8' }));
    var link = el('a', { href: url, download: 'ficha-accion-' + a.orden + '.txt' });
    document.body.appendChild(link);
    link.click();
    link.remove();
  });
  ficha();
  pestanas();
  contenido();
})();
