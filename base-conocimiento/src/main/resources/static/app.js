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

  // Orden importa: cargarHistorialGuardado() abre la conversacion mas reciente
  // y reconcilia su seleccion de documentos guardada contra documentosDisponibles
  // -- si corriera antes de tener la lista, esa seleccion se pisaria con "todos".
  cargarProyectos()
    .then(cargarDocumentosDisponibles)
    .then(cargarHistorialGuardado);

  campoProyecto.addEventListener("change", cargarDocumentosDisponibles);

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
      boton.disabled = false;
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
    boton.disabled = !!streamsActivos.get(conversacionId);

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
      boton.disabled = false;
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
      boton.disabled = false;
    }
    cargarListaConversaciones();
  }

  function pintarTurnoGuardado(registro) {
    const turno = nuevoTurno(registro.pregunta);
    if (registro.reformulacion) {
      turno.reformulacion.textContent = "Buscando también como: “" + registro.reformulacion + "”";
    }
    turno.previa.innerHTML = registro.previa && registro.previa.length
      ? registro.previa.map((c) => itemCita(c, null)).join("")
      : "<li>Sin resultados rápidos.</li>";
    turno.citas.innerHTML = registro.citas && registro.citas.length
      ? registro.citas.map((c, i) => itemCita(c, i + 1)).join("")
      : "<li>Sin citas.</li>";
    turno.respuesta.textContent = registro.respuesta || "";
    if (registro.error) {
      turno.estado.textContent = registro.estadoError || "La respuesta quedó incompleta.";
      turno.estado.classList.add("error");
    } else if (registro.duracionMs != null) {
      turno.estado.textContent = "Respondido en " + formatearDuracion(registro.duracionMs);
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
  }

  async function alCambiarSeleccionDocumentos() {
    documentosActivosActuales = Array.from(listaDocumentos.querySelectorAll('input[type="checkbox"]:checked'))
        .map((casilla) => Number(casilla.dataset.id));
    if (contadorDocumentos) {
      contadorDocumentos.textContent = "(" + documentosActivosActuales.length + "/" + documentosDisponibles.length + ")";
    }
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
    const boton = evento.target.closest(".boton-copiar");
    if (!boton) {
      return;
    }
    try {
      await navigator.clipboard.writeText(boton.dataset.texto || "");
      const original = boton.textContent;
      boton.textContent = "✓";
      boton.disabled = true;
      setTimeout(() => {
        boton.textContent = original;
        boton.disabled = false;
      }, 1200);
    } catch (error) {
      // Sin permiso de portapapeles o navegador viejo: no hay mucho mas que
      // hacer aca, el usuario puede seguir seleccionando el texto a mano.
    }
  });

  function nuevoTurno(pregunta) {
    if (bienvenida) {
      bienvenida.classList.add("oculto");
    }
    const turno = document.createElement("div");
    turno.className = "turno";
    turno.innerHTML =
      '<div class="mensaje mensaje-usuario">' +
      botonCopiar(escaparHtml(pregunta), "Copiar pregunta") +
      `<div class="burbuja">${escaparHtml(pregunta)}</div>` +
      "</div>" +
      '<div class="mensaje mensaje-asistente">' +
      '<div class="avatar-asistente">KB</div>' +
      '<div class="contenido-asistente">' +
      '<p class="turno-reformulacion"></p>' +
      '<p class="turno-estado"></p>' +
      '<div class="turno-respuesta"></div>' +
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
      "</details>" +
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
      estado: turno.querySelector(".turno-estado"),
      reformulacion: turno.querySelector(".turno-reformulacion"),
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
      reformulacionTexto: null,
      queryLogId: null,
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

    boton.disabled = true;
    const turno = nuevoTurno(pregunta);
    const inicioTurno = Date.now();
    const detenerContador = iniciarContador(turno.estado, "Buscando y analizando tu pregunta");

    cargarVistaPrevia(pregunta, proyecto, turno, documentos);
    iniciarStreaming(pregunta, proyecto, turno, detenerContador, conversacionId, inicioTurno, documentos);
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

  function iniciarStreaming(pregunta, proyecto, turno, detenerContador, conversacionId, inicioTurno, documentos) {
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
    const fuente = new EventSource(url);
    streamsActivos.set(conversacionId, { fuente: fuente, turno: turno, detenerContador: detenerContador });

    // Solo llega si el Reformulador cambio el texto de busqueda (ver ChatController):
    // el vocabulario coloquial de la pregunta puede no coincidir con el termino formal
    // de la fuente (ej. "autoboxing" en la pregunta, "boxing conversion" en el corpus).
    fuente.addEventListener("reformulacion", (evento) => {
      const consulta = JSON.parse(evento.data);
      turno.reformulacionTexto = consulta;
      turno.reformulacion.textContent = "Buscando también como: “" + consulta + "”";
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
      boton.disabled = false;
    }
    if (turno.estado.textContent === "Redactando la respuesta…") {
      if (duracionMs != null) {
        turno.estado.textContent = "Respondido en " + formatearDuracion(duracionMs);
        turno.estado.classList.add("completado");
      } else {
        turno.estado.textContent = "";
      }
    }
  }

  async function guardarTurno(pregunta, proyecto, turno, huboError, estadoError, conversacionId, duracionMs) {
    try {
      await kbHistorialDb.guardarTurno(conversacionId, {
        pregunta: pregunta,
        proyecto: proyecto,
        reformulacion: turno.reformulacionTexto,
        respuesta: turno.respuesta.textContent,
        previa: turno.previaDatos,
        citas: turno.citasDatos,
        error: huboError,
        estadoError: estadoError,
        duracionMs: duracionMs == null ? null : duracionMs,
        fecha: new Date().toISOString(),
      });
      // Bump-ea la conversacion al tope de la lista (y le saca el "⋯" de "generando"),
      // sin importar si es la que el usuario esta mirando ahora mismo.
      await cargarListaConversaciones();
    } catch (error) {
      // Sin IndexedDB (navegador viejo, modo privado estricto): el turno
      // queda visible en esta sesion igual, solo no sobrevive a un refresh.
    }
  }
})();
