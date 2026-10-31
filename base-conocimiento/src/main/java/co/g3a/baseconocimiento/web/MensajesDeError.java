package co.g3a.baseconocimiento.web;

import co.g3a.baseconocimiento.acciones.Acciones;

/**
 * Traduce la excepcion que revienta un stream a algo que se pueda leer en pantalla. Compartido por
 * los dos controladores SSE de la pagina de chat: el fallo mas comun (el modelo del perfil sin
 * descargar) merece la misma pista venga de una pregunta o de una accion sobre documentos.
 *
 * <p>Se baja hasta la causa raiz porque lo que llega arriba suele ser un {@code
 * CompletionException} envolviendo lo unico informativo. No se inspeccionan tipos del cliente de
 * OpenAI a proposito: este paquete solo depende de las fachadas y de {@code compartido} — ese
 * limite lo verifica {@code ArquitecturaTest} en cada build —, asi que la unica pista disponible es
 * el texto, y con el basta para los dos casos que de verdad aparecen.
 */
final class MensajesDeError {

  private MensajesDeError() {}

  static String mensajeDe(Throwable error) {
    Throwable raiz = error;
    while (raiz.getCause() != null && raiz.getCause() != raiz) {
      raiz = raiz.getCause();
    }
    // Un cupo agotado no es un corte: el mensaje de la fachada va tal cual, el mismo que
    // las acciones de prosa emiten como texto fijo.
    if (raiz instanceof Acciones.ServidorOcupado) {
      return raiz.getMessage();
    }
    String detalle =
        raiz.getMessage() == null ? raiz.getClass().getSimpleName() : raiz.getMessage();

    // El fallo mas comun desde ADR-0017: llama-server corre en el host, fuera de
    // compose, asi que reiniciar la maquina o un `docker compose up` a mano deja la
    // api arriba y sin LLM. El cliente de OpenAI lo reporta como "Request failed",
    // que no dice que hacer.
    if (detalle.contains("Request failed") || detalle.contains("Connection refused")) {
      return "El LLM no responde: llama-server corre fuera de Docker y no esta arriba."
          + " Para levantarlo: `make llm-up` (o `make up`); el estado, con `make llm-estado`."
          + " Detalle: "
          + detalle;
    }
    return "La respuesta se interrumpio: " + detalle;
  }
}
