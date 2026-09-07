(function () {
  "use strict";

  const NOMBRE_BD = "kb_historial";
  const VERSION_BD = 2;
  const TABLA_CONVERSACIONES = "conversaciones";
  const TABLA_TURNOS = "turnos";
  const LARGO_TITULO = 60;

  function tituloDesde(texto) {
    const limpio = (texto || "Conversación").trim() || "Conversación";
    return limpio.length > LARGO_TITULO ? limpio.slice(0, LARGO_TITULO).trimEnd() + "…" : limpio;
  }

  function abrir() {
    return new Promise((resolve, reject) => {
      if (!window.indexedDB) {
        reject(new Error("IndexedDB no disponible en este navegador."));
        return;
      }
      const solicitud = indexedDB.open(NOMBRE_BD, VERSION_BD);

      solicitud.onupgradeneeded = (evento) => {
        const bd = solicitud.result;
        const tx = evento.target.transaction;
        const turnosYaExistia = bd.objectStoreNames.contains(TABLA_TURNOS);

        if (!bd.objectStoreNames.contains(TABLA_CONVERSACIONES)) {
          bd.createObjectStore(TABLA_CONVERSACIONES, { keyPath: "id", autoIncrement: true });
        }

        const tablaTurnos = turnosYaExistia
          ? tx.objectStore(TABLA_TURNOS)
          : bd.createObjectStore(TABLA_TURNOS, { keyPath: "id", autoIncrement: true });
        if (!tablaTurnos.indexNames.contains("conversacionId")) {
          tablaTurnos.createIndex("conversacionId", "conversacionId", { unique: false });
        }

        // Migracion v1 -> v2: en la v1 todos los turnos eran un unico hilo
        // continuo, sin conversacionId. Se agrupan bajo una sola conversacion
        // "recuperada" para no perder lo que el usuario ya tenia guardado.
        if (turnosYaExistia && evento.oldVersion < 2) {
          const todos = tablaTurnos.getAll();
          todos.onsuccess = () => {
            const viejos = (todos.result || []).filter((t) => t.conversacionId == null);
            if (!viejos.length) {
              return;
            }
            const tablaConversaciones = tx.objectStore(TABLA_CONVERSACIONES);
            const nueva = tablaConversaciones.add({
              titulo: tituloDesde(viejos[0].pregunta),
              fecha: viejos[0].fecha || new Date().toISOString(),
              actualizadoEn: viejos[viejos.length - 1].fecha || viejos[0].fecha || new Date().toISOString(),
            });
            nueva.onsuccess = () => {
              const conversacionId = nueva.result;
              viejos.forEach((t) => {
                t.conversacionId = conversacionId;
                tablaTurnos.put(t);
              });
            };
          };
        }
      };

      solicitud.onsuccess = () => resolve(solicitud.result);
      solicitud.onerror = () => reject(solicitud.error);
    });
  }

  async function listarConversaciones() {
    const bd = await abrir();
    return new Promise((resolve, reject) => {
      const tx = bd.transaction(TABLA_CONVERSACIONES, "readonly");
      const solicitud = tx.objectStore(TABLA_CONVERSACIONES).getAll();
      solicitud.onsuccess = () => {
        const conversaciones = (solicitud.result || [])
          .sort((a, b) => new Date(b.actualizadoEn) - new Date(a.actualizadoEn));
        resolve(conversaciones);
      };
      solicitud.onerror = () => reject(solicitud.error);
    });
  }

  /**
   * @param documentosActivos IDs de documentos a los que acotar la búsqueda en
   *                          esta conversación (F11); vacío/omitido = todos.
   */
  async function crearConversacion(tituloInicial, documentosActivos) {
    const bd = await abrir();
    return new Promise((resolve, reject) => {
      const tx = bd.transaction(TABLA_CONVERSACIONES, "readwrite");
      const ahora = new Date().toISOString();
      const solicitud = tx.objectStore(TABLA_CONVERSACIONES).add({
        titulo: tituloDesde(tituloInicial),
        fecha: ahora,
        actualizadoEn: ahora,
        documentosActivos: documentosActivos || [],
      });
      solicitud.onsuccess = () => resolve(solicitud.result);
      tx.onerror = () => reject(tx.error);
    });
  }

  /** Vacío = sin restricción (todos los documentos activos), igual que en el backend. */
  async function actualizarDocumentosActivos(conversacionId, documentosActivos) {
    const bd = await abrir();
    return new Promise((resolve, reject) => {
      const tx = bd.transaction(TABLA_CONVERSACIONES, "readwrite");
      const store = tx.objectStore(TABLA_CONVERSACIONES);
      const solicitud = store.get(conversacionId);
      solicitud.onsuccess = () => {
        const conversacion = solicitud.result;
        if (conversacion) {
          conversacion.documentosActivos = documentosActivos;
          store.put(conversacion);
        }
      };
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
  }

  async function obtenerConversacion(conversacionId) {
    const bd = await abrir();
    return new Promise((resolve, reject) => {
      const tx = bd.transaction(TABLA_CONVERSACIONES, "readonly");
      const solicitud = tx.objectStore(TABLA_CONVERSACIONES).get(conversacionId);
      solicitud.onsuccess = () => resolve(solicitud.result || null);
      solicitud.onerror = () => reject(solicitud.error);
    });
  }

  async function listarTurnosDeConversacion(conversacionId) {
    const bd = await abrir();
    return new Promise((resolve, reject) => {
      const tx = bd.transaction(TABLA_TURNOS, "readonly");
      const solicitud = tx.objectStore(TABLA_TURNOS).index("conversacionId").getAll(conversacionId);
      solicitud.onsuccess = () => resolve((solicitud.result || []).sort((a, b) => a.id - b.id));
      solicitud.onerror = () => reject(solicitud.error);
    });
  }

  // Guarda un turno completo (pregunta + respuesta final) dentro de una
  // conversacion existente y le actualiza el "actualizadoEn" para que suba al
  // tope de la lista, igual que ChatGPT bump-ea la conversacion activa. No hay
  // guardado incremental token a token: un turno en curso se pierde si el
  // usuario refresca a mitad de una respuesta, pero todo lo ya cerrado persiste.
  async function guardarTurno(conversacionId, turno) {
    const bd = await abrir();
    return new Promise((resolve, reject) => {
      const tx = bd.transaction([TABLA_TURNOS, TABLA_CONVERSACIONES], "readwrite");
      tx.objectStore(TABLA_TURNOS).add(Object.assign({ conversacionId: conversacionId }, turno));
      const conversacionReq = tx.objectStore(TABLA_CONVERSACIONES).get(conversacionId);
      conversacionReq.onsuccess = () => {
        const conversacion = conversacionReq.result;
        if (conversacion) {
          conversacion.actualizadoEn = turno.fecha || new Date().toISOString();
          tx.objectStore(TABLA_CONVERSACIONES).put(conversacion);
        }
      };
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
  }

  async function eliminarConversacion(conversacionId) {
    const bd = await abrir();
    return new Promise((resolve, reject) => {
      const tx = bd.transaction([TABLA_TURNOS, TABLA_CONVERSACIONES], "readwrite");
      tx.objectStore(TABLA_CONVERSACIONES).delete(conversacionId);
      const cursorReq = tx.objectStore(TABLA_TURNOS).index("conversacionId")
        .openCursor(IDBKeyRange.only(conversacionId));
      cursorReq.onsuccess = (evento) => {
        const cursor = evento.target.result;
        if (cursor) {
          cursor.delete();
          cursor.continue();
        }
      };
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
  }

  window.kbHistorialDb = {
    listarConversaciones: listarConversaciones,
    crearConversacion: crearConversacion,
    obtenerConversacion: obtenerConversacion,
    listarTurnosDeConversacion: listarTurnosDeConversacion,
    guardarTurno: guardarTurno,
    eliminarConversacion: eliminarConversacion,
    actualizarDocumentosActivos: actualizarDocumentosActivos,
  };
})();
