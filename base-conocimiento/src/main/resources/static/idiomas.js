(function () {
  "use strict";

  // El unico componente de idiomas de la pagina (issue #38): lo usan el menu de
  // acciones (idioma del resultado y Traducir documentos), el modo traducir de
  // la barra de entrada y el popover "Traducir" de cada turno. Codigos ISO 639-1
  // con el nombre en español; lo que el servidor detecte y no este aca se muestra
  // con su codigo crudo (ver nombreDe), nunca vacio.
  const LISTA = [
    { codigo: "es", nombre: "Español" },
    { codigo: "en", nombre: "Inglés" },
    { codigo: "pt", nombre: "Portugués" },
    { codigo: "fr", nombre: "Francés" },
    { codigo: "de", nombre: "Alemán" },
    { codigo: "it", nombre: "Italiano" },
    { codigo: "ca", nombre: "Catalán" },
    { codigo: "gl", nombre: "Gallego" },
    { codigo: "eu", nombre: "Euskera" },
    { codigo: "nl", nombre: "Neerlandés" },
    { codigo: "sv", nombre: "Sueco" },
    { codigo: "da", nombre: "Danés" },
    { codigo: "no", nombre: "Noruego" },
    { codigo: "fi", nombre: "Finés" },
    { codigo: "pl", nombre: "Polaco" },
    { codigo: "cs", nombre: "Checo" },
    { codigo: "ro", nombre: "Rumano" },
    { codigo: "hu", nombre: "Húngaro" },
    { codigo: "el", nombre: "Griego" },
    { codigo: "tr", nombre: "Turco" },
    { codigo: "ru", nombre: "Ruso" },
    { codigo: "uk", nombre: "Ucraniano" },
    { codigo: "ar", nombre: "Árabe" },
    { codigo: "he", nombre: "Hebreo" },
    { codigo: "hi", nombre: "Hindi" },
    { codigo: "zh", nombre: "Chino" },
    { codigo: "ja", nombre: "Japonés" },
    { codigo: "ko", nombre: "Coreano" },
    { codigo: "vi", nombre: "Vietnamita" },
    { codigo: "id", nombre: "Indonesio" },
    { codigo: "th", nombre: "Tailandés" },
  ];
  const AUTO = "auto";
  const CLAVE_RECIENTES = "kb.idiomas.recientes";
  const MAX_RECIENTES = 4;

  function nombreDe(codigo) {
    if (codigo === AUTO) {
      return "Detectar automáticamente";
    }
    if (codigo === "und") {
      return "idioma no determinado";
    }
    const idioma = LISTA.find((i) => i.codigo === codigo);
    return idioma ? idioma.nombre : (codigo || "");
  }

  // "Recientes" es una comodidad, no un requisito: sin localStorage (modo
  // privado estricto, navegador viejo) el selector funciona igual, solo sin
  // ese grupo. Mismo try/catch que el resto de la pagina para el almacenamiento.
  function recientes() {
    try {
      const guardados = JSON.parse(localStorage.getItem(CLAVE_RECIENTES) || "[]");
      return Array.isArray(guardados) ? guardados.filter((c) => LISTA.some((i) => i.codigo === c)) : [];
    } catch (error) {
      return [];
    }
  }

  function recordar(codigo) {
    if (!codigo || codigo === AUTO || codigo === "und") {
      return;
    }
    try {
      const lista = [codigo].concat(recientes().filter((c) => c !== codigo)).slice(0, MAX_RECIENTES);
      localStorage.setItem(CLAVE_RECIENTES, JSON.stringify(lista));
    } catch (error) {
      // Sin almacenamiento no hay recientes que recordar.
    }
  }

  function escapar(texto) {
    return String(texto).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  }

  function poblar(select, conAuto, actual) {
    let html = "";
    if (conAuto) {
      html += `<option value="${AUTO}">Detectar automáticamente</option>`;
    }
    const ultimos = recientes();
    if (ultimos.length) {
      html += '<optgroup label="Recientes">' +
        ultimos.map((c) => `<option value="${c}">${escapar(nombreDe(c))}</option>`).join("") +
        "</optgroup>";
    }
    html += '<optgroup label="Todos los idiomas">' +
      LISTA.map((i) => `<option value="${i.codigo}">${escapar(i.nombre)}</option>`).join("") +
      "</optgroup>";
    select.innerHTML = html;
    select.value = actual;
    if (select.value !== actual) {
      select.value = conAuto ? AUTO : LISTA[0].codigo;
    }
  }

  /**
   * config.soloDestino: solo el idioma de destino (el "idioma del resultado" del
   * menu de acciones). config.origen / config.destino: valores iniciales.
   * Devuelve { elemento, valores(), fijar({origen, destino}) }; valores().origen
   * es null cuando hay que detectar.
   */
  function crearSelector(config) {
    config = config || {};
    const soloDestino = !!config.soloDestino;
    const raiz = document.createElement("div");
    raiz.className = "selector-idiomas" + (soloDestino ? " solo-destino" : "");
    raiz.innerHTML =
      (soloDestino ? "" :
        '<label class="selector-campo"><span>Origen</span><select class="selector-origen"></select></label>' +
        '<button type="button" class="selector-intercambiar" title="Intercambiar origen y destino" aria-label="Intercambiar origen y destino">⇄</button>') +
      '<label class="selector-campo"><span>' + (soloDestino ? "Idioma del resultado" : "Destino") + '</span><select class="selector-destino"></select></label>';
    const origen = raiz.querySelector(".selector-origen");
    const destino = raiz.querySelector(".selector-destino");
    const intercambiar = raiz.querySelector(".selector-intercambiar");

    function fijar(valores) {
      valores = valores || {};
      if (origen) {
        poblar(origen, true, valores.origen || AUTO);
      }
      poblar(destino, false, valores.destino || config.destino || "es");
    }
    fijar({ origen: config.origen, destino: config.destino });

    if (intercambiar) {
      // Nunca deja "Detectar" como destino: con el origen en auto no hay nada que
      // intercambiar, y el boton lo dice deshabilitandose.
      const sincronizar = () => { intercambiar.disabled = origen.value === AUTO; };
      origen.addEventListener("change", sincronizar);
      sincronizar();
      intercambiar.addEventListener("click", () => {
        if (origen.value === AUTO) {
          return;
        }
        const anterior = origen.value;
        origen.value = destino.value;
        destino.value = anterior;
        sincronizar();
      });
    }

    return {
      elemento: raiz,
      valores: () => ({
        origen: origen && origen.value !== AUTO ? origen.value : null,
        destino: destino.value,
      }),
      fijar: fijar,
    };
  }

  window.kbIdiomas = { LISTA: LISTA, nombreDe: nombreDe, crearSelector: crearSelector, recordar: recordar };
})();
