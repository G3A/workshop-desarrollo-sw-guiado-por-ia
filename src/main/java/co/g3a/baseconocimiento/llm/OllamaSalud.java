package co.g3a.baseconocimiento.llm;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Reporta si Ollama responde y si los modelos que el producto necesita ya estan
 * descargados.
 *
 * <p>La distincion importa: un Ollama vivo pero sin modelos es la causa numero uno
 * de "arranco todo pero la primera consulta falla". Por eso los modelos faltantes
 * aparecen en el detalle de salud, con el comando exacto para resolverlo.
 *
 * <p>Aun asi el estado se reporta UP mientras Ollama responda. Marcarlo DOWN
 * dejaria el contenedor permanentemente enfermo antes del primer
 * {@code make pull-models}, y el healthcheck de Docker nunca pasaria.
 */
@Component("ollama")
class OllamaSalud implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(OllamaSalud.class);

    private final RestClient cliente;
    private final String urlBase;
    private final Set<String> modelosRequeridos;

    OllamaSalud(
            @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String urlBase,
            // Ya no es el modelo principal del pipeline (ver ADR-0009): desde que
            // Planificador/VerificadorGrounding/Sintetizador pasaron a hablarle a
            // llama-server via OpenAI, el unico consumidor de Ollama para chat es
            // el Destilador (Teams, F6).
            @Value("${kb.llm.destilador-modelo:gemma3:4b}") String modeloDestilador,
            @Value("${kb.embeddings.modelo:bge-m3}") String modeloEmbeddings) {

        this.urlBase = urlBase;
        this.modelosRequeridos = Set.of(modeloDestilador, modeloEmbeddings);

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
            var faltantes = modelosRequeridos.stream()
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
     * Ollama reporta {@code gemma3:4b} pero tambien acepta {@code gemma3} como alias.
     * Se compara por nombre base para no reportar un falso faltante.
     */
    private static boolean coincide(String disponible, String requerido) {
        return disponible.equals(requerido)
                || disponible.equals(requerido + ":latest")
                || base(disponible).equals(base(requerido));
    }

    private static String base(String nombre) {
        int i = nombre.indexOf(':');
        return i < 0 ? nombre : nombre.substring(0, i);
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
    private record RespuestaTags(List<Map<String, Object>> models) {
    }
}
