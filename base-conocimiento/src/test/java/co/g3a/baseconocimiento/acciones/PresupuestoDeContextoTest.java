package co.g3a.baseconocimiento.acciones;

import static org.assertj.core.api.Assertions.assertThat;

import co.g3a.baseconocimiento.acciones.Acciones.CoberturaDocumento;
import co.g3a.baseconocimiento.acciones.PresupuestoDeContexto.DocumentoPlanificado;
import co.g3a.baseconocimiento.acciones.SeccionesRepositorio.Seccion;
import co.g3a.baseconocimiento.compartido.Dominio.Cita;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La decision central del modulo (issue #38, sub-issue #60): que entra al contexto del LLM tal
 * cual, que se lee por pasadas, cuantas pasadas son, y que se declara. Sin Spring ni LLM: es
 * aritmetica sobre secciones.
 */
class PresupuestoDeContextoTest {

  @Test
  @DisplayName(
      "Reparto equitativo con redistribucion: el corto entra tal cual y deja su sobrante al largo, "
          + "que no se recorta: se lee por pasadas y entra como notas")
  void repartoConRedistribucion() {
    // Presupuesto 100, lectura 50. A mide 40 (2 secciones de 20); B mide 150 (5 de 30).
    // A toma min(40, 100/2) = 40 y deja 60; B no cabe en 60: 5 tramos de una seccion
    // (dos de 30 mas el separador superan los 50 de una pasada).
    List<Seccion> secciones = new ArrayList<>();
    // 'z' y 'b' a proposito: ninguna de las dos letras aparece en las cabeceras ni en la
    // linea de notas, asi el conteo mide solo el texto de las secciones.
    secciones.add(seccion(1, 10, "A", 0, "z".repeat(20)));
    secciones.add(seccion(2, 10, "A", 1, "z".repeat(20)));
    for (int i = 0; i < 5; i++) {
      secciones.add(seccion(3 + i, 20, "B", i, "b".repeat(30)));
    }
    var presupuesto = new PresupuestoDeContexto(100, 50);

    List<DocumentoPlanificado> docs = presupuesto.planificar(List.of(10L, 20L), secciones);

    // 5 tramos; de a 2 por grupo: 5 + 3 + 2 + 1 = 11 pasadas como maximo.
    assertThat(PresupuestoDeContexto.cobertura(docs))
        .containsExactly(
            new CoberturaDocumento(10L, "A", "file:///A", 2, 0),
            new CoberturaDocumento(20L, "B", "file:///B", 5, 11));
    assertThat(docs.get(0).porPasadas()).isFalse();
    assertThat(docs.get(1).tramos()).hasSize(5).allMatch(t -> t.length() <= 50);
    assertThat(docs.get(1).cuota()).isEqualTo(60);

    String contexto = PresupuestoDeContexto.contexto(docs, Map.of(20L, "notas de B"));
    assertThat(contexto)
        .contains("[1] A (file:///A)")
        .contains("[2] B (file:///B)")
        .contains("notas de lectura")
        .contains("notas de B")
        .doesNotContain("bbbbb");
    assertThat(contexto.chars().filter(c -> c == 'z').count()).isEqualTo(40);
  }

  @Test
  @DisplayName(
      "Diez documentos de una seccion de 4000 con presupuesto 7000: ninguno se recorta, cada uno "
          + "se lee en una pasada y a la llamada final entran sus notas")
  void diezDocumentosLargosVanPorPasadas() {
    List<Seccion> secciones = new ArrayList<>();
    List<Long> pedidos = new ArrayList<>();
    Map<Long, String> notas = new java.util.HashMap<>();
    for (long doc = 1; doc <= 10; doc++) {
      pedidos.add(doc);
      secciones.add(seccion(doc, doc, "D" + doc, 0, "x".repeat(4000)));
      // 'k' a proposito: no aparece en las cabeceras ni en la linea de notas.
      notas.put(doc, "k".repeat(600));
    }
    var presupuesto = new PresupuestoDeContexto(7000, 10000);

    List<DocumentoPlanificado> docs = presupuesto.planificar(pedidos, secciones);

    assertThat(PresupuestoDeContexto.cobertura(docs))
        .allSatisfy(
            c -> {
              assertThat(c.seccionesTotales()).isEqualTo(1);
              assertThat(c.pasadas()).isEqualTo(1);
            });
    assertThat(docs).allMatch(d -> d.cuota() == 700 && d.tramos().size() == 1);
    String contexto = PresupuestoDeContexto.contexto(docs, notas);
    assertThat(contexto).doesNotContain("xxxx");
    assertThat(contexto.chars().filter(c -> c == 'k').count()).isEqualTo(6000);
  }

  @Test
  @DisplayName("Si todos caben, todos entran tal cual, sin pasadas ni linea de notas")
  void todosCaben() {
    List<Seccion> secciones =
        List.of(
            seccion(1, 1, "U", 0, "u".repeat(10)),
            seccion(2, 2, "D", 0, "d".repeat(10)),
            seccion(3, 3, "T", 0, "t".repeat(10)));
    var presupuesto = new PresupuestoDeContexto(100, 50);

    List<DocumentoPlanificado> docs = presupuesto.planificar(List.of(1L, 2L, 3L), secciones);

    assertThat(PresupuestoDeContexto.cobertura(docs)).allMatch(c -> c.pasadas() == 0);
    assertThat(docs).noneMatch(DocumentoPlanificado::porPasadas);
    assertThat(PresupuestoDeContexto.contexto(docs, Map.of()))
        .doesNotContain("notas de lectura")
        .contains("uuuuuuuuuu")
        .contains("tttttttttt");
  }

  @Test
  @DisplayName("Las pasadas se saben de antemano: cada nivel condensa porGrupo notas en una")
  void pasadasEstimadas() {
    // jls25.pdf: 1060 secciones de hasta 4000 en tramos de 10000 son ~530 tramos; de a 8:
    // 530 + 67 + 9 + 2 = 608 (9 notas de 1200 no caben en 7000; 2 si).
    assertThat(PresupuestoDeContexto.pasadasEstimadas(530, 7000, 8)).isEqualTo(608);
    // Un solo tramo: una pasada, aunque la cuota sea menor que una nota.
    assertThat(PresupuestoDeContexto.pasadasEstimadas(1, 700, 8)).isEqualTo(1);
    // Tres notas de 1200 caben en 7000: un solo nivel.
    assertThat(PresupuestoDeContexto.pasadasEstimadas(3, 7000, 8)).isEqualTo(3);
    assertThat(new PresupuestoDeContexto(7000, 10000).notasPorGrupo()).isEqualTo(8);
    // Nunca menos de dos por grupo, o un nivel no reduciria nada.
    assertThat(new PresupuestoDeContexto(100, 50).notasPorGrupo()).isEqualTo(2);
  }

  @Test
  @DisplayName(
      "Los tramos respetan el largo: una seccion mas larga se parte por parrafos y las piezas "
          + "consecutivas se juntan mientras quepan, sin perder texto")
  void tramos() {
    List<Seccion> secciones =
        List.of(seccion(1, 1, "L", 0, "a".repeat(4000)), seccion(2, 1, "L", 1, "c".repeat(100)));

    List<String> tramos = PresupuestoDeContexto.tramos(secciones, 1500);

    assertThat(tramos).hasSize(3).allMatch(t -> t.length() <= 1500);
    assertThat(String.join("", tramos).chars().filter(c -> c == 'a').count()).isEqualTo(4000);
    assertThat(tramos.get(2)).contains("c".repeat(100));

    assertThat(PresupuestoDeContexto.partirTexto("p1\n\np2", 5)).containsExactly("p1", "p2");
    assertThat(PresupuestoDeContexto.partirTexto("p1\n\np2", 10)).containsExactly("p1\n\np2");
  }

  @Test
  @DisplayName(
      "Respeta el orden de la persona, ignora duplicados y deja los inexistentes al final con 0/0")
  void ordenDuplicadosEInexistentes() {
    List<Seccion> secciones =
        List.of(seccion(1, 10, "T10", 0, "diez"), seccion(2, 20, "T20", 0, "veinte"));
    var presupuesto = new PresupuestoDeContexto(1000, 500);

    List<DocumentoPlanificado> docs =
        presupuesto.planificar(List.of(20L, 999L, 10L, 20L), secciones);

    assertThat(PresupuestoDeContexto.cobertura(docs))
        .extracting(CoberturaDocumento::documentoId)
        .containsExactly(20L, 10L, 999L);
    CoberturaDocumento inexistente = PresupuestoDeContexto.cobertura(docs).get(2);
    assertThat(inexistente.indexado()).isFalse();
    assertThat(inexistente.seccionesTotales()).isZero();
    // El [n] del contexto y las citas cuentan solo los indexados, en ese orden.
    assertThat(PresupuestoDeContexto.contexto(docs, Map.of()))
        .contains("[1] T20 (file:///T20)")
        .contains("[2] T10 (file:///T10)")
        .doesNotContain("999");
    assertThat(PresupuestoDeContexto.citas(docs))
        .extracting(Cita::titulo)
        .containsExactly("T20", "T10");
  }

  @Test
  @DisplayName("La etiqueta lista hasta tres titulos y resume el resto como 'y N mas'")
  void etiqueta() {
    List<Seccion> secciones = new ArrayList<>();
    List<Long> pedidos = new ArrayList<>();
    String[] titulos = {"uno", "dos", "tres", "cuatro", "cinco"};
    for (int i = 0; i < titulos.length; i++) {
      pedidos.add((long) i + 1);
      secciones.add(seccion(i + 1, i + 1, titulos[i], 0, "x"));
    }
    var presupuesto = new PresupuestoDeContexto(1000, 500);
    List<DocumentoPlanificado> cinco = presupuesto.planificar(pedidos, secciones);

    assertThat(PresupuestoDeContexto.etiqueta("Resumen de", cinco))
        .isEqualTo("Resumen de 5 documentos: uno, dos, tres y 2 más");
    assertThat(PresupuestoDeContexto.etiqueta("Síntesis de", cinco.subList(0, 1)))
        .isEqualTo("Síntesis de 1 documento: uno");
    assertThat(PresupuestoDeContexto.etiqueta("Resumen de", List.of()))
        .isEqualTo("Resumen de documentos seleccionados");
  }

  private static Seccion seccion(long id, long documentoId, String titulo, int ord, String texto) {
    return new Seccion(id, documentoId, "file:///" + titulo, titulo, ord, texto, "doc_section");
  }
}
