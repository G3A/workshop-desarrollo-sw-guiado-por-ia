package co.g3a.baseconocimiento.acciones;

import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Component;

/**
 * Cuantas acciones pueden estar pidiendole tokens al LLM a la vez. Es el mismo mecanismo que el
 * cupo de consultas del RAG, pero un semaforo aparte: compartir el suyo seria depender de {@code
 * orquestacion}, y la independencia del modulo vale mas que la exactitud del conteo global
 * (supuesto 3 del issue #38). Sin espera: si no hay cupo, quien llama contesta al toque con un
 * mensaje claro en vez de sumar otra espera larga detras de una accion huerfana.
 */
@Component
class CupoDeAcciones {

  private final Semaphore semaforo;

  CupoDeAcciones(AccionesPropiedades propiedades) {
    this.semaforo = new Semaphore(propiedades.maxConcurrentes());
  }

  boolean intentarTomar() {
    return semaforo.tryAcquire();
  }

  /** Solo despues de un {@link #intentarTomar()} que devolvio {@code true}. */
  void liberar() {
    semaforo.release();
  }
}
