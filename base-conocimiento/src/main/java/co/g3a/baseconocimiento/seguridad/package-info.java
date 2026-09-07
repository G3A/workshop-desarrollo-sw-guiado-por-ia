/**
 * El filtro de token Bearer sobre el API programático: exige {@code Authorization: Bearer <token>}
 * en los endpoints sin sesión de persona logueada (ver {@link
 * co.g3a.baseconocimiento.seguridad.ApiTokenFilter} para las rutas exentas, como el Bot Connector y
 * la UI de chat). Solo conoce Servlet y Spring — una prueba de ArchUnit verifica que no llegue a
 * {@code recuperacion}, {@code ingesta}, {@code modelos} ni {@code llm}, la misma regla que aísla a
 * {@code web} y {@code teams}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Seguridad")
package co.g3a.baseconocimiento.seguridad;
