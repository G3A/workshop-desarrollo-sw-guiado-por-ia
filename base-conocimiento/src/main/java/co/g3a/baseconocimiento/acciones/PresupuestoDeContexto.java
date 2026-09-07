package co.g3a.baseconocimiento.acciones;

import co.g3a.baseconocimiento.acciones.Acciones.CoberturaDocumento;
import co.g3a.baseconocimiento.acciones.SeccionesRepositorio.Seccion;
import co.g3a.baseconocimiento.compartido.Dominio.Cita;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Que entra al contexto del LLM cuando la persona elige varios documentos, y que se declara cuando
 * no entra todo. Es la decision central del modulo (issue #38): el modelo local tiene un contexto
 * chico (4096 tokens con Bonsai) y un documento del vault puede tener cientos de secciones.
 *
 * <p>Reparto equitativo con redistribucion: cada documento recibe una parte igual de lo que queda
 * del presupuesto entre los que faltan, atendidos del mas corto al mas largo, asi lo que un
 * documento corto no usa pasa a los largos. Cada documento entra en orden de {@code ord} hasta
 * agotar su cuota; si ni su primera seccion cabe, entra el comienzo de esa seccion, recortado a la
 * cuota. El texto de secciones nunca supera el presupuesto: solo las cabeceras {@code [n]} y los
 * avisos de recorte se suman a el, y son cortos.
 *
 * <p>Lo que no entra se declara dos veces: en el contexto (una linea que el prompt del {@code
 * Redactor} exige reflejar en la prosa) y en la {@link CoberturaDocumento} que ve la UI. Un id que
 * no existe en el proyecto tampoco desaparece: queda al final con {@code 0/0}.
 *
 * <p>Sin Spring: es aritmetica sobre secciones, y asi se prueba.
 */
final class PresupuestoDeContexto {

  static final int LONGITUD_EXTRACTO = 240;

  /** Cuantos titulos lista la etiqueta antes de resumir el resto como «y N mas». */
  static final int TITULOS_EN_ETIQUETA = 3;

  private final int maxCaracteres;

  PresupuestoDeContexto(int maxCaracteres) {
    this.maxCaracteres = maxCaracteres;
  }

  /**
   * Un documento elegido con lo que de el entra al contexto. {@code seccionesTotales == 0} es un id
   * que no existe en el proyecto (o no tiene secciones indexadas todavia).
   */
  record DocumentoRecortado(
      long documentoId,
      String titulo,
      String uri,
      List<Seccion> incluidas,
      int seccionesTotales,
      boolean primeraRecortada) {

    boolean indexado() {
      return seccionesTotales > 0;
    }
  }

  /**
   * @param pedidos IDs en el orden en que la persona los eligio; los duplicados se ignoran
   * @param secciones lo que trajo {@code SeccionesRepositorio} para esos IDs
   * @return los documentos indexados en el orden pedido, recortados al presupuesto, y despues los
   *     que no existen con {@code 0/0}
   */
  List<DocumentoRecortado> recortar(List<Long> pedidos, List<Seccion> secciones) {
    List<Long> unicos = new ArrayList<>(new LinkedHashSet<>(pedidos));
    Map<Long, List<Seccion>> porDocumento = new LinkedHashMap<>();
    for (Seccion s : secciones) {
      porDocumento.computeIfAbsent(s.documentoId(), id -> new ArrayList<>()).add(s);
    }
    List<Long> indexados = unicos.stream().filter(porDocumento::containsKey).toList();
    List<Long> inexistentes = unicos.stream().filter(id -> !porDocumento.containsKey(id)).toList();

    Map<Long, Integer> cuotas = repartir(indexados, porDocumento);

    List<DocumentoRecortado> resultado = new ArrayList<>();
    for (Long id : indexados) {
      resultado.add(recortarDocumento(id, porDocumento.get(id), cuotas.get(id)));
    }
    for (Long id : inexistentes) {
      resultado.add(new DocumentoRecortado(id, "#" + id, null, List.of(), 0, false));
    }
    return resultado;
  }

  /**
   * Del mas corto al mas largo, cada uno toma como maximo una parte igual de lo que queda entre los
   * que faltan; una sola asignacion por documento, asi termina siempre.
   */
  private Map<Long, Integer> repartir(List<Long> indexados, Map<Long, List<Seccion>> porDocumento) {
    Map<Long, Integer> totales = new HashMap<>();
    for (Long id : indexados) {
      totales.put(id, porDocumento.get(id).stream().mapToInt(s -> largo(s.texto())).sum());
    }
    List<Long> porLargo = new ArrayList<>(indexados);
    porLargo.sort(Comparator.comparingInt(totales::get));

    Map<Long, Integer> cuotas = new HashMap<>();
    int restante = maxCaracteres;
    for (int i = 0; i < porLargo.size(); i++) {
      Long id = porLargo.get(i);
      int parteJusta = restante / (porLargo.size() - i);
      int asignado = Math.min(totales.get(id), parteJusta);
      cuotas.put(id, asignado);
      restante -= asignado;
    }
    return cuotas;
  }

  private static DocumentoRecortado recortarDocumento(Long id, List<Seccion> propias, int cuota) {
    Seccion primera = propias.get(0);
    List<Seccion> incluidas = new ArrayList<>();
    boolean primeraRecortada = false;
    int usado = 0;
    for (Seccion seccion : propias) {
      int largo = largo(seccion.texto());
      if (incluidas.isEmpty()) {
        if (largo > cuota) {
          incluidas.add(recortar(seccion, cuota));
          primeraRecortada = true;
          break;
        }
        incluidas.add(seccion);
        usado = largo;
        continue;
      }
      if (usado + largo > cuota) {
        break;
      }
      incluidas.add(seccion);
      usado += largo;
    }
    return new DocumentoRecortado(
        id, tituloDe(primera), primera.uri(), incluidas, propias.size(), primeraRecortada);
  }

  /**
   * Un bloque por documento indexado, con su marcador {@code [n]} (el mismo numero que la cita
   * {@code n}) y, si entro recortado, la linea que lo dice.
   */
  static String contexto(List<DocumentoRecortado> documentos) {
    StringBuilder contexto = new StringBuilder();
    int n = 0;
    for (DocumentoRecortado d : documentos) {
      if (!d.indexado()) {
        continue;
      }
      n++;
      contexto.append("[%d] %s (%s)\n".formatted(n, d.titulo(), d.uri()));
      if (d.primeraRecortada()) {
        contexto.append(
            "(Documento recortado: entra solo el comienzo de la seccion 1 de %d)\n"
                .formatted(d.seccionesTotales()));
      } else if (d.incluidas().size() < d.seccionesTotales()) {
        contexto.append(
            "(Documento recortado: se incluyen solo las primeras %d de %d secciones)\n"
                .formatted(d.incluidas().size(), d.seccionesTotales()));
      }
      for (Seccion s : d.incluidas()) {
        contexto.append(s.texto()).append('\n');
      }
      contexto.append('\n');
    }
    return contexto.toString();
  }

  static List<CoberturaDocumento> cobertura(List<DocumentoRecortado> documentos) {
    return documentos.stream()
        .map(
            d ->
                new CoberturaDocumento(
                    d.documentoId(),
                    d.titulo(),
                    d.uri(),
                    d.incluidas().size(),
                    d.seccionesTotales(),
                    d.primeraRecortada()))
        .toList();
  }

  /** Una cita por documento indexado, en el mismo orden que los {@code [n]} del contexto. */
  static List<Cita> citas(List<DocumentoRecortado> documentos) {
    return documentos.stream()
        .filter(DocumentoRecortado::indexado)
        .map(
            d -> {
              Seccion primera = d.incluidas().get(0);
              String texto = primera.texto() == null ? "" : primera.texto();
              String extracto =
                  texto.length() > LONGITUD_EXTRACTO
                      ? texto.substring(0, LONGITUD_EXTRACTO) + "…"
                      : texto;
              return new Cita(d.uri(), d.titulo(), extracto, primera.tipo());
            })
        .toList();
  }

  /**
   * «Resumen de 3 documentos: a, b, c», o «y N mas» pasados los {@link #TITULOS_EN_ETIQUETA}
   * primeros. Cuenta solo los indexados; sin ninguno, una etiqueta generica.
   *
   * @param verbo el comienzo segun la accion: «Resumen de», «Sintesis de», «Preguntas sobre»,
   *     «Ideas a partir de»
   */
  static String etiqueta(String verbo, List<DocumentoRecortado> documentos) {
    List<DocumentoRecortado> indexados =
        documentos.stream().filter(DocumentoRecortado::indexado).toList();
    if (indexados.isEmpty()) {
      return verbo + " documentos seleccionados";
    }
    String titulos =
        indexados.stream()
            .limit(TITULOS_EN_ETIQUETA)
            .map(DocumentoRecortado::titulo)
            .collect(Collectors.joining(", "));
    int restantes = indexados.size() - TITULOS_EN_ETIQUETA;
    if (restantes > 0) {
      titulos += " y %d más".formatted(restantes);
    }
    return "%s %d documento%s: %s"
        .formatted(verbo, indexados.size(), indexados.size() == 1 ? "" : "s", titulos);
  }

  private static Seccion recortar(Seccion seccion, int largo) {
    String texto = seccion.texto() == null ? "" : seccion.texto();
    return new Seccion(
        seccion.id(),
        seccion.documentoId(),
        seccion.uri(),
        seccion.titulo(),
        seccion.ord(),
        texto.substring(0, Math.max(0, Math.min(largo, texto.length()))) + "…",
        seccion.tipo());
  }

  private static String tituloDe(Seccion s) {
    return (s.titulo() == null || s.titulo().isBlank()) ? s.uri() : s.titulo();
  }

  private static int largo(String texto) {
    return texto == null ? 0 : texto.length();
  }
}
