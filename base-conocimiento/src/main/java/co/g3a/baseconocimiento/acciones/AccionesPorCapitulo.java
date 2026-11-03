package co.g3a.baseconocimiento.acciones;

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
import co.g3a.baseconocimiento.acciones.Acciones.Tipo;
import co.g3a.baseconocimiento.acciones.Capitulos.Capitulo;
import co.g3a.baseconocimiento.acciones.PresupuestoDeContexto.DocumentoPlanificado;
import co.g3a.baseconocimiento.acciones.SeccionesRepositorio.Seccion;
import co.g3a.baseconocimiento.compartido.Dominio.ProyectoId;
import co.g3a.baseconocimiento.llm.Redactor;
import co.g3a.baseconocimiento.llm.Redactor.Idea;
import co.g3a.baseconocimiento.llm.Redactor.NivelBloom;
import co.g3a.baseconocimiento.llm.Redactor.Pregunta;
import co.g3a.baseconocimiento.llm.RendimientoDelModelo;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Preguntas e ideas sobre documentos largos, una tanda por capitulo elegido (issue #66).
 *
 * <p>Antes, todo el documento entraba en el presupuesto de la accion (7000 caracteres) y una sola
 * llamada por nivel generaba las preguntas: con la especificacion de Java eso eran ~290 caracteres
 * por capitulo, y el modelo se quedaba con el capitulo 1. Ahora cada capitulo elegido entra con un
 * extracto del tamano de una pasada y genera lo suyo; el resultado se acumula y viaja completo
 * despues de cada nivel, asi la persona ve crecer la lista y conserva lo hecho si corta.
 *
 * <p>Los documentos cortos elegidos junto al largo van en una tanda propia, primero, enteros y con
 * su numero {@code [n]} de siempre. En una tanda de capitulo la fuente es la de ese documento, la
 * diga o no el modelo.
 */
@Component
class AccionesPorCapitulo {

  static final String MENSAJE_SIN_CAPITULOS =
      "No quedó ningún capítulo elegido de los documentos seleccionados. Vuelve a intentarlo y marca"
          + " al menos uno.";

  private final SeccionesRepositorio repo;
  private final Redactor redactor;
  private final CupoDeAcciones cupo;
  private final AccionesPropiedades propiedades;
  private final RendimientoDelModelo rendimiento;

  AccionesPorCapitulo(
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
   * Una llamada (ideas) o seis (preguntas, una por nivel de Bloom) sobre un mismo contexto. {@code
   * capitulo} es {@code null} en la tanda de los documentos cortos; {@code fuente}, el numero del
   * documento en una tanda de capitulo, o 0 si manda el modelo.
   */
  record Tanda(long documentoId, String capitulo, String contexto, int fuente) {}

  static boolean aplica(Tipo tipo, Modo modo) {
    return modo == Modo.POR_CAPITULOS && (tipo == Tipo.PREGUNTAS || tipo == Tipo.IDEAS);
  }

  ResultadoDeAccion ejecutar(
      Tipo tipo,
      List<Long> documentos,
      ProyectoId proyecto,
      String idioma,
      List<CapituloElegido> elegidos) {
    List<Long> unicos = new ArrayList<>(new LinkedHashSet<>(documentos));
    List<Seccion> secciones = repo.seccionesDe(unicos, proyecto.valor());
    PresupuestoDeContexto presupuesto = PresupuestoDeContexto.para(propiedades, rendimiento);
    List<DocumentoPlanificado> plan = presupuesto.planificar(unicos, secciones);
    boolean hayIndexados = plan.stream().anyMatch(DocumentoPlanificado::indexado);
    List<Tanda> tandas =
        tandas(plan, rutas(plan, proyecto), presupuesto.caracteresPorPasada(), elegidos);

    Flux<EventoAccion> eventos;
    if (!hayIndexados) {
      eventos =
          Flux.just(
              AccionesSobreDocumentos.mensajeFijo(
                  tipo, AccionesSobreDocumentos.MENSAJE_SIN_DOCUMENTOS));
    } else if (tandas.isEmpty()) {
      eventos = Flux.just(AccionesSobreDocumentos.mensajeFijo(tipo, MENSAJE_SIN_CAPITULOS));
    } else {
      eventos =
          Flux.defer(
              () ->
                  cupo.intentarTomar()
                      ? generar(tipo, tandas, idioma).doFinally(senal -> cupo.liberar())
                      : Flux.just(
                          AccionesSobreDocumentos.mensajeFijo(
                              tipo, AccionesSobreDocumentos.MENSAJE_SERVIDOR_OCUPADO)));
    }
    return new ResultadoDeAccion(
        PresupuestoDeContexto.etiqueta(AccionesSobreDocumentos.verboDe(tipo), plan),
        cobertura(plan, tandas),
        PresupuestoDeContexto.citas(plan),
        eventos);
  }

  private Map<Long, List<String>> rutas(List<DocumentoPlanificado> plan, ProyectoId proyecto) {
    List<Long> largos =
        plan.stream()
            .filter(DocumentoPlanificado::porPasadas)
            .map(DocumentoPlanificado::documentoId)
            .toList();
    return largos.isEmpty() ? Map.of() : repo.rutasDe(largos, proyecto.valor());
  }

  /**
   * Primero los documentos cortos, juntos y enteros; despues, por cada documento largo, sus
   * capitulos elegidos en orden. Los capitulos salen de {@link Capitulos#agrupar}, igual que en el
   * estimado: por eso el indice que eligio la persona es el mismo.
   */
  static List<Tanda> tandas(
      List<DocumentoPlanificado> plan,
      Map<Long, List<String>> rutas,
      int largoExtracto,
      List<CapituloElegido> elegidos) {
    Map<Long, Integer> numeros = new HashMap<>();
    int n = 0;
    for (DocumentoPlanificado d : plan) {
      if (d.indexado()) {
        numeros.put(d.documentoId(), ++n);
      }
    }
    List<Tanda> tandas = new ArrayList<>();
    StringBuilder cortos = new StringBuilder();
    long primerCorto = -1;
    for (DocumentoPlanificado d : plan) {
      if (!d.indexado() || d.porPasadas()) {
        continue;
      }
      if (primerCorto < 0) {
        primerCorto = d.documentoId();
      }
      cortos.append(encabezado(numeros.get(d.documentoId()), d));
      for (Seccion s : d.secciones()) {
        cortos.append(s.texto()).append('\n');
      }
      cortos.append('\n');
    }
    if (primerCorto >= 0) {
      tandas.add(new Tanda(primerCorto, null, cortos.toString(), 0));
    }
    for (DocumentoPlanificado d : plan) {
      if (!d.porPasadas()) {
        continue;
      }
      List<Capitulo> capitulos = Capitulos.agrupar(d.secciones(), rutas);
      for (int i = 0; i < capitulos.size(); i++) {
        if (!elegidos.isEmpty() && !elegidos.contains(new CapituloElegido(d.documentoId(), i))) {
          continue;
        }
        Capitulo c = capitulos.get(i);
        int numero = numeros.get(d.documentoId());
        String contexto =
            encabezado(numero, d)
                + "(Documento largo: lo que sigue es un extracto del capitulo «"
                + c.titulo()
                + "», no el texto completo)\n"
                + Capitulos.extracto(c, rutas, largoExtracto);
        tandas.add(new Tanda(d.documentoId(), c.titulo(), contexto, numero));
      }
    }
    return tandas;
  }

  private static String encabezado(int numero, DocumentoPlanificado d) {
    return "[%d] %s (%s)\n".formatted(numero, d.titulo(), d.uri());
  }

  /** La cobertura declara un capitulo por pasada, como el modo por capitulos de siempre. */
  private static List<CoberturaDocumento> cobertura(
      List<DocumentoPlanificado> plan, List<Tanda> tandas) {
    return PresupuestoDeContexto.cobertura(plan).stream()
        .map(
            c -> {
              if (c.modo() == null) {
                return c;
              }
              int propias =
                  (int)
                      tandas.stream()
                          .filter(t -> t.capitulo() != null && t.documentoId() == c.documentoId())
                          .count();
              return new CoberturaDocumento(
                  c.documentoId(),
                  c.titulo(),
                  c.uri(),
                  c.seccionesTotales(),
                  propias,
                  Modo.POR_CAPITULOS);
            })
        .toList();
  }

  /**
   * Las tandas en orden, en su propio hilo (cada llamada al LLM bloquea). Despues de cada nivel (o
   * de las ideas de una tanda) sale el resultado acumulado; al cerrar la tanda, un {@link
   * CapituloLeido} para el progreso.
   */
  private Flux<EventoAccion> generar(Tipo tipo, List<Tanda> tandas, String idioma) {
    Acumulado acumulado = new Acumulado();
    return Flux.range(0, tandas.size())
        .concatMap(
            i -> {
              Tanda t = tandas.get(i);
              Flux<EventoAccion> salida =
                  tipo == Tipo.PREGUNTAS
                      ? preguntas(t, idioma, acumulado)
                      : Mono.fromCallable(
                              () -> {
                                acumulado.agregarIdeas(t, redactor.idear(t.contexto(), idioma));
                                return (EventoAccion) new Resultado(acumulado.ideas());
                              })
                          .flux();
              if (t.capitulo() == null) {
                return salida;
              }
              // El progreso se cuenta por documento largo, como su cobertura: «capitulo k de n»
              // de ESE documento. La tanda de los cortos no lo mueve.
              List<Tanda> delDocumento =
                  tandas.stream()
                      .filter(o -> o.capitulo() != null && o.documentoId() == t.documentoId())
                      .toList();
              return salida.concatWith(
                  Mono.fromSupplier(
                      () ->
                          new CapituloLeido(
                              t.documentoId(),
                              delDocumento.indexOf(t) + 1,
                              delDocumento.size(),
                              t.capitulo(),
                              "")));
            })
        .subscribeOn(Schedulers.boundedElastic());
  }

  /** Los seis niveles sobre el mismo contexto: solo cambia la cola del prompt (cache del LLM). */
  private Flux<EventoAccion> preguntas(Tanda t, String idioma, Acumulado acumulado) {
    List<String> deLaTanda = new ArrayList<>();
    return Flux.fromArray(NivelBloom.values())
        .concatMap(
            nivel ->
                Mono.fromCallable(
                    () -> {
                      List<Pregunta> nuevas =
                          redactor.preguntar(t.contexto(), idioma, nivel, List.copyOf(deLaTanda));
                      nuevas.forEach(p -> deLaTanda.add(p.texto()));
                      acumulado.agregarPreguntas(t, nivel, nuevas);
                      return (EventoAccion) new Resultado(acumulado.preguntas());
                    }));
  }

  /**
   * Lo generado hasta ahora, en el orden de los niveles. Una pregunta o idea que ya salio en otra
   * tanda (mismo texto sin acentos ni puntuacion) no se repite: el modelo no ve las de las demas.
   */
  static final class Acumulado {
    private final Map<String, List<PreguntaUbicada>> porNivel = new LinkedHashMap<>();
    private final List<IdeaUbicada> ideas = new ArrayList<>();
    private final Set<String> vistas = new HashSet<>();

    Acumulado() {
      for (NivelBloom nivel : NivelBloom.values()) {
        porNivel.put(nivel.codigo(), null);
      }
    }

    synchronized void agregarPreguntas(Tanda t, NivelBloom nivel, List<Pregunta> nuevas) {
      List<PreguntaUbicada> lista = porNivel.get(nivel.codigo());
      if (lista == null) {
        lista = new ArrayList<>();
        porNivel.put(nivel.codigo(), lista);
      }
      for (Pregunta p : nuevas) {
        if (vistas.add(clave(p.texto()))) {
          lista.add(
              new PreguntaUbicada(
                  p.texto(), p.tipo(), t.fuente() > 0 ? t.fuente() : p.fuente(), t.capitulo()));
        }
      }
    }

    synchronized void agregarIdeas(Tanda t, Redactor.Ideas nuevas) {
      if (nuevas == null || nuevas.ideas() == null) {
        return;
      }
      for (Idea i : nuevas.ideas()) {
        if (i != null && i.titulo() != null && vistas.add(clave(i.titulo()))) {
          ideas.add(
              new IdeaUbicada(
                  i.titulo(),
                  i.justificacion(),
                  t.fuente() > 0 ? t.fuente() : i.fuente(),
                  t.capitulo()));
        }
      }
    }

    /** Solo los niveles que ya corrieron al menos una vez, como el flujo de siempre. */
    synchronized PreguntasUbicadas preguntas() {
      List<NivelUbicado> niveles = new ArrayList<>();
      porNivel.forEach(
          (codigo, lista) -> {
            if (lista != null) {
              niveles.add(new NivelUbicado(codigo, List.copyOf(lista)));
            }
          });
      return new PreguntasUbicadas(niveles);
    }

    synchronized IdeasUbicadas ideas() {
      return new IdeasUbicadas(List.copyOf(ideas));
    }

    static String clave(String texto) {
      String sinAcentos =
          Normalizer.normalize(texto == null ? "" : texto, Normalizer.Form.NFD)
              .replaceAll("\\p{M}", "");
      return sinAcentos.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
    }
  }
}
