package co.g3a.baseconocimiento.orquestacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import co.g3a.baseconocimiento.compartido.Dominio.Filtros;
import co.g3a.baseconocimiento.compartido.Dominio.Fragmento;
import co.g3a.baseconocimiento.compartido.Dominio.Pregunta;
import co.g3a.baseconocimiento.compartido.Dominio.ProyectoId;
import co.g3a.baseconocimiento.llm.Planificador;
import co.g3a.baseconocimiento.llm.Planificador.PlanDeHerramientas;
import co.g3a.baseconocimiento.llm.Sintetizador;
import co.g3a.baseconocimiento.llm.VerificadorGrounding;
import co.g3a.baseconocimiento.llm.VerificadorGrounding.Veredicto;

import reactor.core.publisher.Flux;

/**
 * Las siete etapas conectadas, con el planner, el sintetizador y los
 * repositorios de Postgres reemplazados por dobles: lo que se prueba aqui es
 * el cableado del pipeline (planificar -> ejecutar -> fusionar -> expandir ->
 * sintetizar -> registrar), no Ollama ni la base de datos real — eso ya lo
 * cubren los smoke tests de cada pieza por separado.
 */
class OrquestadorTest {

    private static final ProyectoId PROYECTO = new ProyectoId("default");
    private static final UmbralRelevanciaPropiedades UMBRAL_POR_DEFECTO =
            new UmbralRelevanciaPropiedades(true, 0.003, 0.05, 500, 8.0);

    @Test
    @DisplayName("Conecta las siete etapas: plan, herramientas, fusion, sintesis y registro")
    void ejecutaLasSieteEtapas() {
        // rerank=9.0 supera techoConfianza: SUFICIENTE sin pasar por el verificador.
        Fragmento fragmento = new Fragmento(1L, 100L, "file:///doc1", "Doc 1",
                "Esto es el fragmento uno.", "doc_section", 0, Instant.EPOCH, Map.of(), 0.05, 9.0);

        var catalogo = new CatalogoHerramientas(List.of(herramientaFalsa("fake_tool", fragmento)));
        var executor = new Executor(catalogo);

        Planificador planificador = (pregunta, herramientas) ->
                new PlanDeHerramientas(List.of("fake_tool"), "porque si");

        Sintetizador sintetizador = (pregunta, contexto) -> Flux.just("Respuesta ", "citando ", "[1].");
        VerificadorGrounding verificadorGrounding = mock(VerificadorGrounding.class);

        ContextoRepositorio contextoRepo = mock(ContextoRepositorio.class);
        when(contextoRepo.vecinos(100L, 0)).thenReturn(List.of());

        HerramientasRepositorio herramientasRepo = mock(HerramientasRepositorio.class);
        when(herramientasRepo.contarChunks(anyString())).thenReturn(100L);

        QueryLogRepositorio queryLog = mock(QueryLogRepositorio.class);
        when(queryLog.registrar(any(), any(), any(), any(), any(), any(), any(), anyLong())).thenReturn(42L);

        var orquestador = new Orquestador(
                planificador, catalogo, executor, contextoRepo, herramientasRepo, sintetizador,
                verificadorGrounding, queryLog, 10, true, UMBRAL_POR_DEFECTO);

        Orquestador.EjecucionPipeline resultado =
                orquestador.ejecutar(new Pregunta("¿Que es esto?"), PROYECTO, Filtros.NINGUNO);

        assertThat(resultado.plan().herramientas()).containsExactly("fake_tool");

        assertThat(resultado.herramientas()).hasSize(1);
        assertThat(resultado.herramientas().get(0).error()).isNull();

        assertThat(resultado.fragmentosUsados()).extracting(Fragmento::id).containsExactly(1L);

        assertThat(resultado.respuesta().texto()).isEqualTo("Respuesta citando [1].");
        assertThat(resultado.respuesta().citas()).hasSize(1);
        assertThat(resultado.respuesta().citas().get(0).uri()).isEqualTo("file:///doc1");

        assertThat(resultado.queryLogId()).isEqualTo(42L);
        verify(queryLog).registrar(any(), any(), any(), any(), any(), any(), any(), anyLong());
        verify(verificadorGrounding, never()).verificar(any(), any());
    }

    @Test
    @DisplayName("ADR-0008: si el mejor rerank no alcanza el umbral, no llama ni al verificador ni al sintetizador")
    void noSintetizaCuandoElContextoNoAlcanza() {
        // rerank bajo a proposito: por debajo del umbral incluso en un corpus chico
        // (chunksProyecto=4, igual que el corpus semilla del taller).
        Fragmento fragmentoDebil = new Fragmento(1L, 100L, "file:///despliegue.md", "despliegue.md",
                "Corre make pull-models para descargar los modelos.", "doc_section", 0, Instant.EPOCH,
                Map.of(), 0.03, 0.0019);

        var catalogo = new CatalogoHerramientas(List.of(herramientaFalsa("fake_tool", fragmentoDebil)));
        var executor = new Executor(catalogo);

        Planificador planificador = (pregunta, herramientas) ->
                new PlanDeHerramientas(List.of("fake_tool"), "porque si");

        Sintetizador sintetizador = mock(Sintetizador.class);
        VerificadorGrounding verificadorGrounding = mock(VerificadorGrounding.class);

        ContextoRepositorio contextoRepo = mock(ContextoRepositorio.class);

        HerramientasRepositorio herramientasRepo = mock(HerramientasRepositorio.class);
        when(herramientasRepo.contarChunks(anyString())).thenReturn(4L);

        QueryLogRepositorio queryLog = mock(QueryLogRepositorio.class);
        when(queryLog.registrar(any(), any(), any(), any(), any(), any(), any(), anyLong())).thenReturn(1L);

        var orquestador = new Orquestador(
                planificador, catalogo, executor, contextoRepo, herramientasRepo, sintetizador,
                verificadorGrounding, queryLog, 10, true, UMBRAL_POR_DEFECTO);

        Orquestador.EjecucionPipeline resultado =
                orquestador.ejecutar(new Pregunta("explicame como usar Java 25"), PROYECTO, Filtros.NINGUNO);

        assertThat(resultado.respuesta().texto()).isEqualTo(Orquestador.MENSAJE_SIN_INFORMACION);
        assertThat(resultado.respuesta().citas()).isEmpty();
        verify(sintetizador, never()).sintetizar(any(), any());
        verify(verificadorGrounding, never()).verificar(any(), any());
    }

    @Test
    @DisplayName("ADR-0008: en la zona ambigua, un veredicto negativo del verificador rechaza sin sintetizar")
    void rechazaCuandoElVerificadorDeGroundingDiceQueNo() {
        // "como usar java 25" puntuo 0.0134: por encima del umbral, lejos de la
        // certeza -- el contraejemplo real de ADR-0008 que un score solo no resuelve.
        Fragmento ambiguo = new Fragmento(1L, 100L, "file:///despliegue.md", "despliegue.md",
                "1. Copia .env.example a .env. 2. Corre docker compose up -d.", "doc_section", 0, Instant.EPOCH,
                Map.of(), 0.03, 0.0134);

        var catalogo = new CatalogoHerramientas(List.of(herramientaFalsa("fake_tool", ambiguo)));
        var executor = new Executor(catalogo);

        Planificador planificador = (pregunta, herramientas) ->
                new PlanDeHerramientas(List.of("fake_tool"), "porque si");

        Sintetizador sintetizador = mock(Sintetizador.class);
        VerificadorGrounding verificadorGrounding = mock(VerificadorGrounding.class);
        when(verificadorGrounding.verificar(anyString(), anyString())).thenReturn(new Veredicto(false));

        ContextoRepositorio contextoRepo = mock(ContextoRepositorio.class);
        when(contextoRepo.vecinos(100L, 0)).thenReturn(List.of());

        HerramientasRepositorio herramientasRepo = mock(HerramientasRepositorio.class);
        when(herramientasRepo.contarChunks(anyString())).thenReturn(4L);

        QueryLogRepositorio queryLog = mock(QueryLogRepositorio.class);
        when(queryLog.registrar(any(), any(), any(), any(), any(), any(), any(), anyLong())).thenReturn(1L);

        var orquestador = new Orquestador(
                planificador, catalogo, executor, contextoRepo, herramientasRepo, sintetizador,
                verificadorGrounding, queryLog, 10, true, UMBRAL_POR_DEFECTO);

        Orquestador.EjecucionPipeline resultado =
                orquestador.ejecutar(new Pregunta("como usar java 25"), PROYECTO, Filtros.NINGUNO);

        assertThat(resultado.respuesta().texto()).isEqualTo(Orquestador.MENSAJE_SIN_INFORMACION);
        assertThat(resultado.respuesta().citas()).isEmpty();
        verify(sintetizador, never()).sintetizar(any(), any());
    }

    @Test
    @DisplayName("ADR-0008: en la zona ambigua, un veredicto positivo del verificador deja sintetizar normal")
    void aceptaCuandoElVerificadorDeGroundingDiceQueSi() {
        Fragmento ambiguo = new Fragmento(1L, 100L, "file:///despliegue.md", "despliegue.md",
                "Se necesita Docker Desktop con el motor iniciado.", "doc_section", 0, Instant.EPOCH,
                Map.of(), 0.03, 0.0134);

        var catalogo = new CatalogoHerramientas(List.of(herramientaFalsa("fake_tool", ambiguo)));
        var executor = new Executor(catalogo);

        Planificador planificador = (pregunta, herramientas) ->
                new PlanDeHerramientas(List.of("fake_tool"), "porque si");

        Sintetizador sintetizador = (pregunta, contexto) -> Flux.just("Respuesta ", "citando ", "[1].");
        VerificadorGrounding verificadorGrounding = mock(VerificadorGrounding.class);
        when(verificadorGrounding.verificar(anyString(), anyString())).thenReturn(new Veredicto(true));

        ContextoRepositorio contextoRepo = mock(ContextoRepositorio.class);
        when(contextoRepo.vecinos(100L, 0)).thenReturn(List.of());

        HerramientasRepositorio herramientasRepo = mock(HerramientasRepositorio.class);
        when(herramientasRepo.contarChunks(anyString())).thenReturn(4L);

        QueryLogRepositorio queryLog = mock(QueryLogRepositorio.class);
        when(queryLog.registrar(any(), any(), any(), any(), any(), any(), any(), anyLong())).thenReturn(1L);

        var orquestador = new Orquestador(
                planificador, catalogo, executor, contextoRepo, herramientasRepo, sintetizador,
                verificadorGrounding, queryLog, 10, true, UMBRAL_POR_DEFECTO);

        Orquestador.EjecucionPipeline resultado =
                orquestador.ejecutar(new Pregunta("como se despliega el servicio"), PROYECTO, Filtros.NINGUNO);

        assertThat(resultado.respuesta().texto()).isEqualTo("Respuesta citando [1].");
        assertThat(resultado.respuesta().citas()).hasSize(1);
    }

    @Test
    @DisplayName("ADR-0008: no bloquea fragmentos de herramientas de listado, que no traen rerank")
    void noBloqueaHerramientasDeListado() {
        // recent_commits/subsystem_index/who_knows no rankean por relevancia: rrf=0, rerank=null.
        Fragmento deListado = new Fragmento(1L, 100L, "file:///doc1", "Doc 1", "texto",
                "subsystem_summary", 0, Instant.EPOCH, Map.of(), 0.0, null);

        var catalogo = new CatalogoHerramientas(List.of(herramientaFalsa("subsystem_index", deListado)));
        var executor = new Executor(catalogo);

        Planificador planificador = (pregunta, herramientas) ->
                new PlanDeHerramientas(List.of("subsystem_index"), "porque si");

        Sintetizador sintetizador = (pregunta, contexto) -> Flux.just("Respuesta.");
        VerificadorGrounding verificadorGrounding = mock(VerificadorGrounding.class);

        ContextoRepositorio contextoRepo = mock(ContextoRepositorio.class);
        when(contextoRepo.vecinos(100L, 0)).thenReturn(List.of());

        HerramientasRepositorio herramientasRepo = mock(HerramientasRepositorio.class);
        when(herramientasRepo.contarChunks(anyString())).thenReturn(4L);

        QueryLogRepositorio queryLog = mock(QueryLogRepositorio.class);
        when(queryLog.registrar(any(), any(), any(), any(), any(), any(), any(), anyLong())).thenReturn(1L);

        var orquestador = new Orquestador(
                planificador, catalogo, executor, contextoRepo, herramientasRepo, sintetizador,
                verificadorGrounding, queryLog, 10, true, UMBRAL_POR_DEFECTO);

        Orquestador.EjecucionPipeline resultado =
                orquestador.ejecutar(new Pregunta("¿quien sabe de auth?"), PROYECTO, Filtros.NINGUNO);

        assertThat(resultado.respuesta().texto()).isEqualTo("Respuesta.");
        verify(verificadorGrounding, never()).verificar(any(), any());
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
            public List<Fragmento> ejecutar(String consulta, ProyectoId proyecto) {
                return List.of(fragmentos);
            }
        };
    }
}
