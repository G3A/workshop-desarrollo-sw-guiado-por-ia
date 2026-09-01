package co.g3a.baseconocimiento.recuperacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Propiedades de {@link RrfFusion}: la fusión por rango del artículo, sin normalizar puntajes. Ver
 * la sección "Pruebas automatizadas" del plan.
 */
class RrfFusionTest {

  private static final RrfFusion.Pesos PESOS_IGUALES = new RrfFusion.Pesos(1.0, 1.0, 1.0, 1.0);

  @Property
  @DisplayName(
      "Dentro de una sola senal, la fusion respeta el rango: el puntaje decrece estrictamente")
  void monotonoDentroDeUnaSenal(@ForAll @IntRange(min = 2, max = 15) int n) {
    List<CandidatoSenal> candidatos = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      candidatos.add(candidato(i, n - i));
    }

    List<CandidatoFusionado> fusionados =
        RrfFusion.fusionar(Map.of(Senal.FTS, candidatos), PESOS_IGUALES, 60, n, n);

    assertThat(fusionados).hasSize(n);
    for (int i = 0; i < n; i++) {
      assertThat(fusionados.get(i).chunkId()).isEqualTo(candidatos.get(i).chunkId());
    }
    for (int i = 1; i < n; i++) {
      assertThat(fusionados.get(i - 1).rrf())
          .as("el candidato en mejor rango debe puntuar mas alto")
          .isGreaterThan(fusionados.get(i).rrf());
    }
  }

  @Property
  @DisplayName("El consenso entre senales gana a un primer puesto aislado en una sola senal")
  void elConsensoGanaAUnPrimerPuestoAislado(
      @ForAll @IntRange(min = 2, max = 4) int numeroDeSenalesConB,
      @ForAll @IntRange(min = 1, max = 1000) int k) {

    // A: primer puesto, pero solo en FTS.
    CandidatoSenal a = candidato(1, 1.0);
    // B: nunca primero -- siempre segundo, detras de un relleno distinto en cada senal --
    // pero presente en varias senales.
    CandidatoSenal b = candidato(2, 1.0);

    Senal[] senales = Senal.values();
    Map<Senal, List<CandidatoSenal>> porSenal = new EnumMap<>(Senal.class);
    porSenal.put(Senal.FTS, List.of(a, b));
    for (int i = 1; i < numeroDeSenalesConB; i++) {
      CandidatoSenal relleno = candidato(100 + i, 1.0);
      porSenal.put(senales[i], List.of(relleno, b));
    }

    List<CandidatoFusionado> fusionados = RrfFusion.fusionar(porSenal, PESOS_IGUALES, k, 10, 10);

    Map<Long, Double> rrfPorId = new HashMap<>();
    fusionados.forEach(c -> rrfPorId.put(c.chunkId(), c.rrf()));

    assertThat(rrfPorId.get(2L))
        .as(
            "el consenso (nunca primero, pero presente en varias senales) debe superar "
                + "a un aislado en primer puesto")
        .isGreaterThan(rrfPorId.get(1L));
    assertThat(fusionados.get(0).chunkId())
        .as("por eso B debe quedar primero en la lista fusionada")
        .isEqualTo(2L);
  }

  @Test
  @DisplayName("Un candidato ausente de una senal no es penalizado: esa senal aporta 0")
  void ausenciaAportaCero() {
    CandidatoSenal soloEnFts = candidato(1, 5.0);
    CandidatoSenal enAmbas = candidato(2, 3.0);

    List<CandidatoFusionado> soloConFts =
        RrfFusion.fusionar(
            Map.of(Senal.FTS, List.of(soloEnFts, enAmbas)), PESOS_IGUALES, 60, 10, 10);
    double rrfSoloFts = puntajeDe(soloConFts, 1L);

    List<CandidatoFusionado> conVectorTambien =
        RrfFusion.fusionar(
            Map.of(Senal.FTS, List.of(soloEnFts, enAmbas), Senal.VECTOR, List.of(enAmbas)),
            PESOS_IGUALES,
            60,
            10,
            10);
    double rrfConVectorTambien = puntajeDe(conVectorTambien, 1L);

    assertThat(rrfConVectorTambien)
        .as("agregar una senal en la que el candidato 1 no aparece no debe cambiar su puntaje")
        .isEqualTo(rrfSoloFts);
  }

  @Test
  @DisplayName("El tope por documento limita cuantos chunks del mismo documento sobreviven")
  void topePorDocumentoLimitaDiversidad() {
    List<CandidatoSenal> tresDelMismoDocumento =
        List.of(
            candidatoEnDocumento(1, 1, 3.0),
            candidatoEnDocumento(2, 1, 2.0),
            candidatoEnDocumento(3, 1, 1.0));

    List<CandidatoFusionado> fusionados =
        RrfFusion.fusionar(Map.of(Senal.FTS, tresDelMismoDocumento), PESOS_IGUALES, 60, 2, 10);

    assertThat(fusionados).hasSize(2);
    assertThat(fusionados).extracting(CandidatoFusionado::chunkId).containsExactly(1L, 2L);
  }

  private static double puntajeDe(List<CandidatoFusionado> candidatos, long chunkId) {
    return candidatos.stream().filter(c -> c.chunkId() == chunkId).findFirst().orElseThrow().rrf();
  }

  private static CandidatoSenal candidato(long id, double puntaje) {
    return candidatoEnDocumento(id, id, puntaje);
  }

  private static CandidatoSenal candidatoEnDocumento(long id, long documentoId, double puntaje) {
    return new CandidatoSenal(
        id,
        documentoId,
        "file:///" + id,
        "titulo " + id,
        "texto " + id,
        "doc_section",
        0,
        Instant.EPOCH,
        puntaje);
  }
}
