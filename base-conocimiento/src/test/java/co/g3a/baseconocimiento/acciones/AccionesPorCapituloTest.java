package co.g3a.baseconocimiento.acciones;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.g3a.baseconocimiento.acciones.Acciones.CapituloElegido;
import co.g3a.baseconocimiento.acciones.Acciones.CoberturaDocumento;
import co.g3a.baseconocimiento.acciones.Acciones.EventoAccion;
import co.g3a.baseconocimiento.acciones.Acciones.EventoAccion.CapituloLeido;
import co.g3a.baseconocimiento.acciones.Acciones.EventoAccion.Resultado;
import co.g3a.baseconocimiento.acciones.Acciones.IdeaUbicada;
import co.g3a.baseconocimiento.acciones.Acciones.IdeasUbicadas;
import co.g3a.baseconocimiento.acciones.Acciones.Modo;
import co.g3a.baseconocimiento.acciones.Acciones.NivelUbicado;
import co.g3a.baseconocimiento.acciones.Acciones.PreguntaUbicada;
import co.g3a.baseconocimiento.acciones.Acciones.PreguntasUbicadas;
import co.g3a.baseconocimiento.acciones.Acciones.ResultadoDeAccion;
import co.g3a.baseconocimiento.acciones.Acciones.SinResultado;
import co.g3a.baseconocimiento.acciones.Acciones.Tipo;
import co.g3a.baseconocimiento.acciones.SeccionesRepositorio.Seccion;
import co.g3a.baseconocimiento.compartido.Dominio.ProyectoId;
import co.g3a.baseconocimiento.llm.Redactor;
import co.g3a.baseconocimiento.llm.Redactor.Idea;
import co.g3a.baseconocimiento.llm.Redactor.Ideas;
import co.g3a.baseconocimiento.llm.Redactor.Pregunta;
import co.g3a.baseconocimiento.llm.RendimientoDelModelo;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Preguntas e ideas por capitulo elegido (issue #66), con el LLM y Postgres doblados. Un documento
 * largo de cinco capitulos ({@code L}, el 10) y uno corto ({@code C}, el 20), pedidos en ese orden
 * inverso: el corto es {@code [1]} y el largo {@code [2]}.
 */
class AccionesPorCapituloTest {

  private static final ProyectoId PROYECTO = new ProyectoId("default");

  /** Cuota de 100 caracteres: el largo (300) no cabe y se parte en capitulos; el corto si. */
  private static final AccionesPropiedades PROPIEDADES =
      new AccionesPropiedades(10, 100, 8000, 1, 50);

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

  private final SeccionesRepositorio repo = mock(SeccionesRepositorio.class);
  private final Redactor redactor = mock(Redactor.class);
  private final CupoDeAcciones cupo = new CupoDeAcciones(PROPIEDADES);
  private final AccionesPorCapitulo acciones =
      new AccionesPorCapitulo(repo, redactor, cupo, PROPIEDADES, RENDIMIENTO);

  @Test
  @DisplayName(
      "Preguntas: solo los capitulos elegidos, despues de los cortos; cada pregunta lleva su"
          + " capitulo y la fuente del documento, y no se repite entre tandas")
  void preguntasDeLosCapitulosElegidos() {
    documentos();
    when(redactor.preguntar(anyString(), eq("es"), any(), any()))
        .thenAnswer(
            invocacion -> {
              String contexto = invocacion.getArgument(0);
              if (contexto.contains("capitulo «Cap 2»")) {
                return List.of(new Pregunta("¿Qué dice el capítulo dos?", "que", 9));
              }
              if (contexto.contains("capitulo «Cap 4»")) {
                // La misma del corto, con otros acentos y signos: no se repite.
                return List.of(new Pregunta("¿Que dice el corto?", "que", 9));
              }
              return List.of(new Pregunta("¿Qué dice el corto?", "que", 1));
            });

    ResultadoDeAccion resultado =
        acciones.ejecutar(
            Tipo.PREGUNTAS,
            List.of(20L, 10L),
            PROYECTO,
            "es",
            List.of(new CapituloElegido(10L, 1), new CapituloElegido(10L, 3)));

    assertThat(resultado.cobertura())
        .containsExactly(
            new CoberturaDocumento(20L, "C", "file:///C", 1, 0, null),
            new CoberturaDocumento(10L, "L", "file:///L", 10, 2, Modo.POR_CAPITULOS));
    List<EventoAccion> eventos = resultado.eventos().collectList().block();

    assertThat(eventos)
        .filteredOn(CapituloLeido.class::isInstance)
        .map(CapituloLeido.class::cast)
        .extracting(CapituloLeido::actual, CapituloLeido::total, CapituloLeido::titulo)
        // Por documento largo: la tanda de los cortos no cuenta como capitulo.
        .containsExactly(tuple(1, 2, "Cap 2"), tuple(2, 2, "Cap 4"));
    PreguntasUbicadas ultimo = (PreguntasUbicadas) ultimoResultado(eventos);
    assertThat(ultimo.niveles()).hasSize(6);
    NivelUbicado recordar = ultimo.niveles().getFirst();
    assertThat(recordar.nivel()).isEqualTo("recordar");
    assertThat(recordar.preguntas())
        .extracting(PreguntaUbicada::texto, PreguntaUbicada::fuente, PreguntaUbicada::capitulo)
        .containsExactly(
            tuple("¿Qué dice el corto?", 1, null), tuple("¿Qué dice el capítulo dos?", 2, "Cap 2"));

    ArgumentCaptor<String> contextos = ArgumentCaptor.forClass(String.class);
    verify(redactor, times(18)).preguntar(contextos.capture(), eq("es"), any(), any());
    assertThat(contextos.getAllValues())
        .noneMatch(c -> c.contains("«Cap 1»") || c.contains("«Cap 3»") || c.contains("«Cap 5»"))
        .anyMatch(c -> c.startsWith("[1] C (file:///C)\ncorto"))
        .anyMatch(c -> c.startsWith("[2] L (file:///L)\n(Documento largo"));
    EsperaDeCupo.vuelveYSeToma(cupo, "el cupo volvio al terminar");
  }

  @Test
  @DisplayName("Ideas: una llamada por tanda, con el capitulo y la fuente del documento")
  void ideasPorCapitulo() {
    documentos();
    when(redactor.idear(anyString(), eq("es")))
        .thenAnswer(
            invocacion -> {
              String contexto = invocacion.getArgument(0);
              String titulo = contexto.contains("«Cap 5»") ? "Idea del cinco" : "Idea del corto";
              return new Ideas(List.of(new Idea(titulo, "Porque si.", 7)));
            });

    ResultadoDeAccion resultado =
        acciones.ejecutar(
            Tipo.IDEAS, List.of(20L, 10L), PROYECTO, "es", List.of(new CapituloElegido(10L, 4)));
    List<EventoAccion> eventos = resultado.eventos().collectList().block();

    IdeasUbicadas ideas = (IdeasUbicadas) ultimoResultado(eventos);
    assertThat(ideas.ideas())
        .extracting(IdeaUbicada::titulo, IdeaUbicada::fuente, IdeaUbicada::capitulo)
        .containsExactly(tuple("Idea del corto", 7, null), tuple("Idea del cinco", 2, "Cap 5"));
    verify(redactor, times(2)).idear(anyString(), eq("es"));
  }

  @Test
  @DisplayName("Sin capitulos elegidos van todos los capitulos de los documentos largos")
  void sinElegidosVanTodos() {
    documentos();
    when(redactor.idear(anyString(), eq("es"))).thenReturn(new Ideas(List.of()));

    acciones
        .ejecutar(Tipo.IDEAS, List.of(10L), PROYECTO, "es", List.of())
        .eventos()
        .collectList()
        .block();

    verify(redactor, times(5)).idear(anyString(), eq("es"));
  }

  @Test
  @DisplayName(
      "Si ningun capitulo elegido existe y no hay cortos, se dice con un mensaje fijo y no se toma"
          + " el cupo")
  void elegidosQueNoExisten() {
    documentos();

    List<EventoAccion> eventos =
        acciones
            .ejecutar(
                Tipo.PREGUNTAS, List.of(10L), PROYECTO, "es", List.of(new CapituloElegido(10L, 99)))
            .eventos()
            .collectList()
            .block();

    assertThat(eventos)
        .singleElement()
        .isEqualTo(new Resultado(new SinResultado(AccionesPorCapitulo.MENSAJE_SIN_CAPITULOS)));
    verify(redactor, never()).preguntar(any(), any(), any(), any());
    assertThat(cupo.intentarTomar()).isTrue();
  }

  @Test
  @DisplayName("La clave de duplicados ignora acentos, mayusculas y signos")
  void claveDeDuplicados() {
    assertThat(AccionesPorCapitulo.Acumulado.clave("¿Qué es un TIPO primitivo?"))
        .isEqualTo(AccionesPorCapitulo.Acumulado.clave("que es un tipo primitivo"));
  }

  /** El largo: cinco capitulos de dos secciones de 30 caracteres. El corto: una seccion. */
  private void documentos() {
    List<Seccion> secciones = new ArrayList<>();
    Map<Long, List<String>> rutas = new HashMap<>();
    for (int c = 1; c <= 5; c++) {
      for (int s = 1; s <= 2; s++) {
        long id = secciones.size() + 1;
        secciones.add(new Seccion(id, 10L, "file:///L", "L", (int) id, "b".repeat(30), "doc"));
        rutas.put(id, List.of("Cap " + c, c + "." + s));
      }
    }
    secciones.add(new Seccion(100L, 20L, "file:///C", "C", 1, "corto", "doc"));
    when(repo.seccionesDe(any(), any())).thenReturn(secciones);
    when(repo.rutasDe(List.of(10L), "default")).thenReturn(rutas);
  }

  private static Object ultimoResultado(List<EventoAccion> eventos) {
    return eventos.stream()
        .filter(Resultado.class::isInstance)
        .map(e -> ((Resultado) e).valor())
        .reduce((primero, segundo) -> segundo)
        .orElseThrow();
  }
}
