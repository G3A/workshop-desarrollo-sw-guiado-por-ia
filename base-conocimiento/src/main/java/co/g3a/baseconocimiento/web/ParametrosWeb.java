package co.g3a.baseconocimiento.web;

import co.g3a.baseconocimiento.compartido.Dominio.ProyectoId;
import java.util.Arrays;
import java.util.List;

/**
 * Como los dos controladores de la pagina de chat leen sus parametros: el proyecto (vacio = el de
 * siempre) y la lista de documentos. Compartido entre {@code ChatController} y {@code
 * AccionesController} para que un CSV mal formado se rechace igual en los dos (hallazgo 7 de la
 * revision del plan del issue #38), igual que {@link Json} es el utilitario compartido del paquete.
 */
final class ParametrosWeb {

  static final String MENSAJE_IDS_INVALIDOS =
      "El parámetro documentos debe ser una lista de ids numéricos separados por coma";

  private ParametrosWeb() {}

  static ProyectoId proyectoDe(String projectId) {
    return (projectId == null || projectId.isBlank())
        ? ProyectoId.POR_DEFECTO
        : new ProyectoId(projectId);
  }

  static List<Long> documentosDe(List<Long> documentos) {
    return documentos == null ? List.of() : documentos;
  }

  /**
   * IDs separados por coma: {@code EventSource} solo sabe hacer GET, asi que el filtro viaja en un
   * query param plano, no en un cuerpo JSON con lista. Vacio o ausente = sin restriccion.
   *
   * @throws IllegalArgumentException si algun valor no es un entero: el que llama decide si eso es
   *     un 400 (antes de abrir un stream) o un error en el stream
   */
  static List<Long> documentosDe(String documentosCsv) {
    if (documentosCsv == null || documentosCsv.isBlank()) {
      return List.of();
    }
    try {
      return Arrays.stream(documentosCsv.split(","))
          .map(String::trim)
          .filter(s -> !s.isEmpty())
          .map(Long::parseLong)
          .toList();
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(MENSAJE_IDS_INVALIDOS, e);
    }
  }
}
