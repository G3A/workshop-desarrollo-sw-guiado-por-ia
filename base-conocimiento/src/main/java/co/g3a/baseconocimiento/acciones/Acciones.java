package co.g3a.baseconocimiento.acciones;

import co.g3a.baseconocimiento.compartido.Dominio.Cita;
import co.g3a.baseconocimiento.compartido.Dominio.ProyectoId;
import java.util.List;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * La fachada del modulo: la unica puerta que los adaptadores pueden cruzar hacia las acciones sobre
 * documentos elegidos. Es deliberadamente paralela a {@code orquestacion.Consultar} y no una
 * extension suya: un adaptador que solo quiera el RAG no necesita saber que esto existe, y
 * viceversa.
 *
 * <p>Ninguna operacion valida el tamano de la lista de documentos ni el largo del texto: esos topes
 * viven en {@link #limites()} y los hace cumplir el adaptador ANTES de llamar, con un 400 y un
 * mensaje, igual que {@code ChatController} valida sus parametros antes de abrir el stream. La
 * fachada si tolera IDs que no existen (o son de otro proyecto): aparecen en la cobertura con
 * {@code 0/0}, nunca desaparecen en silencio.
 */
public interface Acciones {

  /** Las cuatro acciones que redactan a partir del contexto; traducir va aparte. */
  enum Tipo {
    RESUMIR,
    SINTETIZAR,
    PREGUNTAS,
    IDEAS
  }

  /**
   * Los topes que el adaptador hace cumplir antes de llamar. Salen de {@code kb.acciones.*}; la UI
   * los lee para deshabilitar el control en vez de dejar que el 400 llegue como un corte de
   * conexion.
   */
  record Limites(int maxDocumentos, int maxCaracteresTexto) {}

  /**
   * Como entra cada documento elegido al contexto del LLM. Todo documento indexado entra ENTERO: el
   * que cabe en su cuota, tal cual; el que no, leido por pasadas (sub-issue #60): cada tramo se
   * condensa en notas con el LLM antes de la accion, y {@code pasadas} dice cuantas llamadas son —
   * se sabe de antemano, asi la UI lo muestra antes de empezar y avanza con {@link
   * EventoAccion.Lectura}. {@code pasadas == 0} sin {@code modo} es un documento que entro sin
   * condensar. {@code seccionesTotales == 0} significa que el id no existe en el proyecto (o no
   * tiene secciones indexadas todavia). {@code modo} es como se leyo un documento que no cabia
   * (issue #57), y {@code null} si entro entero: en {@code RAPIDO} las pasadas son 0 igual que en
   * uno que cabe.
   */
  record CoberturaDocumento(
      long documentoId, String titulo, String uri, int seccionesTotales, int pasadas, Modo modo) {

    public boolean indexado() {
      return seccionesTotales > 0;
    }
  }

  /**
   * Lo que pasa mientras corre una accion, en un solo flujo ordenado (misma forma que {@link
   * EventoTraduccion}): primero una {@link Lectura} por cada pasada sobre un documento largo, luego
   * la salida — {@link Token}×n para resumir y sintetizar, un unico {@link Resultado} para
   * preguntas e ideas (salida estructurada, decision 11 del issue #38).
   */
  sealed interface EventoAccion
      permits EventoAccion.Lectura,
          EventoAccion.CapituloLeido,
          EventoAccion.Token,
          EventoAccion.Resultado {

    /** Termino la pasada {@code pasadaActual} de {@code pasadasTotales} sobre el documento. */
    record Lectura(long documentoId, int pasadaActual, int pasadasTotales)
        implements EventoAccion {}

    /**
     * Modo «Por capitulos» (issue #57): termino el capitulo {@code actual} de {@code total} del
     * documento; {@code titulo} es lo que la UI muestra en el progreso y {@code nota} lo que el
     * modelo saco de su extracto, para pintarlo apenas esta listo.
     */
    record CapituloLeido(long documentoId, int actual, int total, String titulo, String nota)
        implements EventoAccion {}

    /** Un fragmento de prosa, tal como lo emite el LLM. */
    record Token(String fragmento) implements EventoAccion {}

    /**
     * El record que devuelve {@code llm} ({@code Preguntas} o {@code Ideas}), o un {@link
     * SinResultado}. Viaja como {@code Object} para que el adaptador lo serialice sin depender de
     * {@code llm}. Cada pregunta o idea trae la cita como el entero {@code n} del documento {@code
     * [n]} de la cobertura.
     */
    record Resultado(Object valor) implements EventoAccion {}
  }

  /**
   * Una accion sobre documentos: la etiqueta, la cobertura y las citas (una por documento,
   * numeradas en el orden en que la persona los eligio) estan disponibles de inmediato; los eventos
   * corren recien al suscribirse.
   *
   * @param etiqueta lo que la UI muestra en la burbuja («Resumen de 3 documentos: a, b, c»)
   */
  record ResultadoDeAccion(
      String etiqueta,
      List<CoberturaDocumento> cobertura,
      List<Cita> citas,
      Flux<EventoAccion> eventos) {}

  /**
   * Lo que una accion estructurada emite como {@link EventoAccion.Resultado} cuando no hubo nada
   * que estructurar (ningun documento existe, o el servidor esta ocupado): un mensaje fijo, no un
   * error — el equivalente del {@link EventoAccion.Token} fijo que las acciones de prosa emiten en
   * su lugar.
   */
  record SinResultado(String mensaje) {}

  /**
   * El cupo de acciones esta agotado. Las acciones de prosa y las estructuradas lo dicen con un
   * texto fijo en su lugar (ver {@link SinResultado}); las traducciones no tienen ese lugar — sus
   * eventos van por documento — y lo lanzan dentro del flujo, para que el adaptador lo muestre con
   * el mismo mensaje y sin disfrazarlo de corte.
   */
  final class ServidorOcupado extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ServidorOcupado(String mensaje) {
      super(mensaje);
    }
  }

  /**
   * Lo que pasa mientras se traducen documentos, en un solo flujo ordenado para que el adaptador lo
   * mapee a eventos SSE sin coordinar dos streams. Por documento: {@link IdiomaDetectado} (solo si
   * el origen era «detectar»), luego {@link Omitido} o una secuencia de {@link Progreso} y {@link
   * Texto} por bloque.
   */
  sealed interface EventoTraduccion
      permits EventoTraduccion.IdiomaDetectado,
          EventoTraduccion.Omitido,
          EventoTraduccion.Progreso,
          EventoTraduccion.Texto {

    /** {@code codigo} ISO 639-1, o {@code "und"} si el modelo no supo decirlo. */
    record IdiomaDetectado(long documentoId, String codigo) implements EventoTraduccion {}

    /** El documento ya esta en el idioma destino: no se traduce y se dice. */
    record Omitido(long documentoId, String codigo) implements EventoTraduccion {}

    /** Empieza el bloque {@code bloqueActual} de {@code bloquesTotales} del documento. */
    record Progreso(long documentoId, int bloqueActual, int bloquesTotales)
        implements EventoTraduccion {}

    /** Un fragmento de traduccion del documento, tal como lo emite el LLM. */
    record Texto(long documentoId, String fragmento) implements EventoTraduccion {}
  }

  /**
   * Un documento a traducir, con cuantos bloques va a recorrer (una seccion larga se parte en
   * varios bloques; un {@code code_block} es un bloque que pasa sin traducir). {@code 0} bloques =
   * no indexado.
   */
  record DocumentoATraducir(long documentoId, String titulo, String uri, int bloquesTotales) {}

  record TraduccionDeDocumentos(
      String etiqueta, List<DocumentoATraducir> documentos, Flux<EventoTraduccion> eventos) {}

  /**
   * Traduccion de un texto libre del chat. {@code idiomaOrigen} se resuelve perezosamente (una
   * llamada corta al LLM si el origen era «detectar»), asi el adaptador puede emitir la cabecera
   * SSE antes de esperar esa llamada; {@code texto} la reutiliza, no la repite.
   */
  record TextoTraducido(Mono<String> idiomaOrigen, String idiomaDestino, Flux<String> texto) {}

  /**
   * Como se lee un documento largo (issue #57). {@code COMPLETO} es la lectura por pasadas de #60;
   * {@code POR_CAPITULOS} manda un extracto acotado por capitulo; {@code RAPIDO} elige secciones
   * representativas y hace solo la llamada final.
   */
  enum Modo {
    COMPLETO,
    POR_CAPITULOS,
    RAPIDO
  }

  /**
   * Lo que costaria una accion en un modo, medido con el rendimiento real del modelo.
   *
   * @param seccionesCubiertas cuantas secciones de los documentos largos llegan al LLM (en {@code
   *     COMPLETO}, todas)
   * @param llamadas llamadas al LLM, contando la final
   */
  record EstimadoModo(
      Modo modo, long segundos, int seccionesCubiertas, int seccionesTotales, int llamadas) {}

  /**
   * {@code elegir} es verdadero cuando leer todo pasa del umbral ({@code
   * kb.acciones.umbral-eleccion-segundos}): la UI muestra el selector en vez de arrancar. Sin
   * documentos largos, un unico modo {@code COMPLETO}.
   */
  record Estimado(boolean elegir, List<EstimadoModo> modos) {}

  Limites limites();

  /** Cuanto tardaria la accion en cada modo, antes de empezarla. No llama al LLM. */
  Estimado estimar(Tipo tipo, List<Long> documentos, ProyectoId proyecto);

  /**
   * Resumir, sintetizar, preguntas o ideas sobre los documentos elegidos, enteros.
   *
   * @param idioma codigo ISO 639-1 del idioma en que se redacta el resultado (y las notas de
   *     lectura de los documentos largos)
   * @param modo como se leen los documentos que no caben; {@code COMPLETO} es la lectura por
   *     pasadas, y en {@code POR_CAPITULOS} la cobertura declara un capitulo por pasada
   */
  ResultadoDeAccion ejecutar(
      Tipo tipo, List<Long> documentos, ProyectoId proyecto, String idioma, Modo modo);

  /**
   * @param origen codigo ISO 639-1, o {@code null} para detectarlo por documento
   * @param destino codigo ISO 639-1; un documento cuyo origen (dado o detectado) coincide se omite
   */
  TraduccionDeDocumentos traducirDocumentos(
      List<Long> documentos, ProyectoId proyecto, String origen, String destino);

  /**
   * @param origen codigo ISO 639-1, o {@code null} para detectarlo; si coincide con {@code destino}
   *     el texto vuelve tal cual, sin pasar por el LLM
   */
  TextoTraducido traducirTexto(String texto, String origen, String destino);
}
