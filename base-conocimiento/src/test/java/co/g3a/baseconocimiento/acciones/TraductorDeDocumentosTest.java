package co.g3a.baseconocimiento.acciones;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.g3a.baseconocimiento.acciones.Acciones.DocumentoATraducir;
import co.g3a.baseconocimiento.acciones.Acciones.EventoTraduccion;
import co.g3a.baseconocimiento.acciones.Acciones.EventoTraduccion.IdiomaDetectado;
import co.g3a.baseconocimiento.acciones.Acciones.EventoTraduccion.Omitido;
import co.g3a.baseconocimiento.acciones.Acciones.EventoTraduccion.Progreso;
import co.g3a.baseconocimiento.acciones.Acciones.EventoTraduccion.Texto;
import co.g3a.baseconocimiento.acciones.Acciones.TraduccionDeDocumentos;
import co.g3a.baseconocimiento.acciones.SeccionesRepositorio.Seccion;
import co.g3a.baseconocimiento.compartido.Dominio.ProyectoId;
import co.g3a.baseconocimiento.llm.Redactor;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * La traduccion de documentos completos con el LLM y Postgres doblados: bloques, deteccion perezosa
 * por documento, omision cuando el origen ya es el destino, y el cupo tomado una sola vez por
 * accion.
 */
class TraductorDeDocumentosTest {

  private static final ProyectoId PROYECTO = new ProyectoId("default");
  private static final AccionesPropiedades PROPIEDADES =
      new AccionesPropiedades(10, 7000, 8000, 1, 10000);

  @Test
  @DisplayName(
      "Detecta el idioma por documento, omite el que ya esta en el destino y traduce el resto en orden")
  void detectaOmiteYTraduce() {
    SeccionesRepositorio repo = mock(SeccionesRepositorio.class);
    when(repo.seccionesDe(List.of(1L, 2L, 3L), "default"))
        .thenReturn(
            List.of(
                seccion(1, 1, "A", 0, "hola mundo"),
                seccion(2, 2, "B", 0, "hello world"),
                seccion(3, 3, "C", 0, "adios mundo")));
    Redactor redactor = mock(Redactor.class);
    when(redactor.detectarIdioma(contains("hola"))).thenReturn("es");
    when(redactor.detectarIdioma(contains("hello"))).thenReturn("en");
    when(redactor.detectarIdioma(contains("adios"))).thenReturn("es");
    when(redactor.traducir(anyString(), eq("es"), eq("en")))
        .thenAnswer(inv -> Flux.just("T:" + inv.getArgument(0)));
    var cupo = new CupoDeAcciones(PROPIEDADES);
    var traductor = new TraductorDeDocumentos(repo, redactor, cupo);

    TraduccionDeDocumentos resultado =
        traductor.traducir(List.of(1L, 2L, 3L), PROYECTO, null, "en");
    List<EventoTraduccion> eventos = resultado.eventos().collectList().block();

    assertThat(resultado.etiqueta()).isEqualTo("Traducción de 3 documentos: A, B, C");
    assertThat(resultado.documentos())
        .extracting(DocumentoATraducir::documentoId, DocumentoATraducir::bloquesTotales)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(1L, 1),
            org.assertj.core.groups.Tuple.tuple(2L, 1),
            org.assertj.core.groups.Tuple.tuple(3L, 1));
    assertThat(eventos)
        .containsSubsequence(
            new IdiomaDetectado(1L, "es"),
            new Progreso(1L, 1, 1),
            new Texto(1L, "T:hola mundo"),
            new IdiomaDetectado(2L, "en"),
            new Omitido(2L, "en"),
            new IdiomaDetectado(3L, "es"),
            new Progreso(3L, 1, 1),
            new Texto(3L, "T:adios mundo"));
    verify(redactor, never()).traducir(contains("hello"), any(), any());
    assertThat(cupo.intentarTomar()).as("el cupo volvio al terminar").isTrue();
  }

  @Test
  @DisplayName("Con origen explicito no detecta nada; si coincide con el destino, omite sin LLM")
  void origenExplicito() {
    SeccionesRepositorio repo = mock(SeccionesRepositorio.class);
    when(repo.seccionesDe(any(), any())).thenReturn(List.of(seccion(1, 1, "A", 0, "hola")));
    Redactor redactor = mock(Redactor.class);
    when(redactor.traducir(anyString(), eq("es"), eq("pt"))).thenReturn(Flux.just("olá"));
    var traductor = new TraductorDeDocumentos(repo, redactor, new CupoDeAcciones(PROPIEDADES));

    List<EventoTraduccion> traducidos =
        traductor.traducir(List.of(1L), PROYECTO, "es", "pt").eventos().collectList().block();
    assertThat(traducidos).noneMatch(e -> e instanceof IdiomaDetectado);
    assertThat(traducidos).contains(new Texto(1L, "olá"));

    List<EventoTraduccion> omitidos =
        traductor.traducir(List.of(1L), PROYECTO, "es", "es").eventos().collectList().block();
    assertThat(omitidos).containsExactly(new Omitido(1L, "es"));
    verify(redactor, never()).detectarIdioma(any());
  }

  @Test
  @DisplayName("Nada corre hasta que alguien se suscribe (hallazgo 3): ni la deteccion")
  void esPerezoso() {
    SeccionesRepositorio repo = mock(SeccionesRepositorio.class);
    when(repo.seccionesDe(any(), any())).thenReturn(List.of(seccion(1, 1, "A", 0, "hola")));
    Redactor redactor = mock(Redactor.class);
    var traductor = new TraductorDeDocumentos(repo, redactor, new CupoDeAcciones(PROPIEDADES));

    traductor.traducir(List.of(1L), PROYECTO, null, "en");

    verify(redactor, never()).detectarIdioma(any());
    verify(redactor, never()).traducir(any(), any(), any());
  }

  @Test
  @DisplayName(
      "'und' no omite: se traduce igual, con 'und' como origen para que el modelo lo infiera")
  void undNoOmite() {
    SeccionesRepositorio repo = mock(SeccionesRepositorio.class);
    when(repo.seccionesDe(any(), any())).thenReturn(List.of(seccion(1, 1, "A", 0, "???")));
    Redactor redactor = mock(Redactor.class);
    when(redactor.detectarIdioma(anyString())).thenReturn("und");
    when(redactor.traducir(anyString(), eq("und"), eq("en"))).thenReturn(Flux.just("???"));
    var traductor = new TraductorDeDocumentos(repo, redactor, new CupoDeAcciones(PROPIEDADES));

    List<EventoTraduccion> eventos =
        traductor.traducir(List.of(1L), PROYECTO, null, "en").eventos().collectList().block();

    assertThat(eventos).noneMatch(e -> e instanceof Omitido);
    verify(redactor).traducir("???", "und", "en");
  }

  @Test
  @DisplayName(
      "Bloques: una seccion larga se parte por parrafos en bloques de hasta 1500, un code_block pasa sin LLM")
  void bloques() {
    // 3 parrafos de 700: los dos primeros caben juntos (700 + 2 + 700 <= 1500), el
    // tercero va aparte -> 2 bloques de texto, mas el code_block = 3 bloques.
    String parrafo = "p".repeat(700);
    String larga = parrafo + "\n\n" + parrafo + "\n\n" + parrafo;
    SeccionesRepositorio repo = mock(SeccionesRepositorio.class);
    when(repo.seccionesDe(any(), any()))
        .thenReturn(
            List.of(
                seccion(1, 1, "A", 0, larga),
                new Seccion(2, 1, "file:///A", "A", 1, "int x = 1;", "code_block")));
    Redactor redactor = mock(Redactor.class);
    when(redactor.traducir(anyString(), eq("es"), eq("en")))
        .thenAnswer(inv -> Flux.just("T" + ((String) inv.getArgument(0)).length()));
    var traductor = new TraductorDeDocumentos(repo, redactor, new CupoDeAcciones(PROPIEDADES));

    TraduccionDeDocumentos resultado = traductor.traducir(List.of(1L), PROYECTO, "es", "en");
    List<EventoTraduccion> eventos = resultado.eventos().collectList().block();

    assertThat(resultado.documentos().get(0).bloquesTotales()).isEqualTo(3);
    assertThat(eventos.stream().filter(e -> e instanceof Progreso))
        .containsExactly(new Progreso(1L, 1, 3), new Progreso(1L, 2, 3), new Progreso(1L, 3, 3));
    assertThat(eventos).contains(new Texto(1L, "int x = 1;"));
    verify(redactor, never()).traducir(contains("int x"), any(), any());
    // Ningun bloque enviado al LLM supera el largo maximo.
    verify(redactor, never())
        .traducir(argThatIsLongerThan(TraductorDeDocumentos.LARGO_BLOQUE), any(), any());
  }

  @Test
  @DisplayName("Un id inexistente aparece con 0 bloques y no produce eventos")
  void inexistente() {
    SeccionesRepositorio repo = mock(SeccionesRepositorio.class);
    when(repo.seccionesDe(any(), any())).thenReturn(List.of(seccion(1, 1, "A", 0, "hola")));
    Redactor redactor = mock(Redactor.class);
    when(redactor.traducir(anyString(), eq("es"), eq("en"))).thenReturn(Flux.just("hi"));
    var traductor = new TraductorDeDocumentos(repo, redactor, new CupoDeAcciones(PROPIEDADES));

    TraduccionDeDocumentos resultado = traductor.traducir(List.of(1L, 999L), PROYECTO, "es", "en");
    List<EventoTraduccion> eventos = resultado.eventos().collectList().block();

    assertThat(resultado.documentos())
        .extracting(DocumentoATraducir::documentoId, DocumentoATraducir::bloquesTotales)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(1L, 1),
            org.assertj.core.groups.Tuple.tuple(999L, 0));
    assertThat(eventos).allMatch(e -> documentoDe(e) == 1L);
  }

  @Test
  @DisplayName("Sin cupo, el flujo termina en error con el mensaje fijo y no toca el LLM")
  void sinCupo() {
    SeccionesRepositorio repo = mock(SeccionesRepositorio.class);
    when(repo.seccionesDe(any(), any())).thenReturn(List.of(seccion(1, 1, "A", 0, "hola")));
    Redactor redactor = mock(Redactor.class);
    var traductor =
        new TraductorDeDocumentos(
            repo, redactor, new CupoDeAcciones(new AccionesPropiedades(10, 7000, 8000, 0, 10000)));

    TraduccionDeDocumentos resultado = traductor.traducir(List.of(1L), PROYECTO, "es", "en");

    assertThatThrownBy(() -> resultado.eventos().blockLast())
        .isInstanceOf(Acciones.ServidorOcupado.class)
        .hasMessageContaining(AccionesSobreDocumentos.MENSAJE_SERVIDOR_OCUPADO);
    verify(redactor, never()).traducir(any(), any(), any());
  }

  private static String argThatIsLongerThan(int largo) {
    return org.mockito.ArgumentMatchers.argThat(s -> s != null && s.length() > largo);
  }

  private static long documentoDe(EventoTraduccion e) {
    return switch (e) {
      case IdiomaDetectado d -> d.documentoId();
      case Omitido o -> o.documentoId();
      case Progreso p -> p.documentoId();
      case Texto t -> t.documentoId();
    };
  }

  private static Seccion seccion(long id, long documentoId, String titulo, int ord, String texto) {
    return new Seccion(id, documentoId, "file:///" + titulo, titulo, ord, texto, "doc_section");
  }
}
