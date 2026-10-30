package co.g3a.baseconocimiento.acciones;

import static org.assertj.core.api.Assertions.assertThat;

import co.g3a.baseconocimiento.acciones.Capitulos.Capitulo;
import co.g3a.baseconocimiento.acciones.SeccionesRepositorio.Seccion;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Cómo se parte un documento largo en capítulos para el modo «Por capítulos» (issue #57), con las
 * dos estructuras reales medidas: la especificación de Java (el capítulo es el primer nivel de la
 * ruta) y la NIST SP 800-53 (el primer nivel es el título del documento y las familias de controles
 * están más abajo).
 */
class CapitulosTest {

  private final List<Seccion> secciones = new ArrayList<>();
  private final Map<Long, List<String>> rutas = new HashMap<>();

  @Test
  @DisplayName("JLS: el capítulo es el primer nivel de la ruta")
  void jlsAgrupaPorElPrimerNivel() {
    for (int c = 1; c <= 19; c++) {
      for (int s = 1; s <= 3; s++) {
        seccion(List.of("Capítulo " + c, c + "." + s + " Sección"), "texto " + c + "." + s);
      }
    }

    List<Capitulo> capitulos = Capitulos.agrupar(secciones, rutas);

    assertThat(capitulos).hasSize(19);
    assertThat(capitulos.getFirst().titulo()).isEqualTo("Capítulo 1");
    assertThat(capitulos.getFirst().secciones()).hasSize(3);
  }

  @Test
  @DisplayName(
      "NIST: si el primer nivel no separa nada, baja hasta un nivel que dé capítulos útiles")
  void nistBajaHastaLasFamilias() {
    String doc = "Security and Privacy Controls";
    for (String familia :
        List.of(
            "3.1 ACCESS CONTROL",
            "3.2 AWARENESS",
            "3.3 AUDIT",
            "3.4 ASSESSMENT",
            "3.5 CONFIGURATION",
            "3.6 CONTINGENCY")) {
      for (int k = 1; k <= 4; k++) {
        seccion(List.of(doc, "THE CONTROLS", familia, "XX-" + k + " CONTROL"), "texto");
      }
    }

    List<Capitulo> capitulos = Capitulos.agrupar(secciones, rutas);

    assertThat(capitulos)
        .extracting(Capitulo::titulo)
        .startsWith("3.1 ACCESS CONTROL", "3.2 AWARENESS");
    assertThat(capitulos).hasSize(6);
  }

  @Test
  @DisplayName("Una sección sin ruta en medio de un capítulo se queda en ese capítulo")
  void seccionSinRutaNoParteElCapitulo() {
    for (int c = 1; c <= 6; c++) {
      seccion(List.of("Capítulo " + c, c + ".1"), "texto");
      seccion(List.of(), "una tabla suelta");
      seccion(List.of("Capítulo " + c, c + ".2"), "texto");
    }

    List<Capitulo> capitulos = Capitulos.agrupar(secciones, rutas);

    assertThat(capitulos).hasSize(6).allMatch(c -> c.secciones().size() == 3);
    assertThat(capitulos).extracting(Capitulo::titulo).doesNotContain("Sin título");
  }

  @Test
  @DisplayName("Sin rutas, agrupa por orden en bloques de tamaño parejo")
  void sinRutasAgrupaPorOrden() {
    for (int i = 0; i < 100; i++) {
      seccion(List.of(), "texto " + i);
    }

    List<Capitulo> capitulos = Capitulos.agrupar(secciones, rutas);

    assertThat(capitulos.size()).isBetween(Capitulos.MINIMO, Capitulos.MAXIMO);
    assertThat(capitulos.stream().mapToInt(c -> c.secciones().size()).sum()).isEqualTo(100);
    assertThat(capitulos.getFirst().titulo()).isEqualTo("Secciones 1–5");
  }

  @Test
  @DisplayName("El extracto de un capítulo no pasa del largo pedido y nombra cada sección que toma")
  void extractoAcotado() {
    for (int s = 1; s <= 60; s++) {
      seccion(List.of("Capítulo 1", "1." + s + " Título"), "palabra ".repeat(200));
    }
    Capitulo capitulo = Capitulos.agrupar(secciones, rutas).getFirst();

    String extracto = Capitulos.extracto(capitulo, rutas, 6000);

    assertThat(extracto.length()).isLessThanOrEqualTo(6000);
    assertThat(extracto).contains("1.1 Título").contains("palabra");
  }

  @Test
  @DisplayName("La ruta guardada en el summary se recupera para lo ya indexado")
  void rutaDesdeElSummary() {
    assertThat(Capitulos.rutaDelSummary("Types › 4.2 Primitive Types: The integral types are"))
        .containsExactly("Types", "4.2 Primitive Types");
    assertThat(Capitulos.rutaDelSummary("Introduction: This specification describes"))
        .containsExactly("Introduction");
    assertThat(Capitulos.rutaDelSummary("texto sin ruta. Con una oración: y dos puntos")).isEmpty();
    assertThat(Capitulos.rutaDelSummary(null)).isEmpty();
  }

  private void seccion(List<String> ruta, String texto) {
    long id = secciones.size() + 1;
    secciones.add(new Seccion(id, 1, "file:///doc.pdf", "doc.pdf", (int) id, texto, "doc_section"));
    rutas.put(id, ruta);
  }
}
