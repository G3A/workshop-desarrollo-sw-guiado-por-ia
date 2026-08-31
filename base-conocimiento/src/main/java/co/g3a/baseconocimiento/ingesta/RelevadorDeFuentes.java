package co.g3a.baseconocimiento.ingesta;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Corre los cuatro conectores bajo un candado por tipo de fuente, para que el relevo periódico
 * ({@link RelevadorDeFuentesProgramador}, F8) y el botón "reindexar ahora" de la consola de
 * administración (F9) no puedan pisarse: dos invocaciones concurrentes del mismo conector listarían
 * y escribirían sobre los mismos documentos a la vez.
 *
 * <p>El candado es en memoria, no en la base — correcto porque este proyecto corre un único
 * contenedor {@code api} (ver {@code compose.yml}); no hay segunda instancia con la que
 * coordinarse.
 *
 * <p>Por qué no hay un relevo por cada fila de {@code sources} en vez de por tipo de conector:
 * {@code local_git} descubre sus repos recorriendo {@code ./repos} en cada corrida (una fila de
 * {@code sources} por subcarpeta, número que no se conoce de antemano) y {@code azure_devops} crea
 * dos fuentes fijas (work items y wiki) en la misma llamada — ninguno de los dos conectores tiene
 * una forma de sincronizar "solo esta fila ya conocida". El conector entero es la unidad de trabajo
 * real; {@code sources.refresh_seconds} queda como metadato informativo para la consola de F9, no
 * como el disparador de una fila individual.
 */
@Component
class RelevadorDeFuentes {

  private static final Logger log = LoggerFactory.getLogger(RelevadorDeFuentes.class);

  static final String LOCAL_DOCS = "local_docs";
  static final String LOCAL_GIT = "local_git";
  static final String TEAMS_CHANNEL = "teams_channel";
  static final String AZURE_DEVOPS = "azure_devops";
  static final List<String> TIPOS_CONOCIDOS =
      List.of(LOCAL_DOCS, LOCAL_GIT, TEAMS_CHANNEL, AZURE_DEVOPS);

  private final ConectorDocumentosLocales documentos;
  private final ConectorReposLocales repos;
  private final ConectorTeamsGraph teams;
  private final ConectorAzureDevOps azdo;
  private final Map<String, ReentrantLock> candados = new ConcurrentHashMap<>();

  /**
   * Último resultado por tipo, en memoria: alimenta la consola de administración de F9 ("resultado
   * del último relevo"). No se persiste a propósito — igual que el candado, correcto porque hay un
   * único contenedor {@code api}; un reinicio simplemente vuelve a "sin datos todavía" hasta el
   * próximo relevo, que no tarda más que el intervalo configurado.
   */
  private final Map<String, ResultadoRelevo> ultimoPorTipo = new ConcurrentHashMap<>();

  RelevadorDeFuentes(
      ConectorDocumentosLocales documentos,
      ConectorReposLocales repos,
      ConectorTeamsGraph teams,
      ConectorAzureDevOps azdo) {
    this.documentos = documentos;
    this.repos = repos;
    this.teams = teams;
    this.azdo = azdo;
  }

  record ResultadoRelevo(String tipo, boolean ejecutado, Object resumen, String error) {}

  List<ResultadoRelevo> relevarTodas() {
    return TIPOS_CONOCIDOS.stream().map(this::relevar).toList();
  }

  /**
   * @return {@code ejecutado = false} y un {@code error} explicativo si otro relevo del mismo tipo
   *     ya estaba en curso o el tipo no existe; no lanza excepción en ninguno de los dos casos, ni
   *     si el propio conector falla — {@code error} lo describe siempre.
   */
  ResultadoRelevo relevar(String tipo) {
    ReentrantLock candado = candados.computeIfAbsent(tipo, t -> new ReentrantLock());
    if (!candado.tryLock()) {
      return new ResultadoRelevo(tipo, false, null, "ya hay un relevo de esta fuente en curso");
    }
    try {
      Object resumen = ejecutar(tipo);
      ResultadoRelevo resultado = new ResultadoRelevo(tipo, true, resumen, null);
      ultimoPorTipo.put(tipo, resultado);
      return resultado;
    } catch (Exception e) {
      log.warn("Relevo de {} fallo: {}", tipo, e.getMessage());
      ResultadoRelevo resultado = new ResultadoRelevo(tipo, false, null, e.getMessage());
      ultimoPorTipo.put(tipo, resultado);
      return resultado;
    } finally {
      candado.unlock();
    }
  }

  /** {@code Optional.empty()} si esta fuente todavia no se releva desde que arranco el proceso. */
  Optional<ResultadoRelevo> ultimoResultado(String tipo) {
    return Optional.ofNullable(ultimoPorTipo.get(tipo));
  }

  private Object ejecutar(String tipo) {
    return switch (tipo) {
      case LOCAL_DOCS -> documentos.ingerir();
      case LOCAL_GIT -> repos.ingerir();
      case TEAMS_CHANNEL -> teams.ingerir();
      case AZURE_DEVOPS -> azdo.ingerir();
      default -> throw new IllegalArgumentException("Tipo de fuente desconocido: " + tipo);
    };
  }
}
