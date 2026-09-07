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
   * Cuanto de cada documento elegido entro de verdad al contexto del LLM. El modelo local tiene un
   * contexto acotado, asi que un documento largo entra recortado a sus primeras secciones: esto lo
   * dice en vez de esconderlo. {@code 0/0} significa que el id no existe en el proyecto (o no tiene
   * secciones indexadas todavia).
   */
  record CoberturaDocumento(
      long documentoId,
      String titulo,
      String uri,
      int seccionesIncluidas,
      int seccionesTotales,
      boolean primeraRecortada) {

    /**
     * {@code false} tambien cuando entro una sola seccion pero cortada: «1 de 1» con la mitad del
     * texto afuera no es cobertura completa.
     */
    public boolean completa() {
      return seccionesTotales > 0 && seccionesIncluidas >= seccionesTotales && !primeraRecortada;
    }

    public boolean indexado() {
      return seccionesTotales > 0;
    }
  }

  /**
   * Resumir y sintetizar: la cobertura y las citas (una por documento, numeradas en el orden en que
   * la persona los eligio) estan disponibles de inmediato; el texto llega token a token.
   *
   * @param etiqueta lo que la UI muestra en la burbuja («Resumen de 3 documentos: a, b, c»)
   */
  record ResultadoEnStreaming(
      String etiqueta, List<CoberturaDocumento> cobertura, List<Cita> citas, Flux<String> texto) {}

  /**
   * Preguntas e ideas: salida estructurada del LLM, sin token a token (decision 11 del issue #38).
   * {@code resultado} es el record que devuelve {@code llm} ({@code Preguntas} o {@code Ideas});
   * viaja como {@code Object} para que el adaptador lo serialice sin depender de {@code llm}. Cada
   * pregunta o idea trae la cita como el entero {@code n} del documento {@code [n]} de la
   * cobertura.
   */
  record ResultadoEstructurado(
      String etiqueta,
      List<CoberturaDocumento> cobertura,
      List<Cita> citas,
      Mono<Object> resultado) {}

  /**
   * Lo que {@link ResultadoEstructurado#resultado()} emite cuando no hubo nada que estructurar
   * (ningun documento existe, o el servidor esta ocupado): un mensaje fijo, no un error — es el
   * equivalente del texto fijo que las acciones en streaming emiten en su lugar.
   */
  record SinResultado(String mensaje) {}

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

  Limites limites();

  /**
   * Resumir o sintetizar (solo {@link Tipo#RESUMIR} y {@link Tipo#SINTETIZAR}; los otros dos tipos
   * lanzan {@link IllegalArgumentException} porque su salida es estructurada).
   *
   * @param idioma codigo ISO 639-1 del idioma en que se redacta el resultado
   */
  ResultadoEnStreaming redactar(
      Tipo tipo, List<Long> documentos, ProyectoId proyecto, String idioma);

  /** Preguntas o ideas (solo {@link Tipo#PREGUNTAS} y {@link Tipo#IDEAS}). */
  ResultadoEstructurado estructurar(
      Tipo tipo, List<Long> documentos, ProyectoId proyecto, String idioma);

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
