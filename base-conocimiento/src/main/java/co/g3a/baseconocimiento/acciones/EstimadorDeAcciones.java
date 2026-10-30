package co.g3a.baseconocimiento.acciones;

import co.g3a.baseconocimiento.acciones.Acciones.Estimado;
import co.g3a.baseconocimiento.acciones.Acciones.EstimadoModo;
import co.g3a.baseconocimiento.acciones.Acciones.Modo;
import co.g3a.baseconocimiento.acciones.Acciones.Tipo;
import co.g3a.baseconocimiento.acciones.Capitulos.Capitulo;
import co.g3a.baseconocimiento.acciones.PresupuestoDeContexto.DocumentoPlanificado;
import co.g3a.baseconocimiento.acciones.SeccionesRepositorio.Seccion;
import co.g3a.baseconocimiento.compartido.Dominio.ProyectoId;
import co.g3a.baseconocimiento.llm.Redactor.NivelBloom;
import co.g3a.baseconocimiento.llm.RendimientoDelModelo;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Cuanto tardaria una accion en cada modo, antes de empezarla (issue #57). Medido con jls25.pdf y
 * Granite en la T600, «Resumir» declaraba 212 pasadas de ~3 min: unas 11 h sin aviso. El estimado
 * usa el mismo plan que la accion ({@link PresupuestoDeContexto}) y el costo por llamada que el
 * modelo viene midiendo ({@link RendimientoDelModelo}); no llama al LLM.
 */
@Component
class EstimadorDeAcciones {

  /** Tokens de notas que se piden por capitulo. */
  static final int SALIDA_CAPITULO = 250;

  /** Tokens de salida de la llamada final: prosa, ideas y cada nivel de preguntas. */
  static final int SALIDA_PROSA = 700;

  static final int SALIDA_IDEAS = 500;
  static final int SALIDA_NIVEL_PREGUNTAS = 250;

  private final SeccionesRepositorio repo;
  private final AccionesPropiedades propiedades;
  private final RendimientoDelModelo rendimiento;
  private final long umbralSegundos;

  EstimadorDeAcciones(
      SeccionesRepositorio repo,
      AccionesPropiedades propiedades,
      RendimientoDelModelo rendimiento,
      @Value("${kb.acciones.umbral-eleccion-segundos:600}") long umbralSegundos) {
    this.repo = repo;
    this.propiedades = propiedades;
    this.rendimiento = rendimiento;
    this.umbralSegundos = umbralSegundos;
  }

  Estimado estimar(Tipo tipo, List<Long> documentos, ProyectoId proyecto) {
    List<Long> unicos = new ArrayList<>(new LinkedHashSet<>(documentos));
    PresupuestoDeContexto presupuesto = PresupuestoDeContexto.para(propiedades, rendimiento);
    List<DocumentoPlanificado> largos =
        presupuesto.planificar(unicos, repo.seccionesDe(unicos, proyecto.valor())).stream()
            .filter(DocumentoPlanificado::porPasadas)
            .toList();

    double finalSegundos = segundosFinales(tipo);
    int finalLlamadas = tipo == Tipo.PREGUNTAS ? NivelBloom.values().length : 1;
    int totales = largos.stream().mapToInt(d -> d.secciones().size()).sum();
    int pasadas = largos.stream().mapToInt(DocumentoPlanificado::pasadas).sum();
    double porPasada =
        rendimiento.segundosEstimados(
            presupuesto.caracteresPorPasada(),
            (int) (PresupuestoDeContexto.LARGO_NOTAS / rendimiento.caracteresPorToken()));
    EstimadoModo completo =
        modo(
            Modo.COMPLETO,
            pasadas * porPasada + finalSegundos,
            totales,
            totales,
            pasadas + finalLlamadas);
    if (largos.isEmpty()) {
      return new Estimado(false, List.of(completo));
    }

    Map<Long, List<String>> rutas =
        repo.rutasDe(
            largos.stream().map(DocumentoPlanificado::documentoId).toList(), proyecto.valor());
    int largoExtracto = Capitulos.largoExtracto(presupuesto.caracteresPorPasada());
    double segundosCapitulos = finalSegundos;
    int cubiertasCapitulos = 0;
    int capitulos = 0;
    for (DocumentoPlanificado d : largos) {
      for (Capitulo c : Capitulos.agrupar(d.secciones(), rutas)) {
        int entrada = Math.min(largoExtracto, largo(c.secciones()));
        segundosCapitulos += rendimiento.segundosEstimados(entrada, SALIDA_CAPITULO);
        cubiertasCapitulos +=
            Math.min(c.secciones().size(), Math.max(1, entrada / Capitulos.MINIMO_POR_SECCION));
        capitulos++;
      }
    }
    int cubiertasRapido =
        largos.stream()
            .mapToInt(d -> Math.min(d.secciones().size(), Representativas.cuantas(d.cuota())))
            .sum();

    return new Estimado(
        completo.segundos() > umbralSegundos,
        List.of(
            completo,
            modo(
                Modo.POR_CAPITULOS,
                segundosCapitulos,
                cubiertasCapitulos,
                totales,
                capitulos + finalLlamadas),
            modo(Modo.RAPIDO, finalSegundos, cubiertasRapido, totales, finalLlamadas)));
  }

  /** La llamada final ve el contexto lleno; preguntas son un nivel de Bloom por llamada. */
  private double segundosFinales(Tipo tipo) {
    int contexto = propiedades.maxCaracteresContexto();
    return switch (tipo) {
      case RESUMIR, SINTETIZAR -> rendimiento.segundosEstimados(contexto, SALIDA_PROSA);
      case IDEAS -> rendimiento.segundosEstimados(contexto, SALIDA_IDEAS);
      case PREGUNTAS ->
          NivelBloom.values().length
              * rendimiento.segundosEstimados(contexto, SALIDA_NIVEL_PREGUNTAS);
    };
  }

  private static EstimadoModo modo(
      Modo modo, double segundos, int cubiertas, int totales, int llamadas) {
    return new EstimadoModo(modo, Math.round(segundos), cubiertas, totales, llamadas);
  }

  private static int largo(List<Seccion> secciones) {
    return secciones.stream().mapToInt(s -> s.texto() == null ? 0 : s.texto().length()).sum();
  }
}
