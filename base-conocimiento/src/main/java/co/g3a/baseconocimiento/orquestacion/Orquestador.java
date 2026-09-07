package co.g3a.baseconocimiento.orquestacion;

import co.g3a.baseconocimiento.compartido.Dominio.Cita;
import co.g3a.baseconocimiento.compartido.Dominio.Filtros;
import co.g3a.baseconocimiento.compartido.Dominio.Fragmento;
import co.g3a.baseconocimiento.compartido.Dominio.IdiomaRespuesta;
import co.g3a.baseconocimiento.compartido.Dominio.Pregunta;
import co.g3a.baseconocimiento.compartido.Dominio.ProyectoId;
import co.g3a.baseconocimiento.compartido.Dominio.Respuesta;
import co.g3a.baseconocimiento.llm.Planificador;
import co.g3a.baseconocimiento.llm.Planificador.PlanDeHerramientas;
import co.g3a.baseconocimiento.llm.Reformulador;
import co.g3a.baseconocimiento.llm.Sintetizador;
import co.g3a.baseconocimiento.llm.VerificadorGrounding;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

/**
 * Las siete etapas de F3, conectadas: planificar, ejecutar herramientas en paralelo, fusionar entre
 * ellas, expandir contexto, sintetizar y registrar.
 *
 * <p>Las etapas 1 a 5 (todo lo anterior a la síntesis) son las mismas sin importar si el resultado
 * final es bloqueante o en streaming — por eso {@code prepararHastaContexto} existe: {@link
 * #ejecutar} (F3, para {@code /api/ask}) y {@link #ejecutarEnStreaming} (F4, para el SSE del
 * adaptador web) comparten esa preparación y solo difieren en cómo consumen el {@link Flux} del
 * {@link Sintetizador}.
 *
 * <p>No implementa {@link Consultar} directamente — {@link Consultador} es esa fachada mínima. Esta
 * clase devuelve la traza completa de las siete etapas porque {@code OrquestacionController} la
 * necesita entera para el criterio de salida de F3; los adaptadores de F4/F5 no deberían ver nada
 * de esto.
 */
@Component
class Orquestador {

  /**
   * ADR-0008: lo que se responde cuando {@link UmbralRelevancia} decide que el mejor fragmento no
   * alcanza — en vez de dejar que el sintetizador improvise sobre contexto irrelevante.
   */
  static final String MENSAJE_SIN_INFORMACION =
      "No encontré información suficientemente relevante en la base de conocimiento "
          + "para responder esto. Prueba con otra formulación o verifica que el tema "
          + "esté cubierto por las fuentes ingeridas.";

  /**
   * {@code prepararHastaContexto} es bloqueante de punta a punta (planificador, herramientas,
   * reformulador, verificador de grounding) y arranca ANTES de que exista ningún {@link Flux} — si
   * el cliente se desconecta a mitad de camino (un F5, cerrar la pestaña), no hay forma de que el
   * servidor se entere: la consulta sigue corriendo entera igual, gastando uno de los {@code
   * OLLAMA_NUM_PARALLEL} cupos de Ollama en un trabajo que nadie va a leer. Medido en vivo: esa
   * sola etapa tardó más de 4 minutos con Ministral 3B — de sobra para que una consulta nueva se
   * quede sin cupo detrás de una huérfana y termine mostrando "se perdió la conexión" sin ser ese
   * el problema real. Cancelar la huérfana a mitad de camino exigiría propagar la desconexión a
   * través de Planificador/Reformulador/VerificadorGrounding (varias clases, con cuidado real) —
   * como mitigación más simple, este semáforo evita que una consulta nueva SUME otra espera larga
   * encima: si no hay cupo, corta al toque con un mensaje claro en vez de competir.
   */
  static final String MENSAJE_SERVIDOR_OCUPADO =
      "El servidor ya está atendiendo el máximo de consultas al mismo tiempo. "
          + "Esperá un momento y volvé a intentarlo.";

  /**
   * En modo {@link Consultar.ModoReformulacion.Proponer}, cuantas alternativas hacen falta para que
   * valga la pena preguntarle a la persona: con una sola no hay elección posible, y se responde de
   * inmediato con ella como en el modo automático.
   */
  static final int MIN_ALTERNATIVAS_PARA_ELEGIR = 2;

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
  private final Semaphore cupoConsultas;
  private final StreamsEnCursoRepositorio streamsEnCurso;

  Orquestador(
      Planificador planificador,
      Reformulador reformulador,
      CatalogoHerramientas catalogo,
      Executor executor,
      ContextoRepositorio contextoRepo,
      HerramientasRepositorio herramientasRepo,
      Sintetizador sintetizador,
      VerificadorGrounding verificadorGrounding,
      QueryLogRepositorio queryLog,
      @Value("${kb.orquestacion.max-fragmentos-contexto:10}") int maxFragmentosContexto,
      @Value("${kb.orquestacion.expandir-vecinos:true}") boolean expandirVecinos,
      UmbralRelevanciaPropiedades umbralRelevancia,
      // Default 2 = OLLAMA_NUM_PARALLEL por defecto (compose.yml): no tiene
      // sentido admitir mas consultas reales en simultaneo que las que Ollama
      // puede atender a la vez.
      @Value("${kb.orquestacion.max-consultas-concurrentes:2}") int maxConsultasConcurrentes,
      StreamsEnCursoRepositorio streamsEnCurso) {
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
    this.cupoConsultas = new Semaphore(maxConsultasConcurrentes);
    this.streamsEnCurso = streamsEnCurso;
  }

  record EjecucionPipeline(
      PlanDeHerramientas plan,
      List<Executor.EjecucionHerramienta> herramientas,
      List<Fragmento> fragmentosUsados,
      Respuesta respuesta,
      long queryLogId) {}

  /**
   * Todo lo que las etapas 1-5 producen, listo para que la síntesis (etapa 6) lo consuma. {@code
   * respuestaFija} no nulo (ADR-0008) significa que la etapa 4 decidió que el contexto no alcanza:
   * la síntesis se salta por completo y ese texto es la respuesta. {@code
   * reformulacionesPropuestas} no vacía (solo con {@link Consultar.ModoReformulacion.Proponer})
   * significa que la etapa 4 se detuvo a esperar que la persona elija: no hay síntesis ni
   * respuesta.
   */
  private record PreSintesis(
      PlanDeHerramientas plan,
      List<Executor.EjecucionHerramienta> ejecuciones,
      List<Fragmento> fragmentos,
      List<Cita> citas,
      String contexto,
      String respuestaFija,
      String consultaReformulada,
      List<String> reformulacionesPropuestas,
      long inicioNanos) {}

  /**
   * Bloqueante: espera a que la síntesis termine antes de devolver nada. Usado por {@code /api/ask}
   * y Teams — siempre con {@link Consultar.Preferencias#POR_DEFECTO}, porque ninguno de los dos
   * tiene cómo pedirle a la persona que elija algo a mitad de camino.
   */
  EjecucionPipeline ejecutar(Pregunta pregunta, ProyectoId proyecto, Filtros filtros) {
    boolean cupoAdquirido = cupoConsultas.tryAcquire();
    try {
      PreSintesis pre =
          cupoAdquirido
              ? prepararHastaContexto(
                  pregunta, proyecto, filtros, Consultar.Preferencias.POR_DEFECTO.reformulacion())
              : respuestaServidorOcupado();

      // Etapa 6: sintesis en streaming, coleccionada aqui porque este metodo
      // devuelve una respuesta completa, no un Flux.
      String textoRespuesta =
          sintetizarBloqueante(pregunta, pre, Consultar.Preferencias.POR_DEFECTO.idioma());

      long latenciaMs = (System.nanoTime() - pre.inicioNanos()) / 1_000_000;
      // advertencias queda vacio a proposito: el prompt de sintesis exige señalar
      // contradicciones EN el texto de la respuesta (con [n] y prosa), no en un
      // campo estructurado aparte -- eso pediria una segunda llamada al LLM solo
      // para separar "respuesta" de "advertencia", en contra de que la sintesis
      // sea en streaming.
      Respuesta respuesta =
          new Respuesta(
              textoRespuesta, pre.citas(), List.of(), latenciaMs, pre.consultaReformulada());

      // Etapa 7: registrar.
      long queryLogId =
          queryLog.registrar(
              pregunta.texto(),
              proyecto.valor(),
              pre.plan(),
              pre.ejecuciones(),
              pre.fragmentos(),
              textoRespuesta,
              pre.citas(),
              latenciaMs);

      return new EjecucionPipeline(
          pre.plan(), pre.ejecuciones(), pre.fragmentos(), respuesta, queryLogId);
    } finally {
      if (cupoAdquirido) {
        cupoConsultas.release();
      }
    }
  }

  /**
   * En streaming: las citas de la etapa 5 vuelven de inmediato; el texto de la etapa 6 llega token
   * a token. El registro de la etapa 7 ocurre cuando el {@link Flux} completa, no antes — por eso
   * acumula el texto en un {@link StringBuilder} con {@code doOnNext} en vez de esperar a que el
   * llamador termine de consumirlo.
   */
  Consultar.RespuestaEnStreaming ejecutarEnStreaming(
      Pregunta pregunta,
      ProyectoId proyecto,
      Filtros filtros,
      Long conversacionId,
      Consultar.Preferencias preferencias) {
    // Antes que nada: si esta pagina se recarga al toque de preguntar, que
    // ya haya algo que encontrar via StreamsEnCursoRepositorio.buscar().
    if (conversacionId != null) {
      streamsEnCurso.iniciar(conversacionId, pregunta.texto(), proyecto.valor());
    }

    boolean cupoAdquirido = cupoConsultas.tryAcquire();
    PreSintesis pre =
        cupoAdquirido
            ? prepararHastaContexto(pregunta, proyecto, filtros, preferencias.reformulacion())
            : respuestaServidorOcupado();

    // Modo Proponer y la busqueda original no alcanzo: no hay nada que
    // sintetizar todavia. Se devuelven las alternativas y se suelta todo lo
    // que la consulta tenia tomado -- el cupo (no hay Flux cuyo doFinally lo
    // libere) y la fila de streams_en_curso (no hay respuesta que reconectar;
    // ver StreamsEnCursoRepositorio.descartar). Nada va a query_log: no se
    // respondio nada.
    if (!pre.reformulacionesPropuestas().isEmpty()) {
      if (cupoAdquirido) {
        cupoConsultas.release();
      }
      if (conversacionId != null) {
        streamsEnCurso.descartar(conversacionId);
      }
      return new Consultar.RespuestaEnStreaming(
          List.of(), Flux.empty(), null, Mono.empty(), pre.reformulacionesPropuestas());
    }

    if (conversacionId != null) {
      streamsEnCurso.actualizarCitas(conversacionId, pre.citas(), pre.consultaReformulada());
    }

    StringBuilder acumulado = new StringBuilder();
    // Se llena dentro del doOnComplete de abajo, cuando queryLog.registrar(...)
    // ya devolvio el id -- para TODO camino que complete, incluido "servidor
    // ocupado"/"sin informacion" (esos tambien escriben una fila real en
    // query_log). Queda en null si el Flux termina en error o se cancela: ahi
    // nunca se llega a escribir en query_log, no hay id que dar.
    AtomicReference<Long> queryLogIdHolder = new AtomicReference<>();
    Flux<String> textoBase =
        pre.respuestaFija() != null
            ? Flux.just(pre.respuestaFija())
            : sintetizador.sintetizar(pregunta.texto(), pre.contexto(), preferencias.idioma());
    Flux<String> texto =
        textoBase
            .doOnNext(acumulado::append)
            .doOnComplete(
                () -> {
                  long latenciaMs = (System.nanoTime() - pre.inicioNanos()) / 1_000_000;
                  long queryLogId =
                      queryLog.registrar(
                          pregunta.texto(),
                          proyecto.valor(),
                          pre.plan(),
                          pre.ejecuciones(),
                          pre.fragmentos(),
                          acumulado.toString(),
                          pre.citas(),
                          latenciaMs);
                  queryLogIdHolder.set(queryLogId);
                })
            // El cupo cubre TODA la consulta, no solo prepararHastaContexto: la
            // sintesis tambien le pide tokens a Ollama. Se libera aca, cuando el
            // Flux de verdad termina (completa, falla o el cliente se desconecta),
            // no antes -- doFinally cubre las tres formas de terminar. Mismo
            // razonamiento para dejar el resultado final en streams_en_curso: pasa
            // lo mismo haya o no alguien todavia escuchando del otro lado.
            .doFinally(
                signal -> {
                  if (cupoAdquirido) {
                    cupoConsultas.release();
                  }
                  if (conversacionId != null) {
                    String estadoFinal = signal == SignalType.ON_COMPLETE ? "completo" : "error";
                    streamsEnCurso.finalizar(
                        conversacionId, estadoFinal, acumulado.toString(), queryLogIdHolder.get());
                  }
                });

    // Mono.fromSupplier, no un valor ya calculado: se lee recien cuando alguien
    // lo suscribe (ChatController lo hace despues de agotar `texto` dentro del
    // mismo Flux.concat), momento en el que el doOnComplete de arriba ya corrio
    // y el holder tiene el id real.
    Mono<Long> queryLogId = Mono.fromSupplier(queryLogIdHolder::get);
    return new Consultar.RespuestaEnStreaming(
        pre.citas(), texto, pre.consultaReformulada(), queryLogId, List.of());
  }

  /**
   * Para que la UI se reconecte tras un F5 -- ver el javadoc de {@link StreamsEnCursoRepositorio}.
   */
  Optional<StreamsEnCursoRepositorio.Estado> estadoDeStream(long conversacionId) {
    return streamsEnCurso.buscar(conversacionId);
  }

  private static PreSintesis respuestaServidorOcupado() {
    return new PreSintesis(
        new PlanDeHerramientas(List.of(), "cupo de consultas concurrentes agotado"),
        List.of(),
        List.of(),
        List.of(),
        "",
        MENSAJE_SERVIDOR_OCUPADO,
        null,
        List.of(),
        System.nanoTime());
  }

  private String sintetizarBloqueante(Pregunta pregunta, PreSintesis pre, IdiomaRespuesta idioma) {
    if (pre.respuestaFija() != null) {
      return pre.respuestaFija();
    }
    String texto =
        sintetizador
            .sintetizar(pregunta.texto(), pre.contexto(), idioma)
            .collectList()
            .map(partes -> String.join("", partes))
            .block();
    return texto == null ? "" : texto;
  }

  private PreSintesis prepararHastaContexto(
      Pregunta pregunta,
      ProyectoId proyecto,
      Filtros filtros,
      Consultar.ModoReformulacion modoReformulacion) {
    long inicio = System.nanoTime();
    List<Long> documentosPermitidos = filtros.documentosPermitidos();

    // Etapa 1: planificar -- siempre sobre la pregunta original, aunque la
    // busqueda vaya con otro texto: el plan elige herramientas segun de que
    // trata la pregunta, y eso no cambia con la reformulacion.
    PlanDeHerramientas plan = planificador.planificar(pregunta.texto(), catalogo.descripciones());

    // Con Elegida, la persona ya decidio con que texto buscar (puede ser la
    // propia pregunta, si prefirio no reformular): se busca con eso y no se
    // vuelve a pasar por el Reformulador -- si no, su eleccion quedaria pisada
    // en silencio por una reformulacion automatica.
    String consultaBusqueda = pregunta.texto();
    String consultaReformuladaParaMostrar = null;
    if (modoReformulacion instanceof Consultar.ModoReformulacion.Elegida elegida) {
      consultaBusqueda = elegida.consultaDeBusqueda();
      if (!consultaBusqueda.strip().equalsIgnoreCase(pregunta.texto().strip())) {
        consultaReformuladaParaMostrar = consultaBusqueda;
      }
    }

    // Etapas 2-3: ejecutar herramientas en paralelo, con fallas aisladas por
    // herramienta.
    List<Executor.EjecucionHerramienta> ejecuciones =
        executor.ejecutar(plan.herramientas(), consultaBusqueda, proyecto, documentosPermitidos);

    // Etapa 4: fusionar y deduplicar entre herramientas.
    List<Fragmento> fragmentos =
        FusionDeHerramientas.combinar(
            ejecuciones.stream().map(Executor.EjecucionHerramienta::fragmentos).toList(),
            maxFragmentosContexto);

    // ADR-0008: puerta de relevancia antes de expandir contexto y sintetizar.
    // El score del cross-encoder decide los casos claros sin gastar una
    // llamada al LLM; la zona ambigua se manda a VerificadorGrounding en vez
    // de confiar en que el propio sintetizador se autocensure.
    long chunksProyecto = herramientasRepo.contarChunks(proyecto.valor());
    UmbralRelevancia.Resultado umbral =
        UmbralRelevancia.evaluar(fragmentos, chunksProyecto, umbralRelevancia);

    // Reformula la consulta de busqueda SOLO cuando la busqueda con la pregunta tal cual
    // no llego a SUFICIENTE -- reformular siempre, incluso en las preguntas que ya
    // buscaban bien, sumaba una llamada al LLM de mas en cada pregunta sin necesidad
    // (hallazgos 70-71 de la investigacion de VRAM y modelo LLM confirmaron que ayuda en
    // el caso dificil sin romper el caso facil, pero median el costo de la llamada
    // incondicional). Cubre tanto INSUFICIENTE (el caso que motivo el componente, ver
    // hallazgo 63: "autoboxing" vs. "boxing conversion") como AMBIGUO -- en ambos el score
    // ya senala que el texto original no encontro un candidato fuerte.
    boolean puedeReformular =
        !(modoReformulacion instanceof Consultar.ModoReformulacion.Elegida)
            && umbral.decision() != UmbralRelevancia.Decision.SUFICIENTE;
    if (puedeReformular) {
      // Los fragmentos de la primera ronda, aunque no alcancen, ya son texto REAL
      // de la fuente: le muestran al Reformulador con que palabras y en que
      // idioma esta escrita, en vez de dejarlo adivinar (ver Reformulador).
      Reformulador.Reformulacion reformulacion =
          reformulador.reformular(pregunta.texto(), pistasDelCorpus(fragmentos));
      if (modoReformulacion instanceof Consultar.ModoReformulacion.Proponer
          && reformulacion.alternativas().size() >= MIN_ALTERNATIVAS_PARA_ELEGIR) {
        // La persona elige: se corta aca, sin gastar la segunda ronda de
        // herramientas ni la sintesis. Con una sola alternativa no hay nada
        // que elegir y se responde de inmediato por el camino automatico de
        // abajo (pedido explicito de producto); sin ninguna, tambien.
        return new PreSintesis(
            plan,
            ejecuciones,
            fragmentos,
            List.of(),
            "",
            null,
            null,
            reformulacion.alternativas(),
            inicio);
      }
      if (reformulacion.reformulada()) {
        List<Executor.EjecucionHerramienta> reejecuciones =
            executor.ejecutar(
                plan.herramientas(), reformulacion.textoBusqueda(), proyecto, documentosPermitidos);
        List<Fragmento> refragmentos =
            FusionDeHerramientas.combinar(
                reejecuciones.stream().map(Executor.EjecucionHerramienta::fragmentos).toList(),
                maxFragmentosContexto);
        UmbralRelevancia.Resultado umbralReformulado =
            UmbralRelevancia.evaluar(refragmentos, chunksProyecto, umbralRelevancia);
        // La reformulacion no siempre mejora: un LLM chico puede reformular mal y
        // alejarse del tema (medido en vivo: "que es un enum" -> una pregunta sobre
        // "estructuras de datos con acceso por indice numerico", sin relacion real).
        // Sin esta comparacion, una busqueda original ya buena (AMBIGUO con rerank
        // 5.35, candidata real: "enum Coin { PENNY(1)...") quedaba pisada sin mas
        // por la reformulada, mucho peor (INSUFICIENTE, rerank 0.2) -- se perdia una
        // respuesta que ya estaba bien encontrada. Se queda con lo que rinda mas.
        if (esMejor(umbralReformulado, umbral)) {
          ejecuciones = reejecuciones;
          fragmentos = refragmentos;
          umbral = umbralReformulado;
          consultaReformuladaParaMostrar = reformulacion.textoBusqueda();
        }
      }
    }

    if (umbral.decision() == UmbralRelevancia.Decision.INSUFICIENTE) {
      return new PreSintesis(
          plan,
          ejecuciones,
          fragmentos,
          List.of(),
          "",
          MENSAJE_SIN_INFORMACION,
          consultaReformuladaParaMostrar,
          List.of(),
          inicio);
    }

    // Etapa 5: expansion de contexto con secciones vecinas.
    String contexto = construirContexto(fragmentos);
    List<Cita> citas = fragmentos.stream().map(Citas::desde).toList();

    if (umbral.decision() == UmbralRelevancia.Decision.AMBIGUO) {
      VerificadorGrounding.Veredicto veredicto =
          verificadorGrounding.verificar(pregunta.texto(), contexto);
      if (!veredicto.respondeLaPregunta()) {
        return new PreSintesis(
            plan,
            ejecuciones,
            fragmentos,
            List.of(),
            "",
            MENSAJE_SIN_INFORMACION,
            consultaReformuladaParaMostrar,
            List.of(),
            inicio);
      }
    }

    return new PreSintesis(
        plan,
        ejecuciones,
        fragmentos,
        citas,
        contexto,
        null,
        consultaReformuladaParaMostrar,
        List.of(),
        inicio);
  }

  /**
   * Cuántos fragmentos de la primera ronda se le muestran al Reformulador, y cuánto de cada uno.
   */
  static final int MAX_PISTAS = 5;

  static final int LARGO_PISTA = 200;

  /**
   * Una línea por fragmento: título y el comienzo del texto, aplanado. Acotado a {@link
   * #MAX_PISTAS} por {@link #LARGO_PISTA} caracteres para que quepa de sobra en el contexto del
   * reformulador (unos 300 tokens) sin desplazar al prompt de sistema.
   */
  static List<String> pistasDelCorpus(List<Fragmento> fragmentos) {
    return fragmentos.stream()
        .limit(MAX_PISTAS)
        .map(
            f -> {
              String texto = f.texto() == null ? "" : f.texto().replaceAll("\\s+", " ").strip();
              if (texto.length() > LARGO_PISTA) {
                texto = texto.substring(0, LARGO_PISTA) + "…";
              }
              return "[" + Citas.tituloDe(f) + "] " + texto;
            })
        .toList();
  }

  /**
   * Compara dos rondas de búsqueda por qué tan bien pasan la puerta de relevancia: primero por
   * {@code Decision} (SUFICIENTE > AMBIGUO > INSUFICIENTE), y a igual decisión, por el mejor score
   * del reranker.
   */
  private static boolean esMejor(
      UmbralRelevancia.Resultado candidato, UmbralRelevancia.Resultado base) {
    int rangoCandidato = rangoDecision(candidato.decision());
    int rangoBase = rangoDecision(base.decision());
    if (rangoCandidato != rangoBase) {
      return rangoCandidato > rangoBase;
    }
    double puntajeCandidato =
        candidato.mejorPuntaje() == null ? Double.NEGATIVE_INFINITY : candidato.mejorPuntaje();
    double puntajeBase =
        base.mejorPuntaje() == null ? Double.NEGATIVE_INFINITY : base.mejorPuntaje();
    return puntajeCandidato > puntajeBase;
  }

  private static int rangoDecision(UmbralRelevancia.Decision decision) {
    return switch (decision) {
      case SUFICIENTE -> 2;
      case AMBIGUO -> 1;
      case INSUFICIENTE -> 0;
    };
  }

  /**
   * {@code kb.orquestacion.expandir-vecinos=false} (perfil Bonsai) apaga la expansión: cada
   * fragmento cuesta hasta 3x menos tokens sin sus vecinos, lo que permite subir {@code
   * max-fragmentos-contexto} y que sobrevivan más candidatos distintos dentro del mismo presupuesto
   * de contexto — medido en vivo: con contexto de 4096 tokens, 2 fragmentos CON vecinos ocupaban el
   * mismo espacio que varios más SIN vecinos, y el candidato relevante quedaba afuera cuando no
   * rankeaba entre los 2 primeros.
   */
  private String construirContexto(List<Fragmento> fragmentos) {
    StringBuilder contexto = new StringBuilder();
    for (int i = 0; i < fragmentos.size(); i++) {
      Fragmento f = fragmentos.get(i);
      contexto.append("[%d] %s (%s)\n".formatted(i + 1, Citas.tituloDe(f), f.uri()));

      if (expandirVecinos) {
        List<ContextoRepositorio.Vecino> vecinos = contextoRepo.vecinos(f.documentoId(), f.ord());
        vecinos.stream()
            .filter(v -> v.ord() < f.ord())
            .forEach(v -> contexto.append(v.texto()).append('\n'));
        contexto.append(f.texto()).append('\n');
        vecinos.stream()
            .filter(v -> v.ord() > f.ord())
            .forEach(v -> contexto.append(v.texto()).append('\n'));
      } else {
        contexto.append(f.texto()).append('\n');
      }

      contexto.append('\n');
    }
    return contexto.toString();
  }
}
