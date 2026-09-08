package co.g3a.baseconocimiento.acciones;

import co.g3a.baseconocimiento.acciones.Acciones.TextoTraducido;
import co.g3a.baseconocimiento.llm.Redactor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Traduce un texto libre del chat: lo que la persona escribio con el modo traducir activo, o un
 * turno ya escrito (una respuesta del RAG con sus {@code [n]}). Sin base de datos: el texto viene
 * entero en la peticion, y el tope de largo lo hace cumplir el adaptador con {@code
 * Acciones.limites()}.
 *
 * <p>Perezoso como {@link TraductorDeDocumentos}: la deteccion corre recien cuando alguien lee
 * {@code idiomaOrigen} o el texto, y una sola vez para los dos ({@code cache()}).
 */
@Component
class TraductorDeTexto {

  private final Redactor redactor;
  private final CupoDeAcciones cupo;

  TraductorDeTexto(Redactor redactor, CupoDeAcciones cupo) {
    this.redactor = redactor;
    this.cupo = cupo;
  }

  TextoTraducido traducir(String texto, String origen, String destino) {
    Mono<String> idiomaOrigen =
        (origen != null
                ? Mono.just(origen)
                : Mono.fromCallable(() -> redactor.detectarIdioma(texto)))
            .cache();
    Flux<String> salida =
        idiomaOrigen.flatMapMany(
            codigo -> {
              // Ya esta en el destino: el texto vuelve tal cual, sin LLM ni cupo. "und" no
              // cuenta como coincidencia: no es un idioma.
              if (!"und".equals(codigo) && codigo.equals(destino)) {
                return Flux.just(texto);
              }
              return Flux.defer(
                  () -> {
                    if (!cupo.intentarTomar()) {
                      return Flux.error(
                          new Acciones.ServidorOcupado(
                              AccionesSobreDocumentos.MENSAJE_SERVIDOR_OCUPADO));
                    }
                    return redactor
                        .traducir(texto, codigo, destino)
                        .doFinally(signal -> cupo.liberar());
                  });
            });
    return new TextoTraducido(idiomaOrigen, destino, salida);
  }
}
