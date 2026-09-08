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
 * Que entra al contexto del LLM cuando la persona elige varios documentos, y COMO entra un
 * documento que no cabe: entero, por pasadas (issue #38, sub-issue #60). El modelo local tiene un
 * contexto chico (4096 tokens con Bonsai) y un documento del vault puede tener mas de mil
 * secciones; nada de eso se recorta ni se esconde.
 *
 * <p>Reparto equitativo con redistribucion: cada documento recibe una parte igual de lo que queda
 * del presupuesto entre los que faltan, atendidos del mas corto al mas largo, asi lo que un
 * documento corto no usa pasa a los largos. Un documento que cabe en su cuota entra tal cual. Uno
 * que no, se lee por pasadas ANTES de la accion: sus secciones se parten en tramos de hasta {@code
 * maxCaracteresLectura}, cada tramo se condensa con el LLM en notas de hasta {@link #LARGO_NOTAS}
 * caracteres, y si las notas juntas todavia no caben en la cuota se agrupan de a {@link
 * #notasPorGrupo()} y se vuelven a condensar, hasta que quepan. Como los tramos y los grupos son
 * deterministas, el numero de pasadas se sabe de antemano y la cobertura lo declara. El bucle que
 * llama al LLM vive en {@code AccionesSobreDocumentos}; aqui solo esta la aritmetica.
 *
 * <p>Un id que no existe en el proyecto tampoco desaparece: queda al final con {@code 0/0}.
 *
 * <p>Sin Spring: es aritmetica sobre secciones, y asi se prueba.
 */
final class PresupuestoDeContexto {

  static final int LONGITUD_EXTRACTO = 240;

  /** Cuantos titulos lista la etiqueta antes de resumir el resto como «y N mas». */
  static final int TITULOS_EN_ETIQUETA = 3;

  /**
   * Tope de las notas de una pasada: lo que se le pide al modelo y a lo que se recortan si se pasa.
   * Fija el factor de compresion por nivel (un tramo de 10000 baja a 1200) y, con el, cuantas
   * pasadas hacen falta.
   */
  static final int LARGO_NOTAS = 1200;

  private final int maxCaracteres;
  private final int maxCaracteresLectura;

  /**
   * @param maxCaracteres presupuesto total de contexto para la accion, repartido entre documentos
   * @param maxCaracteresLectura cuanto texto entra en una pasada de lectura (una llamada al LLM que
   *     devuelve notas, no prosa larga: por eso puede ser mayor que el presupuesto)
   */
  PresupuestoDeContexto(int maxCaracteres, int maxCaracteresLectura) {
    this.maxCaracteres = maxCaracteres;
    this.maxCaracteresLectura = maxCaracteresLectura;
  }

  /**
   * Un documento elegido y como entra. {@code tramos} vacio = entra tal cual (cabe en su cuota);
   * con tramos, se lee por pasadas y {@code pasadas} es cuantas llamadas al LLM son en total,
   * contando todos los niveles. {@code secciones} vacia = un id que no existe en el proyecto (o no
   * tiene secciones indexadas todavia).
   */
  record DocumentoPlanificado(
      long documentoId,
      String titulo,
      String uri,
      List<Seccion> secciones,
      int cuota,
      List<String> tramos,
      int pasadas) {

    boolean indexado() {
      return !secciones.isEmpty();
    }

    boolean porPasadas() {
      return !tramos.isEmpty();
    }
  }

  /**
   * @param pedidos IDs en el orden en que la persona los eligio; los duplicados se ignoran
   * @param secciones lo que trajo {@code SeccionesRepositorio} para esos IDs
   * @return los documentos indexados en el orden pedido, con su plan, y despues los que no existen
   *     con {@code 0/0}
   */
  List<DocumentoPlanificado> planificar(List<Long> pedidos, List<Seccion> secciones) {
    List<Long> unicos = new ArrayList<>(new LinkedHashSet<>(pedidos));
    Map<Long, List<Seccion>> porDocumento = new LinkedHashMap<>();
    for (Seccion s : secciones) {
      porDocumento.computeIfAbsent(s.documentoId(), id -> new ArrayList<>()).add(s);
    }
    List<Long> indexados = unicos.stream().filter(porDocumento::containsKey).toList();
    List<Long> inexistentes = unicos.stream().filter(id -> !porDocumento.containsKey(id)).toList();

    Map<Long, Integer> cuotas = repartir(indexados, porDocumento);

    List<DocumentoPlanificado> resultado = new ArrayList<>();
    for (Long id : indexados) {
      resultado.add(planificarDocumento(id, porDocumento.get(id), cuotas.get(id)));
    }
    for (Long id : inexistentes) {
      resultado.add(new DocumentoPlanificado(id, "#" + id, null, List.of(), 0, List.of(), 0));
    }
    return resultado;
  }

  /**
   * Cuantas notas (de hasta {@link #LARGO_NOTAS}) caben juntas en una pasada de lectura. Nunca
   * menos de dos: con una sola por grupo un nivel no reduciria nada y las pasadas no terminarian.
   */
  int notasPorGrupo() {
    return Math.max(2, maxCaracteresLectura / (LARGO_NOTAS + 2));
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

  private DocumentoPlanificado planificarDocumento(Long id, List<Seccion> propias, int cuota) {
    Seccion primera = propias.get(0);
    int total = propias.stream().mapToInt(s -> largo(s.texto())).sum();
    if (total <= cuota) {
      return new DocumentoPlanificado(
          id, tituloDe(primera), primera.uri(), propias, cuota, List.of(), 0);
    }
    List<String> tramos = tramos(propias, maxCaracteresLectura);
    return new DocumentoPlanificado(
        id,
        tituloDe(primera),
        primera.uri(),
        propias,
        cuota,
        tramos,
        pasadasEstimadas(tramos.size(), cuota, notasPorGrupo()));
  }

  /**
   * Cuantas llamadas al LLM lleva leer un documento de {@code tramos} tramos hasta que sus notas
   * quepan en {@code cuota}: cada nivel condensa {@code porGrupo} notas en una. Es una cota
   * superior exacta si cada nota mide {@link #LARGO_NOTAS}; si salen mas cortas, un nivel puede
   * caber antes y sobran pasadas que no se hacen.
   */
  static int pasadasEstimadas(int tramos, int cuota, int porGrupo) {
    int total = 0;
    int n = tramos;
    while (n > 0) {
      total += n;
      if (n == 1 || (long) n * LARGO_NOTAS <= cuota) {
        break;
      }
      n = (n + porGrupo - 1) / porGrupo;
    }
    return total;
  }

  /**
   * Las secciones agrupadas en tramos de hasta {@code largo} caracteres, en orden; una seccion mas
   * larga que un tramo se parte por parrafos.
   */
  static List<String> tramos(List<Seccion> secciones, int largo) {
    List<String> piezas = new ArrayList<>();
    for (Seccion s : secciones) {
      String texto = s.texto() == null ? "" : s.texto();
      if (texto.length() <= largo) {
        piezas.add(texto);
      } else {
        piezas.addAll(partirTexto(texto, largo));
      }
    }
    return agrupar(piezas, largo);
  }

  /** Un texto en piezas de hasta {@code largo}, por parrafos; un parrafo mas largo se corta. */
  static List<String> partirTexto(String texto, int largo) {
    List<String> piezas = new ArrayList<>();
    for (String parrafo : texto.split("\\n\\s*\\n")) {
      if (parrafo.isBlank()) {
        continue;
      }
      for (int i = 0; i < parrafo.length(); i += largo) {
        piezas.add(parrafo.substring(i, Math.min(parrafo.length(), i + largo)));
      }
    }
    return agrupar(piezas, largo);
  }

  /** Piezas consecutivas juntas, separadas por una linea en blanco, mientras quepan en largo. */
  static List<String> agrupar(List<String> piezas, int largo) {
    List<String> grupos = new ArrayList<>();
    StringBuilder actual = new StringBuilder();
    for (String pieza : piezas) {
      if (pieza.isEmpty()) {
        continue;
      }
      if (!actual.isEmpty() && actual.length() + 2 + pieza.length() > largo) {
        grupos.add(actual.toString());
        actual.setLength(0);
      }
      if (!actual.isEmpty()) {
        actual.append("\n\n");
      }
      actual.append(pieza);
    }
    if (!actual.isEmpty()) {
      grupos.add(actual.toString());
    }
    return grupos;
  }

  /**
   * Un bloque por documento indexado, con su marcador {@code [n]} (el mismo numero que la cita
   * {@code n}): el texto de sus secciones, o, si se leyo por pasadas, sus notas de lectura con la
   * linea que lo dice.
   *
   * @param notas las notas finales por id de documento, de los que se leyeron por pasadas
   */
  static String contexto(List<DocumentoPlanificado> documentos, Map<Long, String> notas) {
    StringBuilder contexto = new StringBuilder();
    int n = 0;
    for (DocumentoPlanificado d : documentos) {
      if (!d.indexado()) {
        continue;
      }
      n++;
      contexto.append("[%d] %s (%s)\n".formatted(n, d.titulo(), d.uri()));
      if (d.porPasadas()) {
        contexto.append(
            "(Documento largo, leido completo por pasadas: lo que sigue son las notas de lectura,"
                + " no el texto original)\n");
        contexto.append(notas.getOrDefault(d.documentoId(), "")).append('\n');
      } else {
        for (Seccion s : d.secciones()) {
          contexto.append(s.texto()).append('\n');
        }
      }
      contexto.append('\n');
    }
    return contexto.toString();
  }

  static List<CoberturaDocumento> cobertura(List<DocumentoPlanificado> documentos) {
    return documentos.stream()
        .map(
            d ->
                new CoberturaDocumento(
                    d.documentoId(), d.titulo(), d.uri(), d.secciones().size(), d.pasadas()))
        .toList();
  }

  /** Una cita por documento indexado, en el mismo orden que los {@code [n]} del contexto. */
  static List<Cita> citas(List<DocumentoPlanificado> documentos) {
    return documentos.stream()
        .filter(DocumentoPlanificado::indexado)
        .map(
            d -> {
              Seccion primera = d.secciones().get(0);
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
  static String etiqueta(String verbo, List<DocumentoPlanificado> documentos) {
    List<DocumentoPlanificado> indexados =
        documentos.stream().filter(DocumentoPlanificado::indexado).toList();
    if (indexados.isEmpty()) {
      return verbo + " documentos seleccionados";
    }
    String titulos =
        indexados.stream()
            .limit(TITULOS_EN_ETIQUETA)
            .map(DocumentoPlanificado::titulo)
            .collect(Collectors.joining(", "));
    int restantes = indexados.size() - TITULOS_EN_ETIQUETA;
    if (restantes > 0) {
      titulos += " y %d más".formatted(restantes);
    }
    return "%s %d documento%s: %s"
        .formatted(verbo, indexados.size(), indexados.size() == 1 ? "" : "s", titulos);
  }

  private static String tituloDe(Seccion s) {
    return (s.titulo() == null || s.titulo().isBlank()) ? s.uri() : s.titulo();
  }

  private static int largo(String texto) {
    return texto == null ? 0 : texto.length();
  }
}
