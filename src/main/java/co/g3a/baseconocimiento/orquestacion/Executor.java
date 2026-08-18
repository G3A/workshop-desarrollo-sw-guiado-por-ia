package co.g3a.baseconocimiento.orquestacion;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import co.g3a.baseconocimiento.compartido.Dominio.Fragmento;
import co.g3a.baseconocimiento.compartido.Dominio.ProyectoId;

/**
 * Etapas 2 y 3 del pipeline: toma la lista de herramientas que eligió el
 * planner y las corre en paralelo, sobre hilos virtuales — igual que
 * {@code Recuperador} hace con las cuatro señales en F2.
 *
 * <p>Aísla fallos por herramienta: si una revienta (Postgres momentáneamente
 * caído, {@code rg} no instalado), las demás siguen y esa queda registrada con
 * su error en la traza en vez de tumbar toda la pregunta.
 */
@Component
class Executor {

    private static final Logger log = LoggerFactory.getLogger(Executor.class);

    private final CatalogoHerramientas catalogo;

    Executor(CatalogoHerramientas catalogo) {
        this.catalogo = catalogo;
    }

    /**
     * @param error {@code null} si la herramienta terminó bien, aunque haya
     *              devuelto una lista vacía
     */
    record EjecucionHerramienta(String nombre, List<Fragmento> fragmentos, long duracionMs, String error) {
    }

    List<EjecucionHerramienta> ejecutar(
            List<String> nombresHerramientas, String consulta, ProyectoId proyecto, List<Long> documentosPermitidos) {
        List<Herramienta> seleccionadas = resolver(nombresHerramientas);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<EjecucionHerramienta>> futuros = seleccionadas.stream()
                    .map(h -> executor.submit(() -> ejecutarUna(h, consulta, proyecto, documentosPermitidos)))
                    .toList();
            return futuros.stream().map(Executor::obtener).toList();
        }
    }

    /**
     * Si el planner no eligió nada resoluble (lista vacía, o nombres que no
     * existen en el catálogo), cae a {@code search_unified}: la pregunta nunca
     * se queda sin ninguna búsqueda por un plan imperfecto.
     */
    private List<Herramienta> resolver(List<String> nombresHerramientas) {
        List<Herramienta> resueltas = nombresHerramientas.stream()
                .map(catalogo::porNombre).flatMap(Optional::stream).toList();
        if (!resueltas.isEmpty()) {
            return resueltas;
        }
        return catalogo.porNombre("search_unified").map(List::of).orElse(List.of());
    }

    private static EjecucionHerramienta ejecutarUna(
            Herramienta herramienta, String consulta, ProyectoId proyecto, List<Long> documentosPermitidos) {
        long inicio = System.nanoTime();
        try {
            List<Fragmento> fragmentos = herramienta.ejecutar(consulta, proyecto, documentosPermitidos);
            return new EjecucionHerramienta(herramienta.nombre(), fragmentos, duracionMs(inicio), null);
        } catch (Exception e) {
            log.warn("La herramienta {} fallo: {}", herramienta.nombre(), e.toString());
            return new EjecucionHerramienta(herramienta.nombre(), List.of(), duracionMs(inicio), e.toString());
        }
    }

    private static EjecucionHerramienta obtener(Future<EjecucionHerramienta> futuro) {
        try {
            return futuro.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrumpido esperando una herramienta", e);
        } catch (ExecutionException e) {
            // ejecutarUna ya atrapa las excepciones de la herramienta; llegar
            // aqui significa un fallo del propio hilo virtual, no de la
            // herramienta, y eso si debe propagarse.
            throw new IllegalStateException("Fallo inesperado ejecutando una herramienta", e.getCause());
        }
    }

    private static long duracionMs(long inicioNanos) {
        return (System.nanoTime() - inicioNanos) / 1_000_000;
    }
}
