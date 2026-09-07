package co.g3a.baseconocimiento.orquestacion;

import static org.assertj.core.api.Assertions.assertThat;

import co.g3a.baseconocimiento.compartido.Dominio.Fragmento;
import co.g3a.baseconocimiento.orquestacion.UmbralRelevancia.Decision;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UmbralRelevanciaTest {

  private static final UmbralRelevanciaPropiedades PROPS =
      new UmbralRelevanciaPropiedades(true, 0.003, 0.05, 500, 8.0);

  @Test
  @DisplayName(
      "Umbral efectivo: interpola linealmente entre piso y techo segun chunks del proyecto")
  void interpolaLinealmente() {
    assertThat(UmbralRelevancia.umbralEfectivo(0, PROPS)).isEqualTo(0.003);
    assertThat(UmbralRelevancia.umbralEfectivo(250, PROPS)).isCloseTo(0.0265, Offset.offset(1e-9));
    assertThat(UmbralRelevancia.umbralEfectivo(500, PROPS)).isEqualTo(0.05);
    assertThat(UmbralRelevancia.umbralEfectivo(10_000, PROPS)).isEqualTo(0.05);
  }

  @Test
  @DisplayName("Rechaza sin verificar cuando el mejor rerank no alcanza el umbral del corpus")
  void rechazaContextoClaramenteIrrelevante() {
    // "explicame como usar Java 25" contra el corpus semilla del taller (ADR-0008).
    Fragmento debil = fragmento(0.0019);

    UmbralRelevancia.Resultado resultado = UmbralRelevancia.evaluar(List.of(debil), 4, PROPS);

    assertThat(resultado.decision()).isEqualTo(Decision.INSUFICIENTE);
    assertThat(resultado.mejorPuntaje()).isEqualTo(0.0019);
  }

  @Test
  @DisplayName("Ambiguo: score por encima del umbral pero lejos de la certeza va a verificacion")
  void mandaAVerificacionCuandoElScoreEsAmbiguo() {
    // "cómo se despliega el servicio" -- relevante de verdad, pero el score no
    // alcanza techoConfianza: no se acepta a ciegas, se verifica.
    Fragmento relevante = fragmento(0.0055);

    UmbralRelevancia.Resultado resultado = UmbralRelevancia.evaluar(List.of(relevante), 4, PROPS);

    assertThat(resultado.decision()).isEqualTo(Decision.AMBIGUO);
  }

  @Test
  @DisplayName(
      "Ambiguo tambien atrapa el contraejemplo real: irrelevante que puntuo mas alto que lo relevante")
  void mandaAVerificacionElContraejemploDeAdr0008() {
    // "como usar java 25" puntuo 0.0134 -- mas alto que la pregunta relevante de
    // arriba (0.0055) -- por eso el score solo NO puede aceptarla a ciegas.
    Fragmento irrelevantePeroConScoreAlto = fragmento(0.0134);

    UmbralRelevancia.Resultado resultado =
        UmbralRelevancia.evaluar(List.of(irrelevantePeroConScoreAlto), 4, PROPS);

    assertThat(resultado.decision()).isEqualTo(Decision.AMBIGUO);
  }

  @Test
  @DisplayName(
      "Ambiguo tambien atrapa el segundo contraejemplo: score alto sin ser una copia literal")
  void mandaAVerificacionElSegundoContraejemploDeAdr0008() {
    // "como se configura docker" puntuo 3.499 -- muy por encima del rango normal
    // de una pregunta relevante parafraseada, pero lejos de una copia literal
    // (~9.8). Con el techoConfianza original (1.0) esto se aceptaba a ciegas y
    // producia una respuesta de alcance equivocado (Docker del proyecto, no
    // Docker en general); por eso el techo subio a 8.0.
    Fragmento scoreAltoSinCerteza = fragmento(3.499);

    UmbralRelevancia.Resultado resultado =
        UmbralRelevancia.evaluar(List.of(scoreAltoSinCerteza), 4, PROPS);

    assertThat(resultado.decision()).isEqualTo(Decision.AMBIGUO);
  }

  @Test
  @DisplayName("Suficiente sin verificar cuando el score es una practicamente una copia literal")
  void aceptaSinVerificarUnaCopiaCasiLiteral() {
    Fragmento copiaLiteral = fragmento(9.84);

    UmbralRelevancia.Resultado resultado =
        UmbralRelevancia.evaluar(List.of(copiaLiteral), 4, PROPS);

    assertThat(resultado.decision()).isEqualTo(Decision.SUFICIENTE);
  }

  @Test
  @DisplayName("Sin fragmentos, rechaza sin importar el corpus")
  void rechazaSinFragmentos() {
    assertThat(UmbralRelevancia.evaluar(List.of(), 4, PROPS).decision())
        .isEqualTo(Decision.INSUFICIENTE);
  }

  @Test
  @DisplayName("No bloquea si algun fragmento viene de una herramienta de listado (sin rerank)")
  void noBloqueaConFragmentosSinRerank() {
    Fragmento deListado =
        new Fragmento(
            1L,
            1L,
            "file:///1",
            "t",
            "texto",
            "subsystem_summary",
            0,
            Instant.EPOCH,
            Map.of(),
            0.0,
            null);

    UmbralRelevancia.Resultado resultado = UmbralRelevancia.evaluar(List.of(deListado), 4, PROPS);

    assertThat(resultado.decision()).isEqualTo(Decision.SUFICIENTE);
  }

  @Test
  @DisplayName("Deshabilitado: siempre deja pasar sin verificar")
  void deshabilitadoSiempreDejaPasar() {
    var deshabilitado = new UmbralRelevanciaPropiedades(false, 0.003, 0.05, 500, 1.0);

    UmbralRelevancia.Resultado resultado = UmbralRelevancia.evaluar(List.of(), 4, deshabilitado);

    assertThat(resultado.decision()).isEqualTo(Decision.SUFICIENTE);
  }

  private static Fragmento fragmento(double rerank) {
    return new Fragmento(
        1L, 1L, "file:///1", "t", "texto", "doc_section", 0, Instant.EPOCH, Map.of(), 0.03, rerank);
  }
}
