(function () {
  "use strict";

  const formulario = document.getElementById("formulario");
  const campoPregunta = document.getElementById("pregunta");
  const campoProyecto = document.getElementById("proyecto");
  const boton = document.getElementById("boton-preguntar");
  const historial = document.getElementById("historial");
  const bienvenida = document.getElementById("bienvenida");
  const botonNuevaConversacion = document.getElementById("boton-nueva-conversacion");
  const listaConversaciones = document.getElementById("lista-conversaciones");
  const listaDocumentos = document.getElementById("lista-documentos");
  const contadorDocumentos = document.getElementById("contador-documentos");
  const modalInfoPrevia = document.getElementById("modal-info-previa");
  const modalDocumento = document.getElementById("modal-documento");
  const modalDocumentoTitulo = document.getElementById("modal-documento-titulo");
  const modalDocumentoCuerpo = document.getElementById("modal-documento-cuerpo");
  const modalDocumentoDescarga = document.getElementById("modal-documento-descarga");
  // Acciones sobre los documentos tildados (issue #38): el control bajo la lista,
  // su menu de cinco acciones y el sub-panel de Traducir.
  const botonAcciones = document.getElementById("boton-acciones");
  const botonAccionesTexto = document.getElementById("boton-acciones-texto");
  const menuAcciones = document.getElementById("menu-acciones");
  const submenuTraducir = document.getElementById("submenu-traducir");
  const botonTraducirDocumentos = document.getElementById("boton-traducir-documentos");
  const contenedorIdiomaResultado = document.getElementById("selector-idioma-resultado");
  const contenedorIdiomasDocumentos = document.getElementById("selector-idiomas-documentos");

  // Esta vista previa (Señal 1: FTS) matchea por raíz de palabra, no por
  // significado: si la pregunta no comparte vocabulario con el documento
  // (p. ej. distinto idioma, o un verbo conjugado que stemea distinto), puede
  // no mostrar nada aunque el contenido exista. La respuesta completa de
  // abajo sí busca por significado (embeddings) y no tiene esta limitación.
  const TEXTO_INFO_PREVIA =
    "Esta vista previa busca por coincidencia de palabras, no por significado: " +
    "si tu pregunta no comparte vocabulario con el documento (por ejemplo, está " +
    "en otro idioma, o usa un verbo conjugado distinto), puede no mostrar nada " +
    "aunque el contenido exista. La respuesta completa de abajo sí busca por " +
    "significado y no tiene esta limitación.";

  // Cada conversacion puede tener a lo sumo un stream propio en curso, pero
  // varias conversaciones distintas SI pueden estar generando a la vez: cambiar
  // de conversacion no debe cortar la que quedo generando en otra. La llave es
  // el conversacionId; el valor guarda todo lo que hace falta para reengancharlo
  // a la vista si el usuario vuelve mientras sigue en curso.
  const streamsActivos = new Map();

  // La conversacion que se esta mostrando ahora mismo (ver historial-db.js).
  // null = todavia no se hizo ninguna pregunta desde el ultimo "Nueva
  // conversación": la primera pregunta la crea.
  let conversacionActualId = null;

  // F11: documentos locales que se pueden activar/desactivar por conversacion,
  // para acotar la busqueda. documentosDisponibles viene de /api/admin/documentos;
  // documentosActivosActuales es SIEMPRE la lista literal de lo que esta tildado
  // ahora mismo (nunca "vacio = todos" a este nivel, para no confundir "todavia
  // no se toco nada" con "el usuario destildo todo a proposito") -- la
  // conversion a "vacio = sin restriccion" para guardar/mandar al backend pasa
  // por documentosActivosNormalizados().
  let documentosDisponibles = [];
  let documentosActivosActuales = [];

  // Topes del modulo de acciones (GET /api/acciones/limites): el control se
  // deshabilita antes de que un 400 llegue como un corte de conexion, porque
  // EventSource no puede leer el cuerpo de un 400. Defaults = los del servidor.
  let limitesAcciones = { maxDocumentos: 10, maxCaracteresTexto: 8000 };

  // Un selector para el idioma del resultado (resumir, sintetizar, preguntas,
  // ideas) y otro, con origen, para Traducir documentos. Sin idiomas.js (no
  // deberia pasar: va antes que este archivo) el menu sigue sin ellos.
  const selectorIdiomaResultado =
    window.kbIdiomas && contenedorIdiomaResultado
      ? window.kbIdiomas.crearSelector({ soloDestino: true, destino: leerPreferencia("kb.acciones.idioma", "es") })
      : null;
  const selectorIdiomasDocumentos =
    window.kbIdiomas && contenedorIdiomasDocumentos
      ? window.kbIdiomas.crearSelector({ destino: leerPreferencia("kb.acciones.destino", "en") })
      : null;
  if (selectorIdiomaResultado) {
    contenedorIdiomaResultado.appendChild(selectorIdiomaResultado.elemento);
  }
  if (selectorIdiomasDocumentos) {
    contenedorIdiomasDocumentos.appendChild(selectorIdiomasDocumentos.elemento);
  }

  // Las acciones del menu se registran por nombre desde el codigo que las
  // implementa (resumir/sintetizar/preguntas/ideas y traducir): el control no
  // sabe que hace cada una, solo cuando puede ofrecerlas.
  const accionesDelMenu = {};
  function registrarAccion(nombre, ejecutar) {
    accionesDelMenu[nombre] = ejecutar;
  }

  function leerPreferencia(clave, porDefecto) {
    try {
      return localStorage.getItem(clave) || porDefecto;
    } catch (error) {
      return porDefecto;
    }
  }
  function guardarPreferencia(clave, valor) {
    try {
      localStorage.setItem(clave, valor);
    } catch (error) {
      // Sin almacenamiento: la preferencia dura la sesion.
    }
  }

  // Orden importa: cargarHistorialGuardado() abre la conversacion mas reciente
  // y reconcilia su seleccion de documentos guardada contra documentosDisponibles
  // -- si corriera antes de tener la lista, esa seleccion se pisaria con "todos".
  cargarProyectos()
    .then(cargarDocumentosDisponibles)
    .then(cargarHistorialGuardado)
    .then(cargarLimitesAcciones);

  campoProyecto.addEventListener("change", cargarDocumentosDisponibles);

  // ---------- Control y menu de acciones sobre los documentos tildados ----------

  async function cargarLimitesAcciones() {
    try {
      const respuesta = await fetch("/api/acciones/limites");
      if (respuesta.ok) {
        const limites = await respuesta.json();
        if (limites && limites.maxDocumentos > 0) {
          limitesAcciones = limites;
        }
      }
    } catch (error) {
      // Sin backend todavia: se quedan los defaults, que coinciden con los del servidor.
    }
    actualizarControlAcciones();
  }

  /**
   * Los cuatro estados del control: sin seleccion (deshabilitado con ayuda),
   * N seleccionados, menu abierto, y accion en curso (deshabilitado con spinner).
   * Se llama en cada punto donde cambia la seleccion o donde hoy se toca el
   * boton de enviar: la exclusion mutua es la misma que la del chat, sin un
   * canal de estado aparte (hallazgo 8 de la revision del plan).
   */
  function actualizarControlAcciones() {
    if (!botonAcciones) {
      return;
    }
    const n = documentosActivosActuales.length;
    // El boton de enviar ya modela "hay algo en curso en ESTA conversacion"
    // (incluido el panel de reformulaciones esperando una eleccion): es la misma
    // senal, no un canal de estado aparte.
    const enCurso = boton.disabled || (conversacionActualId != null && streamsActivos.has(conversacionActualId));
    let texto;
    if (n === 0) {
      texto = "Selecciona documentos para ver acciones";
    } else if (n > limitesAcciones.maxDocumentos) {
      texto = "Máximo " + limitesAcciones.maxDocumentos + " documentos por acción";
    } else if (enCurso) {
      texto = "Acción en curso…";
    } else {
      texto = "Acciones sobre " + n + (n === 1 ? " documento" : " documentos");
    }
    botonAccionesTexto.textContent = texto;
    botonAcciones.disabled = n === 0 || n > limitesAcciones.maxDocumentos || enCurso;
    botonAcciones.classList.toggle("en-curso", enCurso);
    if (botonAcciones.disabled) {
      cerrarMenuAcciones();
    }
    if (botonTraducirDocumentos) {
      botonTraducirDocumentos.textContent = "Traducir " + n + (n === 1 ? " documento" : " documentos");
    }
  }

  function fijarBotonEnviar(deshabilitado) {
    boton.disabled = deshabilitado;
    actualizarControlAcciones();
  }

  function abrirMenuAcciones() {
    if (!menuAcciones || botonAcciones.disabled) {
      return;
    }
    menuAcciones.classList.remove("oculto");
    botonAcciones.setAttribute("aria-expanded", "true");
  }

  function cerrarMenuAcciones() {
    if (!menuAcciones) {
      return;
    }
    menuAcciones.classList.add("oculto");
    if (submenuTraducir) {
      submenuTraducir.classList.add("oculto");
    }
    if (botonAcciones) {
      botonAcciones.setAttribute("aria-expanded", "false");
    }
  }

  if (botonAcciones && menuAcciones) {
    botonAcciones.addEventListener("click", () => {
      if (menuAcciones.classList.contains("oculto")) {
        abrirMenuAcciones();
      } else {
        cerrarMenuAcciones();
      }
    });
    menuAcciones.querySelectorAll(".item-accion").forEach((item) => {
      item.addEventListener("click", () => {
        const accion = item.dataset.accion;
        if (accion === "traducir") {
          // El sub-panel de idiomas se despliega en el mismo menu; la accion
          // arranca con su propio boton ("Traducir N documentos").
          if (submenuTraducir) {
            submenuTraducir.classList.toggle("oculto");
          }
          return;
        }
        cerrarMenuAcciones();
        if (accionesDelMenu[accion]) {
          accionesDelMenu[accion]();
        }
      });
    });
    if (botonTraducirDocumentos) {
      botonTraducirDocumentos.addEventListener("click", () => {
        cerrarMenuAcciones();
        if (accionesDelMenu.traducir) {
          accionesDelMenu.traducir();
        }
      });
    }
    // Clic fuera y Escape cierran el menu, como cualquier menu.
    document.addEventListener("click", (evento) => {
      if (!evento.target.closest("#acciones-documentos")) {
        cerrarMenuAcciones();
      }
    });
    document.addEventListener("keydown", (evento) => {
      if (evento.key === "Escape") {
        cerrarMenuAcciones();
      }
    });
  }

  /** Lo que la persona tildo, literal: aqui "[]" significa ninguno, no todos. */
  function documentosSeleccionadosParaAccion() {
    const documentos = documentosActivosActuales.slice();
    if (!documentos.length) {
      alert("Tilda al menos un documento.");
      return null;
    }
    if (documentos.length > limitesAcciones.maxDocumentos) {
      alert("Se pueden elegir hasta " + limitesAcciones.maxDocumentos + " documentos por acción.");
      return null;
    }
    if (conversacionActualId != null && streamsActivos.has(conversacionActualId)) {
      return null;
    }
    return documentos;
  }

  function idiomaDelResultado() {
    const codigo = selectorIdiomaResultado ? selectorIdiomaResultado.valores().destino : "es";
    guardarPreferencia("kb.acciones.idioma", codigo);
    if (window.kbIdiomas) {
      window.kbIdiomas.recordar(codigo);
    }
    return codigo;
  }

  formulario.addEventListener("submit", (evento) => {
    evento.preventDefault();
    const pregunta = campoPregunta.value.trim();
    if (!pregunta || (conversacionActualId != null && streamsActivos.has(conversacionActualId))) {
      return;
    }
    if (documentosDisponibles.length && documentosActivosActuales.length === 0) {
      alert("Seleccioná al menos un documento activo para poder preguntar.");
      return;
    }
    const proyecto = campoProyecto.value.trim() || "default";
    preguntar(pregunta, proyecto);
    campoPregunta.value = "";
  });

  if (botonNuevaConversacion) {
    botonNuevaConversacion.addEventListener("click", () => {
      // No toca streamsActivos: si la conversacion que se deja tenia una
      // respuesta en curso, sigue generando en segundo plano.
      conversacionActualId = null;
      historial.innerHTML = "";
      if (bienvenida) {
        bienvenida.classList.remove("oculto");
      }
      fijarBotonEnviar(false);
      campoPregunta.value = "";
      campoPregunta.focus();
      // "Todos activos" por defecto para la conversacion nueva (no hereda lo
      // que haya quedado tildado en la anterior).
      documentosActivosActuales = documentosDisponibles.map((d) => d.id);
      renderListaDocumentos();
      // No borra nada de IndexedDB: la conversacion anterior queda en la
      // lista, solo se des-resalta porque ya no es la activa.
      cargarListaConversaciones();
    });
  }

  // Repuebla el sidebar y abre la conversacion mas reciente (si hay alguna),
  // tal como quedo la ultima vez, sin repetir el streaming.
  async function cargarHistorialGuardado() {
    try {
      const conversaciones = await kbHistorialDb.listarConversaciones();
      if (conversaciones.length) {
        await abrirConversacion(conversaciones[0].id);
      } else {
        await cargarListaConversaciones();
      }
    } catch (error) {
      // IndexedDB no disponible (navegador viejo, modo privado estricto, etc.):
      // la app sigue funcionando, simplemente sin persistir entre refrescos.
    }
  }

  async function cargarListaConversaciones() {
    try {
      renderListaConversaciones(await kbHistorialDb.listarConversaciones());
    } catch (error) {
      // Sin IndexedDB no hay lista que mostrar.
    }
  }

  function renderListaConversaciones(conversaciones) {
    if (!listaConversaciones) {
      return;
    }
    listaConversaciones.innerHTML = conversaciones
      .map(
        (c) =>
          '<div class="item-conversacion' + (c.id === conversacionActualId ? " activo" : "") + '" data-id="' + c.id + '">' +
          '<span class="titulo-item-conversacion">' + escaparHtml(c.titulo) +
          (streamsActivos.has(c.id) ? " ⋯" : "") + "</span>" +
          '<button type="button" class="boton-eliminar-conversacion" data-id="' + c.id +
          '" title="Eliminar conversación" aria-label="Eliminar conversación">🗑</button>' +
          "</div>"
      )
      .join("");

    listaConversaciones.querySelectorAll(".item-conversacion").forEach((item) => {
      item.addEventListener("click", (evento) => {
        if (evento.target.closest(".boton-eliminar-conversacion")) {
          return;
        }
        abrirConversacion(Number(item.dataset.id));
      });
    });
    listaConversaciones.querySelectorAll(".boton-eliminar-conversacion").forEach((eliminarBtn) => {
      eliminarBtn.addEventListener("click", (evento) => {
        evento.stopPropagation();
        eliminarConversacion(Number(eliminarBtn.dataset.id));
      });
    });
  }

  async function abrirConversacion(conversacionId) {
    conversacionActualId = conversacionId;
    historial.innerHTML = "";
    if (bienvenida) {
      bienvenida.classList.add("oculto");
    }
    let turnos = [];
    try {
      turnos = await kbHistorialDb.listarTurnosDeConversacion(conversacionId);
      turnos.forEach(pintarTurnoGuardado);
    } catch (error) {
      // Sin IndexedDB no hay turnos que recuperar.
    }

    try {
      const registro = await kbHistorialDb.obtenerConversacion(conversacionId);
      const guardados = registro && registro.documentosActivos && registro.documentosActivos.length
        ? registro.documentosActivos.filter((id) => documentosDisponibles.some((d) => d.id === id))
        : documentosDisponibles.map((d) => d.id);
      documentosActivosActuales = guardados;
      renderListaDocumentos();
    } catch (error) {
      // Sin IndexedDB: se sigue con lo que ya estaba tildado.
    }

    // Si esta conversacion quedo generando una respuesta mientras el usuario
    // miraba otra, su turno "en vivo" sigue existiendo (detenido de la vista,
    // no del stream) -- se reengancha al final, con lo que ya lleva escrito.
    // Eso cubre cambiar de conversacion SIN recargar la pagina; un F5 de
    // verdad se pierde streamsActivos entero, para eso esta reconectarSiHaceFalta.
    const activo = streamsActivos.get(conversacionId);
    if (activo) {
      historial.appendChild(activo.turno.raiz);
    } else {
      await reconectarSiHaceFalta(conversacionId, turnos);
    }
    fijarBotonEnviar(!!streamsActivos.get(conversacionId));

    await cargarListaConversaciones();
    historial.lastElementChild?.scrollIntoView({ behavior: "auto", block: "start" });
  }

  /**
   * Le pregunta al servidor si la pregunta mas reciente de esta conversacion
   * (StreamsEnCursoRepositorio, del lado del servidor) ya se ve reflejada en
   * el ultimo turno guardado localmente. Si no -- porque la pagina se recargo
   * a mitad de una respuesta y ese turno nunca llego a guardarse -- la
   * reconstruye: la muestra ya resuelta, o la deja "reconectando" y sondea
   * hasta que el servidor la termine.
   *
   * Comparar por texto de la pregunta (no por un id propio) es una
   * simplificacion a proposito: alcanza para el caso real (recargar a mitad
   * de una respuesta), aunque no distingue dos preguntas identicas seguidas.
   */
  async function reconectarSiHaceFalta(conversacionId, turnosGuardados) {
    let estado;
    try {
      const respuesta = await fetch("/api/chat/estado?conversacionId=" + conversacionId);
      if (!respuesta.ok) {
        return; // 404: esta conversacion nunca le pregunto nada al servidor.
      }
      estado = await respuesta.json();
    } catch (error) {
      return; // Sin conexion con el backend todavia: no hay nada que reconectar.
    }

    const ultimaGuardada = turnosGuardados.length ? turnosGuardados[turnosGuardados.length - 1].pregunta : null;
    if (estado.pregunta === ultimaGuardada) {
      return; // Ya esta guardada por el camino normal (evento "fin"): nada que hacer.
    }

    const turno = nuevoTurno(estado.pregunta);
    if (estado.reformulacion) {
      turno.reformulacion.textContent = "Buscando también como: “" + estado.reformulacion + "”";
    }
    turno.previa.innerHTML = "<li>No disponible después de reconectar.</li>";
    turno.citasDatos = estado.citas || [];
    turno.citas.innerHTML = estado.citas && estado.citas.length
      ? estado.citas.map((c, i) => itemCita(c, i + 1)).join("")
      : "<li>Sin citas.</li>";

    if (estado.estado === "en_curso") {
      turno.estado.textContent = "Reconectando con una respuesta que sigue en curso…";
      iniciarSondeo(conversacionId, turno, estado.projectId);
    } else {
      renderizarStreamResuelto(conversacionId, turno, estado);
    }
  }

  // Registra el sondeo como una entrada mas de streamsActivos (con "fuente"
  // en null, para distinguirla de un EventSource real) -- asi reusa gratis
  // todo lo que ya depende de ese mapa: el boton deshabilitado, el "⋯" de
  // "generando" en la barra lateral, y la limpieza si se borra la conversacion.
  function iniciarSondeo(conversacionId, turno, proyecto) {
    const intervaloId = setInterval(async () => {
      let estado;
      try {
        const respuesta = await fetch("/api/chat/estado?conversacionId=" + conversacionId);
        if (!respuesta.ok) {
          clearInterval(intervaloId);
          streamsActivos.delete(conversacionId);
          return;
        }
        estado = await respuesta.json();
      } catch (error) {
        return; // Un hipo de red no corta el sondeo: reintenta en la proxima vuelta.
      }
      if (estado.estado === "en_curso") {
        return;
      }
      clearInterval(intervaloId);
      streamsActivos.delete(conversacionId);
      turno.citasDatos = estado.citas || [];
      turno.citas.innerHTML = estado.citas && estado.citas.length
        ? estado.citas.map((c, i) => itemCita(c, i + 1)).join("")
        : "<li>Sin citas.</li>";
      renderizarStreamResuelto(conversacionId, turno, estado, proyecto);
    }, 5000);
    streamsActivos.set(conversacionId, { fuente: null, turno: turno, detenerContador: () => {}, intervaloId: intervaloId });
  }

  function renderizarStreamResuelto(conversacionId, turno, estado, proyecto) {
    turno.respuesta.textContent = estado.texto || "";
    const huboError = estado.estado === "error";
    turno.estado.textContent = huboError ? "La respuesta quedó incompleta." : "Respondido";
    turno.estado.classList.add(huboError ? "error" : "completado");
    // estado.queryLogId es null en el camino de error (nunca se llego a
    // escribir en query_log) -- activarFeedback ya maneja ese caso sin hacer
    // nada.
    activarFeedback(turno, estado.queryLogId);
    if (conversacionActualId === conversacionId) {
      fijarBotonEnviar(false);
    }
    guardarTurno(
        estado.pregunta, proyecto || estado.projectId, turno, huboError,
        huboError ? turno.estado.textContent : null, conversacionId, null);
  }

  async function eliminarConversacion(conversacionId) {
    if (!confirm("¿Eliminar esta conversación? No se puede deshacer.")) {
      return;
    }
    const activo = streamsActivos.get(conversacionId);
    if (activo) {
      // "fuente" es null cuando es un sondeo reconectado (ver iniciarSondeo),
      // no un EventSource real -- no tiene .close().
      if (activo.fuente) {
        activo.fuente.close();
      }
      if (activo.intervaloId) {
        clearInterval(activo.intervaloId);
      }
      activo.detenerContador();
      streamsActivos.delete(conversacionId);
    }
    try {
      await kbHistorialDb.eliminarConversacion(conversacionId);
    } catch (error) {
      return;
    }
    if (conversacionId === conversacionActualId) {
      conversacionActualId = null;
      historial.innerHTML = "";
      if (bienvenida) {
        bienvenida.classList.remove("oculto");
      }
      fijarBotonEnviar(false);
    }
    cargarListaConversaciones();
  }

  function pintarTurnoGuardado(registro) {
    // Los turnos guardados antes del issue #38 no tienen tipo: son preguntas.
    const tipo = registro.tipo || "pregunta";
    const turno = nuevoTurno(registro.pregunta, { tipo: tipo });
    turno.registroId = registro.id;
    if (registro.reformulacion) {
      turno.reformulacion.textContent = "Buscando también como: “" + registro.reformulacion + "”";
    }
    if (turno.previa) {
      turno.previa.innerHTML = registro.previa && registro.previa.length
        ? registro.previa.map((c) => itemCita(c, null)).join("")
        : "<li>Sin resultados rápidos.</li>";
    }
    turno.citas.innerHTML = registro.citas && registro.citas.length
      ? registro.citas.map((c, i) => itemCita(c, i + 1)).join("")
      : "<li>Sin citas.</li>";
    turno.respuesta.textContent = registro.respuesta || "";
    if (tipo !== "pregunta") {
      renderCobertura(turno, registro.cobertura || []);
      if (tipo === "preguntas" || tipo === "ideas") {
        renderEstructurado(turno, tipo, registro.resultado);
      }
      if (typeof pintarTurnoDeTraduccionGuardado === "function") {
        pintarTurnoDeTraduccionGuardado(turno, registro);
      }
    }
    if (registro.error) {
      turno.estado.textContent = registro.estadoError || "La respuesta quedó incompleta.";
      turno.estado.classList.add("error");
    } else if (registro.duracionMs != null) {
      turno.estado.textContent = (tipo === "pregunta" ? "Respondido en " : "Listo en ") + formatearDuracion(registro.duracionMs);
      turno.estado.classList.add("completado");
    }
  }

  // Segundos < 1 min; minutos:segundos < 1 h; horas:minutos:segundos en
  // adelante -- el minuto/segundo final siempre a dos digitos (00-59), el
  // primer numero de cada formato sin rellenar, como cualquier reloj de duracion.
  function formatearDuracion(ms) {
    const totales = Math.round(ms / 1000);
    if (totales < 60) {
      return totales + " s";
    }
    const dosDigitos = (n) => String(n).padStart(2, "0");
    const horas = Math.floor(totales / 3600);
    const minutos = Math.floor((totales % 3600) / 60);
    const segundos = totales % 60;
    return horas > 0
      ? horas + ":" + dosDigitos(minutos) + ":" + dosDigitos(segundos)
      : minutos + ":" + dosDigitos(segundos);
  }

  async function cargarProyectos() {
    try {
      const respuesta = await fetch("/api/admin/proyectos");
      if (!respuesta.ok) {
        throw new Error("HTTP " + respuesta.status);
      }
      const proyectos = await respuesta.json();
      poblarSelectorProyecto(proyectos.length ? proyectos : ["default"]);
    } catch (error) {
      // Sin conexion con el backend todavia (o sin ninguna fuente creada): "default"
      // sigue siendo una opcion valida, el corpus de ejemplo la usa.
      poblarSelectorProyecto(["default"]);
    }
  }

  function poblarSelectorProyecto(proyectos) {
    campoProyecto.innerHTML = proyectos.map((p) => `<option value="${escaparHtml(p)}">${escaparHtml(p)}</option>`).join("");
    if (proyectos.includes("default")) {
      campoProyecto.value = "default";
    }
  }

  // Solo documentos "locales" (los que se ven en Administracion > Archivos del
  // vault): repos Git, Teams y Azure DevOps se sincronizan solos, no tiene
  // sentido prenderlos/apagarlos por conversacion desde aca.
  async function cargarDocumentosDisponibles() {
    try {
      const proyecto = campoProyecto.value.trim() || "default";
      const respuesta = await fetch("/api/admin/documentos?projectId=" + encodeURIComponent(proyecto));
      if (!respuesta.ok) {
        throw new Error("HTTP " + respuesta.status);
      }
      documentosDisponibles = await respuesta.json();
    } catch (error) {
      documentosDisponibles = [];
    }
    // Todos activos por defecto, salvo que la conversacion abierta ya tuviera
    // su propia seleccion guardada (abrirConversacion la pisa despues de esto).
    documentosActivosActuales = documentosDisponibles.map((d) => d.id);
    renderListaDocumentos();
  }

  function renderListaDocumentos() {
    if (!listaDocumentos) {
      return;
    }
    if (!documentosDisponibles.length) {
      listaDocumentos.innerHTML = '<div class="sin-documentos">No hay documentos locales indexados todavía.</div>';
    } else {
      listaDocumentos.innerHTML = documentosDisponibles
        .map((d) => {
          const activo = documentosActivosActuales.includes(d.id);
          return '<label class="item-documento"><input type="checkbox" data-id="' + d.id + '"' +
              (activo ? " checked" : "") + "><span>" + escaparHtml(d.titulo) + "</span></label>";
        })
        .join("");
      listaDocumentos.querySelectorAll('input[type="checkbox"]').forEach((casilla) => {
        casilla.addEventListener("change", alCambiarSeleccionDocumentos);
      });
    }
    if (contadorDocumentos) {
      contadorDocumentos.textContent = documentosDisponibles.length
        ? "(" + documentosActivosActuales.length + "/" + documentosDisponibles.length + ")"
        : "";
    }
    actualizarControlAcciones();
  }

  async function alCambiarSeleccionDocumentos() {
    documentosActivosActuales = Array.from(listaDocumentos.querySelectorAll('input[type="checkbox"]:checked'))
        .map((casilla) => Number(casilla.dataset.id));
    if (contadorDocumentos) {
      contadorDocumentos.textContent = "(" + documentosActivosActuales.length + "/" + documentosDisponibles.length + ")";
    }
    actualizarControlAcciones();
    if (conversacionActualId != null) {
      try {
        await kbHistorialDb.actualizarDocumentosActivos(conversacionActualId, documentosActivosNormalizados());
      } catch (error) {
        // Sin IndexedDB: la seleccion sigue rigiendo esta sesion, solo no persiste.
      }
    }
  }

  /** [] = sin restriccion (todos), mismo criterio que el backend -- ver Dominio.Filtros. */
  function documentosActivosNormalizados() {
    return documentosActivosActuales.length === documentosDisponibles.length ? [] : documentosActivosActuales;
  }

  // No basta con textContent->innerHTML (asi era antes): esa serializacion solo
  // escapa &, < y > porque asume que el resultado se usa en posicion de texto.
  // Pero el resultado de escaparHtml tambien se usa dentro de VALORES DE
  // ATRIBUTO (data-titulo="...", data-uri="...") en varios lugares de este
  // archivo -- una comilla doble sin escapar en, por ejemplo, el titulo de un
  // documento cierra el atributo antes de tiempo y deja el resto como HTML
  // nuevo. Por eso escapa las 5 entidades relevantes a mano, validas en
  // cualquier posicion (texto o atributo).
  const ENTIDADES_HTML = { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" };
  function escaparHtml(texto) {
    return String(texto == null ? "" : texto).replace(/[&<>"']/g, (caracter) => ENTIDADES_HTML[caracter]);
  }

  // "texto" ya tiene que venir escapado para HTML (se usa tal cual como atributo).
  function botonCopiar(texto, etiqueta) {
    return `<button type="button" class="boton-copiar" data-texto="${texto}" ` +
        `title="${etiqueta}" aria-label="${etiqueta}">⧉</button>`;
  }

  // "file:///vault/..." identifica un archivo dentro del contenedor: el
  // navegador no puede navegar ahi (ni el esquema file:// abre desde una
  // pagina http, ni esa ruta existe fuera del servidor), por eso estas citas
  // abren el visor modal en vez de un link normal. Las citas de Azure DevOps
  // o Teams son URLs reales y siguen abriendo en pestaña nueva.
  // OJO: este prefijo tiene que coincidir con VaultUri.PREFIJO del lado del
  // servidor (co.g3a.baseconocimiento.ingesta) -- no hay forma de compartir
  // la constante entre Java y JS, asi que un cambio de uno exige el otro.
  function esUriDelVault(uri) {
    return typeof uri === "string" && uri.startsWith("file:///vault/");
  }

  function itemCita(cita, indice) {
    const titulo = escaparHtml(cita.titulo || cita.uri);
    const uri = cita.uri || "";
    const extracto = escaparHtml(cita.extracto || "");
    const prefijo = indice == null ? "" : `[${indice}] `;
    const lineaExtracto = extracto ? `<span class="extracto">${extracto}</span>` : "";
    // Copia el fragmento tal como se ve (el extracto, ya recortado por el
    // servidor); si no hay extracto, al menos el titulo sirve de algo.
    const textoACopiar = escaparHtml(cita.extracto || cita.titulo || cita.uri);
    const enlace = esUriDelVault(uri)
        ? `<button type="button" class="enlace-cita" data-uri="${escaparHtml(uri)}" data-titulo="${titulo}">${titulo}</button>`
        : `<a href="${escaparHtml(uri)}" target="_blank" rel="noopener">${titulo}</a>`;
    return `<li>${prefijo}${enlace}${lineaExtracto}${botonCopiar(textoACopiar, "Copiar fragmento")}</li>`;
  }

  function esPrevisualizable(tipoContenido) {
    return tipoContenido.startsWith("text/") || tipoContenido === "application/pdf";
  }

  // Se incrementa en cada apertura y en cada cierre del modal: cualquier
  // fetch en vuelo de una apertura anterior compara su propio numero contra
  // este antes de tocar el DOM, asi una respuesta que llega tarde (cita A,
  // red lenta) no pisa lo que ya se ve de una cita B abierta despues.
  let peticionModalActual = 0;
  let urlObjetoModalActual = null;

  function liberarUrlObjetoModal() {
    if (urlObjetoModalActual) {
      URL.revokeObjectURL(urlObjetoModalActual);
      urlObjetoModalActual = null;
    }
  }

  async function abrirModalDocumento(uri, titulo) {
    if (!modalDocumento) {
      return;
    }
    const idPeticion = ++peticionModalActual;
    liberarUrlObjetoModal();
    const urlContenido = `/api/vault/contenido?uri=${encodeURIComponent(uri)}`;
    modalDocumentoTitulo.textContent = titulo || uri;
    modalDocumentoDescarga.href = `${urlContenido}&descargar=true`;
    modalDocumentoCuerpo.innerHTML = '<p class="documento-estado">Cargando vista previa…</p>';
    modalDocumento.showModal();
    try {
      // Un solo GET (no HEAD + GET): ademas de la mitad de las idas y vueltas,
      // esto evita la ventana entre "HEAD dijo que existe" y "el iframe hace
      // su propio GET" en la que el archivo se pudo haber borrado -- el mismo
      // fetch que decide si se puede previsualizar es el que trae el contenido.
      const respuesta = await fetch(urlContenido);
      if (idPeticion !== peticionModalActual) {
        return; // El usuario ya cerro este modal o abrio otra cita.
      }
      if (!respuesta.ok) {
        throw new Error("GET " + respuesta.status);
      }
      const tipoContenido = respuesta.headers.get("Content-Type") || "";
      if (!esPrevisualizable(tipoContenido)) {
        modalDocumentoCuerpo.innerHTML =
            '<p class="documento-estado">No hay vista previa disponible para este tipo de archivo. ' +
            "Usa \"Descargar\" para abrirlo.</p>";
        return;
      }
      const blob = await respuesta.blob();
      if (idPeticion !== peticionModalActual) {
        return;
      }
      const urlObjeto = URL.createObjectURL(blob);
      urlObjetoModalActual = urlObjeto;
      const iframe = document.createElement("iframe");
      // sandbox="" (sin allow-scripts ni allow-same-origin): si algun archivo
      // del vault terminara siendo text/html, su contenido se renderiza inerte
      // -- ningun script embebido corre con el origen de esta pagina.
      iframe.setAttribute("sandbox", "");
      iframe.title = "Vista previa del documento";
      iframe.src = urlObjeto;
      modalDocumentoCuerpo.innerHTML = "";
      modalDocumentoCuerpo.appendChild(iframe);
    } catch (error) {
      if (idPeticion === peticionModalActual) {
        modalDocumentoCuerpo.innerHTML =
            '<p class="documento-estado">No se pudo cargar la vista previa. Usa "Descargar" para abrirlo.</p>';
      }
    }
  }

  if (modalDocumento) {
    // Corta cualquier PDF/iframe que siga cargando, invalida cualquier fetch
    // todavia en vuelo (ver peticionModalActual) y evita el flash del
    // contenido anterior la proxima vez que se abra.
    modalDocumento.addEventListener("close", () => {
      peticionModalActual++;
      liberarUrlObjetoModal();
      modalDocumentoCuerpo.innerHTML = "";
    });
  }

  // Un solo delegado en "historial" para ambas acciones de las citas (abrir
  // preview, copiar): "historial" nunca se reemplaza (solo su innerHTML), asi
  // que alcanza para cualquier cita de cualquier turno, incluidos los que
  // todavia no existian al registrarlo.
  historial.addEventListener("click", async (evento) => {
    const enlace = evento.target.closest(".enlace-cita");
    if (enlace) {
      abrirModalDocumento(enlace.dataset.uri, enlace.dataset.titulo);
      return;
    }
    const copiar = evento.target.closest(".boton-copiar");
    if (!copiar) {
      return;
    }
    try {
      await navigator.clipboard.writeText(copiar.dataset.texto || "");
      const original = copiar.textContent;
      copiar.textContent = "✓";
      copiar.disabled = true;
      setTimeout(() => {
        copiar.textContent = original;
        copiar.disabled = false;
      }, 1200);
    } catch (error) {
      // Sin permiso de portapapeles o navegador viejo: no hay mucho mas que
      // hacer aca, el usuario puede seguir seleccionando el texto a mano.
    }
  });

  // Los turnos de accion (issue #38) llevan una etiqueta en la burbuja y ni
  // feedback ni "Resultados rapidos": no son respuestas del RAG.
  const ETIQUETA_TURNO = {
    resumen: "Resumen",
    sintesis: "Síntesis",
    preguntas: "Preguntas",
    ideas: "Ideas",
    "traduccion-documentos": "Traducción",
    "traduccion-texto": "Traducción",
  };

  /**
   * opciones.tipo: "pregunta" (por defecto) o uno de ETIQUETA_TURNO. Un turno de
   * accion se pinta con su etiqueta, el bloque de cobertura y sin feedback.
   */
  function nuevoTurno(pregunta, opciones) {
    const tipo = (opciones && opciones.tipo) || "pregunta";
    const esAccion = tipo !== "pregunta";
    if (bienvenida) {
      bienvenida.classList.add("oculto");
    }
    const turno = document.createElement("div");
    turno.className = "turno turno-tipo-" + tipo;
    turno.innerHTML =
      '<div class="mensaje mensaje-usuario">' +
      botonCopiar(escaparHtml(pregunta), esAccion ? "Copiar etiqueta" : "Copiar pregunta") +
      '<div class="burbuja">' +
      (esAccion ? `<span class="etiqueta-turno">${escaparHtml(ETIQUETA_TURNO[tipo] || "")}</span>` : "") +
      `<span class="texto-burbuja">${escaparHtml(pregunta)}</span>` +
      "</div>" +
      "</div>" +
      '<div class="mensaje mensaje-asistente">' +
      '<div class="avatar-asistente">KB</div>' +
      '<div class="contenido-asistente">' +
      '<p class="turno-reformulacion"></p>' +
      '<p class="turno-estado"></p>' +
      '<div class="turno-eleccion oculto"></div>' +
      '<div class="turno-cobertura oculto"></div>' +
      '<div class="turno-respuesta"></div>' +
      '<div class="turno-estructurado oculto"></div>' +
      '<div class="turno-traduccion oculto"></div>' +
      (esAccion ? "" :
        '<div class="turno-feedback oculto">' +
        '<span>¿Te sirvió esta respuesta?</span>' +
        '<button type="button" class="boton-feedback boton-feedback-si" ' +
        'aria-label="Respuesta útil">👍</button>' +
        '<button type="button" class="boton-feedback boton-feedback-no" ' +
        'aria-label="Respuesta no útil">👎</button>' +
        '<span class="turno-feedback-gracias oculto">¡Gracias!</span>' +
        "</div>" +
        '<details class="turno-detalle">' +
        '<summary>Resultados rápidos ' +
        `<button type="button" class="boton-info" title="${escaparHtml(TEXTO_INFO_PREVIA)}" ` +
        'aria-label="Por qué la vista previa puede no mostrar nada">i</button>' +
        "</summary>" +
        '<ul class="turno-previa"></ul>' +
        "</details>") +
      '<details class="turno-detalle">' +
      "<summary>Citas</summary>" +
      '<ol class="turno-citas"></ol>' +
      "</details>" +
      "</div>" +
      "</div>";
    historial.appendChild(turno);
    const botonInfoPrevia = turno.querySelector(".boton-info");
    if (botonInfoPrevia && modalInfoPrevia) {
      botonInfoPrevia.addEventListener("click", (evento) => {
        // Sin esto, el clic tambien le llega al <summary> padre y
        // abre/cierra el <details> de "Resultados rápidos" de paso.
        evento.preventDefault();
        evento.stopPropagation();
        modalInfoPrevia.showModal();
      });
    }
    turno.scrollIntoView({ behavior: "smooth", block: "start" });
    return {
      raiz: turno,
      tipo: tipo,
      textoBurbuja: turno.querySelector(".texto-burbuja"),
      estado: turno.querySelector(".turno-estado"),
      reformulacion: turno.querySelector(".turno-reformulacion"),
      eleccion: turno.querySelector(".turno-eleccion"),
      cobertura: turno.querySelector(".turno-cobertura"),
      estructurado: turno.querySelector(".turno-estructurado"),
      traduccion: turno.querySelector(".turno-traduccion"),
      previa: turno.querySelector(".turno-previa"),
      respuesta: turno.querySelector(".turno-respuesta"),
      citas: turno.querySelector(".turno-citas"),
      feedback: turno.querySelector(".turno-feedback"),
      botonFeedbackSi: turno.querySelector(".boton-feedback-si"),
      botonFeedbackNo: turno.querySelector(".boton-feedback-no"),
      feedbackGracias: turno.querySelector(".turno-feedback-gracias"),
      // Datos "crudos" (no el HTML ya armado) para poder guardar el turno en
      // IndexedDB tal cual se ve, sin tener que re-parsear el DOM.
      previaDatos: [],
      citasDatos: [],
      coberturaDatos: [],
      resultadoDatos: null,
      documentosDatos: null,
      // El id del registro en IndexedDB, para actualizarlo despues (traducciones).
      registroId: null,
      reformulacionTexto: null,
      queryLogId: null,
      // true mientras el panel de reformulaciones espera que la persona elija:
      // el "fin" de ese primer stream no cierra el turno ni lo guarda.
      eligiendo: false,
    };
  }

  /**
   * Se llama una vez que se conoce el queryLogId de esta respuesta (evento SSE
   * "queryLogId" en vivo, o `estado.queryLogId` tras reconectar por F5) --
   * revela los botones 👍/👎 y los deja listos para un solo click. Una vez por
   * respuesta del lado del cliente: sin login de persona el servidor no puede
   * deduplicar de verdad (ver Consultar.registrarFeedback), asi que esto es la
   * unica barrera real contra un doble click accidental.
   */
  function activarFeedback(turno, queryLogId) {
    if (!turno.feedback || queryLogId == null) {
      return;
    }
    turno.queryLogId = queryLogId;
    turno.feedback.classList.remove("oculto");
    const enviar = async (util) => {
      turno.botonFeedbackSi.disabled = true;
      turno.botonFeedbackNo.disabled = true;
      let registrado = false;
      try {
        // fetch() solo rechaza por fallo de red -- un 400 (queryLogId invalido,
        // payload rechazado) resuelve normal, por eso hay que chequear .ok
        // antes de dar el feedback por guardado.
        const respuesta = await fetch("/api/feedback", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ queryLogId: turno.queryLogId, util: util }),
        });
        registrado = respuesta.ok;
      } catch (error) {
        // Sin conexion: los botones quedan deshabilitados igual -- reintentar
        // en una respuesta que ya se fue no aporta nada.
      }
      if (registrado && turno.feedbackGracias) {
        turno.feedbackGracias.classList.remove("oculto");
      }
    };
    turno.botonFeedbackSi.addEventListener("click", () => enviar(true));
    turno.botonFeedbackNo.addEventListener("click", () => enviar(false));
  }

  // El pipeline completo tarda 2-3 min en CPU (medido en F4): un contador que
  // avanza es la diferencia entre "esto está colgado" y "esto está trabajando".
  function iniciarContador(elementoEstado, etiqueta) {
    const inicio = Date.now();
    elementoEstado.classList.remove("error");
    elementoEstado.textContent = etiqueta + " (0 s)";
    const intervalo = setInterval(() => {
      const segundos = Math.round((Date.now() - inicio) / 1000);
      elementoEstado.textContent = etiqueta + " (" + segundos + " s)";
    }, 1000);
    return () => clearInterval(intervalo);
  }

  async function preguntar(pregunta, proyecto) {
    // Fijado ANTES de crear/tocar la conversacion: si el usuario cambia la
    // seleccion o de conversacion mientras esta responde, este envio se sigue
    // refiriendo a lo que estaba tildado en el momento de preguntar.
    const documentos = documentosActivosNormalizados();

    if (conversacionActualId == null) {
      try {
        conversacionActualId = await kbHistorialDb.crearConversacion(pregunta, documentos);
        await cargarListaConversaciones();
      } catch (error) {
        // Sin IndexedDB: se sigue preguntando igual, solo no persiste ni
        // aparece en la lista de conversaciones.
      }
    }
    // Fijado en una constante propia: si el usuario cambia de conversacion
    // mientras esta responde, conversacionActualId ya apunta a otro lado y
    // este envio no se puede seguir refiriendo a ella.
    const conversacionId = conversacionActualId;

    fijarBotonEnviar(true);
    const turno = nuevoTurno(pregunta);
    const inicioTurno = Date.now();
    const detenerContador = iniciarContador(turno.estado, "Buscando y analizando tu pregunta");

    cargarVistaPrevia(pregunta, proyecto, turno, documentos);
    // proponer: si la busqueda con la pregunta tal cual no alcanza, el servidor
    // no reformula solo -- manda las alternativas y esta pagina se las muestra a
    // la persona para que elija (ver mostrarEleccion).
    iniciarStreaming(pregunta, proyecto, turno, detenerContador, conversacionId, inicioTurno, documentos, { proponer: true });
  }

  async function cargarVistaPrevia(pregunta, proyecto, turno, documentos) {
    try {
      const respuesta = await fetch("/api/preview", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ q: pregunta, projectId: proyecto, documentos: documentos }),
      });
      if (!respuesta.ok) {
        throw new Error("HTTP " + respuesta.status);
      }
      const citas = await respuesta.json();
      turno.previaDatos = citas;
      turno.previa.innerHTML = citas.length
        ? citas.map((c) => itemCita(c, null)).join("")
        : "<li>Sin resultados rápidos.</li>";
    } catch (error) {
      turno.previa.innerHTML = "<li>No se pudo cargar la vista previa.</li>";
    }
  }

  /**
   * opciones: { proponer: true } en la primera llamada (el servidor puede
   * contestar solo con reformulaciones para elegir), o { busqueda, idioma } en
   * la segunda, con lo que la persona eligio (ver mostrarEleccion). Las dos
   * llamadas comparten el mismo turno en pantalla.
   */
  function iniciarStreaming(pregunta, proyecto, turno, detenerContador, conversacionId, inicioTurno, documentos, opciones) {
    opciones = opciones || {};
    let url = "/api/chat?q=" + encodeURIComponent(pregunta) + "&projectId=" + encodeURIComponent(proyecto);
    if (documentos.length) {
      url += "&documentos=" + documentos.join(",");
    }
    // Sin esto el servidor no tiene forma de guardar el progreso en
    // StreamsEnCursoRepositorio -- y sin eso, un F5 a mitad de una respuesta
    // la pierde por completo (ver reconectarSiHaceFalta).
    if (conversacionId != null) {
      url += "&conversacionId=" + conversacionId;
    }
    if (opciones.busqueda) {
      url += "&busqueda=" + encodeURIComponent(opciones.busqueda);
      url += "&idioma=" + encodeURIComponent(opciones.idioma || "es");
    } else if (opciones.proponer) {
      url += "&proponer=true";
    }
    const fuente = new EventSource(url);
    streamsActivos.set(conversacionId, { fuente: fuente, turno: turno, detenerContador: detenerContador });

    // Solo llega si se busco con un texto distinto de la pregunta (ver ChatController):
    // el vocabulario coloquial de la pregunta puede no coincidir con el termino formal
    // de la fuente (ej. "autoboxing" en la pregunta, "boxing conversion" en el corpus).
    // Con `busqueda` es la consulta que la persona eligio -- y ese caso ya lo
    // pinto mostrarEleccion al enviar, con el idioma incluido, asi que aqui no
    // se pisa. Sin `busqueda`, es la que el Reformulador aplico solo (camino
    // automatico, o una sola alternativa).
    fuente.addEventListener("reformulacion", (evento) => {
      if (opciones.busqueda) {
        return;
      }
      const consulta = JSON.parse(evento.data);
      turno.reformulacionTexto = consulta;
      turno.reformulacion.textContent = "Buscando también como: “" + consulta + "”";
    });

    // Solo con proponer=true: la busqueda original no alcanzo y hay alternativas.
    // El servidor manda "fin" justo despues, sin citas ni tokens; la respuesta
    // real llega recien cuando la persona elige y se vuelve a llamar.
    fuente.addEventListener("reformulaciones", (evento) => {
      const alternativas = JSON.parse(evento.data);
      turno.eligiendo = true;
      detenerContador();
      turno.estado.textContent = "Esperando tu elección";
      mostrarEleccion(pregunta, proyecto, turno, alternativas, conversacionId, documentos);
    });

    fuente.addEventListener("citas", (evento) => {
      const citas = JSON.parse(evento.data);
      turno.citasDatos = citas;
      turno.citas.innerHTML = citas.length
        ? citas.map((c, i) => itemCita(c, i + 1)).join("")
        : "<li>Sin citas.</li>";
      detenerContador();
      turno.estado.textContent = "Redactando la respuesta…";
    });

    // Cada token viaja como string JSON, no como texto crudo: el estandar SSE
    // le quita al dato un espacio inicial (es el delimitador "data: "), y la
    // mayoria de los tokens de un LLM empiezan justo con un espacio real
    // (" el", " servicio"). JSON.parse recupera el string exacto, espacio
    // inicial incluido. Esto escribe sobre turno.respuesta sin importar si la
    // conversacion esta visible ahora mismo: si esta desenganchada del DOM,
    // el texto se sigue acumulando igual, listo para cuando el usuario vuelva.
    fuente.addEventListener("token", (evento) => {
      turno.respuesta.textContent += JSON.parse(evento.data);
    });

    fuente.addEventListener("queryLogId", (evento) => {
      activarFeedback(turno, Number(evento.data));
    });

    fuente.addEventListener("fin", () => {
      if (turno.eligiendo) {
        // Fin del primer stream, el de las propuestas: se cierra la conexion
        // (si no, EventSource reintenta solo) pero el turno sigue abierto
        // esperando la eleccion -- no se guarda todavia, no hay respuesta.
        cerrarStreaming(conversacionId, turno, detenerContador);
        return;
      }
      const duracionMs = Date.now() - inicioTurno;
      cerrarStreaming(conversacionId, turno, detenerContador, duracionMs);
      guardarTurno(pregunta, proyecto, turno, false, null, conversacionId, duracionMs);
    });

    // Evento "error" del servidor: la causa REAL, en vez de la conjetura de
    // onerror. Hasta que ChatController lo emitio, un fallo aguas arriba (el
    // modelo del perfil sin descargar, por ejemplo) cortaba el stream sin
    // explicacion y aqui solo quedaba "Se perdio la conexion" -- que ademas
    // culpaba a Ollama, que normalmente estaba perfectamente sano.
    //
    // El close() explicito es obligatorio: sin el, EventSource ve el fin del
    // stream como una desconexion y REINTENTA la misma URL, repitiendo la
    // pregunta que acaba de fallar (mismo motivo que documenta cerrarStreaming).
    fuente.addEventListener("error-servidor", (evento) => {
      detenerContador();
      turno.estado.textContent = JSON.parse(evento.data);
      turno.estado.classList.add("error");
      cerrarStreaming(conversacionId, turno, detenerContador);
      guardarTurno(pregunta, proyecto, turno, true, turno.estado.textContent, conversacionId);
    });

    fuente.onerror = () => {
      detenerContador();
      turno.estado.textContent = "Se perdió la conexión con el servidor (¿Ollama no responde?).";
      turno.estado.classList.add("error");
      cerrarStreaming(conversacionId, turno, detenerContador);
      guardarTurno(pregunta, proyecto, turno, true, turno.estado.textContent, conversacionId);
    };
  }

  let contadorEleccion = 0;

  /**
   * Pinta, dentro del turno, la lista de consultas reformuladas que propuso el
   * servidor (mas "usar mi pregunta tal cual") y un checkbox para pedir la
   * respuesta en el idioma original de las fuentes en vez de español. Solo
   * llega aqui con dos o mas alternativas: con una sola el servidor responde
   * de inmediato. Al enviar, vuelve a preguntar con `busqueda` e `idioma`
   * sobre el MISMO turno: la burbuja de la pregunta no se repite, y la
   * respuesta aparece donde estaba el panel.
   *
   * Un F5 mientras el panel esta abierto pierde la eleccion (el servidor
   * descarto el stream y este turno no se guardo): la persona vuelve a
   * preguntar. Es la misma regla que ya aplica a una respuesta a medio
   * generar (ver historial-db.js).
   */
  function mostrarEleccion(pregunta, proyecto, turno, alternativas, conversacionId, documentos) {
    const idGrupo = ++contadorEleccion;
    const nombreBusqueda = "busqueda-" + idGrupo;
    const nombreIdioma = "idioma-" + idGrupo;
    const opcionesBusqueda = alternativas
      .map((alternativa, i) =>
        '<label class="eleccion-opcion">' +
        `<input type="radio" name="${nombreBusqueda}" value="${i}"${i === 0 ? " checked" : ""}>` +
        `<span>${escaparHtml(alternativa)}</span>` +
        "</label>")
      .join("");
    turno.eleccion.innerHTML =
      '<p class="eleccion-titulo">La búsqueda con tu pregunta tal cual no encontró resultados claros. ' +
      "Elige con qué texto buscar:</p>" +
      '<div class="eleccion-grupo">' +
      opcionesBusqueda +
      '<label class="eleccion-opcion">' +
      `<input type="radio" name="${nombreBusqueda}" value="original">` +
      "<span>Usar mi pregunta tal cual</span>" +
      "</label>" +
      "</div>" +
      '<p class="eleccion-titulo">Se buscará con este texto (puedes editarlo):</p>' +
      `<input type="text" class="eleccion-texto" value="${escaparHtml(alternativas[0])}" spellcheck="false">` +
      '<div class="eleccion-grupo">' +
      '<label class="eleccion-opcion">' +
      `<input type="checkbox" name="${nombreIdioma}">` +
      "<span>Responder en el idioma original de las fuentes (sin marcar: en español)</span>" +
      "</label>" +
      "</div>" +
      '<button type="button" class="boton-eleccion">Enviar</button>';
    turno.eleccion.classList.remove("oculto");
    turno.eleccion.scrollIntoView({ behavior: "smooth", block: "nearest" });

    // La lista precarga el campo de texto; lo que se manda es el campo. Asi la
    // persona puede corregir una alternativa a mano cuando ninguna sirve tal
    // cual (medido: un modelo chico a veces devuelve tres parafrasis de la
    // misma frase), sin perder la comodidad de elegir con un click.
    const campoTexto = turno.eleccion.querySelector(".eleccion-texto");
    turno.eleccion.querySelectorAll(`input[name="${nombreBusqueda}"]`).forEach((radio) => {
      radio.addEventListener("change", () => {
        // "original" = la propia pregunta: el servidor busca con ella sin volver
        // a reformular (si no, pisaria la eleccion con la reformulacion automatica).
        campoTexto.value = radio.value === "original" ? pregunta : alternativas[Number(radio.value)];
      });
    });
    campoTexto.addEventListener("keydown", (evento) => {
      if (evento.key === "Enter") {
        evento.preventDefault();
        turno.eleccion.querySelector(".boton-eleccion").click();
      }
    });

    turno.eleccion.querySelector(".boton-eleccion").addEventListener("click", () => {
      const seleccion = turno.eleccion.querySelector(`input[name="${nombreBusqueda}"]:checked`);
      const enIdiomaOriginal = turno.eleccion.querySelector(`input[name="${nombreIdioma}"]`).checked;
      const editada = campoTexto.value.trim();
      const busqueda = editada
        ? editada
        : (!seleccion || seleccion.value === "original" ? pregunta : alternativas[Number(seleccion.value)]);
      turno.eleccion.classList.add("oculto");
      turno.eleccion.innerHTML = "";
      turno.eligiendo = false;
      // Se pinta ya, sin esperar el evento "reformulacion" del segundo stream
      // (llega recien con las citas, minutos despues): la persona acaba de
      // elegir y debe ver de inmediato con que se busca y en que idioma.
      turno.reformulacionTexto = busqueda === pregunta ? null : busqueda;
      turno.reformulacion.textContent =
        (turno.reformulacionTexto ? "Buscando como: “" + busqueda + "”" : "Buscando con tu pregunta tal cual") +
        (enIdiomaOriginal ? " · respuesta en el idioma original de las fuentes" : "");
      if (conversacionActualId === conversacionId) {
        fijarBotonEnviar(true);
      }
      const detenerContador = iniciarContador(turno.estado, "Buscando y analizando tu pregunta");
      iniciarStreaming(pregunta, proyecto, turno, detenerContador, conversacionId, Date.now(), documentos, {
        busqueda: busqueda,
        idioma: enIdiomaOriginal ? "original" : "es",
      });
    });
  }

  // ---------- Resumir, sintetizar, preguntas e ideas: un turno por accion ----------

  const ACCIONES = {
    resumir: { tipo: "resumen", verbo: "Resumen de", trabajando: "redactando el resumen", redactando: "Redactando el resumen…" },
    sintetizar: { tipo: "sintesis", verbo: "Síntesis de", trabajando: "redactando la síntesis", redactando: "Redactando la síntesis…" },
    preguntas: { tipo: "preguntas", verbo: "Preguntas sobre", trabajando: "proponiendo preguntas", redactando: "Armando las preguntas…" },
    ideas: { tipo: "ideas", verbo: "Ideas a partir de", trabajando: "proponiendo ideas", redactando: "Armando las ideas…" },
  };
  Object.keys(ACCIONES).forEach((accion) => registrarAccion(accion, () => ejecutarAccion(accion)));

  /**
   * Misma forma que la etiqueta del servidor (PresupuestoDeContexto.etiqueta): se
   * pinta de inmediato y el evento "etiqueta" la reemplaza por la definitiva,
   * con los titulos reales y sin los documentos que ya no existan.
   */
  function etiquetaProvisional(verbo, documentos) {
    const titulos = documentos
      .map((id) => (documentosDisponibles.find((d) => d.id === id) || {}).titulo)
      .filter((t) => !!t);
    if (!titulos.length) {
      return verbo + " documentos seleccionados";
    }
    const primeros = titulos.slice(0, 3).join(", ");
    const restantes = titulos.length - 3;
    return verbo + " " + titulos.length + (titulos.length === 1 ? " documento: " : " documentos: ") +
      primeros + (restantes > 0 ? " y " + restantes + " más" : "");
  }

  async function ejecutarAccion(accion) {
    const documentos = documentosSeleccionadosParaAccion();
    if (!documentos) {
      return;
    }
    const definicion = ACCIONES[accion];
    const idioma = idiomaDelResultado();
    const proyecto = campoProyecto.value.trim() || "default";
    const etiqueta = etiquetaProvisional(definicion.verbo, documentos);
    if (conversacionActualId == null) {
      try {
        conversacionActualId = await kbHistorialDb.crearConversacion(etiqueta, documentosActivosNormalizados());
        await cargarListaConversaciones();
      } catch (error) {
        // Sin IndexedDB: se ejecuta igual, solo no persiste.
      }
    }
    const conversacionId = conversacionActualId;
    fijarBotonEnviar(true);
    const turno = nuevoTurno(etiqueta, { tipo: definicion.tipo });
    turno.documentosDatos = documentos;
    const inicioTurno = Date.now();
    const detenerContador = iniciarContador(
      turno.estado,
      "Leyendo " + documentos.length + (documentos.length === 1 ? " documento y " : " documentos y ") + definicion.trabajando);
    iniciarStreamingAccion(accion, documentos, idioma, proyecto, turno, detenerContador, conversacionId, inicioTurno);
  }

  function iniciarStreamingAccion(accion, documentos, idioma, proyecto, turno, detenerContador, conversacionId, inicioTurno) {
    const definicion = ACCIONES[accion];
    const url = "/api/acciones/" + accion + "?documentos=" + documentos.join(",") +
      "&projectId=" + encodeURIComponent(proyecto) + "&idioma=" + encodeURIComponent(idioma);
    // La "pregunta" con la que se guarda el turno: la etiqueta definitiva del
    // servidor en cuanto llega, la provisional mientras tanto.
    let etiqueta = turno.textoBurbuja.textContent;
    const fuente = new EventSource(url);
    streamsActivos.set(conversacionId, { fuente: fuente, turno: turno, detenerContador: detenerContador });
    actualizarControlAcciones();

    fuente.addEventListener("etiqueta", (evento) => {
      etiqueta = JSON.parse(evento.data);
      turno.textoBurbuja.textContent = etiqueta;
    });
    fuente.addEventListener("cobertura", (evento) => {
      renderCobertura(turno, JSON.parse(evento.data));
    });
    fuente.addEventListener("citas", (evento) => {
      const citas = JSON.parse(evento.data);
      turno.citasDatos = citas;
      turno.citas.innerHTML = citas.length
        ? citas.map((c, i) => itemCita(c, i + 1)).join("")
        : "<li>Sin citas.</li>";
      detenerContador();
      turno.estado.textContent = definicion.redactando;
    });
    fuente.addEventListener("token", (evento) => {
      turno.respuesta.textContent += JSON.parse(evento.data);
    });
    fuente.addEventListener("resultado", (evento) => {
      renderEstructurado(turno, definicion.tipo, JSON.parse(evento.data));
    });
    fuente.addEventListener("fin", () => {
      const duracionMs = Date.now() - inicioTurno;
      cerrarStreaming(conversacionId, turno, detenerContador, duracionMs);
      guardarTurno(etiqueta, proyecto, turno, false, null, conversacionId, duracionMs);
    });
    fuente.addEventListener("error-servidor", (evento) => {
      detenerContador();
      turno.estado.textContent = JSON.parse(evento.data);
      turno.estado.classList.add("error");
      cerrarStreaming(conversacionId, turno, detenerContador);
      guardarTurno(etiqueta, proyecto, turno, true, turno.estado.textContent, conversacionId);
    });
    fuente.onerror = () => {
      detenerContador();
      turno.estado.textContent = "No se pudo completar la acción (¿Ollama no responde?).";
      turno.estado.classList.add("error");
      cerrarStreaming(conversacionId, turno, detenerContador);
      guardarTurno(etiqueta, proyecto, turno, true, turno.estado.textContent, conversacionId);
    };
  }

  /**
   * "Documentos usados": cuanto de cada documento entro de verdad al modelo.
   * Los indexados llevan el mismo [n] que las citas; los que no existen en el
   * proyecto quedan al final como "no indexado" en vez de desaparecer.
   */
  function renderCobertura(turno, cobertura) {
    turno.coberturaDatos = cobertura || [];
    if (!turno.cobertura) {
      return;
    }
    if (!turno.coberturaDatos.length) {
      turno.cobertura.classList.add("oculto");
      turno.cobertura.innerHTML = "";
      return;
    }
    let n = 0;
    const parciales = [];
    const filas = turno.coberturaDatos
      .map((c) => {
        const indexado = c.seccionesTotales > 0;
        const titulo = escaparHtml(c.titulo || c.uri || "#" + c.documentoId);
        if (!indexado) {
          return `<li class="no-indexado"><span class="numero"></span><span class="titulo-documento">${titulo}</span>` +
            '<span class="insignia-cobertura no-indexado">no indexado</span></li>';
        }
        n++;
        const uri = c.uri || "";
        const enlace = esUriDelVault(uri)
          ? `<button type="button" class="enlace-cita titulo-documento" data-uri="${escaparHtml(uri)}" data-titulo="${titulo}">${titulo}</button>`
          : (uri
            ? `<a class="titulo-documento" href="${escaparHtml(uri)}" target="_blank" rel="noopener">${titulo}</a>`
            : `<span class="titulo-documento">${titulo}</span>`);
        const completa = c.seccionesIncluidas >= c.seccionesTotales && !c.primeraRecortada;
        let insignia;
        if (completa) {
          insignia = `<span class="insignia-cobertura completa">${c.seccionesTotales}/${c.seccionesTotales} secciones</span>`;
        } else if (c.primeraRecortada) {
          insignia = `<span class="insignia-cobertura parcial">comienzo de la sección 1 de ${c.seccionesTotales} · parcial</span>`;
        } else {
          insignia = `<span class="insignia-cobertura parcial">primeras ${c.seccionesIncluidas} de ${c.seccionesTotales} secciones · parcial</span>`;
        }
        if (!completa) {
          parciales.push(c);
        }
        return `<li><span class="numero">[${n}]</span>${enlace}${insignia}</li>`;
      })
      .join("");
    const aviso = parciales.length
      ? `<p class="aviso-parcial">${parciales.length} de ${turno.coberturaDatos.length} ` +
        (parciales.length === 1
          ? "documentos entró solo en parte: es más largo de lo que cabe en una sola lectura del modelo. Selecciónalo solo para cubrirlo entero."
          : "documentos entraron solo en parte: son más largos de lo que cabe en una sola lectura del modelo. Selecciónalos de a uno para cubrirlos enteros.") +
        "</p>"
      : "";
    turno.cobertura.innerHTML = "<h3>Documentos usados</h3><ol>" + filas + "</ol>" + aviso;
    turno.cobertura.classList.remove("oculto");
  }

  /**
   * Preguntas e ideas llegan de golpe como JSON (decision 11 del issue #38): la
   * UI pinta desde datos, asi el boton "Preguntar" por pregunta y las tarjetas
   * por idea salen exactas en vez de depender de que un modelo chico respete un
   * formato de listas.
   */
  function renderEstructurado(turno, tipo, datos) {
    turno.resultadoDatos = datos || null;
    if (!turno.estructurado) {
      return;
    }
    if (!datos || datos.mensaje) {
      turno.estructurado.innerHTML = "";
      turno.estructurado.classList.add("oculto");
      if (datos && datos.mensaje) {
        turno.respuesta.textContent = datos.mensaje;
      }
      return;
    }
    let html = "";
    if (tipo === "preguntas") {
      const temas = datos.temas || [];
      html = temas.length
        ? temas.map((t) =>
            `<section class="tema"><h4>${escaparHtml(t.tema || "")}</h4><ul>` +
            (t.preguntas || []).map((p) =>
              `<li><span class="pregunta-texto">${escaparHtml(p.texto || "")} <span class="cita-n">[${Number(p.fuente) || "?"}]</span></span>` +
              `<button type="button" class="boton-preguntar" data-pregunta="${escaparHtml(p.texto || "")}">Preguntar</button></li>`).join("") +
            "</ul></section>").join("")
        : '<p class="sin-resultado">El modelo no devolvió preguntas válidas. Vuelve a intentarlo.</p>';
    } else {
      const ideas = datos.ideas || [];
      html = ideas.length
        ? '<div class="grilla-ideas">' + ideas.map((i) =>
            `<article class="idea"><h4>${escaparHtml(i.titulo || "")}</h4>` +
            `<p>${escaparHtml(i.justificacion || "")} <span class="cita-n">[${Number(i.fuente) || "?"}]</span></p>` +
            botonCopiar(escaparHtml((i.titulo || "") + ": " + (i.justificacion || "")), "Copiar idea") +
            "</article>").join("") + "</div>"
        : '<p class="sin-resultado">El modelo no devolvió ideas válidas. Vuelve a intentarlo.</p>';
    }
    turno.estructurado.innerHTML = html;
    turno.estructurado.classList.remove("oculto");
  }

  // "Preguntar" solo rellena la barra de entrada: es el unico puente entre las
  // acciones y el chat, y vive solo en la UI (supuesto 8 del issue #38).
  historial.addEventListener("click", (evento) => {
    const preguntar = evento.target.closest(".boton-preguntar");
    if (!preguntar) {
      return;
    }
    campoPregunta.value = preguntar.dataset.pregunta || "";
    campoPregunta.focus();
  });

  // ---------- Traducir documentos completos, bloque a bloque ----------

  registrarAccion("traducir", traducirDocumentos);

  function nombreIdioma(codigo) {
    return window.kbIdiomas ? window.kbIdiomas.nombreDe(codigo) : (codigo || "");
  }

  async function traducirDocumentos() {
    const documentos = documentosSeleccionadosParaAccion();
    if (!documentos) {
      return;
    }
    const idiomas = selectorIdiomasDocumentos ? selectorIdiomasDocumentos.valores() : { origen: null, destino: "en" };
    guardarPreferencia("kb.acciones.destino", idiomas.destino);
    if (window.kbIdiomas) {
      window.kbIdiomas.recordar(idiomas.destino);
    }
    const proyecto = campoProyecto.value.trim() || "default";
    const etiqueta = etiquetaProvisional("Traducción de", documentos);
    if (conversacionActualId == null) {
      try {
        conversacionActualId = await kbHistorialDb.crearConversacion(etiqueta, documentosActivosNormalizados());
        await cargarListaConversaciones();
      } catch (error) {
        // Sin IndexedDB: se traduce igual, solo no persiste.
      }
    }
    const conversacionId = conversacionActualId;
    fijarBotonEnviar(true);
    const turno = nuevoTurno(etiqueta, { tipo: "traduccion-documentos" });
    turno.documentosDatos = documentos;
    // Todo lo que hace falta para pintar (y repintar tras un reload) la traduccion:
    // idiomas pedidos, estado por documento y el texto traducido por documento.
    turno.resultadoDatos = { origen: idiomas.origen, destino: idiomas.destino, documentos: [], estados: {}, textos: {} };
    const inicioTurno = Date.now();
    const detenerContador = iniciarContador(
      turno.estado,
      "Traduciendo " + documentos.length + (documentos.length === 1 ? " documento" : " documentos") + " al " + nombreIdioma(idiomas.destino).toLowerCase());
    iniciarStreamingTraduccion(documentos, idiomas, proyecto, turno, detenerContador, conversacionId, inicioTurno);
  }

  function iniciarStreamingTraduccion(documentos, idiomas, proyecto, turno, detenerContador, conversacionId, inicioTurno) {
    const url = "/api/acciones/traducir-documentos?documentos=" + documentos.join(",") +
      "&projectId=" + encodeURIComponent(proyecto) +
      "&origen=" + encodeURIComponent(idiomas.origen || "auto") +
      "&destino=" + encodeURIComponent(idiomas.destino);
    let etiqueta = turno.textoBurbuja.textContent;
    const datos = turno.resultadoDatos;
    const fuente = new EventSource(url);
    streamsActivos.set(conversacionId, { fuente: fuente, turno: turno, detenerContador: detenerContador });
    actualizarControlAcciones();

    fuente.addEventListener("etiqueta", (evento) => {
      etiqueta = JSON.parse(evento.data);
      turno.textoBurbuja.textContent = etiqueta;
    });
    fuente.addEventListener("documentos", (evento) => {
      datos.documentos = JSON.parse(evento.data);
      datos.documentos.forEach((d) => {
        datos.estados[d.documentoId] = { bloquesTotales: d.bloquesTotales, bloqueActual: 0, idioma: idiomas.origen, omitido: false };
      });
      renderTraduccion(turno);
    });
    fuente.addEventListener("idioma-detectado", (evento) => {
      const e = JSON.parse(evento.data);
      estadoDe(datos, e.documentoId).idioma = e.codigo;
      renderTraduccion(turno);
    });
    fuente.addEventListener("documento-omitido", (evento) => {
      const e = JSON.parse(evento.data);
      const estado = estadoDe(datos, e.documentoId);
      estado.omitido = true;
      estado.idioma = e.codigo;
      renderTraduccion(turno);
    });
    fuente.addEventListener("progreso", (evento) => {
      const e = JSON.parse(evento.data);
      const estado = estadoDe(datos, e.documentoId);
      estado.bloqueActual = e.bloqueActual;
      estado.bloquesTotales = e.bloquesTotales;
      renderTraduccion(turno);
    });
    fuente.addEventListener("texto", (evento) => {
      const e = JSON.parse(evento.data);
      datos.textos[e.documentoId] = (datos.textos[e.documentoId] || "") + e.fragmento;
      renderTextoTraducido(turno, e.documentoId);
    });
    fuente.addEventListener("fin", () => {
      const duracionMs = Date.now() - inicioTurno;
      cerrarStreaming(conversacionId, turno, detenerContador, duracionMs);
      renderTraduccion(turno, true);
      guardarTurno(etiqueta, proyecto, turno, false, null, conversacionId, duracionMs);
    });
    fuente.addEventListener("error-servidor", (evento) => {
      detenerContador();
      turno.estado.textContent = JSON.parse(evento.data);
      turno.estado.classList.add("error");
      cerrarStreaming(conversacionId, turno, detenerContador);
      guardarTurno(etiqueta, proyecto, turno, true, turno.estado.textContent, conversacionId);
    });
    fuente.onerror = () => {
      detenerContador();
      turno.estado.textContent = "No se pudo completar la traducción (¿Ollama no responde?).";
      turno.estado.classList.add("error");
      cerrarStreaming(conversacionId, turno, detenerContador);
      guardarTurno(etiqueta, proyecto, turno, true, turno.estado.textContent, conversacionId);
    };
  }

  function estadoDe(datos, documentoId) {
    if (!datos.estados[documentoId]) {
      datos.estados[documentoId] = { bloquesTotales: 0, bloqueActual: 0, idioma: null, omitido: false };
    }
    return datos.estados[documentoId];
  }

  /**
   * Una fila por documento (origen detectado, progreso por bloque, omitido o
   * "Listo · Descargar .md") y, debajo, el texto traducido con un encabezado por
   * documento que pone la UI: el nucleo emite solo la traduccion (hallazgo 25).
   * `terminado` = ya llego "fin": los que no se omitieron quedan listos.
   */
  function renderTraduccion(turno, terminado) {
    const datos = turno.resultadoDatos;
    if (!turno.traduccion || !datos) {
      return;
    }
    const encabezado =
      '<p class="traduccion-idiomas">' +
      escaparHtml(datos.origen ? nombreIdioma(datos.origen) : "Detectar el origen") +
      " → " + escaparHtml(nombreIdioma(datos.destino)) + "</p>";
    const filas = (datos.documentos || [])
      .map((d) => {
        const estado = estadoDe(datos, d.documentoId);
        const titulo = escaparHtml(d.titulo || "#" + d.documentoId);
        let detalle;
        let clase = "";
        if (!d.bloquesTotales) {
          detalle = "no indexado";
          clase = "no-indexado";
        } else if (estado.omitido) {
          detalle = "ya está en " + escaparHtml(nombreIdioma(estado.idioma).toLowerCase()) + ", se omitió";
          clase = "omitido";
        } else if (terminado || (datos.textos[d.documentoId] && estado.bloqueActual >= estado.bloquesTotales && turno.estado.classList.contains("completado"))) {
          detalle = "Listo";
          clase = "listo";
        } else if (estado.bloqueActual > 0) {
          detalle = "bloque " + estado.bloqueActual + " de " + estado.bloquesTotales;
          clase = "en-curso";
        } else {
          detalle = "en espera";
        }
        const origen = estado.idioma
          ? `<span class="origen-detectado">origen: ${escaparHtml(nombreIdioma(estado.idioma).toLowerCase())}</span>`
          : "";
        const descarga = clase === "listo"
          ? ` · <a class="enlace-descarga" href="${urlDescargaMarkdown(d, datos)}" download="${escaparHtml(nombreArchivoMarkdown(d, datos))}">Descargar .md</a>`
          : "";
        return `<li class="${clase}"><span class="titulo-documento">${titulo}</span>${origen}` +
          `<span class="estado-traduccion">${detalle}${descarga}</span></li>`;
      })
      .join("");
    let textos = turno.traduccion.querySelector(".textos-traducidos");
    const textosHtml = textos ? textos.innerHTML : "";
    turno.traduccion.innerHTML =
      encabezado + '<ol class="filas-traduccion">' + filas + "</ol>" +
      '<div class="textos-traducidos">' + textosHtml + "</div>";
    turno.traduccion.classList.remove("oculto");
    Object.keys(datos.textos).forEach((id) => renderTextoTraducido(turno, Number(id)));
  }

  function renderTextoTraducido(turno, documentoId) {
    const datos = turno.resultadoDatos;
    const contenedor = turno.traduccion && turno.traduccion.querySelector(".textos-traducidos");
    if (!contenedor) {
      return;
    }
    let seccion = contenedor.querySelector(`[data-documento="${documentoId}"]`);
    if (!seccion) {
      const documento = (datos.documentos || []).find((d) => d.documentoId === documentoId) || {};
      seccion = document.createElement("section");
      seccion.dataset.documento = String(documentoId);
      seccion.innerHTML = `<h4>${escaparHtml(documento.titulo || "#" + documentoId)}</h4><div class="texto-traducido"></div>`;
      contenedor.appendChild(seccion);
    }
    seccion.querySelector(".texto-traducido").textContent = datos.textos[documentoId] || "";
  }

  // El .md se arma en el navegador con lo que llego (fuera de alcance guardarlo en
  // el servidor): encabezado con el titulo, y el texto tal cual lo tradujo el modelo.
  function nombreArchivoMarkdown(documento, datos) {
    const base = String(documento.titulo || "documento").replace(/\.[a-z0-9]+$/i, "").replace(/[^\w.-]+/g, "-");
    return base + "." + datos.destino + ".md";
  }
  function urlDescargaMarkdown(documento, datos) {
    const contenido = "# " + (documento.titulo || "") + "\n\n" + (datos.textos[documento.documentoId] || "");
    return "data:text/markdown;charset=utf-8," + encodeURIComponent(contenido);
  }

  /** Repinta un turno de traduccion guardado: filas, textos y enlaces de descarga. */
  function pintarTurnoDeTraduccionGuardado(turno, registro) {
    if (turno.tipo !== "traduccion-documentos" || !registro.resultado) {
      return;
    }
    turno.resultadoDatos = registro.resultado;
    turno.documentosDatos = registro.documentos || null;
    if (!registro.error) {
      turno.estado.classList.add("completado");
    }
    renderTraduccion(turno, !registro.error);
  }

  function cerrarStreaming(conversacionId, turno, detenerContador, duracionMs) {
    // Sin este close() explicito, EventSource interpreta el fin normal del
    // stream como una desconexion y reintenta solo contra la misma URL --
    // reabriendo la pregunta ya completa una y otra vez.
    const activo = streamsActivos.get(conversacionId);
    if (activo) {
      activo.fuente.close();
    }
    streamsActivos.delete(conversacionId);
    detenerContador();
    // Solo toca el boton si el usuario sigue mirando esta conversacion: si ya
    // se fue a otra, el estado del boton depende de ESA, no de la que termino.
    if (conversacionActualId === conversacionId) {
      fijarBotonEnviar(false);
    }
    if (duracionMs != null) {
      // "Respondido en" para el chat; los turnos de accion dicen "Listo en".
      turno.estado.textContent =
        (turno.tipo === "pregunta" ? "Respondido en " : "Listo en ") + formatearDuracion(duracionMs);
      turno.estado.classList.add("completado");
    } else if (turno.estado.textContent === "Redactando la respuesta…") {
      turno.estado.textContent = "";
    }
  }

  /**
   * Devuelve el id del registro (o null sin IndexedDB) y lo deja en
   * turno.registroId, para que "Traducir" sobre un turno pueda actualizarlo.
   */
  async function guardarTurno(pregunta, proyecto, turno, huboError, estadoError, conversacionId, duracionMs) {
    try {
      const id = await kbHistorialDb.guardarTurno(conversacionId, {
        tipo: turno.tipo,
        pregunta: pregunta,
        proyecto: proyecto,
        reformulacion: turno.reformulacionTexto,
        respuesta: turno.respuesta.textContent,
        previa: turno.previaDatos,
        citas: turno.citasDatos,
        cobertura: turno.coberturaDatos,
        resultado: turno.resultadoDatos,
        documentos: turno.documentosDatos,
        error: huboError,
        estadoError: estadoError,
        duracionMs: duracionMs == null ? null : duracionMs,
        fecha: new Date().toISOString(),
      });
      turno.registroId = id;
      // Bump-ea la conversacion al tope de la lista (y le saca el "⋯" de "generando"),
      // sin importar si es la que el usuario esta mirando ahora mismo.
      await cargarListaConversaciones();
    } catch (error) {
      // Sin IndexedDB (navegador viejo, modo privado estricto): el turno
      // queda visible en esta sesion igual, solo no sobrevive a un refresh.
    }
  }
})();
