(function () {
  "use strict";

  const TOKEN_KEY = "kb_admin_token";
  const ETIQUETAS_TIPO = {
    local_docs: "Documentos locales",
    local_git: "Repositorios Git",
    teams_channel: "Canal de Teams",
    azure_devops: "Azure DevOps",
  };
  const ORDEN_TIPOS = ["local_docs", "local_git", "teams_channel", "azure_devops"];

  const PRIORIDAD_ESTADO = { error: 0, extrayendo: 1, procesando: 1, detectado: 2, listo: 3 };

  const campoToken = document.getElementById("campo-token");
  const divFuentes = document.getElementById("fuentes");
  const divCola = document.getElementById("cola");
  const cuerpoArchivosVault = document.getElementById("archivos-vault");
  const formularioCarga = document.getElementById("formulario-carga");
  const campoArchivo = document.getElementById("campo-archivo");
  const estadoCarga = document.getElementById("estado-carga");
  const botonCarga = formularioCarga.querySelector('button[type="submit"]');

  // Los DOS interruptores, no uno: el flag de la app y el modo del bind mount del vault.
  // Con solo el primero, el contenedor no puede escribir y la carga falla con un error de
  // E/S que desde aca se ve igual de opaco que un 403 sin explicacion.
  const COMO_HABILITAR_LA_CARGA =
    "Pon KB_INGESTA_CARGA_HABILITADA=true y KB_VAULT_MODO=rw en tu archivo .env y vuelve " +
    "a levantar (make down && make up).";

  // null hasta que /api/admin/ayuda responda: mientras tanto no se asume nada, para no
  // deshabilitar los controles por un error de red pasajero.
  let cargaHabilitada = null;

  function aplicarEstadoDeCarga() {
    const apagada = cargaHabilitada === false;
    campoArchivo.disabled = apagada;
    botonCarga.disabled = apagada;
    if (apagada) {
      estadoCarga.textContent = "Carga deshabilitada en el servidor. " + COMO_HABILITAR_LA_CARGA;
    }
  }

  // El servidor ya publicaba este estado (ayuda.js lo usa para el texto del boton `?`),
  // pero la consola ofrecia el formulario igual: el usuario elegia un archivo, apretaba
  // Subir y recien ahi se enteraba, con un 403 que ademas nombraba un solo interruptor.
  async function cargarAyuda() {
    try {
      const respuesta = await pedir("/api/admin/ayuda");
      const ayuda = await respuesta.json();
      cargaHabilitada = ayuda.cargaHabilitada === true;
    } catch (error) {
      cargaHabilitada = null;
    }
    aplicarEstadoDeCarga();
    await cargarArchivosVault();
  }

  campoToken.value = sessionStorage.getItem(TOKEN_KEY) || "";
  campoToken.addEventListener("change", () => {
    sessionStorage.setItem(TOKEN_KEY, campoToken.value.trim());
  });

  function cabeceras() {
    const token = campoToken.value.trim();
    return token ? { Authorization: "Bearer " + token } : {};
  }

  function escaparHtml(texto) {
    const contenedor = document.createElement("div");
    contenedor.textContent = texto == null ? "" : texto;
    return contenedor.innerHTML;
  }

  async function pedir(url, opciones) {
    const respuesta = await fetch(url, Object.assign({}, opciones, { headers: cabeceras() }));
    if (respuesta.status === 401) {
      throw new Error("Token de API invalido o faltante. Complétalo arriba y vuelve a intentar.");
    }
    if (!respuesta.ok) {
      const texto = await respuesta.text().catch(() => "");
      throw new Error(texto || "HTTP " + respuesta.status);
    }
    return respuesta;
  }

  function formatearFecha(iso) {
    if (!iso) {
      return "nunca";
    }
    return new Date(iso).toLocaleString();
  }

  function renderUltimoResultado(ultimo) {
    if (!ultimo) {
      return '<p class="ultimo-resultado sin-datos">Sin datos del último relevo todavía.</p>';
    }
    if (ultimo.ejecutado) {
      return '<p class="ultimo-resultado ok">Último relevo OK: ' + escaparHtml(JSON.stringify(ultimo.resumen)) + "</p>";
    }
    return '<p class="ultimo-resultado error">Último relevo falló: ' + escaparHtml(ultimo.error) + "</p>";
  }

  function renderGrupo(tipo, fuentesDelTipo) {
    const filas = fuentesDelTipo
      .map(
        (f) =>
          "<tr><td>" + escaparHtml(f.fuente.name) + "</td><td>" + escaparHtml(f.fuente.projectId) + "</td>" +
          "<td>" + (f.fuente.enabled ? "sí" : "no") + "</td>" +
          "<td>" + formatearFecha(f.fuente.lastSyncedAt) + "</td>" +
          "<td>" + f.fuente.documentos + "</td><td>" + f.fuente.chunks + "</td></tr>"
      )
      .join("");
    const ultimo = fuentesDelTipo.length ? fuentesDelTipo[0].ultimoRelevo : null;
    return (
      '<div class="grupo-fuente">' +
      '<div class="grupo-fuente-encabezado">' +
      "<h3>" + (ETIQUETAS_TIPO[tipo] || tipo) + "</h3>" +
      '<button type="button" data-tipo="' + tipo + '" class="boton-reindexar">Reindexar ahora</button>' +
      "</div>" +
      renderUltimoResultado(ultimo) +
      (filas
        ? "<table><thead><tr><th>Fuente</th><th>Proyecto</th><th>Habilitada</th>" +
          "<th>Último relevo</th><th>Documentos</th><th>Fragmentos</th></tr></thead>" +
          "<tbody>" + filas + "</tbody></table>"
        : "<p>Todavía no hay ninguna fuente de este tipo indexada.</p>") +
      "</div>"
    );
  }

  async function cargarFuentes() {
    try {
      const respuesta = await pedir("/api/admin/fuentes");
      const fuentes = await respuesta.json();
      const porTipo = new Map();
      for (const f of fuentes) {
        const lista = porTipo.get(f.fuente.kind) || [];
        lista.push(f);
        porTipo.set(f.fuente.kind, lista);
      }
      const tipos = ORDEN_TIPOS.filter((t) => porTipo.has(t)).concat(
        [...porTipo.keys()].filter((t) => !ORDEN_TIPOS.includes(t))
      );
      divFuentes.innerHTML = tipos.length
        ? tipos.map((t) => renderGrupo(t, porTipo.get(t))).join("")
        : ORDEN_TIPOS.map((t) => renderGrupo(t, [])).join("");
      divFuentes.querySelectorAll(".boton-reindexar").forEach((boton) => {
        boton.addEventListener("click", () => reindexar(boton.dataset.tipo, boton));
      });
    } catch (error) {
      divFuentes.innerHTML = "<p>" + escaparHtml(error.message) + "</p>";
    }
  }

  async function reindexar(tipo, boton) {
    boton.disabled = true;
    const textoOriginal = boton.textContent;
    boton.textContent = "Reindexando…";
    try {
      await pedir("/api/admin/fuentes/" + encodeURIComponent(tipo) + "/reindexar", { method: "POST" });
      await Promise.all([cargarFuentes(), cargarCola()]);
    } catch (error) {
      divFuentes.insertAdjacentHTML("afterbegin", "<p>" + escaparHtml(error.message) + "</p>");
    } finally {
      boton.disabled = false;
      boton.textContent = textoOriginal;
    }
  }

  async function cargarCola() {
    try {
      const respuesta = await pedir("/api/admin/cola");
      const conteos = await respuesta.json();
      const porEstado = Object.fromEntries(conteos.map((c) => [c.estado, c.total]));
      const estados = ["pending", "running", "done", "failed"];
      divCola.innerHTML = estados
        .map(
          (estado) =>
            '<div><div class="numero">' + (porEstado[estado] || 0) + '</div><div class="etiqueta">' +
            estado + "</div></div>"
        )
        .join("");
    } catch (error) {
      divCola.innerHTML = "<p>" + escaparHtml(error.message) + "</p>";
    }
  }

  function claseBadge(estado) {
    if (estado === "error") return "estado-error";
    if (estado.startsWith("embebiendo")) return "estado-embebiendo";
    if (estado === "listo") return "estado-listo";
    return "estado-" + estado;
  }

  function prioridadDe(estado) {
    if (estado.startsWith("embebiendo")) return 1;
    return PRIORIDAD_ESTADO[estado] ?? 2;
  }

  function filaArchivoVault(a) {
    const enError = a.estado === "error";
    const botonReintentar = enError
      ? '<button type="button" class="boton-reintentar-archivo" data-tipo="' + escaparHtml(a.kind) + '">Reintentar</button>'
      : "";
    // Reindexar es "reingesta toda la fuente"; eliminar es puntual y solo tiene
    // sentido para local_docs, donde hay un archivo real que borrar del vault
    // (ver el javadoc de AdminController.eliminarArchivo sobre por que).
    const botonEliminar = a.kind === "local_docs" && cargaHabilitada !== false
      ? '<button type="button" class="boton-eliminar-archivo" data-id="' + a.id + '">Eliminar</button>'
      : "";
    const detalleError = enError && a.lastError
      ? '<div style="font-size:0.78rem;">' + escaparHtml(a.lastError) + "</div>"
      : "";
    return (
      '<tr class="' + (enError ? "fila-archivo-error" : "") + '">' +
      '<td class="col-archivo">' + escaparHtml(a.externalId) + "</td>" +
      "<td>" + escaparHtml(ETIQUETAS_TIPO[a.kind] || a.kind) + " / " + escaparHtml(a.fuenteNombre) + "</td>" +
      '<td><span class="estado-badge ' + claseBadge(a.estado) + '">' + escaparHtml(a.estado) + "</span>" + detalleError + "</td>" +
      "<td>" + formatearFecha(a.actualizadoEn) + "</td>" +
      '<td class="col-acciones">' + botonReintentar + botonEliminar + "</td>" +
      "</tr>"
    );
  }

  function renderArchivosVault(archivos) {
    const ordenados = archivos.slice().sort((a, b) => {
      const diferencia = prioridadDe(a.estado) - prioridadDe(b.estado);
      return diferencia !== 0 ? diferencia : new Date(b.actualizadoEn) - new Date(a.actualizadoEn);
    });
    cuerpoArchivosVault.innerHTML = ordenados.length
      ? ordenados.map(filaArchivoVault).join("")
      : '<tr><td colspan="5">Todavía no se detectó ningún archivo.</td></tr>';
    cuerpoArchivosVault.querySelectorAll(".boton-reintentar-archivo").forEach((boton) => {
      boton.addEventListener("click", () => reintentarArchivo(boton.dataset.tipo, boton));
    });
    cuerpoArchivosVault.querySelectorAll(".boton-eliminar-archivo").forEach((boton) => {
      boton.addEventListener("click", () => eliminarArchivo(boton.dataset.id, boton));
    });
  }

  async function cargarArchivosVault() {
    try {
      const respuesta = await pedir("/api/admin/vault/archivos");
      renderArchivosVault(await respuesta.json());
    } catch (error) {
      cuerpoArchivosVault.innerHTML = '<tr><td colspan="5">' + escaparHtml(error.message) + "</td></tr>";
    }
  }

  async function reintentarArchivo(tipo, boton) {
    boton.disabled = true;
    try {
      await pedir("/api/admin/fuentes/" + encodeURIComponent(tipo) + "/reindexar", { method: "POST" });
      await Promise.all([cargarArchivosVault(), cargarFuentes(), cargarCola()]);
    } catch (error) {
      boton.disabled = false;
    }
  }

  async function eliminarArchivo(id, boton) {
    if (!confirm("¿Eliminar este archivo del índice? Borra también el archivo del vault y no se puede deshacer.")) {
      return;
    }
    boton.disabled = true;
    try {
      await pedir("/api/admin/vault/archivos/" + encodeURIComponent(id), { method: "DELETE" });
      await Promise.all([cargarArchivosVault(), cargarFuentes(), cargarCola()]);
    } catch (error) {
      boton.disabled = false;
      alert(error.message);
    }
  }

  formularioCarga.addEventListener("submit", async (evento) => {
    evento.preventDefault();
    const archivo = campoArchivo.files[0];
    if (!archivo) {
      return;
    }
    estadoCarga.textContent = "Subiendo…";
    const datos = new FormData();
    datos.append("archivo", archivo);
    try {
      const respuesta = await pedir("/api/admin/vault/documentos", { method: "POST", body: datos });
      estadoCarga.textContent = await respuesta.text();
      campoArchivo.value = "";
      await Promise.all([cargarFuentes(), cargarArchivosVault()]);
    } catch (error) {
      estadoCarga.textContent = error.message;
    }
  });

  // cargarAyuda() encadena cargarArchivosVault(): la tabla depende de si el borrado esta
  // habilitado, asi que no se pinta antes de saberlo.
  cargarAyuda();
  cargarFuentes();
  cargarCola();
  // Estilo Job Runner: la tabla se refresca sola mientras la ingesta avanza,
  // sin que el usuario tenga que recargar la página a mano.
  setInterval(() => {
    cargarCola();
    cargarArchivosVault();
  }, 3000);
})();
