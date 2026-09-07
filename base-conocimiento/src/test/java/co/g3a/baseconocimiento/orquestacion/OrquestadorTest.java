package co.g3a.baseconocimiento.orquestacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.g3a.baseconocimiento.compartido.Dominio.Filtros;
import co.g3a.baseconocimiento.compartido.Dominio.Fragmento;
import co.g3a.baseconocimiento.compartido.Dominio.IdiomaRespuesta;
import co.g3a.baseconocimiento.compartido.Dominio.Pregunta;
import co.g3a.baseconocimiento.compartido.Dominio.ProyectoId;
import co.g3a.baseconocimiento.llm.Planificador;
import co.g3a.baseconocimiento.llm.Planificador.PlanDeHerramientas;
import co.g3a.baseconocimiento.llm.Reformulador;
import co.g3a.baseconocimiento.llm.Sintetizador;
import co.g3a.baseconocimiento.llm.VerificadorGrounding;
import co.g3a.baseconocimiento.llm.VerificadorGrounding.Veredicto;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Las siete etapas conectadas, con el planner, el sintetizador y los repositorios de Postgres
 * reemplazados por dobles: lo que se prueba aqui es el cableado del pipeline (planificar ->
 * ejecutar -> fusionar -> expandir -> sintetizar -> registrar), no Ollama ni la base de datos real
 * — eso ya lo cubren los smoke tests de cada pieza por separado.
 */
class OrquestadorTest {

  private static final ProyectoId PROYECTO = new ProyectoId("default");
  private static final UmbralRelevanciaPropiedades UMBRAL_POR_DEFECTO =
      new UmbralRelevanciaPropiedades(true, 0.003, 0.05, 500, 8.0);
  private static final Reformulador REFORMULADOR_SIN_CAMBIOS =
      (pregunta, pistas) -> Reformulador.Reformulacion.sinCambios(pregunta);

  @Test
  @DisplayName("Conecta las siete etapas: plan, herramientas, fusion, sintesis y registro")
  void ejecutaLasSieteEtapas() {
    // rerank=9.0 supera techoConfianza: SUFICIENTE sin pasar por el verificador.
    Fragmento fragmento =
        new Fragmento(
            1L,
            100L,
            "file:///doc1",
            "Doc 1",
            "Esto es el fragmento uno.",
            "doc_section",
            0,
            Instant.EPOCH,
            Map.of(),
            0.05,
            9.0);

    var catalogo = new CatalogoHerramientas(List.of(herramientaFalsa("fake_tool", fragmento)));
    var executor = new Executor(catalogo);

    Planificador planificador =
        (pregunta, herramientas) -> new PlanDeHerramientas(List.of("fake_tool"), "porque si");

    Sintetizador sintetizador =
        (pregunta, contexto, idioma) -> Flux.just("Respuesta ", "citando ", "[1].");
    VerificadorGrounding verificadorGrounding = mock(VerificadorGrounding.class);

    ContextoRepositorio contextoRepo = mock(ContextoRepositorio.class);
    when(contextoRepo.vecinos(100L, 0)).thenReturn(List.of());

    HerramientasRepositorio herramientasRepo = mock(HerramientasRepositorio.class);
    when(herramientasRepo.contarChunks(anyString())).thenReturn(100L);

    QueryLogRepositorio queryLog = mock(QueryLogRepositorio.class);
    when(queryLog.registrar(any(), any(), any(), any(), any(), any(), any(), anyLong()))
        .thenReturn(42L);

    var orquestador =
        new Orquestador(
            planificador,
            REFORMULADOR_SIN_CAMBIOS,
            catalogo,
            executor,
            contextoRepo,
            herramientasRepo,
            sintetizador,
            verificadorGrounding,
            queryLog,
            10,
            true,
            UMBRAL_POR_DEFECTO,
            10,
            mock(StreamsEnCursoRepositorio.class));

    Orquestador.EjecucionPipeline resultado =
        orquestador.ejecutar(new Pregunta("¿Que es esto?"), PROYECTO, Filtros.NINGUNO);

    assertThat(resultado.plan().herramientas()).containsExactly("fake_tool");

    assertThat(resultado.herramientas()).hasSize(1);
    assertThat(resultado.herramientas().get(0).error()).isNull();

    assertThat(resultado.fragmentosUsados()).extracting(Fragmento::id).containsExactly(1L);

    assertThat(resultado.respuesta().texto()).isEqualTo("Respuesta citando [1].");
    assertThat(resultado.respuesta().citas()).hasSize(1);
    assertThat(resultado.respuesta().citas().get(0).uri()).isEqualTo("file:///doc1");
    assertThat(resultado.respuesta().consultaReformulada()).isNull();

    assertThat(resultado.queryLogId()).isEqualTo(42L);
    verify(queryLog).registrar(any(), any(), any(), any(), any(), any(), any(), anyLong());
    verify(verificadorGrounding, never()).verificar(any(), any());
  }

  @Test
  @DisplayName(
      "ADR-0008: si el mejor rerank no alcanza el umbral, no llama ni al verificador ni al sintetizador")
  void noSintetizaCuandoElContextoNoAlcanza() {
    // rerank bajo a proposito: por debajo del umbral incluso en un corpus chico
    // (chunksProyecto=4, igual que el corpus semilla del taller).
    Fragmento fragmentoDebil =
        new Fragmento(
            1L,
            100L,
            "file:///despliegue.md",
            "despliegue.md",
            "Corre make pull-models para descargar los modelos.",
            "doc_section",
            0,
            Instant.EPOCH,
            Map.of(),
            0.03,
            0.0019);

    var catalogo = new CatalogoHerramientas(List.of(herramientaFalsa("fake_tool", fragmentoDebil)));
    var executor = new Executor(catalogo);

    Planificador planificador =
        (pregunta, herramientas) -> new PlanDeHerramientas(List.of("fake_tool"), "porque si");

    Sintetizador sintetizador = mock(Sintetizador.class);
    VerificadorGrounding verificadorGrounding = mock(VerificadorGrounding.class);

    ContextoRepositorio contextoRepo = mock(ContextoRepositorio.class);

    HerramientasRepositorio herramientasRepo = mock(HerramientasRepositorio.class);
    when(herramientasRepo.contarChunks(anyString())).thenReturn(4L);

    QueryLogRepositorio queryLog = mock(QueryLogRepositorio.class);
    when(queryLog.registrar(any(), any(), any(), any(), any(), any(), any(), anyLong()))
        .thenReturn(1L);

    var orquestador =
        new Orquestador(
            planificador,
            REFORMULADOR_SIN_CAMBIOS,
            catalogo,
            executor,
            contextoRepo,
            herramientasRepo,
            sintetizador,
            verificadorGrounding,
            queryLog,
            10,
            true,
            UMBRAL_POR_DEFECTO,
            10,
            mock(StreamsEnCursoRepositorio.class));

    Orquestador.EjecucionPipeline resultado =
        orquestador.ejecutar(
            new Pregunta("explicame como usar Java 25"), PROYECTO, Filtros.NINGUNO);

    assertThat(resultado.respuesta().texto()).isEqualTo(Orquestador.MENSAJE_SIN_INFORMACION);
    assertThat(resultado.respuesta().citas()).isEmpty();
    verify(sintetizador, never()).sintetizar(any(), any(), any());
    verify(verificadorGrounding, never()).verificar(any(), any());
  }

  @Test
  @DisplayName(
      "ADR-0008: en la zona ambigua, un veredicto negativo del verificador rechaza sin sintetizar")
  void rechazaCuandoElVerificadorDeGroundingDiceQueNo() {
    // "como usar java 25" puntuo 0.0134: por encima del umbral, lejos de la
    // certeza -- el contraejemplo real de ADR-0008 que un score solo no resuelve.
    Fragmento ambiguo =
        new Fragmento(
            1L,
            100L,
            "file:///despliegue.md",
            "despliegue.md",
            "1. Copia .env.example a .env. 2. Corre docker compose up -d.",
            "doc_section",
            0,
            Instant.EPOCH,
            Map.of(),
            0.03,
            0.0134);

    var catalogo = new CatalogoHerramientas(List.of(herramientaFalsa("fake_tool", ambiguo)));
    var executor = new Executor(catalogo);

    Planificador planificador =
        (pregunta, herramientas) -> new PlanDeHerramientas(List.of("fake_tool"), "porque si");

    Sintetizador sintetizador = mock(Sintetizador.class);
    VerificadorGrounding verificadorGrounding = mock(VerificadorGrounding.class);
    when(verificadorGrounding.verificar(anyString(), anyString())).thenReturn(new Veredicto(false));

    ContextoRepositorio contextoRepo = mock(ContextoRepositorio.class);
    when(contextoRepo.vecinos(100L, 0)).thenReturn(List.of());

    HerramientasRepositorio herramientasRepo = mock(HerramientasRepositorio.class);
    when(herramientasRepo.contarChunks(anyString())).thenReturn(4L);

    QueryLogRepositorio queryLog = mock(QueryLogRepositorio.class);
    when(queryLog.registrar(any(), any(), any(), any(), any(), any(), any(), anyLong()))
        .thenReturn(1L);

    var orquestador =
        new Orquestador(
            planificador,
            REFORMULADOR_SIN_CAMBIOS,
            catalogo,
            executor,
            contextoRepo,
            herramientasRepo,
            sintetizador,
            verificadorGrounding,
            queryLog,
            10,
            true,
            UMBRAL_POR_DEFECTO,
            10,
            mock(StreamsEnCursoRepositorio.class));

    Orquestador.EjecucionPipeline resultado =
        orquestador.ejecutar(new Pregunta("como usar java 25"), PROYECTO, Filtros.NINGUNO);

    assertThat(resultado.respuesta().texto()).isEqualTo(Orquestador.MENSAJE_SIN_INFORMACION);
    assertThat(resultado.respuesta().citas()).isEmpty();
    verify(sintetizador, never()).sintetizar(any(), any(), any());
  }

  @Test
  @DisplayName(
      "ADR-0008: en la zona ambigua, un veredicto positivo del verificador deja sintetizar normal")
  void aceptaCuandoElVerificadorDeGroundingDiceQueSi() {
    Fragmento ambiguo =
        new Fragmento(
            1L,
            100L,
            "file:///despliegue.md",
            "despliegue.md",
            "Se necesita Docker Desktop con el motor iniciado.",
            "doc_section",
            0,
            Instant.EPOCH,
            Map.of(),
            0.03,
            0.0134);

    var catalogo = new CatalogoHerramientas(List.of(herramientaFalsa("fake_tool", ambiguo)));
    var executor = new Executor(catalogo);

    Planificador planificador =
        (pregunta, herramientas) -> new PlanDeHerramientas(List.of("fake_tool"), "porque si");

    Sintetizador sintetizador =
        (pregunta, contexto, idioma) -> Flux.just("Respuesta ", "citando ", "[1].");
    VerificadorGrounding verificadorGrounding = mock(VerificadorGrounding.class);
    when(verificadorGrounding.verificar(anyString(), anyString())).thenReturn(new Veredicto(true));

    ContextoRepositorio contextoRepo = mock(ContextoRepositorio.class);
    when(contextoRepo.vecinos(100L, 0)).thenReturn(List.of());

    HerramientasRepositorio herramientasRepo = mock(HerramientasRepositorio.class);
    when(herramientasRepo.contarChunks(anyString())).thenReturn(4L);

    QueryLogRepositorio queryLog = mock(QueryLogRepositorio.class);
    when(queryLog.registrar(any(), any(), any(), any(), any(), any(), any(), anyLong()))
        .thenReturn(1L);

    var orquestador =
        new Orquestador(
            planificador,
            REFORMULADOR_SIN_CAMBIOS,
            catalogo,
            executor,
            contextoRepo,
            herramientasRepo,
            sintetizador,
            verificadorGrounding,
            queryLog,
            10,
            true,
            UMBRAL_POR_DEFECTO,
            10,
            mock(StreamsEnCursoRepositorio.class));

    Orquestador.EjecucionPipeline resultado =
        orquestador.ejecutar(
            new Pregunta("como se despliega el servicio"), PROYECTO, Filtros.NINGUNO);

    assertThat(resultado.respuesta().texto()).isEqualTo("Respuesta citando [1].");
    assertThat(resultado.respuesta().citas()).hasSize(1);
  }

  @Test
  @DisplayName("ADR-0008: no bloquea fragmentos de herramientas de listado, que no traen rerank")
  void noBloqueaHerramientasDeListado() {
    // recent_commits/subsystem_index/who_knows no rankean por relevancia: rrf=0, rerank=null.
    Fragmento deListado =
        new Fragmento(
            1L,
            100L,
            "file:///doc1",
            "Doc 1",
            "texto",
            "subsystem_summary",
            0,
            Instant.EPOCH,
            Map.of(),
            0.0,
            null);

    var catalogo =
        new CatalogoHerramientas(List.of(herramientaFalsa("subsystem_index", deListado)));
    var executor = new Executor(catalogo);

    Planificador planificador =
        (pregunta, herramientas) -> new PlanDeHerramientas(List.of("subsystem_index"), "porque si");

    Sintetizador sintetizador = (pregunta, contexto, idioma) -> Flux.just("Respuesta.");
    VerificadorGrounding verificadorGrounding = mock(VerificadorGrounding.class);

    ContextoRepositorio contextoRepo = mock(ContextoRepositorio.class);
    when(contextoRepo.vecinos(100L, 0)).thenReturn(List.of());

    HerramientasRepositorio herramientasRepo = mock(HerramientasRepositorio.class);
    when(herramientasRepo.contarChunks(anyString())).thenReturn(4L);

    QueryLogRepositorio queryLog = mock(QueryLogRepositorio.class);
    when(queryLog.registrar(any(), any(), any(), any(), any(), any(), any(), anyLong()))
        .thenReturn(1L);

    var orquestador =
        new Orquestador(
            planificador,
            REFORMULADOR_SIN_CAMBIOS,
            catalogo,
            executor,
            contextoRepo,
            herramientasRepo,
            sintetizador,
            verificadorGrounding,
            queryLog,
            10,
            true,
            UMBRAL_POR_DEFECTO,
            10,
            mock(StreamsEnCursoRepositorio.class));

    Orquestador.EjecucionPipeline resultado =
        orquestador.ejecutar(new Pregunta("¿quien sabe de auth?"), PROYECTO, Filtros.NINGUNO);

    assertThat(resultado.respuesta().texto()).isEqualTo("Respuesta.");
    verify(verificadorGrounding, never()).verificar(any(), any());
  }

  @Test
  @DisplayName(
      "Cuando la busqueda con la pregunta original no llega a SUFICIENTE, el Reformulador "
          + "entra en juego y las herramientas reciben el texto reformulado en un segundo intento")
  void pasaLaConsultaReformuladaALasHerramientasSoloCuandoElPrimerIntentoNoAlcanza() {
    // rerank=0.01 esta por debajo del umbral efectivo con chunksProyecto=100
    // (piso=0.003, techo=0.05, chunksReferencia=500 -> umbral=0.0124): INSUFICIENTE.
    Fragmento fragmentoDebil =
        new Fragmento(
            1L,
            100L,
            "file:///doc1",
            "Doc 1",
            "algo tangencial a la pregunta",
            "doc_section",
            0,
            Instant.EPOCH,
            Map.of(),
            0.05,
            0.01);
    // rerank=9.0 supera techoConfianza: SUFICIENTE en el segundo intento.
    Fragmento fragmentoFuerte =
        new Fragmento(
            2L,
            100L,
            "file:///doc1",
            "Doc 1",
            "Boxing conversion treats expressions...",
            "doc_section",
            0,
            Instant.EPOCH,
            Map.of(),
            0.05,
            9.0);

    List<String> consultasRecibidas = new ArrayList<>();
    Herramienta herramientaQueRegistraLaConsulta =
        new Herramienta() {
          @Override
          public String nombre() {
            return "fake_tool";
          }

          @Override
          public String descripcion() {
            return "de prueba";
          }

          @Override
          public List<Fragmento> ejecutar(
              String consulta, ProyectoId proyecto, List<Long> documentosPermitidos) {
            consultasRecibidas.add(consulta);
            return consulta.equals("boxing conversion")
                ? List.of(fragmentoFuerte)
                : List.of(fragmentoDebil);
          }
        };
    var catalogo = new CatalogoHerramientas(List.of(herramientaQueRegistraLaConsulta));
    var executor = new Executor(catalogo);

    Planificador planificador =
        (pregunta, herramientas) -> new PlanDeHerramientas(List.of("fake_tool"), "porque si");
    Reformulador reformulador =
        (pregunta, pistas) ->
            new Reformulador.Reformulacion(pregunta, List.of("boxing conversion"));

    Sintetizador sintetizador = (pregunta, contexto, idioma) -> Flux.just("Respuesta.");
    VerificadorGrounding verificadorGrounding = mock(VerificadorGrounding.class);

    ContextoRepositorio contextoRepo = mock(ContextoRepositorio.class);
    when(contextoRepo.vecinos(100L, 0)).thenReturn(List.of());

    HerramientasRepositorio herramientasRepo = mock(HerramientasRepositorio.class);
    when(herramientasRepo.contarChunks(anyString())).thenReturn(100L);

    QueryLogRepositorio queryLog = mock(QueryLogRepositorio.class);
    when(queryLog.registrar(any(), any(), any(), any(), any(), any(), any(), anyLong()))
        .thenReturn(1L);

    var orquestador =
        new Orquestador(
            planificador,
            reformulador,
            catalogo,
            executor,
            contextoRepo,
            herramientasRepo,
            sintetizador,
            verificadorGrounding,
            queryLog,
            10,
            true,
            UMBRAL_POR_DEFECTO,
            10,
            mock(StreamsEnCursoRepositorio.class));

    Orquestador.EjecucionPipeline resultado =
        orquestador.ejecutar(new Pregunta("que es el autoboxing"), PROYECTO, Filtros.NINGUNO);

    assertThat(consultasRecibidas).containsExactly("que es el autoboxing", "boxing conversion");
    assertThat(resultado.respuesta().consultaReformulada()).isEqualTo("boxing conversion");
    assertThat(resultado.respuesta().texto()).isEqualTo("Respuesta.");
    verify(verificadorGrounding, never()).verificar(any(), any());
  }

  @Test
  @DisplayName(
      "Si la reformulacion empeora el resultado (rerank mas bajo que el original), se descarta y "
          + "se sintetiza con la ronda original")
  void descartaLaReformulacionCuandoEmpeoraElResultado() {
    // rerank=5.0: por encima del umbral efectivo (0.0124) pero por debajo de
    // techoConfianza (8.0) -> AMBIGUO, dispara la reformulacion.
    Fragmento fragmentoOriginalAmbiguo =
        new Fragmento(
            1L,
            100L,
            "file:///doc1",
            "Doc 1",
            "enum Coin { PENNY(1), NICKEL(5)... }",
            "doc_section",
            0,
            Instant.EPOCH,
            Map.of(),
            0.05,
            5.0);
    // rerank=0.01: la reformulacion se fue al tema equivocado y busca peor que
    // el original -> INSUFICIENTE, mucho peor que el AMBIGUO de arriba.
    Fragmento fragmentoReformuladoDebil =
        new Fragmento(
            2L,
            100L,
            "file:///doc2",
            "Doc 2",
            "algo sin relacion con la pregunta original",
            "doc_section",
            0,
            Instant.EPOCH,
            Map.of(),
            0.05,
            0.01);

    Herramienta herramientaQueEmpeoraAlReformular =
        new Herramienta() {
          @Override
          public String nombre() {
            return "fake_tool";
          }

          @Override
          public String descripcion() {
            return "de prueba";
          }

          @Override
          public List<Fragmento> ejecutar(
              String consulta, ProyectoId proyecto, List<Long> documentosPermitidos) {
            return consulta.equals("que es un enum")
                ? List.of(fragmentoOriginalAmbiguo)
                : List.of(fragmentoReformuladoDebil);
          }
        };
    var catalogo = new CatalogoHerramientas(List.of(herramientaQueEmpeoraAlReformular));
    var executor = new Executor(catalogo);

    Planificador planificador =
        (pregunta, herramientas) -> new PlanDeHerramientas(List.of("fake_tool"), "porque si");
    // Como el "que es un record" real: un LLM chico puede reformular lejos del tema.
    Reformulador reformulador =
        (pregunta, pistas) ->
            new Reformulador.Reformulacion(
                pregunta, List.of("estructura con acceso por indice numerico"));

    Sintetizador sintetizador =
        (pregunta, contexto, idioma) -> Flux.just("Un enum es un tipo restringido.");
    VerificadorGrounding verificadorGrounding = mock(VerificadorGrounding.class);
    when(verificadorGrounding.verificar(any(), any())).thenReturn(new Veredicto(true));

    ContextoRepositorio contextoRepo = mock(ContextoRepositorio.class);
    when(contextoRepo.vecinos(100L, 0)).thenReturn(List.of());

    HerramientasRepositorio herramientasRepo = mock(HerramientasRepositorio.class);
    when(herramientasRepo.contarChunks(anyString())).thenReturn(100L);

    QueryLogRepositorio queryLog = mock(QueryLogRepositorio.class);
    when(queryLog.registrar(any(), any(), any(), any(), any(), any(), any(), anyLong()))
        .thenReturn(1L);

    var orquestador =
        new Orquestador(
            planificador,
            reformulador,
            catalogo,
            executor,
            contextoRepo,
            herramientasRepo,
            sintetizador,
            verificadorGrounding,
            queryLog,
            10,
            true,
            UMBRAL_POR_DEFECTO,
            10,
            mock(StreamsEnCursoRepositorio.class));

    Orquestador.EjecucionPipeline resultado =
        orquestador.ejecutar(new Pregunta("que es un enum"), PROYECTO, Filtros.NINGUNO);

    // Se queda con el fragmento original (id=1), no con el de la reformulacion (id=2).
    assertThat(resultado.fragmentosUsados()).extracting(Fragmento::id).containsExactly(1L);
    assertThat(resultado.respuesta().texto()).isEqualTo("Un enum es un tipo restringido.");
    // No se muestra una reformulacion que ni siquiera se termino usando.
    assertThat(resultado.respuesta().consultaReformulada()).isNull();
    verify(verificadorGrounding).verificar(any(), any());
  }

  @Test
  @DisplayName(
      "Si la busqueda con la pregunta original ya llega a SUFICIENTE, el Reformulador no se llama")
  void noLlamaAlReformuladorCuandoElPrimerIntentoYaEsSuficiente() {
    Fragmento fragmentoFuerte =
        new Fragmento(
            1L,
            100L,
            "file:///doc1",
            "Doc 1",
            "Esto es el fragmento uno.",
            "doc_section",
            0,
            Instant.EPOCH,
            Map.of(),
            0.05,
            9.0);

    var catalogo =
        new CatalogoHerramientas(List.of(herramientaFalsa("fake_tool", fragmentoFuerte)));
    var executor = new Executor(catalogo);

    Planificador planificador =
        (pregunta, herramientas) -> new PlanDeHerramientas(List.of("fake_tool"), "porque si");
    Reformulador reformulador = mock(Reformulador.class);

    Sintetizador sintetizador = (pregunta, contexto, idioma) -> Flux.just("Respuesta.");
    VerificadorGrounding verificadorGrounding = mock(VerificadorGrounding.class);

    ContextoRepositorio contextoRepo = mock(ContextoRepositorio.class);
    when(contextoRepo.vecinos(100L, 0)).thenReturn(List.of());

    HerramientasRepositorio herramientasRepo = mock(HerramientasRepositorio.class);
    when(herramientasRepo.contarChunks(anyString())).thenReturn(100L);

    QueryLogRepositorio queryLog = mock(QueryLogRepositorio.class);
    when(queryLog.registrar(any(), any(), any(), any(), any(), any(), any(), anyLong()))
        .thenReturn(1L);

    var orquestador =
        new Orquestador(
            planificador,
            reformulador,
            catalogo,
            executor,
            contextoRepo,
            herramientasRepo,
            sintetizador,
            verificadorGrounding,
            queryLog,
            10,
            true,
            UMBRAL_POR_DEFECTO,
            10,
            mock(StreamsEnCursoRepositorio.class));

    orquestador.ejecutar(new Pregunta("pregunta cualquiera"), PROYECTO, Filtros.NINGUNO);

    verify(reformulador, never()).reformular(any(), any());
  }

  @Test
  @DisplayName(
      "Sin cupo de consultas concurrentes, corta antes de planificar y devuelve un mensaje claro")
  void devuelveServidorOcupadoSinCupoDisponible() {
    var catalogo = new CatalogoHerramientas(List.of());
    var executor = new Executor(catalogo);

    Planificador planificador = mock(Planificador.class);
    Reformulador reformulador = mock(Reformulador.class);
    Sintetizador sintetizador = mock(Sintetizador.class);
    VerificadorGrounding verificadorGrounding = mock(VerificadorGrounding.class);
    ContextoRepositorio contextoRepo = mock(ContextoRepositorio.class);
    HerramientasRepositorio herramientasRepo = mock(HerramientasRepositorio.class);

    QueryLogRepositorio queryLog = mock(QueryLogRepositorio.class);
    when(queryLog.registrar(any(), any(), any(), any(), any(), any(), any(), anyLong()))
        .thenReturn(1L);

    // 0 cupos: tryAcquire() nunca consigue permiso, sea cual sea la pregunta.
    var orquestador =
        new Orquestador(
            planificador,
            reformulador,
            catalogo,
            executor,
            contextoRepo,
            herramientasRepo,
            sintetizador,
            verificadorGrounding,
            queryLog,
            10,
            true,
            UMBRAL_POR_DEFECTO,
            0,
            mock(StreamsEnCursoRepositorio.class));

    Orquestador.EjecucionPipeline resultado =
        orquestador.ejecutar(new Pregunta("cualquier cosa"), PROYECTO, Filtros.NINGUNO);

    assertThat(resultado.respuesta().texto()).isEqualTo(Orquestador.MENSAJE_SERVIDOR_OCUPADO);
    assertThat(resultado.respuesta().citas()).isEmpty();
    verify(planificador, never()).planificar(any(), any());
    verify(sintetizador, never()).sintetizar(any(), any(), any());
    verify(verificadorGrounding, never()).verificar(any(), any());
  }

  @Test
  @DisplayName(
      "En streaming, con conversacionId persiste el inicio, las citas y el resultado final "
          + "en StreamsEnCursoRepositorio -- para que la UI se pueda reconectar tras un F5")
  void persisteElEstadoDelStreamCuandoHayConversacionId() {
    Fragmento fragmento =
        new Fragmento(
            1L,
            100L,
            "file:///doc1",
            "Doc 1",
            "Esto es el fragmento uno.",
            "doc_section",
            0,
            Instant.EPOCH,
            Map.of(),
            0.05,
            9.0);

    var catalogo = new CatalogoHerramientas(List.of(herramientaFalsa("fake_tool", fragmento)));
    var executor = new Executor(catalogo);

    Planificador planificador =
        (pregunta, herramientas) -> new PlanDeHerramientas(List.of("fake_tool"), "porque si");

    Sintetizador sintetizador = (pregunta, contexto, idioma) -> Flux.just("Respuesta ", "final.");
    VerificadorGrounding verificadorGrounding = mock(VerificadorGrounding.class);

    ContextoRepositorio contextoRepo = mock(ContextoRepositorio.class);
    when(contextoRepo.vecinos(100L, 0)).thenReturn(List.of());

    HerramientasRepositorio herramientasRepo = mock(HerramientasRepositorio.class);
    when(herramientasRepo.contarChunks(anyString())).thenReturn(100L);

    QueryLogRepositorio queryLog = mock(QueryLogRepositorio.class);
    when(queryLog.registrar(any(), any(), any(), any(), any(), any(), any(), anyLong()))
        .thenReturn(1L);

    StreamsEnCursoRepositorio streamsEnCurso = mock(StreamsEnCursoRepositorio.class);

    var orquestador =
        new Orquestador(
            planificador,
            REFORMULADOR_SIN_CAMBIOS,
            catalogo,
            executor,
            contextoRepo,
            herramientasRepo,
            sintetizador,
            verificadorGrounding,
            queryLog,
            10,
            true,
            UMBRAL_POR_DEFECTO,
            10,
            streamsEnCurso);

    Consultar.RespuestaEnStreaming resultado =
        orquestador.ejecutarEnStreaming(
            new Pregunta("¿Que es esto?"),
            PROYECTO,
            Filtros.NINGUNO,
            42L,
            Consultar.Preferencias.POR_DEFECTO);
    // El Flux es perezoso: doOnNext/doFinally recien corren al suscribirse.
    String textoCompleto =
        resultado.texto().collectList().map(partes -> String.join("", partes)).block();

    assertThat(textoCompleto).isEqualTo("Respuesta final.");
    assertThat(resultado.queryLogId().block()).isEqualTo(1L);
    verify(streamsEnCurso).iniciar(42L, "¿Que es esto?", "default");
    verify(streamsEnCurso).actualizarCitas(eq(42L), any(), any());
    verify(streamsEnCurso).finalizar(42L, "completo", "Respuesta final.", 1L);
  }

  @Test
  @DisplayName(
      "Modo Proponer: si la busqueda original no alcanza, devuelve las alternativas sin sintetizar, "
          + "suelta el cupo y descarta el stream en curso")
  void enModoProponerDevuelveLasAlternativasSinResponder() {
    // rerank=0.01 -> INSUFICIENTE con la pregunta tal cual: dispara el Reformulador.
    Fragmento fragmentoDebil = fragmento(0.01);
    List<String> consultasRecibidas = new ArrayList<>();
    var catalogo =
        new CatalogoHerramientas(
            List.of(herramientaQueRegistra("fake_tool", consultasRecibidas, fragmentoDebil)));
    var executor = new Executor(catalogo);

    Planificador planificador =
        (pregunta, herramientas) -> new PlanDeHerramientas(List.of("fake_tool"), "porque si");
    Reformulador reformulador =
        (pregunta, pistas) ->
            new Reformulador.Reformulacion(
                pregunta, List.of("boxing conversion", "autoboxing Java"));
    Sintetizador sintetizador = mock(Sintetizador.class);
    QueryLogRepositorio queryLog = mock(QueryLogRepositorio.class);
    StreamsEnCursoRepositorio streamsEnCurso = mock(StreamsEnCursoRepositorio.class);
    HerramientasRepositorio herramientasRepo = mock(HerramientasRepositorio.class);
    when(herramientasRepo.contarChunks(anyString())).thenReturn(100L);

    // Cupo de 1: si el modo Proponer no lo soltara, la segunda llamada de abajo
    // caeria en "servidor ocupado".
    var orquestador =
        new Orquestador(
            planificador,
            reformulador,
            catalogo,
            executor,
            mock(ContextoRepositorio.class),
            herramientasRepo,
            sintetizador,
            mock(VerificadorGrounding.class),
            queryLog,
            10,
            true,
            UMBRAL_POR_DEFECTO,
            1,
            streamsEnCurso);
    var preferencias =
        new Consultar.Preferencias(
            new Consultar.ModoReformulacion.Proponer(), IdiomaRespuesta.ESPANOL);

    Consultar.RespuestaEnStreaming resultado =
        orquestador.ejecutarEnStreaming(
            new Pregunta("que es el autoboxing"), PROYECTO, Filtros.NINGUNO, 42L, preferencias);

    assertThat(resultado.reformulacionesPropuestas())
        .containsExactly("boxing conversion", "autoboxing Java");
    assertThat(resultado.citas()).isEmpty();
    assertThat(resultado.consultaReformulada()).isNull();
    assertThat(resultado.texto().collectList().block()).isEmpty();
    assertThat(resultado.queryLogId().blockOptional()).isEmpty();
    // Solo la ronda original: la segunda ronda la decide la persona.
    assertThat(consultasRecibidas).containsExactly("que es el autoboxing");
    verify(sintetizador, never()).sintetizar(any(), any(), any());
    verify(queryLog, never()).registrar(any(), any(), any(), any(), any(), any(), any(), anyLong());
    verify(streamsEnCurso).descartar(42L);
    verify(streamsEnCurso, never()).finalizar(anyLong(), any(), any(), any());

    Consultar.RespuestaEnStreaming segunda =
        orquestador.ejecutarEnStreaming(
            new Pregunta("que es el autoboxing"), PROYECTO, Filtros.NINGUNO, 42L, preferencias);
    assertThat(segunda.reformulacionesPropuestas()).isNotEmpty();
  }

  @Test
  @DisplayName(
      "Modo Proponer: si el Reformulador no propone nada, responde como el modo automatico "
          + "(sin alternativas que ofrecer)")
  void enModoProponerSinAlternativasRespondeIgualQueElAutomatico() {
    Fragmento fragmentoDebil = fragmento(0.01);
    var catalogo = new CatalogoHerramientas(List.of(herramientaFalsa("fake_tool", fragmentoDebil)));
    var executor = new Executor(catalogo);
    Planificador planificador =
        (pregunta, herramientas) -> new PlanDeHerramientas(List.of("fake_tool"), "porque si");
    HerramientasRepositorio herramientasRepo = mock(HerramientasRepositorio.class);
    when(herramientasRepo.contarChunks(anyString())).thenReturn(100L);
    QueryLogRepositorio queryLog = mock(QueryLogRepositorio.class);
    when(queryLog.registrar(any(), any(), any(), any(), any(), any(), any(), anyLong()))
        .thenReturn(5L);

    var orquestador =
        new Orquestador(
            planificador,
            REFORMULADOR_SIN_CAMBIOS,
            catalogo,
            executor,
            mock(ContextoRepositorio.class),
            herramientasRepo,
            mock(Sintetizador.class),
            mock(VerificadorGrounding.class),
            queryLog,
            10,
            true,
            UMBRAL_POR_DEFECTO,
            10,
            mock(StreamsEnCursoRepositorio.class));

    Consultar.RespuestaEnStreaming resultado =
        orquestador.ejecutarEnStreaming(
            new Pregunta("pregunta rara"),
            PROYECTO,
            Filtros.NINGUNO,
            null,
            new Consultar.Preferencias(
                new Consultar.ModoReformulacion.Proponer(), IdiomaRespuesta.ESPANOL));

    assertThat(resultado.reformulacionesPropuestas()).isEmpty();
    String texto = resultado.texto().collectList().map(p -> String.join("", p)).block();
    assertThat(texto).isEqualTo(Orquestador.MENSAJE_SIN_INFORMACION);
  }

  @Test
  @DisplayName(
      "Modo Elegida: busca con la consulta elegida, nunca llama al Reformulador, la muestra como "
          + "reformulacion y sintetiza en el idioma pedido")
  void enModoElegidaBuscaConLaConsultaElegidaSinReformular() {
    Fragmento fragmentoFuerte = fragmento(9.0);
    List<String> consultasRecibidas = new ArrayList<>();
    var catalogo =
        new CatalogoHerramientas(
            List.of(herramientaQueRegistra("fake_tool", consultasRecibidas, fragmentoFuerte)));
    var executor = new Executor(catalogo);
    Planificador planificador =
        (pregunta, herramientas) -> new PlanDeHerramientas(List.of("fake_tool"), "porque si");
    Reformulador reformulador = mock(Reformulador.class);
    List<IdiomaRespuesta> idiomasRecibidos = new ArrayList<>();
    Sintetizador sintetizador =
        (pregunta, contexto, idioma) -> {
          idiomasRecibidos.add(idioma);
          return Flux.just("Boxing conversion is...");
        };
    ContextoRepositorio contextoRepo = mock(ContextoRepositorio.class);
    when(contextoRepo.vecinos(100L, 0)).thenReturn(List.of());
    HerramientasRepositorio herramientasRepo = mock(HerramientasRepositorio.class);
    when(herramientasRepo.contarChunks(anyString())).thenReturn(100L);
    QueryLogRepositorio queryLog = mock(QueryLogRepositorio.class);
    when(queryLog.registrar(any(), any(), any(), any(), any(), any(), any(), anyLong()))
        .thenReturn(3L);
    StreamsEnCursoRepositorio streamsEnCurso = mock(StreamsEnCursoRepositorio.class);

    var orquestador =
        new Orquestador(
            planificador,
            reformulador,
            catalogo,
            executor,
            contextoRepo,
            herramientasRepo,
            sintetizador,
            mock(VerificadorGrounding.class),
            queryLog,
            10,
            true,
            UMBRAL_POR_DEFECTO,
            10,
            streamsEnCurso);

    Consultar.RespuestaEnStreaming resultado =
        orquestador.ejecutarEnStreaming(
            new Pregunta("que es el autoboxing"),
            PROYECTO,
            Filtros.NINGUNO,
            42L,
            new Consultar.Preferencias(
                new Consultar.ModoReformulacion.Elegida("boxing conversion"),
                IdiomaRespuesta.ORIGINAL_DEL_CORPUS));
    String texto = resultado.texto().collectList().map(p -> String.join("", p)).block();

    assertThat(consultasRecibidas).containsExactly("boxing conversion");
    assertThat(resultado.consultaReformulada()).isEqualTo("boxing conversion");
    assertThat(resultado.reformulacionesPropuestas()).isEmpty();
    assertThat(texto).isEqualTo("Boxing conversion is...");
    assertThat(idiomasRecibidos).containsExactly(IdiomaRespuesta.ORIGINAL_DEL_CORPUS);
    verify(reformulador, never()).reformular(any(), any());
    // Lo que queda registrado sigue siendo la pregunta original, no la consulta elegida.
    verify(queryLog)
        .registrar(eq("que es el autoboxing"), any(), any(), any(), any(), any(), any(), anyLong());
    verify(streamsEnCurso).actualizarCitas(eq(42L), any(), eq("boxing conversion"));
  }

  @Test
  @DisplayName(
      "Modo Elegida con la propia pregunta (la persona prefirio no reformular): busca con ella, no "
          + "reformula y no muestra reformulacion")
  void enModoElegidaConLaPreguntaOriginalNoReformulaNiLaMuestra() {
    // rerank=0.01: INSUFICIENTE, el modo automatico si llamaria al Reformulador aca.
    Fragmento fragmentoDebil = fragmento(0.01);
    var catalogo = new CatalogoHerramientas(List.of(herramientaFalsa("fake_tool", fragmentoDebil)));
    var executor = new Executor(catalogo);
    Planificador planificador =
        (pregunta, herramientas) -> new PlanDeHerramientas(List.of("fake_tool"), "porque si");
    Reformulador reformulador = mock(Reformulador.class);
    HerramientasRepositorio herramientasRepo = mock(HerramientasRepositorio.class);
    when(herramientasRepo.contarChunks(anyString())).thenReturn(100L);
    QueryLogRepositorio queryLog = mock(QueryLogRepositorio.class);
    when(queryLog.registrar(any(), any(), any(), any(), any(), any(), any(), anyLong()))
        .thenReturn(4L);

    var orquestador =
        new Orquestador(
            planificador,
            reformulador,
            catalogo,
            executor,
            mock(ContextoRepositorio.class),
            herramientasRepo,
            mock(Sintetizador.class),
            mock(VerificadorGrounding.class),
            queryLog,
            10,
            true,
            UMBRAL_POR_DEFECTO,
            10,
            mock(StreamsEnCursoRepositorio.class));

    Consultar.RespuestaEnStreaming resultado =
        orquestador.ejecutarEnStreaming(
            new Pregunta("que es el autoboxing"),
            PROYECTO,
            Filtros.NINGUNO,
            null,
            new Consultar.Preferencias(
                new Consultar.ModoReformulacion.Elegida("Que es el autoboxing "),
                IdiomaRespuesta.ESPANOL));
    String texto = resultado.texto().collectList().map(p -> String.join("", p)).block();

    verify(reformulador, never()).reformular(any(), any());
    assertThat(resultado.consultaReformulada()).isNull();
    assertThat(texto).isEqualTo(Orquestador.MENSAJE_SIN_INFORMACION);
  }

  @Test
  @DisplayName("El camino bloqueante (Teams, /api/ask) sintetiza en español, sin preguntar nada")
  void elCaminoBloqueanteSintetizaEnEspanol() {
    Fragmento fragmentoFuerte = fragmento(9.0);
    var catalogo =
        new CatalogoHerramientas(List.of(herramientaFalsa("fake_tool", fragmentoFuerte)));
    var executor = new Executor(catalogo);
    Planificador planificador =
        (pregunta, herramientas) -> new PlanDeHerramientas(List.of("fake_tool"), "porque si");
    List<IdiomaRespuesta> idiomasRecibidos = new ArrayList<>();
    Sintetizador sintetizador =
        (pregunta, contexto, idioma) -> {
          idiomasRecibidos.add(idioma);
          return Flux.just("Respuesta.");
        };
    ContextoRepositorio contextoRepo = mock(ContextoRepositorio.class);
    when(contextoRepo.vecinos(100L, 0)).thenReturn(List.of());
    HerramientasRepositorio herramientasRepo = mock(HerramientasRepositorio.class);
    when(herramientasRepo.contarChunks(anyString())).thenReturn(100L);
    QueryLogRepositorio queryLog = mock(QueryLogRepositorio.class);
    when(queryLog.registrar(any(), any(), any(), any(), any(), any(), any(), anyLong()))
        .thenReturn(6L);

    var orquestador =
        new Orquestador(
            planificador,
            REFORMULADOR_SIN_CAMBIOS,
            catalogo,
            executor,
            contextoRepo,
            herramientasRepo,
            sintetizador,
            mock(VerificadorGrounding.class),
            queryLog,
            10,
            true,
            UMBRAL_POR_DEFECTO,
            10,
            mock(StreamsEnCursoRepositorio.class));

    orquestador.ejecutar(new Pregunta("cualquier cosa"), PROYECTO, Filtros.NINGUNO);

    assertThat(idiomasRecibidos).containsExactly(IdiomaRespuesta.ESPANOL);
  }

  @Test
  @DisplayName(
      "Modo Proponer con UNA sola alternativa: no hay nada que elegir, responde de inmediato "
          + "buscando con ella como el modo automatico")
  void enModoProponerConUnaSolaAlternativaRespondeDeInmediato() {
    Fragmento fragmentoDebil = fragmento(0.01);
    Fragmento fragmentoFuerte = fragmento(9.0);
    List<String> consultasRecibidas = new ArrayList<>();
    Herramienta herramienta =
        new Herramienta() {
          @Override
          public String nombre() {
            return "fake_tool";
          }

          @Override
          public String descripcion() {
            return "de prueba";
          }

          @Override
          public List<Fragmento> ejecutar(
              String consulta, ProyectoId proyecto, List<Long> documentosPermitidos) {
            consultasRecibidas.add(consulta);
            return consulta.equals("boxing conversion")
                ? List.of(fragmentoFuerte)
                : List.of(fragmentoDebil);
          }
        };
    var catalogo = new CatalogoHerramientas(List.of(herramienta));
    var executor = new Executor(catalogo);
    Planificador planificador =
        (pregunta, herramientas) -> new PlanDeHerramientas(List.of("fake_tool"), "porque si");
    Reformulador reformulador =
        (pregunta, pistas) ->
            new Reformulador.Reformulacion(pregunta, List.of("boxing conversion"));
    Sintetizador sintetizador = (pregunta, contexto, idioma) -> Flux.just("Respuesta.");
    ContextoRepositorio contextoRepo = mock(ContextoRepositorio.class);
    when(contextoRepo.vecinos(100L, 0)).thenReturn(List.of());
    HerramientasRepositorio herramientasRepo = mock(HerramientasRepositorio.class);
    when(herramientasRepo.contarChunks(anyString())).thenReturn(100L);
    QueryLogRepositorio queryLog = mock(QueryLogRepositorio.class);
    when(queryLog.registrar(any(), any(), any(), any(), any(), any(), any(), anyLong()))
        .thenReturn(8L);

    var orquestador =
        new Orquestador(
            planificador,
            reformulador,
            catalogo,
            executor,
            contextoRepo,
            herramientasRepo,
            sintetizador,
            mock(VerificadorGrounding.class),
            queryLog,
            10,
            true,
            UMBRAL_POR_DEFECTO,
            10,
            mock(StreamsEnCursoRepositorio.class));

    Consultar.RespuestaEnStreaming resultado =
        orquestador.ejecutarEnStreaming(
            new Pregunta("que es el autoboxing"),
            PROYECTO,
            Filtros.NINGUNO,
            null,
            new Consultar.Preferencias(
                new Consultar.ModoReformulacion.Proponer(), IdiomaRespuesta.ESPANOL));
    String texto = resultado.texto().collectList().map(p -> String.join("", p)).block();

    assertThat(resultado.reformulacionesPropuestas()).isEmpty();
    assertThat(consultasRecibidas).containsExactly("que es el autoboxing", "boxing conversion");
    assertThat(resultado.consultaReformulada()).isEqualTo("boxing conversion");
    assertThat(texto).isEqualTo("Respuesta.");
  }

  @Test
  @DisplayName(
      "El Reformulador recibe como pistas los fragmentos de la primera ronda: titulo y comienzo del "
          + "texto, aplanados, como maximo MAX_PISTAS")
  void elReformuladorRecibeLosFragmentosDeLaPrimeraRondaComoPistas() {
    // rerank=0.01 -> INSUFICIENTE: dispara el Reformulador.
    Fragmento f1 =
        new Fragmento(
            1L,
            100L,
            "file:///jls25.pdf",
            "jls25.pdf",
            "If a field is declared static,\n\n  there exists exactly one incarnation of the field.",
            "doc_section",
            0,
            Instant.EPOCH,
            Map.of(),
            0.05,
            0.01);
    Fragmento f2 =
        new Fragmento(
            2L,
            100L,
            "file:///jls25.pdf",
            "jls25.pdf",
            "x".repeat(Orquestador.LARGO_PISTA + 50),
            "doc_section",
            1,
            Instant.EPOCH,
            Map.of(),
            0.04,
            0.01);
    var catalogo = new CatalogoHerramientas(List.of(herramientaFalsa("fake_tool", f1, f2)));
    var executor = new Executor(catalogo);
    Planificador planificador =
        (pregunta, herramientas) -> new PlanDeHerramientas(List.of("fake_tool"), "porque si");
    List<List<String>> pistasRecibidas = new ArrayList<>();
    Reformulador reformulador =
        (pregunta, pistas) -> {
          pistasRecibidas.add(pistas);
          return Reformulador.Reformulacion.sinCambios(pregunta);
        };
    HerramientasRepositorio herramientasRepo = mock(HerramientasRepositorio.class);
    when(herramientasRepo.contarChunks(anyString())).thenReturn(100L);
    QueryLogRepositorio queryLog = mock(QueryLogRepositorio.class);
    when(queryLog.registrar(any(), any(), any(), any(), any(), any(), any(), anyLong()))
        .thenReturn(9L);

    var orquestador =
        new Orquestador(
            planificador,
            reformulador,
            catalogo,
            executor,
            mock(ContextoRepositorio.class),
            herramientasRepo,
            mock(Sintetizador.class),
            mock(VerificadorGrounding.class),
            queryLog,
            10,
            true,
            UMBRAL_POR_DEFECTO,
            10,
            mock(StreamsEnCursoRepositorio.class));

    orquestador.ejecutar(new Pregunta("que significa static"), PROYECTO, Filtros.NINGUNO);

    assertThat(pistasRecibidas).hasSize(1);
    List<String> pistas = pistasRecibidas.getFirst();
    assertThat(pistas).hasSize(2);
    // Titulo entre corchetes, saltos de linea y espacios repetidos aplanados a uno.
    assertThat(pistas.getFirst())
        .isEqualTo(
            "[jls25.pdf] If a field is declared static, there exists exactly one incarnation of"
                + " the field.");
    // El texto largo se corta en LARGO_PISTA y se marca.
    assertThat(pistas.get(1)).hasSize("[jls25.pdf] ".length() + Orquestador.LARGO_PISTA + 1);
    assertThat(pistas.get(1)).endsWith("…");
  }

  @Test
  @DisplayName("pistasDelCorpus se queda con los primeros MAX_PISTAS fragmentos")
  void pistasDelCorpusRespetaElMaximo() {
    List<Fragmento> muchos =
        java.util.stream.IntStream.range(0, Orquestador.MAX_PISTAS + 3)
            .mapToObj(i -> fragmento(1.0))
            .toList();

    assertThat(Orquestador.pistasDelCorpus(muchos)).hasSize(Orquestador.MAX_PISTAS);
    assertThat(Orquestador.pistasDelCorpus(List.of())).isEmpty();
  }

  private static Fragmento fragmento(double rerank) {
    return new Fragmento(
        1L,
        100L,
        "file:///doc1",
        "Doc 1",
        "Esto es el fragmento uno.",
        "doc_section",
        0,
        Instant.EPOCH,
        Map.of(),
        0.05,
        rerank);
  }

  /** Como {@link #herramientaFalsa}, pero anota cada consulta con la que la llamaron. */
  private static Herramienta herramientaQueRegistra(
      String nombre, List<String> consultasRecibidas, Fragmento... fragmentos) {
    return new Herramienta() {
      @Override
      public String nombre() {
        return nombre;
      }

      @Override
      public String descripcion() {
        return "de prueba";
      }

      @Override
      public List<Fragmento> ejecutar(
          String consulta, ProyectoId proyecto, List<Long> documentosPermitidos) {
        consultasRecibidas.add(consulta);
        return List.of(fragmentos);
      }
    };
  }

  private static Herramienta herramientaFalsa(String nombre, Fragmento... fragmentos) {
    return new Herramienta() {
      @Override
      public String nombre() {
        return nombre;
      }

      @Override
      public String descripcion() {
        return "de prueba";
      }

      @Override
      public List<Fragmento> ejecutar(
          String consulta, ProyectoId proyecto, List<Long> documentosPermitidos) {
        return List.of(fragmentos);
      }
    };
  }
}
