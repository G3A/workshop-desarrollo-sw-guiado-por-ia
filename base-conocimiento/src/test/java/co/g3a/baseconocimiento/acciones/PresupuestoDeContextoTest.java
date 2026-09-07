package co.g3a.baseconocimiento.acciones;

import static org.assertj.core.api.Assertions.assertThat;

import co.g3a.baseconocimiento.acciones.Acciones.CoberturaDocumento;
import co.g3a.baseconocimiento.acciones.PresupuestoDeContexto.DocumentoRecortado;
import co.g3a.baseconocimiento.acciones.SeccionesRepositorio.Seccion;
import co.g3a.baseconocimiento.compartido.Dominio.Cita;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La decision central del modulo (issue #38, hallazgo 1 de la revision adversarial): que entra al
 * contexto del LLM, que se recorta, y que se declara. Sin Spring ni LLM: es aritmetica sobre
 * secciones.
 */
class PresupuestoDeContextoTest {

  @Test
  @DisplayName(
      "Reparto equitativo con redistribucion: el corto entra entero y deja su sobrante al largo, "
          + "que entra recortado y lo declara")
  void repartoConRedistribucion() {
    // Presupuesto 100. A mide 40 (2 secciones de 20); B mide 150 (5 de 30).
    // A toma min(40, 100/2) = 40 y deja 60; B toma 60 = sus 2 primeras secciones.
    List<Seccion> secciones = new ArrayList<>();
    // 'z' y 'b' a proposito: ninguna de las dos letras aparece en las cabeceras ni en el
    // aviso de recorte, asi el conteo mide solo el texto de las secciones.
    secciones.add(seccion(1, 10, "A", 0, "z".repeat(20)));
    secciones.add(seccion(2, 10, "A", 1, "z".repeat(20)));
    for (int i = 0; i < 5; i++) {
      secciones.add(seccion(3 + i, 20, "B", i, "b".repeat(30)));
    }
    var presupuesto = new PresupuestoDeContexto(100);

    List<DocumentoRecortado> docs = presupuesto.recortar(List.of(10L, 20L), secciones);
    String contexto = PresupuestoDeContexto.contexto(docs);

    assertThat(PresupuestoDeContexto.cobertura(docs))
        .containsExactly(
            new CoberturaDocumento(10L, "A", "file:///A", 2, 2, false),
            new CoberturaDocumento(20L, "B", "file:///B", 2, 5, false));
    assertThat(PresupuestoDeContexto.cobertura(docs).get(0).completa()).isTrue();
    assertThat(PresupuestoDeContexto.cobertura(docs).get(1).completa()).isFalse();
    assertThat(contexto)
        .contains("[1] A (file:///A)")
        .contains("[2] B (file:///B)")
        .contains("(Documento recortado: se incluyen solo las primeras 2 de 5 secciones)")
        .doesNotContain("2 de 2");
    assertThat(contexto.chars().filter(c -> c == 'b').count()).isEqualTo(60);
    assertThat(contexto.chars().filter(c -> c == 'z').count()).isEqualTo(40);
  }

  @Test
  @DisplayName(
      "Diez documentos cuya primera seccion es mas larga que su cuota: la primera seccion "
          + "tambien se recorta, el texto nunca supera el presupuesto y se declara")
  void laPrimeraSeccionTambienSeRecorta() {
    List<Seccion> secciones = new ArrayList<>();
    List<Long> pedidos = new ArrayList<>();
    for (long doc = 1; doc <= 10; doc++) {
      pedidos.add(doc);
      secciones.add(seccion(doc, doc, "D" + doc, 0, "x".repeat(4000)));
    }
    var presupuesto = new PresupuestoDeContexto(7000);

    List<DocumentoRecortado> docs = presupuesto.recortar(pedidos, secciones);
    String contexto = PresupuestoDeContexto.contexto(docs);

    assertThat(contexto.chars().filter(c -> c == 'x').count()).isLessThanOrEqualTo(7000);
    assertThat(PresupuestoDeContexto.cobertura(docs))
        .allSatisfy(
            c -> {
              assertThat(c.seccionesIncluidas()).isEqualTo(1);
              assertThat(c.seccionesTotales()).isEqualTo(1);
              assertThat(c.primeraRecortada()).isTrue();
              assertThat(c.completa()).isFalse();
            });
    assertThat(contexto)
        .contains("(Documento recortado: entra solo el comienzo de la seccion 1 de 1)");
    // Las cabeceras y avisos son lo unico que se suma al presupuesto: acotados.
    assertThat(contexto.length()).isLessThanOrEqualTo(7000 + 10 * 160);
  }

  @Test
  @DisplayName("Si todos caben, todos entran enteros y no hay aviso de recorte")
  void todosCaben() {
    List<Seccion> secciones =
        List.of(
            seccion(1, 1, "U", 0, "u".repeat(10)),
            seccion(2, 2, "D", 0, "d".repeat(10)),
            seccion(3, 3, "T", 0, "t".repeat(10)));
    var presupuesto = new PresupuestoDeContexto(100);

    List<DocumentoRecortado> docs = presupuesto.recortar(List.of(1L, 2L, 3L), secciones);

    assertThat(PresupuestoDeContexto.cobertura(docs)).allMatch(CoberturaDocumento::completa);
    assertThat(PresupuestoDeContexto.contexto(docs)).doesNotContain("Documento recortado");
  }

  @Test
  @DisplayName(
      "Respeta el orden de la persona, ignora duplicados y deja los inexistentes al final con 0/0")
  void ordenDuplicadosEInexistentes() {
    List<Seccion> secciones =
        List.of(seccion(1, 10, "T10", 0, "diez"), seccion(2, 20, "T20", 0, "veinte"));
    var presupuesto = new PresupuestoDeContexto(1000);

    List<DocumentoRecortado> docs = presupuesto.recortar(List.of(20L, 999L, 10L, 20L), secciones);

    assertThat(PresupuestoDeContexto.cobertura(docs))
        .extracting(CoberturaDocumento::documentoId)
        .containsExactly(20L, 10L, 999L);
    CoberturaDocumento inexistente = PresupuestoDeContexto.cobertura(docs).get(2);
    assertThat(inexistente.indexado()).isFalse();
    assertThat(inexistente.seccionesTotales()).isZero();
    // El [n] del contexto y las citas cuentan solo los indexados, en ese orden.
    assertThat(PresupuestoDeContexto.contexto(docs))
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
    var presupuesto = new PresupuestoDeContexto(1000);
    List<DocumentoRecortado> cinco = presupuesto.recortar(pedidos, secciones);

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
