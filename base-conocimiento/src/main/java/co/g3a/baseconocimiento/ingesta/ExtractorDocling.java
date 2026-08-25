package co.g3a.baseconocimiento.ingesta;

import java.time.Duration;
import java.util.Base64;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import ai.docling.serve.api.DoclingServeApi;
import ai.docling.serve.api.convert.request.ConvertDocumentRequest;
import ai.docling.serve.api.convert.request.options.ConvertDocumentOptions;
import ai.docling.serve.api.convert.request.source.FileSource;
import ai.docling.serve.api.convert.request.target.InBodyTarget;
import ai.docling.serve.api.convert.response.ConvertDocumentResponse;
import ai.docling.serve.api.convert.response.InBodyConvertDocumentResponse;
import ai.docling.serve.api.task.request.TaskResultRequest;
import ai.docling.serve.api.task.response.TaskStatus;
import ai.docling.serve.api.task.response.TaskStatusPollResponse;
import io.arconia.docling.autoconfigure.DoclingProperties;

/**
 * Extrae Markdown de PDF/DOCX/PPTX vía docling-serve (ADR-0010), en vez de
 * PDFBox. A diferencia del {@code PDFTextStripper} plano que reemplaza, el
 * Markdown de Docling conserva encabezados reales, así que alimenta el mismo
 * {@link ChunkerEncabezados} que ya usan los archivos {@code .md}/{@code .txt}
 * — no hace falta un chunker aparte para lo que sale de acá.
 *
 * <p>Usa la API asíncrona de tareas de docling-serve: submit + polling de
 * estado + fetch del resultado, en vez de {@code POST /v1/convert/source}
 * (sincrónico). La versión sincrónica corta a los
 * {@code DOCLING_SERVE_MAX_SYNC_WAIT} segundos de docling-serve sin importar
 * cuánto falte, así que un documento de cientos de páginas nunca termina
 * (medido en vivo con un PDF de 892 páginas y con jls25.pdf, ~900 páginas —
 * ver ADR-0010). Con polling, cada llamada HTTP es corta sin importar cuánto
 * tarde la conversión completa: eso es justamente lo que resuelve.
 *
 * <p><b>Por qué el submit se hace con un {@link RestClient} propio en vez de
 * {@link DoclingServeApi}</b>: se probaron en vivo, contra un docling-serve
 * real, los dos caminos que ofrece {@code arconia-docling} 0.29.0 para enviar
 * una tarea async y ninguno sirve para un documento embebido (base64):
 * <ul>
 *   <li>{@code DoclingServeApi#convertSourceAsync} llama a
 *       {@code POST /v1/convert/source/async} en un hilo virtual pero
 *       deserializa la respuesta como {@link ConvertDocumentResponse} — el
 *       servidor real responde ahí con el descriptor de tarea (sin
 *       documento), así que esto no funciona (confirmado en el bytecode del
 *       cliente: no hace ningún polling interno).</li>
 *   <li>{@code DoclingServeApi#convertSourceBatch} sí deserializa
 *       correctamente el descriptor de tarea, pero
 *       {@code POST /v1/convert/source/batch} solo acepta fuentes remotas
 *       (http/s3/azure_blob/gcs/google_drive) y destinos remotos — devuelve
 *       422 con un {@link FileSource} o un {@link InBodyTarget} (probado en
 *       vivo). Es para convertir muchos documentos ya alojados en algún
 *       storage, no para un solo archivo embebido.</li>
 * </ul>
 * {@code POST /v1/convert/source/async} sí acepta {@link FileSource} +
 * {@link InBodyTarget} — el mismo cuerpo que usa el endpoint sincrónico — y
 * responde con el descriptor de tarea de inmediato; solo hay que armar esa
 * llamada a mano porque el cliente generado la deserializa mal.
 *
 * <p>El poll de estado ({@code GET /v1/status/poll/{taskId}?wait=...}) TAMBIÉN
 * se arma a mano, por otro bug del cliente: {@code DoclingServeApi
 * #pollTaskStatus} recibe un {@link Duration} y lo manda tal cual como valor
 * del query param {@code wait} — Spring serializa un {@code Duration} en
 * formato ISO-8601 ({@code "PT10S"}), pero docling-serve espera ahí un
 * número de segundos en punto flotante y responde 422 (confirmado en vivo:
 * {@code "Input should be a valid number, unable to parse string as a
 * number"}). Solo {@code convertTaskResult} ({@code GET
 * /v1/result/{taskId}}, sin query params) sí funciona tal cual la expone
 * {@link DoclingServeApi}.
 *
 * <p><b>El {@code wait} del poll NO se puede confiar como único freno.</b>
 * Medido en vivo contra jls25.pdf: aun mandando {@code ?wait=10} bien
 * formado, docling-serve respondió cada poll de inmediato en vez de
 * bloquear hasta 10s esperando un cambio de estado — sin un freno del lado
 * del cliente, el loop de polling lo golpeó a ~200-270 requests/segundo
 * durante horas, compitiendo por CPU con la conversión real y sin motivo
 * claro para no terminar nunca. Por eso además del {@code wait} del query
 * param, este cliente duerme {@link #ESPERA_POR_POLL} entre poll y poll del
 * lado de Java: no depende de que el servidor bloquee de verdad.
 *
 * <p><b>{@link #submitirTarea} y {@link #esperarYExtraer} están separados</b>
 * (en vez de un único {@code extraerMarkdown}) para que {@link
 * ConectorDocumentosLocales} pueda persistir el {@code taskId} entre el
 * submit y el poll: si {@code kb-api} se reinicia mientras una conversión de
 * varios minutos sigue en curso, el próximo intento retoma esa misma tarea
 * en vez de mandar una duplicada a docling-serve (ver ADR-0010).
 */
@Component
class ExtractorDocling {

    // Cada poll bloquea del lado del servidor hasta este tiempo esperando un
    // cambio de estado ("long poll"), en vez de golpear /v1/status/poll en un
    // loop ajustado. Muy por debajo de arconia.docling.read-timeout: ese
    // acota cada llamada HTTP individual (submit, poll, resultado), no la
    // conversión completa -- ese es justamente el punto de usar la API async.
    private static final Duration ESPERA_POR_POLL = Duration.ofSeconds(10);

    private final DoclingServeApi doclingServeApi;
    private final RestClient doclingRestClient;

    ExtractorDocling(DoclingServeApi doclingServeApi, DoclingProperties doclingProperties,
            RestClient.Builder restClientBuilder) {
        this.doclingServeApi = doclingServeApi;

        // Misma receta que DoclingAutoConfiguration usa para armar el
        // RestClient interno de DoclingServeApi (base-url + X-Api-Key si hay
        // uno configurado), para que este cliente hable con el mismo
        // docling-serve y la misma serialización que el resto de la clase.
        RestClient.Builder builder = restClientBuilder.baseUrl(doclingProperties.getBaseUrl().toString());
        if (StringUtils.hasText(doclingProperties.getApiKey())) {
            builder = builder.defaultHeader(DoclingProperties.API_KEY_HEADER_NAME, doclingProperties.getApiKey());
        }
        this.doclingRestClient = builder.build();
    }

    /** Envía el documento a docling-serve y devuelve de inmediato el {@code taskId} de la tarea. */
    String submitirTarea(String nombreArchivo, byte[] bytes) {
        String base64 = Base64.getEncoder().encodeToString(bytes);

        ConvertDocumentRequest peticion = ConvertDocumentRequest.builder()
                .source(FileSource.builder()
                        .filename(nombreArchivo)
                        .base64String(base64)
                        .build())
                .target(InBodyTarget.builder().build())
                // Sin esto, docling-serve corre OCR completo (rasteriza +
                // RapidOCR) en CADA pagina, incluso con texto nativo ya
                // extraible -- medido en vivo: minutos y satura la CPU para
                // un PDF de texto simple. El corpus de este proyecto no
                // tiene escaneados todavia (ADR-0010); si aparecen, esto
                // vuelve a ser una opcion configurable, no una constante.
                .options(ConvertDocumentOptions.builder()
                        .doOcr(false)
                        .build())
                .build();

        TaskStatusPollResponse estado = doclingRestClient.post()
                .uri("/v1/convert/source/async")
                .body(peticion)
                .retrieve()
                .body(TaskStatusPollResponse.class);
        return estado.getTaskId();
    }

    /**
     * Espera a que {@code taskId} termine (nueva o retomada tras un reinicio
     * de {@code kb-api}) y devuelve el Markdown. Lanza
     * {@link org.springframework.web.client.HttpClientErrorException.NotFound}
     * si docling-serve no reconoce {@code taskId} (p. ej. también se reinició
     * o la tarea expiró) — {@link ConectorDocumentosLocales} decide ahí si
     * reintentar con una tarea nueva.
     */
    String esperarYExtraer(String nombreArchivo, String taskId) {
        TaskStatusPollResponse estado = pollTaskStatus(taskId);
        while (estado.getTaskStatus() == TaskStatus.PENDING || estado.getTaskStatus() == TaskStatus.STARTED) {
            dormir(ESPERA_POR_POLL);
            estado = pollTaskStatus(taskId);
        }

        if (estado.getTaskStatus() != TaskStatus.SUCCESS) {
            throw new IllegalStateException(
                    "docling-serve no pudo convertir " + nombreArchivo + ": la tarea " + taskId
                            + " terminó en estado " + estado.getTaskStatus());
        }

        // Con InBodyTarget en la tarea, docling-serve entrega el resultado en
        // el cuerpo de la respuesta (InBodyConvertDocumentResponse es la única
        // implementación con documento y errores accesibles).
        ConvertDocumentResponse respuesta = doclingServeApi.convertTaskResult(
                TaskResultRequest.builder().taskId(taskId).build());
        if (!(respuesta instanceof InBodyConvertDocumentResponse enCuerpo)) {
            throw new IllegalStateException(
                    "Respuesta inesperada de docling-serve para " + nombreArchivo + ": "
                            + respuesta.getClass().getSimpleName());
        }
        List<?> errores = enCuerpo.getErrors();
        if (errores != null && !errores.isEmpty()) {
            throw new IllegalStateException(
                    "docling-serve no pudo convertir " + nombreArchivo + ": " + errores);
        }
        return enCuerpo.getDocument().getMarkdownContent();
    }

    private TaskStatusPollResponse pollTaskStatus(String taskId) {
        return doclingRestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/v1/status/poll/{taskId}")
                        .queryParam("wait", ESPERA_POR_POLL.toSeconds())
                        .build(taskId))
                .retrieve()
                .body(TaskStatusPollResponse.class);
    }

    private static void dormir(Duration duracion) {
        try {
            Thread.sleep(duracion);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrumpido esperando la tarea de docling-serve", e);
        }
    }
}
