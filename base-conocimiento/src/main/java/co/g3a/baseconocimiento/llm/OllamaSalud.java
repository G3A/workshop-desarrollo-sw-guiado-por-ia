package co.g3a.baseconocimiento.llm;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Reporta si Ollama responde y si los modelos que el producto necesita ya estan descargados.
 *
 * <p>La distincion importa: un Ollama vivo pero sin modelos es la causa numero uno de "arranco todo
 * pero la primera consulta falla". Por eso los modelos faltantes aparecen en el detalle de salud,
 * con el comando exacto para resolverlo.
 *
 * <p>Se comprueban los tres que puede pedirle el producto: el del chat ({@code KB_LLM_MODELO}), el
 * del destilador de Teams y el de embeddings. El del chat se comprueba SIEMPRE, sin mirar a que
 * backend apunte la API compatible con OpenAI: es lo que hace que el indicador sirva en los nueve
 * perfiles de Ollama, que son los que cambian ese modelo.
 *
 * <p>Contrapartida conocida, en el perfil Bonsai: ahi {@code KB_LLM_MODELO} es un GGUF que sirve
 * {@code llama-server}, no Ollama, asi que aparecera como faltante aunque el perfil funcione. Es
 * ruido acotado y visible; la alternativa -- condicionar la comprobacion al backend -- deja al
 * indicador callado justo en el caso frecuente, que es estrenar un perfil de Ollama sin haber
 * corrido su {@code make pull-...}.
 *
 * <p>Aun asi el estado se reporta UP mientras Ollama responda. Marcarlo DOWN dejaria el contenedor
 * permanentemente enfermo antes del primer {@code make pull-models}, y el healthcheck de Docker
 * nunca pasaria.
 */
@Component("ollama")
class OllamaSalud implements HealthIndicator {

  private static final Logger log = LoggerFactory.getLogger(OllamaSalud.class);

  private final RestClient cliente;
  private final String urlBase;
  private final Set<String> modelosRequeridos;

  OllamaSalud(
      @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String urlBase,
      // El modelo que sirve el chat: planificador, reformulador, verificador de
      // grounding y sintetizador. Es KB_LLM_MODELO, y ANTES NO SE COMPROBABA.
      //
      // El indicador solo miraba `destilador-modelo` (el del destilador de Teams,
      // F6) y los embeddings, con este razonamiento en su javadoc: desde ADR-0009
      // el chat le habla a llama-server via OpenAI, asi que el unico consumidor de
      // Ollama seria el destilador. Eso era cierto cuando Bonsai era el unico
      // backend OpenAI; dejo de serlo cuando los nueve perfiles de Ollama pasaron a
      // apuntar SPRING_AI_OPENAI_BASE_URL a http://ollama:11434/v1. El comentario
      // documentaba por que algo era correcto, y no se actualizo cuando cambio lo
      // de al lado.
      //
      // Sintoma medido: con `make up-granite41` (KB_LLM_MODELO=granite4.1:3b) sin
      // el modelo descargado, `make health` reportaba "faltantes: ninguna" --
      // comprobaba gemma3:4b, que si estaba. Y la primera consulta fallaba con un
      // 404 de Ollama que nada habia anticipado.
      //
      // Se comprueba SIEMPRE, sin mirar a que backend apunte OpenAI. Ver la nota
      // sobre el perfil Bonsai en el javadoc de la clase.
      @Value("${spring.ai.openai.chat.options.model:}") String modeloChat,
      @Value("${kb.llm.destilador-modelo:gemma3:4b}") String modeloDestilador,
      @Value("${kb.embeddings.modelo:bge-m3}") String modeloEmbeddings) {

    this.urlBase = urlBase;
    this.modelosRequeridos =
        Stream.of(modeloChat, modeloDestilador, modeloEmbeddings)
            .filter(m -> m != null && !m.isBlank())
            .collect(Collectors.toUnmodifiableSet());

    var fabrica = new SimpleClientHttpRequestFactory();
    fabrica.setConnectTimeout(Duration.ofSeconds(3));
    fabrica.setReadTimeout(Duration.ofSeconds(5));
    this.cliente = RestClient.builder().baseUrl(urlBase).requestFactory(fabrica).build();
  }

  @Override
  public Health health() {
    try {
      var respuesta = cliente.get().uri("/api/tags").retrieve().body(RespuestaTags.class);
      var disponibles = nombresDe(respuesta);
      var faltantes =
          modelosRequeridos.stream()
              .filter(requerido -> disponibles.stream().noneMatch(d -> coincide(d, requerido)))
              .sorted()
              .toList();

      if (!faltantes.isEmpty()) {
        log.warn("Ollama responde pero faltan modelos {}. Corre: make pull-models", faltantes);
      }

      return Health.up()
          .withDetail("urlBase", urlBase)
          .withDetail("modelosDisponibles", disponibles)
          .withDetail("modelosFaltantes", faltantes)
          .withDetail("accion", faltantes.isEmpty() ? "ninguna" : "corre `make pull-models`")
          .build();

    } catch (Exception e) {
      return Health.down(e).withDetail("urlBase", urlBase).build();
    }
  }

  /**
   * Ollama reporta {@code bge-m3:latest} para lo que se pide como {@code bge-m3}. Se tolera esa
   * equivalencia, y solo esa.
   *
   * <p>ANTES caia tambien a comparar el nombre base -- lo anterior al {@code :} -- para admitir el
   * alias {@code gemma3} = {@code gemma3:4b}. Eso ignoraba la etiqueta ENTERA: con {@code
   * granite4.1:8b} descargado, pedir {@code granite4.1:3b} se daba por presente, igual que
   * cualquier otro tamano o cuantizacion. La etiqueta es justo lo que distingue un modelo que cabe
   * en la tarjeta de uno que no, asi que tratarla como ruido convertia al indicador en un falso
   * negativo silencioso.
   *
   * <p>Un requerido SIN etiqueta si equivale al {@code :latest} correspondiente, que es la
   * convencion de Ollama y no pierde informacion.
   */
  private static boolean coincide(String disponible, String requerido) {
    return disponible.equals(requerido)
        || (!requerido.contains(":") && disponible.equals(requerido + ":latest"));
  }

  private static List<String> nombresDe(RespuestaTags respuesta) {
    if (respuesta == null || respuesta.models() == null) {
      return List.of();
    }
    return respuesta.models().stream()
        .map(m -> String.valueOf(m.get("name")))
        .sorted()
        .collect(Collectors.toList());
  }

  /** Forma minima de {@code GET /api/tags}: solo se necesita el nombre de cada modelo. */
  private record RespuestaTags(List<Map<String, Object>> models) {}
}
