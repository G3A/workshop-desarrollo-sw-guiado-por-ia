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

    // Con diferencia el fallo mas comun al estrenar un perfil: cada uno sirve su
    // propio modelo y se descarga aparte, con su `make pull-<perfil>`. Sin esta
    // pista el 404 no dice que hacer.
    //
    // El mensaje nombra el modelo que Ollama rechazo -- viene en el propio error --
    // en vez de mandar a `make health` a averiguarlo. Es mas directo, y ademas
    // health llego a mentir sobre esto: comprobaba el modelo del destilador de
    // Teams y no KB_LLM_MODELO, asi que con un perfil de Ollama recien estrenado
    // decia "faltantes: ninguna" mientras la consulta fallaba con este mismo 404.
    // Eso ya esta corregido (ver OllamaSalud), pero el mensaje no depende de ello:
    // el dato que hace falta ya lo trae la excepcion.
    if (detalle.contains("not found") && detalle.contains("model")) {
      return "Ollama no tiene descargado el modelo que pide este perfil. Descargalo con el"
          + " `make pull-...` correspondiente (o `make pull-models` para el perfil base), y"
          + " comprueba con `docker exec kb-ollama ollama list` que el nombre coincide"
          + " EXACTAMENTE, etiqueta incluida. Detalle: "
          + detalle;
    }
    return "La respuesta se interrumpio: " + detalle;
  }
}
