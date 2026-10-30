package co.g3a.baseconocimiento.acciones;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import co.g3a.baseconocimiento.acciones.Acciones.CoberturaDocumento;
import co.g3a.baseconocimiento.acciones.Acciones.EventoAccion;
import co.g3a.baseconocimiento.acciones.Acciones.EventoAccion.CapituloLeido;
import co.g3a.baseconocimiento.acciones.Acciones.EventoAccion.Lectura;
import co.g3a.baseconocimiento.acciones.Acciones.EventoAccion.Resultado;
import co.g3a.baseconocimiento.acciones.Acciones.EventoAccion.Token;
import co.g3a.baseconocimiento.acciones.Acciones.Modo;
import co.g3a.baseconocimiento.acciones.Acciones.ResultadoDeAccion;
import co.g3a.baseconocimiento.acciones.Acciones.SinResultado;
import co.g3a.baseconocimiento.acciones.Acciones.Tipo;
import co.g3a.baseconocimiento.acciones.SeccionesRepositorio.Seccion;
import co.g3a.baseconocimiento.compartido.Dominio.Cita;
import co.g3a.baseconocimiento.compartido.Dominio.ProyectoId;
import co.g3a.baseconocimiento.llm.Redactor;
import co.g3a.baseconocimiento.llm.RendimientoDelModelo;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

/**
 * Las cuatro acciones que redactan a partir del contexto, con el LLM y Postgres doblados: cableado
 * (cargar, planificar, cupo, leer por pasadas, redactar, liberar), mensajes fijos y etiquetas. La
 * aritmetica del presupuesto ya tiene su propia prueba.
 */
class AccionesSobreDocumentosTest {

  private static final ProyectoId PROYECTO = new ProyectoId("default");
  private static final AccionesPropiedades PROPIEDADES =
      new AccionesPropiedades(10, 7000, 8000, 1, 10000);

  /**
   * Un contexto enorme: el tamano de la pasada lo fija el tope de cada prueba, como antes de que se
   * calculara con el contexto del modelo (issue #57). Eso lo prueba {@code
   * PresupuestoDeContextoTest}.
   */
  private static final RendimientoDelModelo RENDIMIENTO =
      new RendimientoDelModelo() {
        @Override
        public double caracteresPorToken() {
          return 3.0;
        }

        @Override
        public double segundosEstimados(int caracteresEntrada, int tokensSalida) {
          return 1;
        }

        @Override
        public int contextoTokens() {
          return 1_000_000;
        }
      };

  @Test
  @DisplayName(
      "Resumir: carga, planifica, redacta en el idioma pedido y libera el cupo al terminar")
  void resumirCableaTodo() {
    SeccionesRepositorio repo = mock(SeccionesRepositorio.class);
    when(repo.seccionesDe(List.of(10L, 20L), "default"))
        .thenReturn(List.of(seccion(1, 10, "A", "texto a"), seccion(2, 20, "B", "texto b")));
    Redactor redactor = mock(Redactor.class);
    when(redactor.resumir(anyString(), eq("en"))).thenReturn(Flux.just("Resumen ", "[1] [2]."));
    var cupo = new CupoDeAcciones(PROPIEDADES);
    var acciones = new AccionesSobreDocumentos(repo, redactor, cupo, PROPIEDADES, RENDIMIENTO);

    ResultadoDeAccion resultado =
        acciones.ejecutar(Tipo.RESUMIR, List.of(10L, 20L, 10L), PROYECTO, "en");

    assertThat(resultado.etiqueta()).isEqualTo("Resumen de 2 documentos: A, B");
    assertThat(resultado.cobertura())
        .extracting(CoberturaDocumento::documentoId)
        .containsExactly(10L, 20L);
    assertThat(resultado.cobertura()).allMatch(c -> c.pasadas() == 0);
    assertThat(resultado.citas()).extracting(Cita::titulo).containsExactly("A", "B");
    List<EventoAccion> eventos = resultado.eventos().collectList().block();
    assertThat(texto(eventos)).isEqualTo("Resumen [1] [2].");
    assertThat(eventos).noneMatch(e -> e instanceof Lectura);

    ArgumentCaptor<String> contexto = ArgumentCaptor.forClass(String.class);
    verify(redactor).resumir(contexto.capture(), eq("en"));
    assertThat(contexto.getValue()).contains("[1] A (file:///A)").contains("[2] B (file:///B)");
    verify(redactor, never()).sintetizar(any(), any());
    verify(redactor, never()).condensar(any(), any(), anyInt());
    // Los duplicados se quitan antes de ir a la base.
    verify(repo).seccionesDe(List.of(10L, 20L), "default");
    EsperaDeCupo.vuelveYSeToma(cupo, "el cupo volvio al terminar");
  }

  @Test
  @DisplayName("Sintetizar usa el otro prompt y su propia etiqueta")
  void sintetizar() {
    SeccionesRepositorio repo = mock(SeccionesRepositorio.class);
    when(repo.seccionesDe(any(), any())).thenReturn(List.of(seccion(1, 10, "A", "texto a")));
    Redactor redactor = mock(Redactor.class);
    when(redactor.sintetizar(anyString(), eq("es"))).thenReturn(Flux.just("Sintesis."));
    var acciones =
        new AccionesSobreDocumentos(
            repo, redactor, new CupoDeAcciones(PROPIEDADES), PROPIEDADES, RENDIMIENTO);

    ResultadoDeAccion resultado = acciones.ejecutar(Tipo.SINTETIZAR, List.of(10L), PROYECTO, "es");

    assertThat(resultado.etiqueta()).isEqualTo("Síntesis de 1 documento: A");
    assertThat(texto(resultado.eventos().collectList().block())).isEqualTo("Sintesis.");
    verify(redactor, never()).resumir(any(), any());
  }

  @Test
  @DisplayName(
      "Un documento que no cabe se lee entero por pasadas: una Lectura por tramo, las notas van a "
          + "la llamada final en lugar del texto, y el cupo vuelve")
  void documentoLargoPorPasadas() {
    // Presupuesto 100, lectura 50: cinco secciones de 30 son cinco tramos, 11 pasadas
    // declaradas (5 + 3 + 2 + 1). Las notas salen cortas ("n"), asi que tras el primer
    // nivel ya caben en la cuota: 5 pasadas reales y el cierre al total declarado.
    var propiedades = new AccionesPropiedades(10, 100, 8000, 1, 50);
    SeccionesRepositorio repo = mock(SeccionesRepositorio.class);
    when(repo.seccionesDe(any(), any())).thenReturn(cincoSecciones());
    Redactor redactor = mock(Redactor.class);
    when(redactor.condensar(anyString(), eq("es"), anyInt())).thenReturn("n");
    when(redactor.resumir(anyString(), eq("es"))).thenReturn(Flux.just("Resumen."));
    var cupo = new CupoDeAcciones(propiedades);
    var acciones = new AccionesSobreDocumentos(repo, redactor, cupo, propiedades, RENDIMIENTO);

    ResultadoDeAccion resultado = acciones.ejecutar(Tipo.RESUMIR, List.of(10L), PROYECTO, "es");

    assertThat(resultado.cobertura())
        .containsExactly(new CoberturaDocumento(10L, "L", "file:///L", 5, 11, Modo.COMPLETO));
    List<EventoAccion> eventos = resultado.eventos().collectList().block();
    assertThat(eventos.stream().filter(e -> e instanceof Lectura).map(e -> (Lectura) e))
        .containsExactly(
            new Lectura(10L, 1, 11),
            new Lectura(10L, 2, 11),
            new Lectura(10L, 3, 11),
            new Lectura(10L, 4, 11),
            new Lectura(10L, 5, 11),
            new Lectura(10L, 11, 11));
    assertThat(eventos.getLast()).isEqualTo(new Token("Resumen."));
    verify(redactor, times(5)).condensar(anyString(), eq("es"), eq(1200));

    ArgumentCaptor<String> contexto = ArgumentCaptor.forClass(String.class);
    verify(redactor).resumir(contexto.capture(), eq("es"));
    assertThat(contexto.getValue())
        .contains("[1] L (file:///L)")
        .contains("notas de lectura")
        .contains("n\n\nn")
        .doesNotContain("bbbbb");
    EsperaDeCupo.vuelveYSeToma(cupo, "el cupo volvio al terminar");
  }

  @Test
  @DisplayName(
      "Con notas que no caben en la cuota, los niveles siguen hasta que cabe una sola, y la ultima "
          + "pasada pide notas del largo de la cuota")
  void variosNivelesDeLectura() {
    // Cuota 10; las notas miden 6: 5 -> 3 -> 2 -> 1 = 11 pasadas, todas hechas.
    var propiedades = new AccionesPropiedades(10, 10, 8000, 1, 50);
    SeccionesRepositorio repo = mock(SeccionesRepositorio.class);
    when(repo.seccionesDe(any(), any())).thenReturn(cincoSecciones());
    Redactor redactor = mock(Redactor.class);
    when(redactor.condensar(anyString(), eq("es"), anyInt())).thenReturn("nnnnnn");
    when(redactor.idear(anyString(), eq("es"))).thenReturn(new Redactor.Ideas(List.of()));
    var acciones =
        new AccionesSobreDocumentos(
            repo, redactor, new CupoDeAcciones(propiedades), propiedades, RENDIMIENTO);

    List<EventoAccion> eventos =
        acciones.ejecutar(Tipo.IDEAS, List.of(10L), PROYECTO, "es").eventos().collectList().block();

    assertThat(eventos.stream().filter(e -> e instanceof Lectura)).hasSize(11);
    assertThat(eventos.get(10)).isEqualTo(new Lectura(10L, 11, 11));
    assertThat(eventos.getLast()).isEqualTo(new Resultado(new Redactor.Ideas(List.of())));
    // Diez pasadas con el largo normal y la ultima, de un solo tramo, con el de la cuota.
    verify(redactor, times(10)).condensar(anyString(), eq("es"), eq(1200));
    verify(redactor, times(1)).condensar(anyString(), eq("es"), eq(10));
    ArgumentCaptor<String> contexto = ArgumentCaptor.forClass(String.class);
    verify(redactor).idear(contexto.capture(), eq("es"));
    assertThat(contexto.getValue()).contains("nnnnnn").doesNotContain("bbbbb");
  }

  @Test
  @DisplayName("Si el cliente corta a mitad de las pasadas, el bucle para y el cupo vuelve")
  void cortarAMitadDeLasPasadas() throws InterruptedException {
    var propiedades = new AccionesPropiedades(10, 100, 8000, 1, 50);
    SeccionesRepositorio repo = mock(SeccionesRepositorio.class);
    when(repo.seccionesDe(any(), any())).thenReturn(cincoSecciones());
    Redactor redactor = mock(Redactor.class);
    when(redactor.condensar(anyString(), eq("es"), anyInt())).thenReturn("n");
    var cupo = new CupoDeAcciones(propiedades);
    var acciones = new AccionesSobreDocumentos(repo, redactor, cupo, propiedades, RENDIMIENTO);

    acciones.ejecutar(Tipo.RESUMIR, List.of(10L), PROYECTO, "es").eventos().take(2).blockLast();

    EsperaDeCupo.vuelveYSeToma(cupo, "el cupo volvio al cancelar");
    // El bucle corre en su propio hilo: la cancelacion se nota al ir a emitir la pasada
    // siguiente, asi que puede haber hecho una llamada mas, nunca las cinco.
    Thread.sleep(200);
    verify(redactor, never()).resumir(any(), any());
    verify(redactor, atLeast(2)).condensar(anyString(), eq("es"), anyInt());
    verify(redactor, atMost(3)).condensar(anyString(), eq("es"), anyInt());
  }

  @Test
  @DisplayName(
      "Sin ningun documento existente: mensaje fijo, cobertura 0/0, sin tocar el LLM ni el cupo")
  void sinDocumentos() {
    SeccionesRepositorio repo = mock(SeccionesRepositorio.class);
    when(repo.seccionesDe(any(), any())).thenReturn(List.of());
    Redactor redactor = mock(Redactor.class);
    var cupo = new CupoDeAcciones(new AccionesPropiedades(10, 7000, 8000, 0, 10000));
    var acciones = new AccionesSobreDocumentos(repo, redactor, cupo, PROPIEDADES, RENDIMIENTO);

    ResultadoDeAccion resultado = acciones.ejecutar(Tipo.RESUMIR, List.of(999L), PROYECTO, "es");

    assertThat(resultado.eventos().collectList().block())
        .containsExactly(new Token(AccionesSobreDocumentos.MENSAJE_SIN_DOCUMENTOS));
    assertThat(resultado.cobertura())
        .containsExactly(new CoberturaDocumento(999L, "#999", null, 0, 0, null));
    assertThat(resultado.citas()).isEmpty();
    verifyNoInteractions(redactor);
  }

  @Test
  @DisplayName("Sin cupo: mensaje fijo con tuteo, sin tocar el LLM")
  void sinCupo() {
    SeccionesRepositorio repo = mock(SeccionesRepositorio.class);
    when(repo.seccionesDe(any(), any())).thenReturn(List.of(seccion(1, 10, "A", "texto a")));
    Redactor redactor = mock(Redactor.class);
    var cupo = new CupoDeAcciones(new AccionesPropiedades(10, 7000, 8000, 0, 10000));
    var acciones = new AccionesSobreDocumentos(repo, redactor, cupo, PROPIEDADES, RENDIMIENTO);

    ResultadoDeAccion resultado = acciones.ejecutar(Tipo.RESUMIR, List.of(10L), PROYECTO, "es");

    assertThat(texto(resultado.eventos().collectList().block()))
        .isEqualTo(AccionesSobreDocumentos.MENSAJE_SERVIDOR_OCUPADO)
        .doesNotContain("Esperá")
        .contains("Espera un momento y vuelve a intentarlo");
    verifyNoInteractions(redactor);
  }

  @Test
  @DisplayName(
      "Armar el resultado no toma el cupo: se toma al suscribirse (hallazgo de la revision)")
  void elCupoSeTomaAlSuscribirse() {
    SeccionesRepositorio repo = mock(SeccionesRepositorio.class);
    when(repo.seccionesDe(any(), any())).thenReturn(List.of(seccion(1, 10, "A", "texto a")));
    Redactor redactor = mock(Redactor.class);
    when(redactor.resumir(anyString(), anyString())).thenReturn(Flux.just("ok"));
    when(redactor.preguntar(anyString(), anyString(), any(), any())).thenReturn(List.of());
    var cupo = new CupoDeAcciones(PROPIEDADES);
    var acciones = new AccionesSobreDocumentos(repo, redactor, cupo, PROPIEDADES, RENDIMIENTO);

    // Un cliente que corta antes de leer el cuerpo: el resultado se arma y nadie lo lee.
    acciones.ejecutar(Tipo.RESUMIR, List.of(10L), PROYECTO, "es");
    acciones.ejecutar(Tipo.PREGUNTAS, List.of(10L), PROYECTO, "es");
    verifyNoInteractions(redactor);
    // Estas dos aserciones son INMEDIATAS a proposito, y no van con EsperaDeCupo: no miran el
    // post-estado de una suscripcion que termino, miran que no hubo suscripcion. Una espera aqui
    // pasaria igual si el cupo se tomara y se devolviera, que es justo la regresion a atrapar.
    assertThat(cupo.intentarTomar()).as("nadie se suscribio: el cupo sigue libre").isTrue();
    cupo.liberar();

    // Y el cupo se mira al suscribirse, no al armar: el mismo resultado da el mensaje
    // fijo con el cupo ocupado y el texto real cuando vuelve a estar libre.
    ResultadoDeAccion resultado = acciones.ejecutar(Tipo.RESUMIR, List.of(10L), PROYECTO, "es");
    assertThat(cupo.intentarTomar()).isTrue(); // toma el cupo para dejarlo ocupado: es preparacion
    assertThat(texto(resultado.eventos().collectList().block()))
        .isEqualTo(AccionesSobreDocumentos.MENSAJE_SERVIDOR_OCUPADO);
    cupo.liberar();
    assertThat(texto(resultado.eventos().collectList().block())).isEqualTo("ok");
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
    var acciones = new AccionesSobreDocumentos(repo, redactor, cupo, PROPIEDADES, RENDIMIENTO);

    ResultadoDeAccion resultado = acciones.ejecutar(Tipo.RESUMIR, List.of(10L), PROYECTO, "es");

    assertThatThrownBy(() -> resultado.eventos().blockLast())
        .isInstanceOf(IllegalStateException.class);
    EsperaDeCupo.vuelveYSeToma(cupo, "el cupo volvio tras el error");
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
    var acciones = new AccionesSobreDocumentos(repo, redactor, cupo, PROPIEDADES, RENDIMIENTO);

    ResultadoDeAccion resultado = acciones.ejecutar(Tipo.RESUMIR, List.of(10L), PROYECTO, "es");

    // La excepcion viaja por el Flux (asi el adaptador la convierte en error-servidor),
    // y el permiso vuelve.
    assertThatThrownBy(() -> resultado.eventos().blockLast())
        .isInstanceOf(IllegalStateException.class);
    EsperaDeCupo.vuelveYSeToma(cupo, "el cupo volvio tras el error");
  }

  @Test
  @DisplayName(
      "Preguntas (un nivel de Bloom por llamada, acumuladas) e ideas: salida estructurada, con su"
          + " etiqueta, y el cupo vuelve al completar")
  void estructurar() {
    SeccionesRepositorio repo = mock(SeccionesRepositorio.class);
    when(repo.seccionesDe(any(), any())).thenReturn(List.of(seccion(1, 10, "A", "texto a")));
    Redactor redactor = mock(Redactor.class);
    var recordar = new Redactor.Pregunta("¿Qué hace make up?", "que", 1);
    var aplicar = new Redactor.Pregunta("¿Cómo lo usarías sin GPU?", "como", 1);
    when(redactor.preguntar(anyString(), eq("pt"), any(), any())).thenReturn(List.of());
    when(redactor.preguntar(anyString(), eq("pt"), eq(Redactor.NivelBloom.RECORDAR), any()))
        .thenReturn(List.of(recordar));
    when(redactor.preguntar(anyString(), eq("pt"), eq(Redactor.NivelBloom.APLICAR), any()))
        .thenReturn(List.of(aplicar));
    var ideas = new Redactor.Ideas(List.of(new Redactor.Idea("Titulo", "porque", 1)));
    when(redactor.idear(anyString(), eq("es"))).thenReturn(ideas);
    var cupo = new CupoDeAcciones(PROPIEDADES);
    var acciones = new AccionesSobreDocumentos(repo, redactor, cupo, PROPIEDADES, RENDIMIENTO);

    ResultadoDeAccion conPreguntas =
        acciones.ejecutar(Tipo.PREGUNTAS, List.of(10L), PROYECTO, "pt");
    assertThat(conPreguntas.etiqueta()).isEqualTo("Preguntas sobre 1 documento: A");
    List<EventoAccion> eventos = conPreguntas.eventos().collectList().block();
    // Seis Resultado acumulados, uno por nivel, en el orden de Bloom; el ultimo es el completo.
    assertThat(eventos).hasSize(6).allMatch(e -> e instanceof Resultado);
    assertThat(((Redactor.Preguntas) ((Resultado) eventos.get(0)).valor()).niveles())
        .containsExactly(new Redactor.Nivel("recordar", List.of(recordar)));
    Redactor.Preguntas completo = (Redactor.Preguntas) ((Resultado) eventos.get(5)).valor();
    assertThat(completo.niveles())
        .extracting(Redactor.Nivel::nivel)
        .containsExactly("recordar", "comprender", "aplicar", "analizar", "evaluar", "crear");
    assertThat(completo.niveles().get(2).preguntas()).containsExactly(aplicar);
    assertThat(completo.niveles().get(5).preguntas()).isEmpty();
    for (Redactor.NivelBloom nivel : Redactor.NivelBloom.values()) {
      verify(redactor).preguntar(anyString(), eq("pt"), eq(nivel), any());
    }
    // Cada nivel recibe las preguntas ya formuladas: recordar no tiene ninguna, aplicar
    // recibe la de recordar, y crear las dos.
    verify(redactor)
        .preguntar(anyString(), eq("pt"), eq(Redactor.NivelBloom.RECORDAR), eq(List.of()));
    verify(redactor)
        .preguntar(
            anyString(), eq("pt"), eq(Redactor.NivelBloom.APLICAR), eq(List.of(recordar.texto())));
    verify(redactor)
        .preguntar(
            anyString(),
            eq("pt"),
            eq(Redactor.NivelBloom.CREAR),
            eq(List.of(recordar.texto(), aplicar.texto())));
    EsperaDeCupo.vuelveYSeToma(cupo, "el cupo volvio al terminar");
    cupo.liberar();

    ResultadoDeAccion conIdeas = acciones.ejecutar(Tipo.IDEAS, List.of(10L), PROYECTO, "es");
    assertThat(conIdeas.etiqueta()).isEqualTo("Ideas a partir de 1 documento: A");
    assertThat(conIdeas.eventos().collectList().block()).containsExactly(new Resultado(ideas));
    EsperaDeCupo.vuelveYSeToma(cupo, "el cupo volvio al terminar");
  }

  @Test
  @DisplayName("Estructurar sin documentos ni cupo: SinResultado con el mensaje fijo, sin LLM")
  void estructurarSinDocumentosNiCupo() {
    SeccionesRepositorio repo = mock(SeccionesRepositorio.class);
    when(repo.seccionesDe(any(), any())).thenReturn(List.of());
    Redactor redactor = mock(Redactor.class);
    var acciones =
        new AccionesSobreDocumentos(
            repo, redactor, new CupoDeAcciones(PROPIEDADES), PROPIEDADES, RENDIMIENTO);

    ResultadoDeAccion sinDocumentos =
        acciones.ejecutar(Tipo.PREGUNTAS, List.of(999L), PROYECTO, "es");
    assertThat(sinDocumentos.eventos().collectList().block())
        .containsExactly(
            new Resultado(new SinResultado(AccionesSobreDocumentos.MENSAJE_SIN_DOCUMENTOS)));

    when(repo.seccionesDe(any(), any())).thenReturn(List.of(seccion(1, 10, "A", "texto a")));
    var sinCupo =
        new AccionesSobreDocumentos(
            repo,
            redactor,
            new CupoDeAcciones(new AccionesPropiedades(10, 7000, 8000, 0, 10000)),
            PROPIEDADES,
            RENDIMIENTO);
    ResultadoDeAccion ocupado = sinCupo.ejecutar(Tipo.IDEAS, List.of(10L), PROYECTO, "es");
    assertThat(ocupado.eventos().collectList().block())
        .containsExactly(
            new Resultado(new SinResultado(AccionesSobreDocumentos.MENSAJE_SERVIDOR_OCUPADO)));
    verifyNoInteractions(redactor);
  }

  private static String texto(List<EventoAccion> eventos) {
    StringBuilder texto = new StringBuilder();
    for (EventoAccion e : eventos) {
      if (e instanceof Token t) {
        texto.append(t.fragmento());
      }
    }
    return texto.toString();
  }

  /** Un documento largo: cinco secciones de 30 'b', que no caben en una cuota de 100 ni de 10. */
  private static List<Seccion> cincoSecciones() {
    return List.of(
        seccion(1, 10, "L", "b".repeat(30)),
        seccion(2, 10, "L", "b".repeat(30)),
        seccion(3, 10, "L", "b".repeat(30)),
        seccion(4, 10, "L", "b".repeat(30)),
        seccion(5, 10, "L", "b".repeat(30)));
  }

  @Test
  @DisplayName(
      "Por capitulos: una llamada por capitulo con su extracto, un CapituloLeido por cada uno, y el"
          + " contexto final avisa que son extractos")
  void documentoLargoPorCapitulos() {
    var propiedades = new AccionesPropiedades(10, 100, 8000, 1, 50);
    List<Seccion> secciones = new ArrayList<>();
    Map<Long, List<String>> rutas = new HashMap<>();
    for (int c = 1; c <= 5; c++) {
      for (int s = 1; s <= 2; s++) {
        long id = secciones.size() + 1;
        secciones.add(new Seccion(id, 10L, "file:///L", "L", (int) id, "b".repeat(30), "doc"));
        rutas.put(id, List.of("Cap " + c, c + "." + s));
      }
    }
    SeccionesRepositorio repo = mock(SeccionesRepositorio.class);
    when(repo.seccionesDe(any(), any())).thenReturn(secciones);
    when(repo.rutasDe(List.of(10L), "default")).thenReturn(rutas);
    Redactor redactor = mock(Redactor.class);
    when(redactor.condensar(anyString(), eq("es"), anyInt())).thenReturn("nota");
    when(redactor.resumir(anyString(), eq("es"))).thenReturn(Flux.just("Resumen."));
    var cupo = new CupoDeAcciones(propiedades);
    var acciones = new AccionesSobreDocumentos(repo, redactor, cupo, propiedades, RENDIMIENTO);

    ResultadoDeAccion resultado =
        acciones.ejecutar(Tipo.RESUMIR, List.of(10L), PROYECTO, "es", Modo.POR_CAPITULOS);

    assertThat(resultado.cobertura())
        .containsExactly(new CoberturaDocumento(10L, "L", "file:///L", 10, 5, Modo.POR_CAPITULOS));
    List<EventoAccion> eventos = resultado.eventos().collectList().block();
    assertThat(eventos).noneMatch(e -> e instanceof Lectura);
    assertThat(eventos.stream().filter(e -> e instanceof CapituloLeido).map(e -> (CapituloLeido) e))
        .extracting(CapituloLeido::actual, CapituloLeido::titulo)
        .containsExactly(
            tuple(1, "Cap 1"),
            tuple(2, "Cap 2"),
            tuple(3, "Cap 3"),
            tuple(4, "Cap 4"),
            tuple(5, "Cap 5"));
    verify(redactor, times(5)).condensar(anyString(), eq("es"), anyInt());
    verify(redactor).condensar(startsWith("Capítulo: Cap 3"), eq("es"), anyInt());

    ArgumentCaptor<String> contexto = ArgumentCaptor.forClass(String.class);
    verify(redactor).resumir(contexto.capture(), eq("es"));
    assertThat(contexto.getValue()).contains("leido por capitulos").contains("## Cap 1");
    EsperaDeCupo.vuelveYSeToma(cupo, "el cupo volvio al terminar");
  }

  @Test
  @DisplayName(
      "Rapido: sin pasadas ni condensar; la llamada final ve secciones representativas y el aviso")
  void documentoLargoRapido() {
    var propiedades = new AccionesPropiedades(10, 1000, 8000, 1, 500);
    List<Seccion> secciones = new ArrayList<>();
    Map<Long, float[]> vectores = new HashMap<>();
    for (int i = 0; i < 40; i++) {
      secciones.add(new Seccion(i + 1, 10L, "file:///L", "L", i, "seccion " + i, "doc"));
      vectores.put(i + 1L, new float[] {i < 20 ? 1 : 0, i < 20 ? 0 : 1, i * 0.001f});
    }
    secciones.add(new Seccion(41, 10L, "file:///L", "L", 40, "z".repeat(2000), "doc"));
    SeccionesRepositorio repo = mock(SeccionesRepositorio.class);
    when(repo.seccionesDe(any(), any())).thenReturn(secciones);
    when(repo.rutasDe(List.of(10L), "default")).thenReturn(Map.of());
    when(repo.embeddingsDe(List.of(10L), "default")).thenReturn(vectores);
    Redactor redactor = mock(Redactor.class);
    when(redactor.resumir(anyString(), eq("es"))).thenReturn(Flux.just("Resumen."));
    var cupo = new CupoDeAcciones(propiedades);
    var acciones = new AccionesSobreDocumentos(repo, redactor, cupo, propiedades, RENDIMIENTO);

    ResultadoDeAccion resultado =
        acciones.ejecutar(Tipo.RESUMIR, List.of(10L), PROYECTO, "es", Modo.RAPIDO);

    assertThat(resultado.cobertura())
        .containsExactly(new CoberturaDocumento(10L, "L", "file:///L", 41, 0, Modo.RAPIDO));
    assertThat(resultado.eventos().collectList().block()).containsExactly(new Token("Resumen."));
    verify(redactor, never()).condensar(anyString(), anyString(), anyInt());
    ArgumentCaptor<String> contexto = ArgumentCaptor.forClass(String.class);
    verify(redactor).resumir(contexto.capture(), eq("es"));
    assertThat(contexto.getValue())
        .contains("lectura rapida")
        .containsPattern("seccion 1?[0-9]\\b")
        .containsPattern("seccion [23][0-9]\\b")
        .hasSizeLessThan(1400);
    EsperaDeCupo.vuelveYSeToma(cupo, "el cupo volvio al terminar");
  }

  @Test
  @DisplayName("La nota de un capitulo pierde la linea de encabezado si el modelo la repitio")
  void notaSinEncabezado() {
    assertThat(AccionesSobreDocumentos.sinEncabezado("Capítulo: Interfaces\n\n- Una interfaz..."))
        .isEqualTo("- Una interfaz...");
    assertThat(AccionesSobreDocumentos.sinEncabezado("- Sin encabezado"))
        .isEqualTo("- Sin encabezado");
    assertThat(AccionesSobreDocumentos.sinEncabezado("Capítulo: solo esto")).isEmpty();
  }

  private static Seccion seccion(long id, long documentoId, String titulo, String texto) {
    return new Seccion(id, documentoId, "file:///" + titulo, titulo, 0, texto, "doc_section");
  }
}
