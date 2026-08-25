(function () {
  "use strict";

  function escaparHtml(texto) {
    const contenedor = document.createElement("div");
    contenedor.textContent = texto == null ? "" : texto;
    return contenedor.innerHTML;
  }

  function formatearIntervalo(ms) {
    const segundos = Math.round(ms / 1000);
    if (segundos < 60) {
      return segundos + " s";
    }
    const minutos = Math.round(segundos / 60);
    if (minutos < 60) {
      return minutos + " min";
    }
    return Math.round(minutos / 60) + " h";
  }

  function render(ayuda) {
    const extensiones = ayuda.extensionesAceptadas.map((e) => "." + e).join(", ");
    const relevoTexto = ayuda.relevoHabilitado
      ? "cada " + formatearIntervalo(ayuda.relevoIntervaloMs)
      : "el relevo automático está deshabilitado (kb.ingesta.relevo.habilitado=false); usa <code>make ingest</code>";
    const cargaTexto = ayuda.cargaHabilitada
      ? "También puedes arrastrar un archivo desde la consola de administración."
      : "La carga desde el navegador está deshabilitada (kb.ingesta.carga-habilitada=false).";
    return (
      "<h2>Dónde poner tus archivos</h2>" +
      "<p>Todo vive en una sola carpeta fuera del repositorio, el <strong>vault</strong> " +
      "(<code>KB_VAULT_DIR</code> en tu <code>.env</code>), con dos subcarpetas fijas.</p>" +
      "<p><strong>Documentos</strong> (" + escaparHtml(extensiones) + "): cópialos a " +
      "<code>vault/documentos</code>. El servidor está leyendo ahora mismo: <code>" +
      escaparHtml(ayuda.documentosDir) + "</code>.</p>" +
      "<p><strong>Código</strong>: clona el repositorio dentro de <code>vault/repos</code>. " +
      "El servidor está leyendo ahora mismo: <code>" + escaparHtml(ayuda.reposDir) + "</code>.</p>" +
      "<p>No hace falta correr ningún comando: el sistema revisa esas carpetas " + relevoTexto + ". " +
      "Si borras un archivo, deja de aparecer en las respuestas en la siguiente revisión.</p>" +
      "<p>" + cargaTexto + "</p>"
    );
  }

  /**
   * Conecta un botón `?` con un `<dialog>` que explica dónde poner los
   * archivos, usando las rutas y valores reales que el servidor está leyendo
   * (GET /api/admin/ayuda) — no una convención escrita a mano en el HTML.
   * Compartido entre la página de chat y la consola de administración.
   */
  function inicializar(idBoton, idModal, idContenido) {
    const boton = document.getElementById(idBoton);
    const modal = document.getElementById(idModal);
    const contenido = document.getElementById(idContenido);
    if (!boton || !modal || !contenido) {
      return;
    }

    boton.addEventListener("click", async () => {
      modal.showModal();
      contenido.innerHTML = "<p>Cargando…</p>";
      try {
        const respuesta = await fetch("/api/admin/ayuda");
        if (!respuesta.ok) {
          throw new Error("HTTP " + respuesta.status);
        }
        const ayuda = await respuesta.json();
        contenido.innerHTML = render(ayuda);
      } catch (error) {
        contenido.innerHTML = "<p>No se pudo cargar la ayuda.</p>";
      }
    });

    modal.addEventListener("click", (evento) => {
      if (evento.target === modal) {
        modal.close();
      }
    });
  }

  window.kbAyuda = { inicializar: inicializar };
})();
