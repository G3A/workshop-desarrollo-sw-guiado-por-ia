package co.g3a.baseconocimiento.ingesta;

import co.g3a.baseconocimiento.compartido.Dominio.ProyectoId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

/**
 * Ingiere work items (por WIQL) y páginas de wiki de Azure DevOps.
 *
 * <p>Ambas fuentes son contenido ya estructurado (título/descripción de un work item, encabezados
 * de una página de wiki) — la destilación es heurística, igual que {@link
 * ConectorDocumentosLocales}, sin pasar por el LLM. Eso es propio de {@code ConectorTeamsGraph}
 * (hilos de chat).
 *
 * <p>Incrementalidad por hash de contenido, igual que los demás conectores: se vuelve a consultar
 * todo en cada corrida (WIQL no tiene un filtro de "cambiado desde" tan simple como el delta link
 * de Graph), pero solo lo que cambió se re-trocea y se vuelve a encolar para embeber.
 */
@Component
class ConectorAzureDevOps {

  private static final Logger log = LoggerFactory.getLogger(ConectorAzureDevOps.class);
  private static final String KIND = "azure_devops";
  private static final int MAX_CARACTERES_WORK_ITEM = 4_000;
  private static final int LONGITUD_RESUMEN = 280;

  private static final String WIQL_QUERY =
      "SELECT [System.Id] FROM WorkItems ORDER BY [System.ChangedDate] DESC";

  private final IngestaRepositorio repo;
  private final ClienteAzureDevOps cliente;
  private final AzureDevOpsPropiedades propiedades;

  ConectorAzureDevOps(
      IngestaRepositorio repo, ClienteAzureDevOps cliente, AzureDevOpsPropiedades propiedades) {
    this.repo = repo;
    this.cliente = cliente;
    this.propiedades = propiedades;
  }

  record Resumen(
      int workItemsVistos,
      int workItemsActualizados,
      int paginasWikiVistas,
      int paginasWikiActualizadas,
      int chunksCreados) {}

  private record ResultadoParcial(int vistos, int actualizados, int chunks) {}

  Resumen ingerir() {
    if (!propiedades.habilitado()) {
      log.info("Conector de Azure DevOps deshabilitado (kb.azdo.habilitado=false)");
      return new Resumen(0, 0, 0, 0, 0);
    }
    ResultadoParcial workItems = ingerirWorkItems();
    ResultadoParcial wiki = ingerirWiki();

    log.info(
        "Ingesta de Azure DevOps: {} work items ({} actualizados), {} paginas de wiki ({} actualizadas)",
        workItems.vistos(),
        workItems.actualizados(),
        wiki.vistos(),
        wiki.actualizados());
    return new Resumen(
        workItems.vistos(),
        workItems.actualizados(),
        wiki.vistos(),
        wiki.actualizados(),
        workItems.chunks() + wiki.chunks());
  }

  private ResultadoParcial ingerirWorkItems() {
    long sourceId = repo.obtenerOCrearFuente(KIND, "work_items");

    String urlWiql =
        "/" + propiedades.org() + "/" + propiedades.proyecto() + "/_apis/wit/wiql?api-version=7.1";
    JsonNode resultadoWiql =
        Json.leer(cliente.post(urlWiql, Json.escribir(Map.of("query", WIQL_QUERY))));

    List<Long> ids = new ArrayList<>();
    JsonNode workItemsNodo = resultadoWiql.get("workItems");
    if (workItemsNodo != null) {
      for (JsonNode wi : workItemsNodo) {
        ids.add(wi.get("id").asLong());
      }
    }
    if (ids.isEmpty()) {
      return new ResultadoParcial(0, 0, 0);
    }

    Map<String, Object> cuerpoLote =
        Map.of(
            "ids",
            ids,
            "fields",
            List.of("System.Id", "System.Title", "System.Description", "System.WorkItemType"));
    JsonNode lote =
        Json.leer(
            cliente.post(
                "/" + propiedades.org() + "/_apis/wit/workitemsbatch?api-version=7.1",
                Json.escribir(cuerpoLote)));

    List<String> vistos = new ArrayList<>();
    int actualizados = 0;
    int chunksCreados = 0;

    JsonNode valores = lote.get("value");
    if (valores != null) {
      for (JsonNode wi : valores) {
        long id = wi.get("id").asLong();
        String externalId = String.valueOf(id);
        vistos.add(externalId);

        JsonNode campos = wi.get("fields");
        String titulo = campos == null ? "" : Json.textoDe(campos, "System.Title");
        String descripcion =
            campos == null ? "" : stripHtml(Json.textoDe(campos, "System.Description"));
        String texto = (titulo + "\n" + descripcion).strip();
        String hash = sha256Hex(texto.getBytes(StandardCharsets.UTF_8));

        var existente = repo.buscarDocumento(sourceId, externalId);
        if (existente.isPresent() && existente.get().contentHash().equals(hash)) {
          continue;
        }

        String uri =
            "https://dev.azure.com/"
                + propiedades.org()
                + "/"
                + propiedades.proyecto()
                + "/_workitems/edit/"
                + id;
        long documentoId =
            repo.upsertDocumento(
                sourceId, externalId, uri, titulo, texto, hash, ProyectoId.POR_DEFECTO.valor());

        int ord = 0;
        for (String ventana : ChunkerVentanas.trocear(texto, MAX_CARACTERES_WORK_ITEM)) {
          String distilled = Json.escribir(Map.of("summary", resumenDe(ventana)));
          long chunkId =
              repo.insertarChunk(
                  documentoId,
                  sourceId,
                  ProyectoId.POR_DEFECTO.valor(),
                  ord++,
                  "work_item",
                  ventana,
                  distilled);
          repo.encolarEmbeberChunk(chunkId);
          chunksCreados++;
        }
        actualizados++;
      }
    }

    for (long huerfano : repo.documentosHuerfanos(sourceId, vistos)) {
      repo.eliminarDocumento(huerfano);
    }
    return new ResultadoParcial(vistos.size(), actualizados, chunksCreados);
  }

  private ResultadoParcial ingerirWiki() {
    if (propiedades.wiki() == null || propiedades.wiki().isBlank()) {
      return new ResultadoParcial(0, 0, 0);
    }
    long sourceId = repo.obtenerOCrearFuente(KIND, "wiki:" + propiedades.wiki());

    JsonNode arbol = Json.leer(cliente.get(urlPagina("/", true, false)));
    List<String> rutas = aplanarPaginas(arbol);

    List<String> vistos = new ArrayList<>();
    int actualizados = 0;
    int chunksCreados = 0;

    for (String ruta : rutas) {
      vistos.add(ruta);
      JsonNode pagina = Json.leer(cliente.get(urlPagina(ruta, false, true)));
      String contenido = Json.textoDe(pagina, "content");
      if (contenido.isBlank()) {
        continue;
      }

      String hash = sha256Hex(contenido.getBytes(StandardCharsets.UTF_8));
      var existente = repo.buscarDocumento(sourceId, ruta);
      if (existente.isPresent() && existente.get().contentHash().equals(hash)) {
        continue;
      }

      String uri =
          "https://dev.azure.com/"
              + propiedades.org()
              + "/"
              + propiedades.proyecto()
              + "/_wiki/wikis/"
              + propiedades.wiki()
              + "?pagePath="
              + ruta;
      long documentoId =
          repo.upsertDocumento(
              sourceId, ruta, uri, ruta, contenido, hash, ProyectoId.POR_DEFECTO.valor());

      int ord = 0;
      for (ChunkerEncabezados.Seccion seccion : ChunkerEncabezados.trocear(contenido)) {
        String resumen = resumenDe(seccion.cuerpo());
        String tituloSeccion =
            seccion.rutaEncabezados().isEmpty()
                ? resumen
                : String.join(" › ", seccion.rutaEncabezados()) + ": " + resumen;
        String distilled = Json.escribir(Map.of("summary", tituloSeccion));
        long chunkId =
            repo.insertarChunk(
                documentoId,
                sourceId,
                ProyectoId.POR_DEFECTO.valor(),
                ord++,
                "wiki_section",
                seccion.cuerpo(),
                distilled);
        repo.encolarEmbeberChunk(chunkId);
        chunksCreados++;
      }
      actualizados++;
    }

    for (long huerfano : repo.documentosHuerfanos(sourceId, vistos)) {
      repo.eliminarDocumento(huerfano);
    }
    return new ResultadoParcial(vistos.size(), actualizados, chunksCreados);
  }

  private String urlPagina(String path, boolean recursionFull, boolean includeContent) {
    UriComponentsBuilder builder =
        UriComponentsBuilder.fromPath("/{org}/{proyecto}/_apis/wiki/wikis/{wiki}/pages")
            .queryParam("path", "{path}")
            .queryParam("api-version", "7.1");
    if (recursionFull) {
      builder.queryParam("recursionLevel", "Full");
    }
    if (includeContent) {
      builder.queryParam("includeContent", "true");
    }
    return builder
        .buildAndExpand(propiedades.org(), propiedades.proyecto(), propiedades.wiki(), path)
        .encode()
        .toUriString();
  }

  private static List<String> aplanarPaginas(JsonNode nodo) {
    List<String> rutas = new ArrayList<>();
    String path = Json.textoDe(nodo, "path");
    if (!path.isBlank()) {
      rutas.add(path);
    }
    JsonNode subPages = nodo.get("subPages");
    if (subPages != null && subPages.isArray()) {
      for (JsonNode hijo : subPages) {
        rutas.addAll(aplanarPaginas(hijo));
      }
    }
    return rutas;
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

  private static String resumenDe(String texto) {
    String vista = texto.replaceAll("\\s+", " ").strip();
    return vista.length() > LONGITUD_RESUMEN ? vista.substring(0, LONGITUD_RESUMEN) + "…" : vista;
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
