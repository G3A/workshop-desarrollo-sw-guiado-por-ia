package co.g3a.baseconocimiento.ingesta;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * El disparador periódico de F8: cierra la promesa de "pon los archivos en una carpeta y olvídate"
 * (ver la sección homónima del plan). Antes de esta clase, la ingesta solo corría cuando alguien
 * pegaba a {@code /api/ingest/*} a mano ({@code make ingest}).
 *
 * <p>Apagable con {@code kb.ingesta.relevo.habilitado=false} — mismo patrón que {@code
 * kb.ingesta.worker.habilitado} de F1.
 */
@Component
@ConditionalOnProperty(prefix = "kb.ingesta.relevo", name = "habilitado", matchIfMissing = true)
class RelevadorDeFuentesProgramador {

  private final RelevadorDeFuentes relevador;

  RelevadorDeFuentesProgramador(RelevadorDeFuentes relevador) {
    this.relevador = relevador;
  }

  /**
   * {@code initialDelayString} igual al intervalo, a propósito: sin él, {@code @Scheduled} corre la
   * primera vez apenas arranca el contexto de Spring, lo que en las pruebas de integración de los
   * conectores (`@SpringBootTest`, sin este bean deshabilitado) corría en paralelo con la
   * invocación manual del propio test sobre el mismo conector — dos ingestas concurrentes
   * escribiendo los mismos documentos, y un {@code duplicate key value violates chunks_orden_unico}
   * real, visto en la propia suite. Esperar un intervalo completo antes del primer relevo también
   * es lo correcto para el producto: recién booteado no hay nada nuevo que revisar todavía.
   */
  @Scheduled(
      initialDelayString = "${kb.ingesta.relevo.intervalo-ms:900000}",
      fixedDelayString = "${kb.ingesta.relevo.intervalo-ms:900000}")
  void relevar() {
    relevador.relevarTodas();
  }
}
