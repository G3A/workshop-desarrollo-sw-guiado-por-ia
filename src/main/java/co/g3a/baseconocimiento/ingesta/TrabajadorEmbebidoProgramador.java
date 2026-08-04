package co.g3a.baseconocimiento.ingesta;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(TrabajadorEmbebidoProgramador.class);
    private static final int TAMANO_LOTE = 25;

    private final TrabajadorEmbebido trabajador;

    TrabajadorEmbebidoProgramador(TrabajadorEmbebido trabajador) {
        this.trabajador = trabajador;
    }

    /**
     * Lanza el lote entero en paralelo sobre hilos virtuales: cada
     * {@code procesarUno()} toma su propio trabajo con {@code SKIP LOCKED}, así
     * que no compiten entre sí por fila. Ya no corta al primer "no hay más
     * trabajo" (antes era secuencial y eso servía de atajo) — con 25 hilos
     * virtuales de por medio, casi todos devuelven falso de inmediato si la
     * cola ya está vacía, así que el costo de no cortar antes es despreciable.
     */
    @Scheduled(fixedDelayString = "${kb.ingesta.worker.intervalo-ms:2000}")
    void ejecutarLote() {
        List<Future<Boolean>> futuros;
        try (ExecutorService hilos = Executors.newVirtualThreadPerTaskExecutor()) {
            futuros = IntStream.range(0, TAMANO_LOTE)
                    .<Callable<Boolean>>mapToObj(i -> trabajador::procesarUno)
                    .map(hilos::submit)
                    .toList();
        }
        for (Future<Boolean> futuro : futuros) {
            try {
                futuro.get();
            } catch (Exception e) {
                log.warn("Fallo inesperado procesando un item del lote de embeddings: {}", e.toString());
            }
        }
    }
}
