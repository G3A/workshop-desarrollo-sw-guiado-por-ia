package co.g3a.baseconocimiento.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

/**
 * El latido del SSE de las acciones (issue #57): mientras una pasada de lectura tarda minutos, el
 * stream no queda mudo. Un comentario SSE cada tanto mantiene viva la conexión ante un proxy o un
 * cliente con timeout de inactividad (el fetch de Node corta a los 300 s), y el EventSource del
 * navegador lo ignora.
 */
class LatidoSseTest {

  @Test
  @DisplayName("Mientras no llega nada, manda comentarios de latido; termina con el evento fin")
  void mandaLatidosMientrasEsperaYTerminaConFin() {
    Flux<ServerSentEvent<Object>> lento =
        Flux.concat(
            Flux.just(evento("lectura")).delayElements(Duration.ofMillis(300)),
            Flux.just(evento("fin")));

    List<ServerSentEvent<Object>> recibidos =
        AccionesController.conLatido(lento, Duration.ofMillis(50))
            .collectList()
            .block(Duration.ofSeconds(5));

    assertThat(recibidos).isNotNull();
    assertThat(recibidos.stream().filter(e -> "latido".equals(e.comment())).count())
        .isGreaterThanOrEqualTo(3);
    assertThat(recibidos.getLast().event()).isEqualTo("fin");
  }

  @Test
  @DisplayName("Un stream que falla termina igual: el latido no lo deja abierto")
  void terminaConElErrorDelServidor() {
    Flux<ServerSentEvent<Object>> conError =
        Flux.concat(
            Flux.just(evento("lectura")).delayElements(Duration.ofMillis(120)),
            Flux.just(evento("error-servidor")));

    List<ServerSentEvent<Object>> recibidos =
        AccionesController.conLatido(conError, Duration.ofMillis(50))
            .collectList()
            .block(Duration.ofSeconds(5));

    assertThat(recibidos).isNotNull();
    assertThat(recibidos.getLast().event()).isEqualTo("error-servidor");
  }

  private static ServerSentEvent<Object> evento(String nombre) {
    return ServerSentEvent.builder().event(nombre).<Object>data("").build();
  }
}
