package co.g3a.baseconocimiento.acciones;

import co.g3a.baseconocimiento.acciones.Acciones.ResultadoEnStreaming;
import co.g3a.baseconocimiento.acciones.Acciones.ResultadoEstructurado;
import co.g3a.baseconocimiento.acciones.Acciones.SinResultado;
import co.g3a.baseconocimiento.acciones.Acciones.Tipo;
import co.g3a.baseconocimiento.acciones.PresupuestoDeContexto.DocumentoRecortado;
import co.g3a.baseconocimiento.acciones.SeccionesRepositorio.Seccion;
import co.g3a.baseconocimiento.compartido.Dominio.ProyectoId;
import co.g3a.baseconocimiento.llm.Redactor;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Las cuatro acciones que redactan a partir del contexto: resumir y sintetizar (prosa en
 * streaming), preguntas e ideas (salida estructurada, decision 11 del issue #38). De las siete
 * etapas del RAG sobreviven dos: armar el contexto y llamar al LLM. No hay planner, retrieval,
 * umbral de relevancia ni registro: la seleccion de la persona es toda la evidencia.
 *
 * <p>El orden es fijo y por eso el cupo no se filtra (hallazgo 2 de la revision adversarial):
 * cargar y recortar primero, tomar el cupo despues, y construir el Flux dentro de un {@code try}
 * que lo devuelve si el {@link Redactor} lanza antes de que exista un {@code doFinally} que lo
 * haga.
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

  private final SeccionesRepositorio repo;
  private final Redactor redactor;
  private final CupoDeAcciones cupo;
  private final PresupuestoDeContexto presupuesto;

  AccionesSobreDocumentos(
      SeccionesRepositorio repo,
      Redactor redactor,
      CupoDeAcciones cupo,
      AccionesPropiedades propiedades) {
    this.repo = repo;
    this.redactor = redactor;
    this.cupo = cupo;
    this.presupuesto = new PresupuestoDeContexto(propiedades.maxCaracteresContexto());
  }

  /** Lo que las cuatro acciones comparten antes de llamar al LLM. */
  record Preparacion(List<DocumentoRecortado> documentos, String contexto, boolean hayIndexados) {}

  ResultadoEnStreaming redactar(
      Tipo tipo, List<Long> documentos, ProyectoId proyecto, String idioma) {
    if (tipo != Tipo.RESUMIR && tipo != Tipo.SINTETIZAR) {
      throw new IllegalArgumentException(
          "redactar solo admite RESUMIR o SINTETIZAR; " + tipo + " es estructurado");
    }
    Preparacion pre = preparar(documentos, proyecto);
    String etiqueta = PresupuestoDeContexto.etiqueta(verboDe(tipo), pre.documentos());

    Flux<String> texto;
    if (!pre.hayIndexados()) {
      texto = Flux.just(MENSAJE_SIN_DOCUMENTOS);
    } else if (!cupo.intentarTomar()) {
      texto = Flux.just(MENSAJE_SERVIDOR_OCUPADO);
    } else {
      texto = conCupo(tipo, pre.contexto(), idioma);
    }
    return new ResultadoEnStreaming(
        etiqueta,
        PresupuestoDeContexto.cobertura(pre.documentos()),
        PresupuestoDeContexto.citas(pre.documentos()),
        texto);
  }

  ResultadoEstructurado estructurar(
      Tipo tipo, List<Long> documentos, ProyectoId proyecto, String idioma) {
    if (tipo != Tipo.PREGUNTAS && tipo != Tipo.IDEAS) {
      throw new IllegalArgumentException(
          "estructurar solo admite PREGUNTAS o IDEAS; " + tipo + " es prosa en streaming");
    }
    Preparacion pre = preparar(documentos, proyecto);
    String etiqueta = PresupuestoDeContexto.etiqueta(verboDe(tipo), pre.documentos());

    Mono<Object> resultado;
    if (!pre.hayIndexados()) {
      resultado = Mono.just(new SinResultado(MENSAJE_SIN_DOCUMENTOS));
    } else if (!cupo.intentarTomar()) {
      resultado = Mono.just(new SinResultado(MENSAJE_SERVIDOR_OCUPADO));
    } else {
      // El cupo ya esta tomado: el fromCallable corre recien al suscribirse, y el
      // doFinally lo devuelve haya emitido, fallado o sido cancelado.
      resultado =
          Mono.<Object>fromCallable(
                  () ->
                      tipo == Tipo.PREGUNTAS
                          ? redactor.preguntar(pre.contexto(), idioma)
                          : redactor.idear(pre.contexto(), idioma))
              .doFinally(signal -> cupo.liberar());
    }
    return new ResultadoEstructurado(
        etiqueta,
        PresupuestoDeContexto.cobertura(pre.documentos()),
        PresupuestoDeContexto.citas(pre.documentos()),
        resultado);
  }

  private Preparacion preparar(List<Long> documentos, ProyectoId proyecto) {
    List<Long> unicos = new ArrayList<>(new LinkedHashSet<>(documentos));
    List<Seccion> secciones = repo.seccionesDe(unicos, proyecto.valor());
    List<DocumentoRecortado> recortados = presupuesto.recortar(unicos, secciones);
    boolean hayIndexados = recortados.stream().anyMatch(DocumentoRecortado::indexado);
    return new Preparacion(recortados, PresupuestoDeContexto.contexto(recortados), hayIndexados);
  }

  /** Solo con el cupo ya tomado: lo devuelve pase lo que pase, incluso si el Redactor lanza. */
  private Flux<String> conCupo(Tipo tipo, String contexto, String idioma) {
    Flux<String> base;
    try {
      base =
          tipo == Tipo.RESUMIR
              ? redactor.resumir(contexto, idioma)
              : redactor.sintetizar(contexto, idioma);
    } catch (RuntimeException e) {
      // Antes de que exista un Flux con doFinally: el permiso vuelve aqui, y el error
      // viaja por el Flux para que el adaptador lo convierta en un evento legible.
      cupo.liberar();
      return Flux.error(e);
    }
    return base.doFinally(signal -> cupo.liberar());
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
