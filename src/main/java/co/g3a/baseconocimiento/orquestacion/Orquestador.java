package co.g3a.baseconocimiento.orquestacion;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import co.g3a.baseconocimiento.compartido.Dominio.Cita;
import co.g3a.baseconocimiento.compartido.Dominio.Filtros;
import co.g3a.baseconocimiento.compartido.Dominio.Fragmento;
import co.g3a.baseconocimiento.compartido.Dominio.Pregunta;
import co.g3a.baseconocimiento.compartido.Dominio.ProyectoId;
import co.g3a.baseconocimiento.compartido.Dominio.Respuesta;
import co.g3a.baseconocimiento.llm.Planificador;
import co.g3a.baseconocimiento.llm.Planificador.PlanDeHerramientas;
import co.g3a.baseconocimiento.llm.Reformulador;
import co.g3a.baseconocimiento.llm.Sintetizador;
import co.g3a.baseconocimiento.llm.VerificadorGrounding;

import reactor.core.publisher.Flux;

/**
 * Las siete etapas de F3, conectadas: planificar, ejecutar herramientas en
 * paralelo, fusionar entre ellas, expandir contexto, sintetizar y registrar.
 *
 * <p>Las etapas 1 a 5 (todo lo anterior a la síntesis) son las mismas sin
 * importar si el resultado final es bloqueante o en streaming — por eso
 * {@link #prepararHastaContexto(Pregunta, ProyectoId)} existe: {@link #ejecutar}
 * (F3, para {@code /api/ask}) y {@link #ejecutarEnStreaming} (F4, para el SSE
 * del adaptador web) comparten esa preparación y solo difieren en cómo
 * consumen el {@link Flux} del {@link Sintetizador}.
 *
 * <p>No implementa {@link Consultar} directamente — {@link Consultador} es esa
 * fachada mínima. Esta clase devuelve la traza completa de las siete etapas
 * porque {@code OrquestacionController} la necesita entera para el criterio de
 * salida de F3; los adaptadores de F4/F5 no deberían ver nada de esto.
 */
@Component
class Orquestador {

    /**
     * ADR-0008: lo que se responde cuando {@link UmbralRelevancia} decide que
     * el mejor fragmento no alcanza — en vez de dejar que el sintetizador
     * improvise sobre contexto irrelevante.
     */
    static final String MENSAJE_SIN_INFORMACION =
            "No encontré información suficientemente relevante en la base de conocimiento "
                    + "para responder esto. Prueba con otra formulación o verifica que el tema "
                    + "esté cubierto por las fuentes ingeridas.";

    private final Planificador planificador;
    private final Reformulador reformulador;
    private final CatalogoHerramientas catalogo;
    private final Executor executor;
    private final ContextoRepositorio contextoRepo;
    private final HerramientasRepositorio herramientasRepo;
    private final Sintetizador sintetizador;
    private final VerificadorGrounding verificadorGrounding;
    private final QueryLogRepositorio queryLog;
    private final int maxFragmentosContexto;
    private final boolean expandirVecinos;
    private final UmbralRelevanciaPropiedades umbralRelevancia;

    Orquestador(
            Planificador planificador, Reformulador reformulador, CatalogoHerramientas catalogo, Executor executor,
            ContextoRepositorio contextoRepo, HerramientasRepositorio herramientasRepo,
            Sintetizador sintetizador, VerificadorGrounding verificadorGrounding, QueryLogRepositorio queryLog,
            @Value("${kb.orquestacion.max-fragmentos-contexto:10}") int maxFragmentosContexto,
            @Value("${kb.orquestacion.expandir-vecinos:true}") boolean expandirVecinos,
            UmbralRelevanciaPropiedades umbralRelevancia) {
        this.planificador = planificador;
        this.reformulador = reformulador;
        this.catalogo = catalogo;
        this.executor = executor;
        this.contextoRepo = contextoRepo;
        this.herramientasRepo = herramientasRepo;
        this.sintetizador = sintetizador;
        this.verificadorGrounding = verificadorGrounding;
        this.queryLog = queryLog;
        this.maxFragmentosContexto = maxFragmentosContexto;
        this.expandirVecinos = expandirVecinos;
        this.umbralRelevancia = umbralRelevancia;
    }

    record EjecucionPipeline(
            PlanDeHerramientas plan,
            List<Executor.EjecucionHerramienta> herramientas,
            List<Fragmento> fragmentosUsados,
            Respuesta respuesta,
            long queryLogId) {
    }

    /**
     * Todo lo que las etapas 1-5 producen, listo para que la síntesis (etapa 6)
     * lo consuma. {@code respuestaFija} no nulo (ADR-0008) significa que la
     * etapa 4 decidió que el contexto no alcanza: la síntesis se salta por
     * completo y ese texto es la respuesta.
     */
    private record PreSintesis(
            PlanDeHerramientas plan,
            List<Executor.EjecucionHerramienta> ejecuciones,
            List<Fragmento> fragmentos,
            List<Cita> citas,
            String contexto,
            String respuestaFija,
            String consultaReformulada,
            long inicioNanos) {
    }

    /** Bloqueante: espera a que la síntesis termine antes de devolver nada. Usado por {@code /api/ask}. */
    EjecucionPipeline ejecutar(Pregunta pregunta, ProyectoId proyecto, Filtros filtros) {
        PreSintesis pre = prepararHastaContexto(pregunta, proyecto);

        // Etapa 6: sintesis en streaming, coleccionada aqui porque este metodo
        // devuelve una respuesta completa, no un Flux.
        String textoRespuesta = sintetizarBloqueante(pregunta, pre);

        long latenciaMs = (System.nanoTime() - pre.inicioNanos()) / 1_000_000;
        // advertencias queda vacio a proposito: el prompt de sintesis exige señalar
        // contradicciones EN el texto de la respuesta (con [n] y prosa), no en un
        // campo estructurado aparte -- eso pediria una segunda llamada al LLM solo
        // para separar "respuesta" de "advertencia", en contra de que la sintesis
        // sea en streaming.
        Respuesta respuesta = new Respuesta(textoRespuesta, pre.citas(), List.of(), latenciaMs, pre.consultaReformulada());

        // Etapa 7: registrar.
        long queryLogId = queryLog.registrar(
                pregunta.texto(), proyecto.valor(), pre.plan(), pre.ejecuciones(), pre.fragmentos(),
                textoRespuesta, pre.citas(), latenciaMs);

        return new EjecucionPipeline(pre.plan(), pre.ejecuciones(), pre.fragmentos(), respuesta, queryLogId);
    }

    /**
     * En streaming: las citas de la etapa 5 vuelven de inmediato; el texto de
     * la etapa 6 llega token a token. El registro de la etapa 7 ocurre cuando
     * el {@link Flux} completa, no antes — por eso acumula el texto en un
     * {@link StringBuilder} con {@code doOnNext} en vez de esperar a que el
     * llamador termine de consumirlo.
     */
    Consultar.RespuestaEnStreaming ejecutarEnStreaming(Pregunta pregunta, ProyectoId proyecto, Filtros filtros) {
        PreSintesis pre = prepararHastaContexto(pregunta, proyecto);

        StringBuilder acumulado = new StringBuilder();
        Flux<String> textoBase = pre.respuestaFija() != null
                ? Flux.just(pre.respuestaFija())
                : sintetizador.sintetizar(pregunta.texto(), pre.contexto());
        Flux<String> texto = textoBase
                .doOnNext(acumulado::append)
                .doOnComplete(() -> {
                    long latenciaMs = (System.nanoTime() - pre.inicioNanos()) / 1_000_000;
                    queryLog.registrar(
                            pregunta.texto(), proyecto.valor(), pre.plan(), pre.ejecuciones(), pre.fragmentos(),
                            acumulado.toString(), pre.citas(), latenciaMs);
                });

        return new Consultar.RespuestaEnStreaming(pre.citas(), texto, pre.consultaReformulada());
    }

    private String sintetizarBloqueante(Pregunta pregunta, PreSintesis pre) {
        if (pre.respuestaFija() != null) {
            return pre.respuestaFija();
        }
        String texto = sintetizador.sintetizar(pregunta.texto(), pre.contexto())
                .collectList()
                .map(partes -> String.join("", partes))
                .block();
        return texto == null ? "" : texto;
    }

    private PreSintesis prepararHastaContexto(Pregunta pregunta, ProyectoId proyecto) {
        long inicio = System.nanoTime();

        // Etapa 1: planificar.
        PlanDeHerramientas plan = planificador.planificar(pregunta.texto(), catalogo.descripciones());

        // Etapas 2-3: ejecutar herramientas en paralelo con la pregunta tal cual, con fallas
        // aisladas por herramienta.
        List<Executor.EjecucionHerramienta> ejecuciones =
                executor.ejecutar(plan.herramientas(), pregunta.texto(), proyecto);

        // Etapa 4: fusionar y deduplicar entre herramientas.
        List<Fragmento> fragmentos = FusionDeHerramientas.combinar(
                ejecuciones.stream().map(Executor.EjecucionHerramienta::fragmentos).toList(),
                maxFragmentosContexto);

        // ADR-0008: puerta de relevancia antes de expandir contexto y sintetizar.
        // El score del cross-encoder decide los casos claros sin gastar una
        // llamada al LLM; la zona ambigua se manda a VerificadorGrounding en vez
        // de confiar en que el propio sintetizador se autocensure.
        long chunksProyecto = herramientasRepo.contarChunks(proyecto.valor());
        UmbralRelevancia.Resultado umbral = UmbralRelevancia.evaluar(fragmentos, chunksProyecto, umbralRelevancia);

        // Reformula la consulta de busqueda SOLO cuando la busqueda con la pregunta tal cual
        // no llego a SUFICIENTE -- reformular siempre, incluso en las preguntas que ya
        // buscaban bien, sumaba una llamada al LLM de mas en cada pregunta sin necesidad
        // (hallazgos 70-71 de la investigacion de VRAM y modelo LLM confirmaron que ayuda en
        // el caso dificil sin romper el caso facil, pero median el costo de la llamada
        // incondicional). Cubre tanto INSUFICIENTE (el caso que motivo el componente, ver
        // hallazgo 63: "autoboxing" vs. "boxing conversion") como AMBIGUO -- en ambos el score
        // ya senala que el texto original no encontro un candidato fuerte.
        String consultaReformuladaParaMostrar = null;
        if (umbral.decision() != UmbralRelevancia.Decision.SUFICIENTE) {
            Reformulador.Reformulacion reformulacion = reformulador.reformular(pregunta.texto());
            if (reformulacion.reformulada()) {
                List<Executor.EjecucionHerramienta> reejecuciones =
                        executor.ejecutar(plan.herramientas(), reformulacion.textoBusqueda(), proyecto);
                List<Fragmento> refragmentos = FusionDeHerramientas.combinar(
                        reejecuciones.stream().map(Executor.EjecucionHerramienta::fragmentos).toList(),
                        maxFragmentosContexto);
                ejecuciones = reejecuciones;
                fragmentos = refragmentos;
                umbral = UmbralRelevancia.evaluar(fragmentos, chunksProyecto, umbralRelevancia);
                consultaReformuladaParaMostrar = reformulacion.textoBusqueda();
            }
        }

        if (umbral.decision() == UmbralRelevancia.Decision.INSUFICIENTE) {
            return new PreSintesis(plan, ejecuciones, fragmentos, List.of(), "", MENSAJE_SIN_INFORMACION,
                    consultaReformuladaParaMostrar, inicio);
        }

        // Etapa 5: expansion de contexto con secciones vecinas.
        String contexto = construirContexto(fragmentos);
        List<Cita> citas = fragmentos.stream().map(Citas::desde).toList();

        if (umbral.decision() == UmbralRelevancia.Decision.AMBIGUO) {
            VerificadorGrounding.Veredicto veredicto = verificadorGrounding.verificar(pregunta.texto(), contexto);
            if (!veredicto.respondeLaPregunta()) {
                return new PreSintesis(plan, ejecuciones, fragmentos, List.of(), "", MENSAJE_SIN_INFORMACION,
                        consultaReformuladaParaMostrar, inicio);
            }
        }

        return new PreSintesis(plan, ejecuciones, fragmentos, citas, contexto, null,
                consultaReformuladaParaMostrar, inicio);
    }

    /**
     * {@code kb.orquestacion.expandir-vecinos=false} (perfil Bonsai) apaga la
     * expansión: cada fragmento cuesta hasta 3x menos tokens sin sus vecinos,
     * lo que permite subir {@code max-fragmentos-contexto} y que sobrevivan
     * más candidatos distintos dentro del mismo presupuesto de contexto —
     * medido en vivo: con contexto de 4096 tokens, 2 fragmentos CON vecinos
     * ocupaban el mismo espacio que varios más SIN vecinos, y el candidato
     * relevante quedaba afuera cuando no rankeaba entre los 2 primeros.
     */
    private String construirContexto(List<Fragmento> fragmentos) {
        StringBuilder contexto = new StringBuilder();
        for (int i = 0; i < fragmentos.size(); i++) {
            Fragmento f = fragmentos.get(i);
            contexto.append("[%d] %s (%s)\n".formatted(i + 1, Citas.tituloDe(f), f.uri()));

            if (expandirVecinos) {
                List<ContextoRepositorio.Vecino> vecinos = contextoRepo.vecinos(f.documentoId(), f.ord());
                vecinos.stream().filter(v -> v.ord() < f.ord()).forEach(v -> contexto.append(v.texto()).append('\n'));
                contexto.append(f.texto()).append('\n');
                vecinos.stream().filter(v -> v.ord() > f.ord()).forEach(v -> contexto.append(v.texto()).append('\n'));
            } else {
                contexto.append(f.texto()).append('\n');
            }

            contexto.append('\n');
        }
        return contexto.toString();
    }
}
