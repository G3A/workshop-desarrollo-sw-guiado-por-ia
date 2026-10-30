package co.g3a.baseconocimiento.acciones;

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
import co.g3a.baseconocimiento.acciones.Capitulos.Capitulo;
import co.g3a.baseconocimiento.acciones.PresupuestoDeContexto.DocumentoPlanificado;
import co.g3a.baseconocimiento.acciones.SeccionesRepositorio.Seccion;
import co.g3a.baseconocimiento.compartido.Dominio.ProyectoId;
import co.g3a.baseconocimiento.llm.Redactor;
import co.g3a.baseconocimiento.llm.Redactor.Nivel;
import co.g3a.baseconocimiento.llm.Redactor.NivelBloom;
import co.g3a.baseconocimiento.llm.Redactor.Pregunta;
import co.g3a.baseconocimiento.llm.Redactor.Preguntas;
import co.g3a.baseconocimiento.llm.RendimientoDelModelo;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Las cuatro acciones que redactan a partir del contexto: resumir y sintetizar (prosa en
 * streaming), preguntas e ideas (salida estructurada, decision 11 del issue #38). De las siete
 * etapas del RAG sobreviven dos: armar el contexto y llamar al LLM. No hay planner, retrieval,
 * umbral de relevancia ni registro: la seleccion de la persona es toda la evidencia.
 *
 * <p>Un documento que no cabe en su cuota no se recorta: se lee entero por pasadas (sub-issue #60)
 * antes de la accion, con el plan que arma {@link PresupuestoDeContexto} — cada tramo se condensa
 * en notas, y las notas se vuelven a condensar hasta que quepan. Cada pasada se anuncia con una
 * {@link Lectura}; si el cliente corta, el bucle para en la pasada siguiente.
 *
 * <p>Todo corre recien al suscribirse ({@code Flux.defer}): el cupo se toma ahi y se devuelve en el
 * {@code doFinally} pase lo que pase, y un {@link Redactor} que lance antes de crear su Flux
 * termina como error del flujo, no como excepcion del que arma el resultado.
 */
@Component
class AccionesSobreDocumentos {

  static final String MENSAJE_SIN_DOCUMENTOS =
      "Ninguno de los documentos seleccionados existe ya en este proyecto: es posible que se hayan"
          + " quitado del vault desde que se abrió esta conversación. Actualiza la lista de"
          + " documentos y vuelve a intentarlo.";

  static final String MENSAJE_SERVIDOR_OCUPADO =
      "El servidor ya está atendiendo el máximo de acciones al mismo tiempo. Espera un momento y"
          + " vuelve a intentarlo.";

  /** Lo que encabeza el extracto de un capitulo; el modelo a veces lo repite al comienzo. */
  static final String ENCABEZADO_CAPITULO = "Capítulo: ";

  /** Por debajo de esto la nota de un capitulo ya no dice nada. */
  static final int MINIMO_NOTAS_CAPITULO = 200;

  private final SeccionesRepositorio repo;
  private final Redactor redactor;
  private final CupoDeAcciones cupo;
  private final AccionesPropiedades propiedades;
  private final RendimientoDelModelo rendimiento;

  AccionesSobreDocumentos(
      SeccionesRepositorio repo,
      Redactor redactor,
      CupoDeAcciones cupo,
      AccionesPropiedades propiedades,
      RendimientoDelModelo rendimiento) {
    this.repo = repo;
    this.redactor = redactor;
    this.cupo = cupo;
    this.propiedades = propiedades;
    this.rendimiento = rendimiento;
  }

  /**
   * Como se leen los documentos largos en el modo «Por capitulos»: sus capitulos y la ruta de cada
   * seccion (para nombrarla en el extracto). Vacio en el modo completo.
   */
  private record LecturaPorCapitulos(
      Map<Long, List<Capitulo>> capitulos, Map<Long, List<String>> rutas, int largoExtracto) {

    static final LecturaPorCapitulos NINGUNA = new LecturaPorCapitulos(Map.of(), Map.of(), 0);
  }

  ResultadoDeAccion ejecutar(Tipo tipo, List<Long> documentos, ProyectoId proyecto, String idioma) {
    return ejecutar(tipo, documentos, proyecto, idioma, Modo.COMPLETO);
  }

  ResultadoDeAccion ejecutar(
      Tipo tipo, List<Long> documentos, ProyectoId proyecto, String idioma, Modo modo) {
    List<Long> unicos = new ArrayList<>(new LinkedHashSet<>(documentos));
    List<Seccion> secciones = repo.seccionesDe(unicos, proyecto.valor());
    PresupuestoDeContexto presupuesto = PresupuestoDeContexto.para(propiedades, rendimiento);
    List<DocumentoPlanificado> plan = presupuesto.planificar(unicos, secciones);
    boolean hayIndexados = plan.stream().anyMatch(DocumentoPlanificado::indexado);
    String etiqueta = PresupuestoDeContexto.etiqueta(verboDe(tipo), plan);
    LecturaPorCapitulos porCapitulos =
        modo == Modo.POR_CAPITULOS
            ? porCapitulos(plan, proyecto, presupuesto.caracteresPorPasada())
            : LecturaPorCapitulos.NINGUNA;
    Map<Long, String> rapidas = modo == Modo.RAPIDO ? lecturaRapida(plan, proyecto) : Map.of();

    Flux<EventoAccion> eventos =
        !hayIndexados
            ? Flux.just(mensajeFijo(tipo, MENSAJE_SIN_DOCUMENTOS))
            : Flux.defer(
                () ->
                    cupo.intentarTomar()
                        ? conCupo(
                            tipo,
                            plan,
                            presupuesto.notasPorGrupo(),
                            porCapitulos,
                            rapidas,
                            modo,
                            idioma)
                        : Flux.just(mensajeFijo(tipo, MENSAJE_SERVIDOR_OCUPADO)));
    return new ResultadoDeAccion(
        etiqueta,
        cobertura(plan, porCapitulos, rapidas, modo),
        PresupuestoDeContexto.citas(plan),
        eventos);
  }

  private LecturaPorCapitulos porCapitulos(
      List<DocumentoPlanificado> plan, ProyectoId proyecto, int caracteresPorPasada) {
    List<DocumentoPlanificado> largos =
        plan.stream().filter(DocumentoPlanificado::porPasadas).toList();
    if (largos.isEmpty()) {
      return LecturaPorCapitulos.NINGUNA;
    }
    Map<Long, List<String>> rutas =
        repo.rutasDe(
            largos.stream().map(DocumentoPlanificado::documentoId).toList(), proyecto.valor());
    Map<Long, List<Capitulo>> capitulos = new LinkedHashMap<>();
    for (DocumentoPlanificado d : largos) {
      capitulos.put(d.documentoId(), Capitulos.agrupar(d.secciones(), rutas));
    }
    return new LecturaPorCapitulos(capitulos, rutas, Capitulos.largoExtracto(caracteresPorPasada));
  }

  /**
   * Modo «Rapido»: por cada documento largo, las secciones que lo representan (k-means sobre sus
   * embeddings) hasta llenar su cuota. Sin llamadas al LLM: son las notas con que entra.
   */
  private Map<Long, String> lecturaRapida(List<DocumentoPlanificado> plan, ProyectoId proyecto) {
    List<DocumentoPlanificado> largos =
        plan.stream().filter(DocumentoPlanificado::porPasadas).toList();
    if (largos.isEmpty()) {
      return Map.of();
    }
    List<Long> ids = largos.stream().map(DocumentoPlanificado::documentoId).toList();
    Map<Long, List<String>> rutas = repo.rutasDe(ids, proyecto.valor());
    Map<Long, float[]> vectores = repo.embeddingsDe(ids, proyecto.valor());
    Map<Long, String> notas = new HashMap<>();
    for (DocumentoPlanificado d : largos) {
      List<Seccion> elegidas =
          Representativas.elegir(d.secciones(), vectores, Representativas.cuantas(d.cuota()));
      notas.put(d.documentoId(), Capitulos.extracto(new Capitulo("", elegidas), rutas, d.cuota()));
    }
    return notas;
  }

  /**
   * Las pasadas que la UI va a contar: por capitulos, un capitulo por pasada; en lectura rapida,
   * ninguna.
   */
  private static List<CoberturaDocumento> cobertura(
      List<DocumentoPlanificado> plan,
      LecturaPorCapitulos porCapitulos,
      Map<Long, String> rapidas,
      Modo modo) {
    return PresupuestoDeContexto.cobertura(plan).stream()
        .map(
            c -> {
              List<Capitulo> propios = porCapitulos.capitulos().get(c.documentoId());
              int pasadas =
                  rapidas.containsKey(c.documentoId())
                      ? 0
                      : propios == null ? c.pasadas() : propios.size();
              return new CoberturaDocumento(
                  c.documentoId(),
                  c.titulo(),
                  c.uri(),
                  c.seccionesTotales(),
                  pasadas,
                  c.modo() == null ? null : modo);
            })
        .toList();
  }

  static boolean esProsa(Tipo tipo) {
    return tipo == Tipo.RESUMIR || tipo == Tipo.SINTETIZAR;
  }

  /** El mismo mensaje, en la forma que cada tipo de accion sabe mostrar. */
  static EventoAccion mensajeFijo(Tipo tipo, String mensaje) {
    return esProsa(tipo) ? new Token(mensaje) : new Resultado(new SinResultado(mensaje));
  }

  /** Solo con el cupo ya tomado: lo devuelve pase lo que pase. */
  private Flux<EventoAccion> conCupo(
      Tipo tipo,
      List<DocumentoPlanificado> plan,
      int porGrupo,
      LecturaPorCapitulos porCapitulos,
      Map<Long, String> rapidas,
      Modo modo,
      String idioma) {
    Map<Long, String> notas = new ConcurrentHashMap<>(rapidas);
    // En su propio hilo: el bucle bloquea en cada llamada al LLM, y si corriera en el hilo
    // que escribe el SSE, este no vaciaria nada hasta terminar todas las pasadas y el
    // progreso llegaria de golpe al final (visto en vivo con la primera demo de #60).
    Flux<EventoAccion> lecturas =
        Flux.<EventoAccion>create(sink -> leer(plan, porGrupo, porCapitulos, idioma, notas, sink))
            .subscribeOn(Schedulers.boundedElastic());
    Flux<EventoAccion> accion =
        Flux.defer(() -> accion(tipo, PresupuestoDeContexto.contexto(plan, notas, modo), idioma));
    return Flux.concat(lecturas, accion).doFinally(signal -> cupo.liberar());
  }

  /**
   * Las pasadas de lectura de los documentos largos, en orden y de forma sincronica sobre el hilo
   * que se suscribe (igual que {@code fromCallable}): por nivel, condensa cada tramo en notas; si
   * las notas juntas no caben en la cuota, las agrupa y repite. Deja las notas finales en {@code
   * notas} y emite una {@link Lectura} por pasada; si el estimado sobro (las notas salieron mas
   * cortas), cierra con la ultima pasada declarada para que el progreso llegue al final.
   */
  private void leer(
      List<DocumentoPlanificado> plan,
      int porGrupo,
      LecturaPorCapitulos porCapitulos,
      String idioma,
      Map<Long, String> notas,
      FluxSink<EventoAccion> sink) {
    try {
      for (DocumentoPlanificado d : plan) {
        // Los de lectura rapida ya traen sus notas: no hay pasadas.
        if (!d.porPasadas() || notas.containsKey(d.documentoId())) {
          continue;
        }
        List<Capitulo> capitulos = porCapitulos.capitulos().get(d.documentoId());
        if (capitulos != null) {
          if (!leerPorCapitulos(d, capitulos, porCapitulos, idioma, notas, sink)) {
            return;
          }
          continue;
        }
        int hechas = 0;
        List<String> nivel = d.tramos();
        while (true) {
          List<String> condensadas = new ArrayList<>();
          int objetivo =
              nivel.size() == 1
                  ? Math.min(PresupuestoDeContexto.LARGO_NOTAS, d.cuota())
                  : PresupuestoDeContexto.LARGO_NOTAS;
          for (String tramo : nivel) {
            if (sink.isCancelled()) {
              return;
            }
            condensadas.add(recortar(redactor.condensar(tramo, idioma, objetivo), objetivo));
            hechas++;
            sink.next(new Lectura(d.documentoId(), hechas, d.pasadas()));
          }
          String juntas = String.join("\n\n", condensadas);
          if (condensadas.size() == 1 || juntas.length() <= d.cuota()) {
            notas.put(d.documentoId(), recortar(juntas, d.cuota()));
            break;
          }
          nivel = agrupar(condensadas, porGrupo);
        }
        if (hechas < d.pasadas()) {
          sink.next(new Lectura(d.documentoId(), d.pasadas(), d.pasadas()));
        }
      }
      sink.complete();
    } catch (RuntimeException e) {
      sink.error(e);
    }
  }

  /**
   * Una llamada por capitulo con un extracto acotado de sus secciones; las notas de cada capitulo
   * se reparten la cuota del documento. Devuelve falso si el cliente corto.
   */
  private boolean leerPorCapitulos(
      DocumentoPlanificado d,
      List<Capitulo> capitulos,
      LecturaPorCapitulos porCapitulos,
      String idioma,
      Map<Long, String> notas,
      FluxSink<EventoAccion> sink) {
    int objetivo =
        Math.max(
            MINIMO_NOTAS_CAPITULO,
            Math.min(PresupuestoDeContexto.LARGO_NOTAS, d.cuota() / capitulos.size() - 80));
    StringBuilder juntas = new StringBuilder();
    for (int i = 0; i < capitulos.size(); i++) {
      if (sink.isCancelled()) {
        return false;
      }
      Capitulo c = capitulos.get(i);
      String extracto = Capitulos.extracto(c, porCapitulos.rutas(), porCapitulos.largoExtracto());
      String nota =
          recortar(
              sinEncabezado(
                  redactor.condensar(
                      ENCABEZADO_CAPITULO + c.titulo() + "\n\n" + extracto, idioma, objetivo)),
              objetivo);
      juntas.append("## ").append(c.titulo()).append('\n').append(nota).append("\n\n");
      sink.next(new CapituloLeido(d.documentoId(), i + 1, capitulos.size(), c.titulo(), nota));
    }
    notas.put(d.documentoId(), recortar(juntas.toString(), d.cuota()));
    return true;
  }

  /**
   * Sin la linea {@code Capítulo: ...} si el modelo la copio del prompt (visto con Granite y la
   * especificacion de Java): el titulo ya va aparte en la UI y en el contexto.
   */
  static String sinEncabezado(String nota) {
    String limpia = nota == null ? "" : nota.strip();
    if (!limpia.startsWith(ENCABEZADO_CAPITULO)) {
      return limpia;
    }
    int salto = limpia.indexOf('\n');
    return salto < 0 ? "" : limpia.substring(salto + 1).strip();
  }

  private Flux<EventoAccion> accion(Tipo tipo, String contexto, String idioma) {
    return switch (tipo) {
      case RESUMIR -> redactor.resumir(contexto, idioma).<EventoAccion>map(Token::new);
      case SINTETIZAR -> redactor.sintetizar(contexto, idioma).<EventoAccion>map(Token::new);
      // Un nivel de Bloom por llamada (sub-issue #61): cada una es corta y cabe en el
      // timeout en CPU, y el resultado sale acumulado nivel a nivel para que la UI
      // pinte lo que ya hay y diga por cual va. El ultimo Resultado es el completo.
      case PREGUNTAS -> preguntasPorNivel(contexto, idioma);
      case IDEAS ->
          Mono.fromCallable(() -> redactor.idear(contexto, idioma))
              .<EventoAccion>map(Resultado::new)
              .flux();
    };
  }

  /**
   * Un nivel de Bloom por llamada, en orden, y cada nivel recibe las preguntas ya formuladas para
   * no repetirlas. La lista vive dentro de esta suscripcion: {@code accion} corre dentro de un
   * {@code Flux.defer}, y {@code concatMap} garantiza que los niveles van uno detras de otro.
   */
  private Flux<EventoAccion> preguntasPorNivel(String contexto, String idioma) {
    List<String> yaFormuladas = new ArrayList<>();
    return Flux.fromArray(NivelBloom.values())
        .concatMap(
            nivel ->
                Mono.fromCallable(
                    () -> {
                      List<Pregunta> preguntas =
                          redactor.preguntar(contexto, idioma, nivel, List.copyOf(yaFormuladas));
                      preguntas.forEach(p -> yaFormuladas.add(p.texto()));
                      return new Nivel(nivel.codigo(), preguntas);
                    }))
        .scan(
            List.<Nivel>of(),
            (acumulado, nivel) -> {
              List<Nivel> lista = new ArrayList<>(acumulado);
              lista.add(nivel);
              return List.copyOf(lista);
            })
        .skip(1)
        .<EventoAccion>map(niveles -> new Resultado(new Preguntas(niveles)));
  }

  /** De a {@code porGrupo} notas consecutivas por grupo: asi el conteo de pasadas es exacto. */
  static List<String> agrupar(List<String> notas, int porGrupo) {
    List<String> grupos = new ArrayList<>();
    for (int i = 0; i < notas.size(); i += porGrupo) {
      grupos.add(String.join("\n\n", notas.subList(i, Math.min(notas.size(), i + porGrupo))));
    }
    return grupos;
  }

  private static String recortar(String texto, int largo) {
    String limpio = texto == null ? "" : texto.strip();
    return limpio.length() <= largo ? limpio : limpio.substring(0, Math.max(0, largo));
  }

  static String verboDe(Tipo tipo) {
    return switch (tipo) {
      case RESUMIR -> "Resumen de";
      case SINTETIZAR -> "Síntesis de";
      case PREGUNTAS -> "Preguntas sobre";
      case IDEAS -> "Ideas a partir de";
    };
  }
}
