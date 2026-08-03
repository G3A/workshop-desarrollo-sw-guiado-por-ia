(function () {
  "use strict";

  const formulario = document.getElementById("formulario");
  const campoPregunta = document.getElementById("pregunta");
  const campoProyecto = document.getElementById("proyecto");
  const boton = document.getElementById("boton-preguntar");
  const historial = document.getElementById("historial");

  // Solo puede haber un stream activo a la vez: una pregunta nueva cierra el
  // EventSource anterior antes de abrir uno propio.
  let fuenteActual = null;

  cargarProyectos();

  formulario.addEventListener("submit", (evento) => {
    evento.preventDefault();
    const pregunta = campoPregunta.value.trim();
    if (!pregunta) {
      return;
    }
    const proyecto = campoProyecto.value.trim() || "default";
    preguntar(pregunta, proyecto);
    campoPregunta.value = "";
  });

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

  function escaparHtml(texto) {
    const contenedor = document.createElement("div");
    contenedor.textContent = texto == null ? "" : texto;
    return contenedor.innerHTML;
  }

  function itemCita(cita, indice) {
    const titulo = escaparHtml(cita.titulo || cita.uri);
    const uri = escaparHtml(cita.uri);
    const extracto = escaparHtml(cita.extracto || "");
    const prefijo = indice == null ? "" : `[${indice}] `;
    const lineaExtracto = extracto ? `<span class="extracto">${extracto}</span>` : "";
    return `<li>${prefijo}<a href="${uri}" target="_blank" rel="noopener">${titulo}</a>${lineaExtracto}</li>`;
  }

  function nuevoTurno(pregunta) {
    const turno = document.createElement("div");
    turno.className = "turno";
    turno.innerHTML =
      `<p class="turno-pregunta">${escaparHtml(pregunta)}</p>` +
      '<p class="turno-estado"></p>' +
      "<h3>Respuesta</h3>" +
      '<div class="turno-respuesta"></div>' +
      '<details class="turno-detalle">' +
      "<summary>Resultados rápidos</summary>" +
      '<ul class="turno-previa"></ul>' +
      "</details>" +
      '<details class="turno-detalle">' +
      "<summary>Citas</summary>" +
      '<ol class="turno-citas"></ol>' +
      "</details>";
    historial.appendChild(turno);
    turno.scrollIntoView({ behavior: "smooth", block: "start" });
    return {
      raiz: turno,
      estado: turno.querySelector(".turno-estado"),
      previa: turno.querySelector(".turno-previa"),
      respuesta: turno.querySelector(".turno-respuesta"),
      citas: turno.querySelector(".turno-citas"),
    };
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

  function preguntar(pregunta, proyecto) {
    if (fuenteActual) {
      fuenteActual.close();
      fuenteActual = null;
    }

    boton.disabled = true;
    const turno = nuevoTurno(pregunta);
    const detenerContador = iniciarContador(turno.estado, "Buscando y analizando tu pregunta");

    cargarVistaPrevia(pregunta, proyecto, turno);
    iniciarStreaming(pregunta, proyecto, turno, detenerContador);
  }

  async function cargarVistaPrevia(pregunta, proyecto, turno) {
    try {
      const respuesta = await fetch("/api/preview", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ q: pregunta, projectId: proyecto }),
      });
      if (!respuesta.ok) {
        throw new Error("HTTP " + respuesta.status);
      }
      const citas = await respuesta.json();
      turno.previa.innerHTML = citas.length
        ? citas.map((c) => itemCita(c, null)).join("")
        : "<li>Sin resultados rápidos.</li>";
    } catch (error) {
      turno.previa.innerHTML = "<li>No se pudo cargar la vista previa.</li>";
    }
  }

  function iniciarStreaming(pregunta, proyecto, turno, detenerContador) {
    const url = "/api/chat?q=" + encodeURIComponent(pregunta) + "&projectId=" + encodeURIComponent(proyecto);
    const fuente = new EventSource(url);
    fuenteActual = fuente;

    fuente.addEventListener("citas", (evento) => {
      const citas = JSON.parse(evento.data);
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
    // inicial incluido.
    fuente.addEventListener("token", (evento) => {
      turno.respuesta.textContent += JSON.parse(evento.data);
    });

    fuente.addEventListener("fin", () => {
      cerrarStreaming(turno, detenerContador);
    });

    fuente.onerror = () => {
      detenerContador();
      turno.estado.textContent = "Se perdió la conexión con el servidor (¿Ollama no responde?).";
      turno.estado.classList.add("error");
      cerrarStreaming(turno, detenerContador);
    };
  }

  function cerrarStreaming(turno, detenerContador) {
    if (fuenteActual) {
      fuenteActual.close();
      fuenteActual = null;
    }
    detenerContador();
    boton.disabled = false;
    if (turno.estado.textContent === "Redactando la respuesta…") {
      turno.estado.textContent = "";
    }
  }
})();
