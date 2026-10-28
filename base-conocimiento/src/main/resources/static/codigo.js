(function () {
  "use strict";

  // Bloques de codigo en las respuestas del chat (issue #54). Modulo puro, sin
  // DOM: parte el texto crudo del modelo en segmentos de texto y de codigo, y
  // reparte en lineas el HTML que devuelve highlight.js. app.js lo pinta; las
  // pruebas (src/test/js/codigo.test.js) lo cargan con require desde Node.
  //
  // Solo cercas de backticks al estilo CommonMark: tres o mas, con hasta tres
  // espacios de sangria, y un cierre con al menos tantos backticks como la
  // apertura. El resto del Markdown (~~~, codigo en linea, negritas) sigue como
  // texto: el issue pide bloques, no un renderizador de Markdown.
  const APERTURA = /^( {0,3})(`{3,})([^`]*)$/;
  const CIERRE = /^ {0,3}(`{3,})[ \t]*$/;
  // Lo que todavia puede terminar siendo una cerca: mientras llega el stream se
  // calla, para no mostrar "``" un token y retirarlo en el siguiente.
  const CERCA_A_MEDIAS = /^ {0,3}`{1,}[ \t]*$/;
  // El lenguaje termina en un atributo data-lenguaje y en el rotulo: solo pasa
  // lo que tiene forma de nombre de lenguaje (c++, c#, objective-c, vue.js).
  const LENGUAJE = /^[\w+#.-]+$/;

  /**
   * Devuelve [{tipo:"texto", texto} | {tipo:"codigo", lenguaje, codigo, cerrado}].
   * "terminado" dice que el turno ya no recibe mas tokens: entonces un cierre
   * sin salto final cierra, y un bloque que el modelo nunca cerro se da por
   * cerrado (muestra su boton de copiar en vez de quedar abierto para siempre).
   */
  function partir(texto, terminado) {
    const lineas = String(texto == null ? "" : texto).replace(/\r\n?/g, "\n").split("\n");
    const segmentos = [];
    let textoPendiente = [];
    let bloque = null;
    let despuesDeBloque = false;

    // Las lineas en blanco pegadas a un bloque se quitan de los dos lados: el
    // margen del bloque ya separa, y en pre-wrap una sobrante antes no se ve
    // pero una despues si (el texto quedaba mas lejos abajo que arriba).
    const soltarTexto = (antesDeBloque) => {
      while (despuesDeBloque && textoPendiente.length && textoPendiente[0] === "") {
        textoPendiente.shift();
      }
      while (antesDeBloque && textoPendiente.length && textoPendiente[textoPendiente.length - 1] === "") {
        textoPendiente.pop();
      }
      const unido = textoPendiente.join("\n");
      if (unido !== "") {
        segmentos.push({ tipo: "texto", texto: unido });
      }
      textoPendiente = [];
    };

    lineas.forEach((linea, i) => {
      // La ultima linea sin "\n" puede estar a medio llegar: "```" puede ser
      // el comienzo de "```js", y "```jav" todavia no es el lenguaje.
      const completa = i < lineas.length - 1 || terminado;
      if (bloque) {
        const cierre = CIERRE.exec(linea);
        if (cierre && cierre[1].length >= bloque.cerca && completa) {
          segmentos.push({ tipo: "codigo", lenguaje: bloque.lenguaje, codigo: bloque.lineas.join("\n"), cerrado: true });
          bloque = null;
          despuesDeBloque = true;
        } else if (!completa && CERCA_A_MEDIAS.test(linea)) {
          // Puede ser el cierre a medio llegar: se espera al siguiente token.
        } else {
          bloque.lineas.push(quitarSangria(linea, bloque.sangria));
        }
        return;
      }
      const apertura = APERTURA.exec(linea);
      if (apertura) {
        soltarTexto(true);
        const palabra = (apertura[3].trim().split(/\s+/)[0] || "").toLowerCase();
        bloque = {
          sangria: apertura[1].length,
          cerca: apertura[2].length,
          lenguaje: completa && LENGUAJE.test(palabra) ? palabra : "",
          lineas: [],
        };
        return;
      }
      if (!completa && CERCA_A_MEDIAS.test(linea)) {
        return;
      }
      textoPendiente.push(linea);
    });

    if (bloque) {
      segmentos.push({ tipo: "codigo", lenguaje: bloque.lenguaje, codigo: bloque.lineas.join("\n"), cerrado: !!terminado });
    }
    soltarTexto(false);
    return segmentos;
  }

  function quitarSangria(linea, sangria) {
    let i = 0;
    while (i < sangria && linea[i] === " ") {
      i++;
    }
    return linea.slice(i);
  }

  /**
   * Reparte en lineas el HTML de highlight.js (texto ya escapado, <span class>
   * y </span>). Un span puede cruzar lineas (un comentario de bloque, un string
   * multilinea): se cierra al final de cada linea y se reabre al comienzo de la
   * siguiente, asi cada linea es HTML balanceado por si sola y se puede
   * envolver en su propio elemento para numerarla.
   */
  function dividirEnLineas(html) {
    const lineas = [];
    const abiertos = [];
    let actual = "";
    const piezas = String(html).match(/<span\b[^>]*>|<\/span>|\n|[^<\n]+|</g) || [];
    piezas.forEach((pieza) => {
      if (pieza === "\n") {
        lineas.push(actual + "</span>".repeat(abiertos.length));
        actual = abiertos.join("");
      } else if (pieza === "</span>") {
        abiertos.pop();
        actual += pieza;
      } else {
        if (pieza.startsWith("<span")) {
          abiertos.push(pieza);
        }
        actual += pieza;
      }
    });
    lineas.push(actual);
    return lineas;
  }

  function mismoSegmento(a, b) {
    return a.tipo === b.tipo && a.texto === b.texto && a.lenguaje === b.lenguaje &&
        a.codigo === b.codigo && a.cerrado === b.cerrado;
  }

  /**
   * El primer indice en el que "anteriores" y "nuevos" difieren, o el largo de
   * "anteriores" si "nuevos" solo agrega al final. Lo de antes de ese indice ya
   * esta pintado tal cual y no se toca: un token nuevo solo cambia el ultimo
   * segmento, y repintar todo borraria la seleccion y el scroll de los bloques.
   */
  function primerSegmentoDistinto(anteriores, nuevos) {
    const limite = Math.min(anteriores.length, nuevos.length);
    for (let i = 0; i < limite; i++) {
      if (!mismoSegmento(anteriores[i], nuevos[i])) {
        return i;
      }
    }
    return limite;
  }

  // Para un bloque sin lenguaje declarado. highlightAuto sobre los 36 lenguajes
  // de la build se equivoca justo con lo que mas aparece aca (le pone css a un
  // SELECT, csharp a una clase Java, go a unos comandos de make: medido con
  // 11.12.0 al escribir esto; el caso del SELECT queda en codigo.test.js). Se
  // limita a los lenguajes de los repositorios
  // y documentos que ingiere esta base, y una relevancia baja no se rotula: un
  // "texto" sin color es mejor que un lenguaje equivocado.
  const LENGUAJES_DETECTABLES = ["java", "sql", "bash", "yaml", "json", "xml", "javascript", "python", "ini"];
  const RELEVANCIA_MINIMA = 3;

  /**
   * Devuelve { html, lenguaje } con el HTML ya escapado por highlight.js;
   * lenguaje es null cuando no se reconoce ninguno con confianza. Recibe hljs
   * para seguir siendo un modulo puro (las pruebas le pasan el vendorizado).
   */
  function detectarLenguaje(hljs, codigo) {
    const detectado = hljs.highlightAuto(codigo, LENGUAJES_DETECTABLES);
    if (detectado.language && detectado.relevance >= RELEVANCIA_MINIMA) {
      return { html: detectado.value, lenguaje: detectado.language };
    }
    return { html: hljs.highlight(codigo, { language: "plaintext" }).value, lenguaje: null };
  }

  const api = {
    partir: partir,
    dividirEnLineas: dividirEnLineas,
    primerSegmentoDistinto: primerSegmentoDistinto,
    detectarLenguaje: detectarLenguaje,
  };
  if (typeof module !== "undefined" && module.exports) {
    module.exports = api;
  } else {
    window.kbCodigo = api;
  }
})();
