package co.g3a.baseconocimiento.acciones;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import co.g3a.baseconocimiento.acciones.Acciones.CoberturaDocumento;
import co.g3a.baseconocimiento.acciones.Acciones.ResultadoEnStreaming;
import co.g3a.baseconocimiento.acciones.Acciones.ResultadoEstructurado;
import co.g3a.baseconocimiento.acciones.Acciones.SinResultado;
import co.g3a.baseconocimiento.acciones.Acciones.Tipo;
import co.g3a.baseconocimiento.acciones.SeccionesRepositorio.Seccion;
import co.g3a.baseconocimiento.compartido.Dominio.Cita;
import co.g3a.baseconocimiento.compartido.Dominio.ProyectoId;
import co.g3a.baseconocimiento.llm.Redactor;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

/**
 * Las cuatro acciones que redactan a partir del contexto, con el LLM y Postgres doblados: cableado
 * (cargar, recortar, cupo, redactar, liberar), mensajes fijos y etiquetas. La aritmetica del
 * presupuesto ya tiene su propia prueba.
 */
class AccionesSobreDocumentosTest {

  private static final ProyectoId PROYECTO = new ProyectoId("default");
  private static final AccionesPropiedades PROPIEDADES = new AccionesPropiedades(10, 7000, 8000, 1);

  @Test
  @DisplayName("Resumir: carga, recorta, redacta en el idioma pedido y libera el cupo al terminar")
  void resumirCableaTodo() {
    SeccionesRepositorio repo = mock(SeccionesRepositorio.class);
    when(repo.seccionesDe(List.of(10L, 20L), "default"))
        .thenReturn(List.of(seccion(1, 10, "A", "texto a"), seccion(2, 20, "B", "texto b")));
    Redactor redactor = mock(Redactor.class);
    when(redactor.resumir(anyString(), eq("en"))).thenReturn(Flux.just("Resumen ", "[1] [2]."));
    var cupo = new CupoDeAcciones(PROPIEDADES);
    var acciones = new AccionesSobreDocumentos(repo, redactor, cupo, PROPIEDADES);

    ResultadoEnStreaming resultado =
        acciones.redactar(Tipo.RESUMIR, List.of(10L, 20L, 10L), PROYECTO, "en");

    assertThat(resultado.etiqueta()).isEqualTo("Resumen de 2 documentos: A, B");
    assertThat(resultado.cobertura())
        .extracting(CoberturaDocumento::documentoId)
        .containsExactly(10L, 20L);
    assertThat(resultado.citas()).extracting(Cita::titulo).containsExactly("A", "B");
    assertThat(String.join("", resultado.texto().collectList().block()))
        .isEqualTo("Resumen [1] [2].");

    ArgumentCaptor<String> contexto = ArgumentCaptor.forClass(String.class);
    verify(redactor).resumir(contexto.capture(), eq("en"));
    assertThat(contexto.getValue()).contains("[1] A (file:///A)").contains("[2] B (file:///B)");
    verify(redactor, never()).sintetizar(any(), any());
    // Los duplicados se quitan antes de ir a la base.
    verify(repo).seccionesDe(List.of(10L, 20L), "default");
    assertThat(cupo.intentarTomar()).as("el cupo volvio al terminar").isTrue();
  }

  @Test
  @DisplayName("Sintetizar usa el otro prompt y su propia etiqueta")
  void sintetizar() {
    SeccionesRepositorio repo = mock(SeccionesRepositorio.class);
    when(repo.seccionesDe(any(), any())).thenReturn(List.of(seccion(1, 10, "A", "texto a")));
    Redactor redactor = mock(Redactor.class);
    when(redactor.sintetizar(anyString(), eq("es"))).thenReturn(Flux.just("Sintesis."));
    var acciones =
        new AccionesSobreDocumentos(repo, redactor, new CupoDeAcciones(PROPIEDADES), PROPIEDADES);

    ResultadoEnStreaming resultado =
        acciones.redactar(Tipo.SINTETIZAR, List.of(10L), PROYECTO, "es");

    assertThat(resultado.etiqueta()).isEqualTo("Síntesis de 1 documento: A");
    assertThat(String.join("", resultado.texto().collectList().block())).isEqualTo("Sintesis.");
    verify(redactor, never()).resumir(any(), any());
  }

  @Test
  @DisplayName(
      "Sin ningun documento existente: mensaje fijo, cobertura 0/0, sin tocar el LLM ni el cupo")
  void sinDocumentos() {
    SeccionesRepositorio repo = mock(SeccionesRepositorio.class);
    when(repo.seccionesDe(any(), any())).thenReturn(List.of());
    Redactor redactor = mock(Redactor.class);
    var cupo = new CupoDeAcciones(new AccionesPropiedades(10, 7000, 8000, 0));
    var acciones = new AccionesSobreDocumentos(repo, redactor, cupo, PROPIEDADES);

    ResultadoEnStreaming resultado = acciones.redactar(Tipo.RESUMIR, List.of(999L), PROYECTO, "es");

    assertThat(String.join("", resultado.texto().collectList().block()))
        .isEqualTo(AccionesSobreDocumentos.MENSAJE_SIN_DOCUMENTOS);
    assertThat(resultado.cobertura())
        .containsExactly(new CoberturaDocumento(999L, "#999", null, 0, 0, false));
    assertThat(resultado.citas()).isEmpty();
    verifyNoInteractions(redactor);
  }

  @Test
  @DisplayName("Sin cupo: mensaje fijo con tuteo, sin tocar el LLM")
  void sinCupo() {
    SeccionesRepositorio repo = mock(SeccionesRepositorio.class);
    when(repo.seccionesDe(any(), any())).thenReturn(List.of(seccion(1, 10, "A", "texto a")));
    Redactor redactor = mock(Redactor.class);
    var cupo = new CupoDeAcciones(new AccionesPropiedades(10, 7000, 8000, 0));
    var acciones = new AccionesSobreDocumentos(repo, redactor, cupo, PROPIEDADES);

    ResultadoEnStreaming resultado = acciones.redactar(Tipo.RESUMIR, List.of(10L), PROYECTO, "es");

    assertThat(String.join("", resultado.texto().collectList().block()))
        .isEqualTo(AccionesSobreDocumentos.MENSAJE_SERVIDOR_OCUPADO)
        .doesNotContain("Esperá")
        .contains("Espera un momento y vuelve a intentarlo");
    verifyNoInteractions(redactor);
  }

  @Test
  @DisplayName("El cupo vuelve aunque el Flux termine en error")
  void cupoVuelveConFluxError() {
    SeccionesRepositorio repo = mock(SeccionesRepositorio.class);
    when(repo.seccionesDe(any(), any())).thenReturn(List.of(seccion(1, 10, "A", "texto a")));
    Redactor redactor = mock(Redactor.class);
    when(redactor.resumir(anyString(), anyString()))
        .thenReturn(Flux.error(new IllegalStateException("Ollama no responde")));
    var cupo = new CupoDeAcciones(PROPIEDADES);
    var acciones = new AccionesSobreDocumentos(repo, redactor, cupo, PROPIEDADES);

    ResultadoEnStreaming resultado = acciones.redactar(Tipo.RESUMIR, List.of(10L), PROYECTO, "es");

    assertThatThrownBy(() -> resultado.texto().blockLast())
        .isInstanceOf(IllegalStateException.class);
    assertThat(cupo.intentarTomar()).isTrue();
  }

  @Test
  @DisplayName("El cupo vuelve aunque el Redactor lance ANTES de crear el Flux (hallazgo 2)")
  void cupoVuelveConExcepcionSincronica() {
    SeccionesRepositorio repo = mock(SeccionesRepositorio.class);
    when(repo.seccionesDe(any(), any())).thenReturn(List.of(seccion(1, 10, "A", "texto a")));
    Redactor redactor = mock(Redactor.class);
    when(redactor.resumir(anyString(), anyString()))
        .thenThrow(new IllegalStateException("bean mal configurado"));
    var cupo = new CupoDeAcciones(PROPIEDADES);
    var acciones = new AccionesSobreDocumentos(repo, redactor, cupo, PROPIEDADES);

    ResultadoEnStreaming resultado = acciones.redactar(Tipo.RESUMIR, List.of(10L), PROYECTO, "es");

    // La excepcion viaja por el Flux (asi el adaptador la convierte en error-servidor),
    // y el permiso ya volvio antes de que nadie se suscriba.
    assertThat(cupo.intentarTomar()).isTrue();
    cupo.liberar();
    assertThatThrownBy(() -> resultado.texto().blockLast())
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("Cada operacion acepta solo sus tipos")
  void tiposIncompatibles() {
    var acciones =
        new AccionesSobreDocumentos(
            mock(SeccionesRepositorio.class),
            mock(Redactor.class),
            new CupoDeAcciones(PROPIEDADES),
            PROPIEDADES);

    assertThatThrownBy(() -> acciones.redactar(Tipo.PREGUNTAS, List.of(1L), PROYECTO, "es"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> acciones.estructurar(Tipo.RESUMIR, List.of(1L), PROYECTO, "es"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName(
      "Preguntas e ideas: salida estructurada, con su etiqueta, y el cupo vuelve al completar")
  void estructurar() {
    SeccionesRepositorio repo = mock(SeccionesRepositorio.class);
    when(repo.seccionesDe(any(), any())).thenReturn(List.of(seccion(1, 10, "A", "texto a")));
    Redactor redactor = mock(Redactor.class);
    var preguntas =
        new Redactor.Preguntas(
            List.of(new Redactor.Tema("Despliegue", List.of(new Redactor.Pregunta("¿Cómo?", 1)))));
    when(redactor.preguntar(anyString(), eq("pt"))).thenReturn(preguntas);
    var ideas = new Redactor.Ideas(List.of(new Redactor.Idea("Titulo", "porque", 1)));
    when(redactor.idear(anyString(), eq("es"))).thenReturn(ideas);
    var cupo = new CupoDeAcciones(PROPIEDADES);
    var acciones = new AccionesSobreDocumentos(repo, redactor, cupo, PROPIEDADES);

    ResultadoEstructurado conPreguntas =
        acciones.estructurar(Tipo.PREGUNTAS, List.of(10L), PROYECTO, "pt");
    assertThat(conPreguntas.etiqueta()).isEqualTo("Preguntas sobre 1 documento: A");
    assertThat(conPreguntas.resultado().block()).isEqualTo(preguntas);
    assertThat(cupo.intentarTomar()).isTrue();
    cupo.liberar();

    ResultadoEstructurado conIdeas = acciones.estructurar(Tipo.IDEAS, List.of(10L), PROYECTO, "es");
    assertThat(conIdeas.etiqueta()).isEqualTo("Ideas a partir de 1 documento: A");
    assertThat(conIdeas.resultado().block()).isEqualTo(ideas);
    assertThat(cupo.intentarTomar()).isTrue();
  }

  @Test
  @DisplayName("Estructurar sin documentos ni cupo: SinResultado con el mensaje fijo, sin LLM")
  void estructurarSinDocumentosNiCupo() {
    SeccionesRepositorio repo = mock(SeccionesRepositorio.class);
    when(repo.seccionesDe(any(), any())).thenReturn(List.of());
    Redactor redactor = mock(Redactor.class);
    var acciones =
        new AccionesSobreDocumentos(repo, redactor, new CupoDeAcciones(PROPIEDADES), PROPIEDADES);

    ResultadoEstructurado sinDocumentos =
        acciones.estructurar(Tipo.PREGUNTAS, List.of(999L), PROYECTO, "es");
    assertThat(sinDocumentos.resultado().block())
        .isEqualTo(new SinResultado(AccionesSobreDocumentos.MENSAJE_SIN_DOCUMENTOS));

    when(repo.seccionesDe(any(), any())).thenReturn(List.of(seccion(1, 10, "A", "texto a")));
    var sinCupo =
        new AccionesSobreDocumentos(
            repo,
            redactor,
            new CupoDeAcciones(new AccionesPropiedades(10, 7000, 8000, 0)),
            PROPIEDADES);
    ResultadoEstructurado ocupado = sinCupo.estructurar(Tipo.IDEAS, List.of(10L), PROYECTO, "es");
    assertThat(ocupado.resultado().block())
        .isEqualTo(new SinResultado(AccionesSobreDocumentos.MENSAJE_SERVIDOR_OCUPADO));
    verifyNoInteractions(redactor);
  }

  private static Seccion seccion(long id, long documentoId, String titulo, String texto) {
    return new Seccion(id, documentoId, "file:///" + titulo, titulo, 0, texto, "doc_section");
  }
}
