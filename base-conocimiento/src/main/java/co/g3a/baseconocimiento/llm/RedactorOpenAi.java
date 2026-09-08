package co.g3a.baseconocimiento.llm;

import com.openai.errors.OpenAIException;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * El {@link Redactor} sobre el mismo cliente que {@link SintetizadorOpenAi}. Cuatro {@link
 * ChatClient}: prosa en streaming (resumir, sintetizar), traduccion (salida mas larga, sin
 * penalidad de presencia: una traduccion fiel repite lo que el original repite), salida
 * estructurada (preguntas, ideas: JSON forzado y temperatura 0, como {@link
 * VerificadorGroundingOpenAi}) y deteccion de idioma (un solo campo, tope minimo de tokens).
 *
 * <p>Los parametros de sampling de la prosa son los de {@link SintetizadorOpenAi} — ver el
 * comentario de su constructor sobre {@code repeat_penalty}, {@code repeat_last_n}, {@code
 * reasoning_effort} y {@code presencePenalty}: nacieron de fallas medidas en vivo que aplican a
 * cualquier generacion larga con este backend. Los {@code maxTokens} son mas altos que los 512 de
 * la sintesis porque aqui la salida cubre varios documentos, y siguen siendo una red contra un
 * bucle sin fin, no un recorte esperado; el presupuesto de contexto de {@code acciones} ya
 * descuenta esa salida del {@code num_ctx} del modelo.
 */
@Component
class RedactorOpenAi implements Redactor {

  private static final Logger log = LoggerFactory.getLogger(RedactorOpenAi.class);

  /** Cuanto texto se le muestra al modelo para decir en que idioma esta escrito. */
  static final int LARGO_MUESTRA = 1500;

  private static final String REGLAS_COMUNES =
      """
            Trabajas SOLO con lo que aparece en los documentos numerados del contexto, nunca con
            conocimiento propio. Cada afirmacion lleva el marcador [n] del documento del que sale,
            pegado al final de esa afirmacion -- nunca antes, nunca varios marcadores sueltos al
            cierre. Si el encabezado de un documento dice que lo que sigue son notas de lectura de
            un documento largo, trabaja con esas notas como si fueran el texto, sin comentar que
            son notas ni que el documento es largo. Si dos documentos se contradicen, señala la
            contradiccion en vez de elegir uno en silencio. Ve directo al resultado: NO narres tu
            razonamiento ("primero voy a...", "veamos los documentos..."). %s
            """;

  private static final String SISTEMA_RESUMIR =
      "Eres el resumidor de una base de conocimiento interna. "
          + REGLAS_COMUNES
          + """

            Escribe un resumen POR DOCUMENTO, en el mismo orden en que aparecen: un parrafo corto
            por documento que empiece con su titulo y recoja sus puntos clave (decisiones, pasos,
            requisitos, advertencias). Sin listas de viñetas: prosa corta.
            """;

  private static final String SISTEMA_SINTETIZAR =
      "Eres el sintetizador de una base de conocimiento interna. "
          + REGLAS_COMUNES
          + """

            Escribe UN SOLO texto que cruce todos los documentos, con exactamente tres partes y
            estos subtitulos en su propia linea: "En comun", "Donde se contradicen" y
            "Conclusion". En "En comun", que dicen los documentos en conjunto. En "Donde se
            contradicen", las diferencias reales entre ellos (si no hay ninguna, dilo en una
            frase). En "Conclusion", que se desprende del conjunto. Prosa corta, sin viñetas.
            """;

  private static final String SISTEMA_PREGUNTAS =
      "Eres el asistente de estudio de una base de conocimiento interna. "
          + REGLAS_COMUNES
          + """

            Generas preguntas de UN solo nivel de la taxonomia de Bloom: "%s" (%s). Las
            preguntas de este nivel suelen empezar como %s. Recorre los seis interrogativos --
            que, quien, cuando, donde, por que, como -- y para cada uno formula UNA pregunta de
            ese nivel SOLO si algun documento la responde de verdad; si no, omite ese
            interrogativo. Puede no quedar ninguna. Cada "texto" es una PREGUNTA completa, corta
            y concreta, que termina en signo de interrogacion: nunca una afirmacion ni una frase
            copiada del documento. "tipo" es el interrogativo usado (exactamente uno de: que,
            quien, cuando, donde, por-que, como) y "fuente" el numero n del documento [n] que la
            responde. Preguntas ya formuladas en niveles anteriores, que NO debes repetir ni
            reformular: %s
            """;

  private static final String SISTEMA_IDEAS =
      "Eres el asistente de estudio de una base de conocimiento interna. "
          + REGLAS_COMUNES
          + """

            Propone ideas concretas y accionables que se desprenden de estos documentos: mejoras,
            experimentos, decisiones a tomar, riesgos a atender. Cada idea tiene un titulo corto,
            una justificacion de una o dos frases apoyada en lo que dice un documento, y "fuente"
            con el numero n del documento [n] que la respalda. Entre cuatro y ocho ideas.
            """;

  private static final String SISTEMA_CONDENSAR =
      """
            Tomas notas de lectura de un tramo de un documento largo; otro paso las usara en lugar
            del texto original, asi que tienen que ser fieles y completas en lo esencial:
            definiciones, reglas, decisiones, pasos, requisitos, cifras, nombres, advertencias y
            ejemplos clave, en el orden del texto y sin valorar ni agregar nada. Sin introducciones
            ni cierres, sin repetir el titulo, sin frases como "el texto dice". Prosa compacta o
            lineas cortas. Como maximo %d palabras. %s
            """;

  private static final String SISTEMA_TRADUCIR =
      """
            Eres un traductor profesional. Devuelves SOLO la traduccion del texto que recibes:
            sin comentarios, sin explicaciones, sin encabezados propios, sin repetir el original.
            Conserva el formato Markdown (encabezados, listas, tablas, enfasis), los bloques de
            codigo tal cual (no traduzcas codigo, identificadores ni comandos) y cualquier marcador
            de cita [n] exactamente donde esta. No resumas ni omitas nada.
            """;

  private static final String SISTEMA_DETECTAR =
      """
            Identificas en que idioma esta escrito un texto. Responde SOLO el codigo ISO 639-1 de
            dos letras del idioma predominante (por ejemplo "es", "en", "pt"). Decide por el
            idioma de las FRASES (articulos, verbos, conectores), no por los nombres tecnicos,
            comandos, rutas ni palabras en ingles sueltas que un texto tecnico en otro idioma
            suele traer. Si el texto no tiene suficiente lenguaje natural para saberlo (solo
            codigo, numeros o simbolos), responde "und".
            """;

  /** Lo que un modelo chico de verdad devuelve cuando se le pide un codigo de idioma. */
  private static final Map<String, String> NOMBRES_A_CODIGO =
      Map.ofEntries(
          Map.entry("english", "en"),
          Map.entry("ingles", "en"),
          Map.entry("inglés", "en"),
          Map.entry("spanish", "es"),
          Map.entry("espanol", "es"),
          Map.entry("español", "es"),
          Map.entry("castellano", "es"),
          Map.entry("portuguese", "pt"),
          Map.entry("portugues", "pt"),
          Map.entry("portugués", "pt"),
          Map.entry("french", "fr"),
          Map.entry("frances", "fr"),
          Map.entry("francés", "fr"),
          Map.entry("german", "de"),
          Map.entry("aleman", "de"),
          Map.entry("alemán", "de"),
          Map.entry("italian", "it"),
          Map.entry("italiano", "it"),
          Map.entry("japanese", "ja"),
          Map.entry("japones", "ja"),
          Map.entry("japonés", "ja"),
          Map.entry("chinese", "zh"),
          Map.entry("chino", "zh"),
          Map.entry("korean", "ko"),
          Map.entry("coreano", "ko"),
          Map.entry("russian", "ru"),
          Map.entry("ruso", "ru"),
          Map.entry("arabic", "ar"),
          Map.entry("arabe", "ar"),
          Map.entry("árabe", "ar"),
          Map.entry("dutch", "nl"),
          Map.entry("neerlandes", "nl"),
          Map.entry("neerlandés", "nl"),
          Map.entry("holandes", "nl"),
          Map.entry("holandés", "nl"),
          Map.entry("polish", "pl"),
          Map.entry("polaco", "pl"),
          Map.entry("turkish", "tr"),
          Map.entry("turco", "tr"),
          Map.entry("hindi", "hi"),
          Map.entry("catalan", "ca"),
          Map.entry("catalán", "ca"),
          Map.entry("swedish", "sv"),
          Map.entry("sueco", "sv"),
          // ISO 639-2, que algunos modelos prefieren
          Map.entry("spa", "es"),
          Map.entry("eng", "en"),
          Map.entry("por", "pt"),
          Map.entry("fra", "fr"),
          Map.entry("fre", "fr"),
          Map.entry("deu", "de"),
          Map.entry("ger", "de"),
          Map.entry("ita", "it"),
          Map.entry("jpn", "ja"),
          Map.entry("zho", "zh"),
          Map.entry("chi", "zh"),
          Map.entry("kor", "ko"),
          Map.entry("rus", "ru"),
          Map.entry("ara", "ar"),
          Map.entry("nld", "nl"),
          Map.entry("dut", "nl"),
          Map.entry("pol", "pl"),
          Map.entry("tur", "tr"),
          Map.entry("hin", "hi"),
          Map.entry("cat", "ca"));

  private static final Set<String> CODIGOS_ISO = Set.of(Locale.getISOLanguages());
  private static final Pattern CODIGO_CON_REGION = Pattern.compile("^([a-z]{2})[-_][a-z0-9]+$");

  /** Salida estructurada de la deteccion: un solo campo, para que el tope de tokens sea minimo. */
  record IdiomaDetectado(String codigo) {}

  private final ChatClient prosa;
  private final ChatClient traduccion;
  private final ChatClient estructurado;
  private final ChatClient deteccion;
  private final ChatClient lectura;

  RedactorOpenAi(OpenAiChatModel modelo) {
    Map<String, Object> extra =
        Map.of("repeat_penalty", 1.1, "repeat_last_n", 4096, "reasoning_effort", "none");
    this.prosa =
        ChatClient.builder(modelo)
            .defaultOptions(
                OpenAiChatOptions.builder().extraBody(extra).presencePenalty(0.1).maxTokens(900))
            .build();
    // Sin presencePenalty: penalizar la repeticion en una traduccion la aleja del
    // original, que repite lo que repite. temperature baja para fidelidad.
    this.traduccion =
        ChatClient.builder(modelo)
            .defaultOptions(
                OpenAiChatOptions.builder().extraBody(extra).temperature(0.1).maxTokens(1200))
            .build();
    this.estructurado =
        ChatClient.builder(modelo)
            .defaultOptions(
                OpenAiChatOptions.builder().extraBody(extra).temperature(0.0).maxTokens(900))
            .build();
    this.deteccion =
        ChatClient.builder(modelo)
            .defaultOptions(
                OpenAiChatOptions.builder()
                    .extraBody(Map.of("repeat_penalty", 1.1, "reasoning_effort", "none"))
                    .temperature(0.0)
                    .maxTokens(20))
            .build();
    // Notas de lectura (sub-issue #60): salida corta y fiel, como la traduccion; el tope
    // de tokens cubre LARGO_NOTAS caracteres con margen y frena un bucle.
    this.lectura =
        ChatClient.builder(modelo)
            .defaultOptions(
                OpenAiChatOptions.builder().extraBody(extra).temperature(0.1).maxTokens(600))
            .build();
  }

  @Override
  public String condensar(String tramo, String idioma, int maxCaracteres) {
    int palabras = Math.max(40, maxCaracteres / 7);
    String notas =
        lectura
            .prompt()
            .system(SISTEMA_CONDENSAR.formatted(palabras, instruccionIdioma(idioma)))
            .user("Tramo:\n\n" + tramo)
            .call()
            .content();
    return notas == null ? "" : notas.strip();
  }

  @Override
  public Flux<String> resumir(String contexto, String idioma) {
    return prosa
        .prompt()
        .system(SISTEMA_RESUMIR.formatted(instruccionIdioma(idioma)))
        .user("Documentos:\n\n" + contexto)
        .stream()
        .content();
  }

  @Override
  public Flux<String> sintetizar(String contexto, String idioma) {
    return prosa
        .prompt()
        .system(SISTEMA_SINTETIZAR.formatted(instruccionIdioma(idioma)))
        .user("Documentos:\n\n" + contexto)
        .stream()
        .content();
  }

  /** Salida estructurada de un nivel: solo la lista, el nivel ya lo sabe quien llama. */
  record PreguntasDeNivel(List<Pregunta> preguntas) {}

  @Override
  public List<Pregunta> preguntar(
      String contexto, String idioma, NivelBloom nivel, List<String> yaFormuladas) {
    try {
      String evitar =
          yaFormuladas.isEmpty() ? "ninguna todavia." : String.join(" | ", yaFormuladas);
      PreguntasDeNivel salida =
          estructurado
              .prompt()
              .system(
                  SISTEMA_PREGUNTAS.formatted(
                      instruccionIdioma(idioma),
                      nivel.codigo(),
                      nivel.descripcion(),
                      nivel.ejemplos(),
                      evitar))
              .user("Documentos:\n\n" + contexto)
              .call()
              .entity(PreguntasDeNivel.class, spec -> spec.useProviderStructuredOutput());
      if (salida == null || salida.preguntas() == null) {
        return List.of();
      }
      return depurar(salida.preguntas(), yaFormuladas);
    } catch (RuntimeException e) {
      if (esFalloDeInfraestructura(e)) {
        throw e;
      }
      // Igual que VerificadorGroundingOpenAi: un JSON truncado o invalido no es un
      // resultado; el nivel queda vacio y los demas siguen.
      log.warn(
          "El modelo no devolvio preguntas validas para el nivel {}: {}",
          nivel.codigo(),
          e.toString());
      return List.of();
    }
  }

  /**
   * Lo que un modelo chico devuelve de mas, quitado con reglas verificables (visto en vivo con
   * gemma3:4b): afirmaciones copiadas del documento en vez de preguntas, y la misma pregunta
   * repetida en otro nivel o dos veces en el mismo. Solo queda lo que termina en signo de
   * interrogacion y no coincide, sin acentos ni puntuacion, con una ya formulada.
   */
  static List<Pregunta> depurar(List<Pregunta> crudas, List<String> yaFormuladas) {
    Set<String> vistas = new java.util.HashSet<>();
    for (String texto : yaFormuladas) {
      vistas.add(clave(texto));
    }
    List<Pregunta> limpias = new java.util.ArrayList<>();
    for (Pregunta p : crudas) {
      if (p == null || p.texto() == null) {
        continue;
      }
      String texto = p.texto().strip();
      if (!texto.endsWith("?") && !texto.endsWith("？")) {
        continue;
      }
      if (!vistas.add(clave(texto))) {
        continue;
      }
      limpias.add(new Pregunta(texto, normalizarTipo(p.tipo()), p.fuente()));
    }
    return List.copyOf(limpias);
  }

  private static String clave(String texto) {
    return Normalizer.normalize(texto == null ? "" : texto, Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "")
        .toLowerCase(Locale.ROOT)
        .replaceAll("[^\\p{L}\\p{N}]+", " ")
        .strip();
  }

  /**
   * El interrogativo tal como lo devuelve un modelo chico (con acento, en ingles, con espacio) a
   * uno de los seis codigos 5W1H; vacio si no se reconoce, para que la UI no invente uno.
   */
  static String normalizarTipo(String tipo) {
    if (tipo == null) {
      return "";
    }
    String plano =
        Normalizer.normalize(tipo, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .toLowerCase(Locale.ROOT)
            .strip()
            .replaceAll("[\\s_]+", "-");
    return switch (plano) {
      case "que", "what", "cual", "cuales", "which" -> "que";
      case "quien", "quienes", "who" -> "quien";
      case "cuando", "when" -> "cuando";
      case "donde", "where" -> "donde";
      case "por-que", "porque", "why" -> "por-que";
      case "como", "how" -> "como";
      default -> "";
    };
  }

  /**
   * Un JSON truncado o invalido es un resultado vacio; una falla del cliente (modelo sin descargar,
   * Ollama caido, timeout) no lo es: tiene que llegar al adaptador con su pista, la misma que da
   * resumir. Se recorre la cadena de causas porque Spring AI puede envolver la del SDK.
   */
  static boolean esFalloDeInfraestructura(Throwable error) {
    for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
      if (t instanceof OpenAIException) {
        return true;
      }
    }
    return false;
  }

  @Override
  public Ideas idear(String contexto, String idioma) {
    try {
      Ideas ideas =
          estructurado
              .prompt()
              .system(SISTEMA_IDEAS.formatted(instruccionIdioma(idioma)))
              .user("Documentos:\n\n" + contexto)
              .call()
              .entity(Ideas.class, spec -> spec.useProviderStructuredOutput());
      return ideas == null || ideas.ideas() == null ? new Ideas(List.of()) : ideas;
    } catch (RuntimeException e) {
      if (esFalloDeInfraestructura(e)) {
        throw e;
      }
      log.warn("El modelo no devolvio ideas validas: {}", e.toString());
      return new Ideas(List.of());
    }
  }

  @Override
  public Flux<String> traducir(String texto, String origen, String destino) {
    return traduccion
        .prompt()
        .system(SISTEMA_TRADUCIR)
        .user(mensajeDeTraduccion(texto, origen, destino))
        .stream()
        .content();
  }

  @Override
  public String detectarIdioma(String muestra) {
    String recortada = recortarMuestra(muestra);
    if (recortada.isEmpty()) {
      return "und";
    }
    try {
      IdiomaDetectado detectado =
          deteccion
              .prompt()
              .system(SISTEMA_DETECTAR)
              .user(recortada)
              .call()
              .entity(IdiomaDetectado.class, spec -> spec.useProviderStructuredOutput());
      return detectado == null ? "und" : normalizarCodigo(detectado.codigo());
    } catch (Exception e) {
      // "und" y no una excepcion: quien traduce sigue adelante sin omitir nada.
      log.warn("No se pudo detectar el idioma, se sigue sin asumirlo: {}", e.toString());
      return "und";
    }
  }

  /**
   * Lo que el modelo devuelve como idioma, llevado a ISO 639-1 o a {@code "und"}. Tolera nombres en
   * español e ingles, ISO 639-2 y regionales ({@code en-US}, {@code pt_BR}); todo lo demas es
   * {@code "und"} (nunca una excepcion, y nunca un codigo que no exista).
   */
  static String normalizarCodigo(String crudo) {
    if (crudo == null) {
      return "und";
    }
    String s = crudo.strip().toLowerCase(Locale.ROOT).replace("\"", "");
    if (s.isEmpty()) {
      return "und";
    }
    String porNombre = NOMBRES_A_CODIGO.get(s);
    if (porNombre != null) {
      return porNombre;
    }
    var conRegion = CODIGO_CON_REGION.matcher(s);
    if (conRegion.matches()) {
      s = conRegion.group(1);
    }
    return s.length() == 2 && CODIGOS_ISO.contains(s) ? s : "und";
  }

  /**
   * La frase del prompt que fija el idioma del resultado, con el nombre del idioma en español (sale
   * del {@link Locale}, sin lista propia). {@code "und"} o un codigo desconocido caen a español: es
   * el idioma del producto, no una adivinanza.
   */
  static String instruccionIdioma(String codigo) {
    String normalizado = normalizarCodigo(codigo);
    if ("und".equals(normalizado) || "es".equals(normalizado)) {
      return "Redacta en español latinoamericano neutro.";
    }
    return "Redacta en %s (código ISO 639-1 \"%s\"), aunque los documentos estén en otro idioma."
        .formatted(nombreDe(normalizado), normalizado);
  }

  static String mensajeDeTraduccion(String texto, String origen, String destino) {
    String normalizadoOrigen = normalizarCodigo(origen);
    String nombreDestino = nombreDe(normalizarCodigo(destino));
    String cabecera =
        "und".equals(normalizadoOrigen)
            ? "Traduce al %s el siguiente texto, desde el idioma en que esté escrito:"
                .formatted(nombreDestino)
            : "Traduce del %s al %s el siguiente texto:"
                .formatted(nombreDe(normalizadoOrigen), nombreDestino);
    return cabecera + "\n\n" + texto;
  }

  /** Aplanada y recortada: al modelo le alcanza con el comienzo para decir el idioma. */
  static String recortarMuestra(String texto) {
    if (texto == null) {
      return "";
    }
    String plano = texto.replaceAll("\\s+", " ").strip();
    return plano.length() > LARGO_MUESTRA ? plano.substring(0, LARGO_MUESTRA) : plano;
  }

  private static String nombreDe(String codigo) {
    return Locale.of(codigo).getDisplayLanguage(Locale.of("es"));
  }
}
