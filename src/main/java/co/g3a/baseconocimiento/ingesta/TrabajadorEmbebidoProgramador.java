package co.g3a.baseconocimiento.ingesta;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * El disparador periódico del worker de ingesta.
 *
 * <p>Vive separado de {@link TrabajadorEmbebido} para que {@code @Transactional}
 * en {@code procesarUno()} funcione de verdad — ver el comentario en esa clase.
 *
 * <p>Se puede apagar con {@code kb.ingesta.worker.habilitado=false}: sirve para
 * pruebas de integración que no necesitan (ni pueden, si no hay Ollama) que la
 * cola se procese sola de fondo.
 */
@Component
@ConditionalOnProperty(prefix = "kb.ingesta.worker", name = "habilitado", matchIfMissing = true)
class TrabajadorEmbebidoProgramador {

    private static final int TAMANO_LOTE = 25;

    private final TrabajadorEmbebido trabajador;

    TrabajadorEmbebidoProgramador(TrabajadorEmbebido trabajador) {
        this.trabajador = trabajador;
    }

    @Scheduled(fixedDelayString = "${kb.ingesta.worker.intervalo-ms:2000}")
    void ejecutarLote() {
        for (int i = 0; i < TAMANO_LOTE; i++) {
            if (!trabajador.procesarUno()) {
                return;
            }
        }
    }
}
