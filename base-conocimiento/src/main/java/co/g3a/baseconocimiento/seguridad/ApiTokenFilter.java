package co.g3a.baseconocimiento.seguridad;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/**
 * Exige {@code Authorization: Bearer <KB_API_TOKEN>} en los endpoints programaticos ({@code
 * /api/ask}, {@code /api/search}, {@code /api/ingest/*}).
 *
 * <p>Deja afuera, a proposito:
 *
 * <ul>
 *   <li>{@code /api/messages} — el Bot Connector ya trae su propia validacion de JWT contra Azure
 *       AD ({@code ValidadorTokenBotFramework}); exigir ademas este token rechazaria trafico
 *       legitimo de Azure Bot Service.
 *   <li>{@code /api/chat}, {@code /api/chat/estado} y {@code /api/preview} — la UI web de F4. El
 *       {@code EventSource} nativo del navegador no puede mandar cabeceras propias, y estas rutas
 *       son justamente el "keyword search on landing" (mas la reconexion tras un F5, ver {@code
 *       StreamsEnCursoRepositorio}) pensadas para no tener friccion. El MVP no tiene login de
 *       persona (ver Supuestos del plan): este token protege llamadas programaticas, no la pagina
 *       que cualquiera con acceso a la red ya puede abrir.
 *   <li>{@code /api/admin/ayuda}, {@code /api/admin/proyectos} y {@code /api/admin/documentos}
 *       (F9/F10) — el botón {@code ?}, el selector de proyecto y el checklist de documentos activos
 *       por conversación viven también en la página de chat, con el mismo problema de {@code
 *       /api/chat}: no hay una sesión de persona logueada que les pase un token. Los tres son de
 *       solo lectura y no exponen contenido del corpus (rutas/config el primero, nombres de
 *       proyecto el segundo, id+título de documento el tercero) — el resto de {@code /api/admin/*}
 *       (fuentes, reindexar, la cola, subir/borrar archivos) sigue exigiendo el token.
 *   <li>{@code /api/vault/contenido} — el visor modal de citas de la página de chat, mismo motivo
 *       que {@code /api/chat}: sin sesión ni token. Acotado igual a solo lectura sobre archivos
 *       indexados de verdad (no cualquier archivo físicamente presente bajo el vault) — ver el
 *       chequeo contra {@code documents.uri} en {@code ContenidoVaultController}.
 *   <li>{@code /api/feedback} — los botones 👍/👎 bajo cada respuesta, mismo motivo que {@code
 *       /api/chat}: la página no maneja el token, así que si esta ruta lo exigiera el botón nunca
 *       podría llamarla. Riesgo aceptado y documentado en {@code Consultar.registrarFeedback}: sin
 *       sesión de persona, nada valida que quien manda el feedback vio realmente esa respuesta.
 * </ul>
 */
class ApiTokenFilter extends HttpFilter {

  private static final long serialVersionUID = 1L;
  private static final Logger log = LoggerFactory.getLogger(ApiTokenFilter.class);
  private static final Set<String> RUTAS_SIN_TOKEN =
      Set.of(
          "/api/messages",
          "/api/chat",
          "/api/chat/estado",
          "/api/preview",
          "/api/admin/ayuda",
          "/api/admin/proyectos",
          "/api/admin/documentos",
          "/api/vault/contenido",
          "/api/feedback");

  private final transient SeguridadPropiedades propiedades;

  ApiTokenFilter(SeguridadPropiedades propiedades) {
    this.propiedades = propiedades;
  }

  @Override
  protected void doFilter(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    if (!propiedades.habilitada() || RUTAS_SIN_TOKEN.contains(request.getRequestURI())) {
      chain.doFilter(request, response);
      return;
    }

    String recibido = request.getHeader(HttpHeaders.AUTHORIZATION);
    String esperado = "Bearer " + propiedades.apiToken();
    if (recibido == null || !coincideEnTiempoConstante(recibido, esperado)) {
      log.warn(
          "Peticion a {} rechazada: falta o no coincide Authorization Bearer",
          request.getRequestURI());
      response.setStatus(HttpStatus.UNAUTHORIZED.value());
      return;
    }
    chain.doFilter(request, response);
  }

  private static boolean coincideEnTiempoConstante(String recibido, String esperado) {
    return MessageDigest.isEqual(
        recibido.getBytes(StandardCharsets.UTF_8), esperado.getBytes(StandardCharsets.UTF_8));
  }
}
