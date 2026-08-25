package co.g3a.baseconocimiento.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * "/" ya sirve {@code static/index.html} como welcome page (autoconfiguración de Spring Boot, vía
 * un forward interno a "/index.html") — sin este filtro, "/index.html" servía el mismo archivo
 * también por su propia ruta, dos URLs distintas para la misma página. "/" queda como la única
 * canónica.
 *
 * <p>Tiene que ser un {@code Filter} y no un {@code @GetMapping("/index.html")}: un controller
 * intercepta TAMBIÉN el forward interno de la welcome page (que en el servidor reentra por la misma
 * ruta "/index.html"), así que "/" terminaba rebotando en un 302 en vez de servir la página. Un
 * {@code FilterRegistrationBean} sin dispatcher types explícitos solo corre en el REQUEST original
 * del cliente, no en forwards internos — por eso acá sí funciona.
 */
class RedireccionIndiceFilter extends HttpFilter {

  private static final long serialVersionUID = 1L;

  @Override
  protected void doFilter(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    response.sendRedirect("/");
  }
}
