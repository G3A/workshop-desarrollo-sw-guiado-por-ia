package co.g3a.baseconocimiento.teams;

import java.util.List;

// Excepcion real en Jackson 3: jackson-annotations (@JsonIgnoreProperties, @JsonInclude...)
// se queda en com.fasterxml.jackson.annotation a proposito, para que el mismo
// modelo de anotaciones sirva sin cambios en proyectos Jackson 2.x y 3.x --
// verificado contra la guia de migracion oficial, no memoria.
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Subconjunto del esquema {@code Activity} del protocolo Bot Connector: solo
 * los campos que este adaptador necesita para leer un mensaje entrante y
 * armar la respuesta. {@code @JsonIgnoreProperties(ignoreUnknown = true)}
 * porque una Activity real trae muchos más campos (entities, channelData,
 * locale...) que no nos hace falta modelar.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Activity(
        String type,
        String id,
        String timestamp,
        String serviceUrl,
        String channelId,
        ChannelAccount from,
        ConversationAccount conversation,
        ChannelAccount recipient,
        String text,
        String replyToId,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<Attachment> attachments) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChannelAccount(String id, String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConversationAccount(String id) {
    }

    public record Attachment(String contentType, Object content) {
    }

    static final String TIPO_MENSAJE = "message";
    static final String TIPO_ESCRIBIENDO = "typing";
    static final String CONTENT_TYPE_ADAPTIVE_CARD = "application/vnd.microsoft.card.adaptive";

    /** La Activity de respuesta: intercambia {@code from}/{@code recipient} y referencia el mensaje original. */
    static Activity respuestaA(Activity entrante, String tipo, List<Attachment> attachments, String texto) {
        return new Activity(
                tipo,
                null,
                null,
                entrante.serviceUrl(),
                entrante.channelId(),
                entrante.recipient(),
                entrante.conversation(),
                entrante.from(),
                texto,
                entrante.id(),
                attachments);
    }
}
