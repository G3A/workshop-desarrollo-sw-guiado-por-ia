package co.g3a.baseconocimiento.teams;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import co.g3a.baseconocimiento.compartido.Dominio.Cita;
import co.g3a.baseconocimiento.compartido.Dominio.Respuesta;

/**
 * Construye la Adaptive Card 1.4 que Teams renderiza como respuesta: el texto
 * sintetizado, las advertencias del sintetizador si las hay, y una acción
 * {@code Action.OpenUrl} por cada cita — el equivalente en tarjeta de los
 * enlaces clicables que F4 ya muestra en la UI web.
 */
final class TarjetaAdaptativa {

    private TarjetaAdaptativa() {
    }

    static Activity.Attachment desde(Respuesta respuesta) {
        List<Object> cuerpo = new ArrayList<>();
        cuerpo.add(bloqueTexto(respuesta.texto(), false));

        if (!respuesta.advertencias().isEmpty()) {
            cuerpo.add(bloqueTexto("⚠ " + String.join("\n⚠ ", respuesta.advertencias()), true));
        }

        Map<String, Object> tarjeta = new LinkedHashMap<>();
        tarjeta.put("type", "AdaptiveCard");
        tarjeta.put("$schema", "http://adaptivecards.io/schemas/adaptive-card.json");
        tarjeta.put("version", "1.4");
        tarjeta.put("body", cuerpo);
        if (!respuesta.citas().isEmpty()) {
            tarjeta.put("actions", respuesta.citas().stream().map(TarjetaAdaptativa::accionDe).toList());
        }

        return new Activity.Attachment(Activity.CONTENT_TYPE_ADAPTIVE_CARD, tarjeta);
    }

    private static Map<String, Object> bloqueTexto(String texto, boolean negrita) {
        Map<String, Object> bloque = new LinkedHashMap<>();
        bloque.put("type", "TextBlock");
        bloque.put("text", texto);
        bloque.put("wrap", true);
        if (negrita) {
            bloque.put("weight", "Bolder");
        }
        return bloque;
    }

    private static Map<String, Object> accionDe(Cita cita) {
        Map<String, Object> accion = new LinkedHashMap<>();
        accion.put("type", "Action.OpenUrl");
        accion.put("title", tituloDe(cita));
        accion.put("url", cita.uri());
        return accion;
    }

    private static String tituloDe(Cita cita) {
        return (cita.titulo() == null || cita.titulo().isBlank()) ? cita.uri() : cita.titulo();
    }
}
