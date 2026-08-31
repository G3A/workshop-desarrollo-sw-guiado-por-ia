package co.g3a.baseconocimiento.ingesta;

import co.g3a.baseconocimiento.compartido.Dominio.ProyectoId;
import co.g3a.baseconocimiento.llm.Destilador;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Ingiere un canal de Teams vía Microsoft Graph: delta query de mensajes raíz, sus respuestas,
 * destilación del hilo completo por LLM y bursting de los mensajes individuales que superan el gate
 * de IDF/longitud.
 *
 * <p>A diferencia de {@link ConectorDocumentosLocales} y {@link ConectorReposLocales}, la
 * destilación aquí SÍ pasa por el LLM — ver {@code package-info} del módulo: un hilo de chat es
 * contenido conversacional ruidoso, no un documento ya estructurado.
 */
@Component
class ConectorTeamsGraph {

  private static final Logger log = LoggerFactory.getLogger(ConectorTeamsGraph.class);
  private static final String KIND = "teams_channel";
  private static final int UMBRAL_CARACTERES_BURSTING = 200;
  private static final double UMBRAL_IDF_BURSTING = 4.0;

  private final IngestaRepositorio repo;
  private final ClienteGraph cliente;
  private final Destilador destilador;
  private final GraphPropiedades propiedades;

  ConectorTeamsGraph(
      IngestaRepositorio repo,
      ClienteGraph cliente,
      Destilador destilador,
      GraphPropiedades propiedades) {
    this.repo = repo;
    this.cliente = cliente;
    this.destilador = destilador;
    this.propiedades = propiedades;
  }

  record Resumen(int hilosVistos, int hilosActualizados, int chunksCreados) {}

  Resumen ingerir() {
    if (!propiedades.habilitado()) {
      log.info("Conector de Teams por Graph deshabilitado (kb.graph.habilitado=false)");
      return new Resumen(0, 0, 0);
    }

    long sourceId =
        repo.obtenerOCrearFuente(KIND, propiedades.teamId() + "/" + propiedades.channelId());
    String url =
        repo.obtenerSyncState(sourceId)
            .map(Json::leer)
            .map(nodo -> nodo.get("delta_link"))
            .filter(n -> n != null && !n.isNull())
            .map(JsonNode::asString)
            .orElse(
                "/teams/"
                    + propiedades.teamId()
                    + "/channels/"
                    + propiedades.channelId()
                    + "/messages/delta");

    int hilosVistos = 0;
    int hilosActualizados = 0;
    int chunksCreados = 0;
    String deltaLinkFinal = null;

    while (url != null) {
      JsonNode pagina = Json.leer(cliente.get(url));
      for (JsonNode mensaje : pagina.get("value")) {
        if (!esRaiz(mensaje)) {
          continue;
        }
        String messageId = Json.textoDe(mensaje, "id");
        if (esBorrado(mensaje)) {
          repo.buscarDocumento(sourceId, messageId).ifPresent(d -> repo.eliminarDocumento(d.id()));
          continue;
        }
        hilosVistos++;
        int creados = ingerirHilo(sourceId, mensaje, messageId);
        if (creados > 0) {
          hilosActualizados++;
        }
        chunksCreados += creados;
      }

      JsonNode delta = pagina.get("@odata.deltaLink");
      if (delta != null && !delta.isNull()) {
        deltaLinkFinal = delta.asString();
      }
      JsonNode next = pagina.get("@odata.nextLink");
      url = (next != null && !next.isNull()) ? next.asString() : null;
    }

    if (deltaLinkFinal != null) {
      repo.actualizarSyncState(sourceId, Json.escribir(Map.of("delta_link", deltaLinkFinal)));
    }

    log.info(
        "Ingesta de Teams por Graph: {} hilos vistos, {} actualizados, {} chunks nuevos",
        hilosVistos,
        hilosActualizados,
        chunksCreados);
    return new Resumen(hilosVistos, hilosActualizados, chunksCreados);
  }

  private int ingerirHilo(long sourceId, JsonNode raiz, String messageId) {
    List<JsonNode> respuestas = obtenerRespuestas(messageId);

    StringBuilder texto = new StringBuilder();
    texto.append(autorDe(raiz)).append(": ").append(textoDe(raiz));
    for (JsonNode r : respuestas) {
      texto.append('\n').append(autorDe(r)).append(": ").append(textoDe(r));
    }
    String textoCompleto = texto.toString().strip();
    if (textoCompleto.isBlank()) {
      return 0;
    }

    String hash = sha256Hex(textoCompleto.getBytes(StandardCharsets.UTF_8));
    var existente = repo.buscarDocumento(sourceId, messageId);
    if (existente.isPresent() && existente.get().contentHash().equals(hash)) {
      return 0;
    }

    long documentoId =
        repo.upsertDocumento(
            sourceId,
            messageId,
            "https://teams.microsoft.com/l/message/" + propiedades.channelId() + "/" + messageId,
            "Hilo de Teams",
            textoCompleto,
            hash,
            ProyectoId.POR_DEFECTO.valor());

    Destilador.Destilado destilado = destilador.destilar(textoCompleto);
    String distilledHilo =
        Json.escribir(
            Map.of(
                "searchable_question", nvl(destilado.searchableQuestion()),
                "summary", nvl(destilado.summary()),
                "resolution", nvl(destilado.resolution()),
                "systems_mentioned", nvlLista(destilado.systemsMentioned()),
                "code_references", nvlLista(destilado.codeReferences())));

    int ord = 0;
    long chunkHilo =
        repo.insertarChunk(
            documentoId,
            sourceId,
            ProyectoId.POR_DEFECTO.valor(),
            ord++,
            "thread",
            textoCompleto,
            distilledHilo);
    repo.encolarEmbeberChunk(chunkHilo);
    int chunksCreados = 1;

    for (JsonNode r : respuestas) {
      String textoBurst = textoDe(r);
      if (textoBurst.length() < UMBRAL_CARACTERES_BURSTING) {
        continue;
      }
      if (repo.maxIdf(textoBurst) < UMBRAL_IDF_BURSTING) {
        continue;
      }
      String distilledBurst = Json.escribir(Map.of("summary", autorDe(r) + ": " + textoBurst));
      long chunkBurst =
          repo.insertarChunk(
              documentoId,
              sourceId,
              ProyectoId.POR_DEFECTO.valor(),
              ord++,
              "thread_burst",
              textoBurst,
              distilledBurst);
      repo.encolarEmbeberChunk(chunkBurst);
      chunksCreados++;
    }
    return chunksCreados;
  }

  private List<JsonNode> obtenerRespuestas(String messageId) {
    List<JsonNode> respuestas = new ArrayList<>();
    String url =
        "/teams/"
            + propiedades.teamId()
            + "/channels/"
            + propiedades.channelId()
            + "/messages/"
            + messageId
            + "/replies";
    while (url != null) {
      JsonNode pagina = Json.leer(cliente.get(url));
      for (JsonNode r : pagina.get("value")) {
        if (!esBorrado(r)) {
          respuestas.add(r);
        }
      }
      JsonNode next = pagina.get("@odata.nextLink");
      url = (next != null && !next.isNull()) ? next.asString() : null;
    }
    respuestas.sort(Comparator.comparing(r -> Json.textoDe(r, "createdDateTime")));
    return respuestas;
  }

  private static boolean esRaiz(JsonNode mensaje) {
    JsonNode replyToId = mensaje.get("replyToId");
    return replyToId == null || replyToId.isNull();
  }

  private static boolean esBorrado(JsonNode mensaje) {
    JsonNode del = mensaje.get("deletedDateTime");
    return del != null && !del.isNull();
  }

  private static String autorDe(JsonNode mensaje) {
    JsonNode from = mensaje.get("from");
    JsonNode user = (from == null || from.isNull()) ? null : from.get("user");
    String nombre = (user == null) ? "" : Json.textoDe(user, "displayName");
    return nombre.isBlank() ? "desconocido" : nombre;
  }

  private static String textoDe(JsonNode mensaje) {
    JsonNode body = mensaje.get("body");
    String contenido = (body == null || body.isNull()) ? "" : Json.textoDe(body, "content");
    return stripHtml(contenido);
  }

  private static String stripHtml(String html) {
    return html.replaceAll("<[^>]+>", " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replaceAll("\\s+", " ")
        .strip();
  }

  private static String nvl(String texto) {
    return texto == null ? "" : texto;
  }

  private static List<String> nvlLista(List<String> lista) {
    return lista == null ? List.of() : lista;
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      var digest = MessageDigest.getInstance("SHA-256").digest(bytes);
      return java.util.HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
