package co.g3a.baseconocimiento.teams;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/messages}: el único endpoint que Azure Bot Service (o el
 * Bot Framework Emulator) llama. Devuelve 200 de inmediato — la respuesta de
 * verdad viaja por su cuenta al Connector API vía {@link ProcesadorDeMensajes},
 * porque la síntesis tarda minutos en CPU (ver Riesgos vivos del plan) y el
 * webhook no puede esperarla.
 *
 * <p>Deshabilitado por defecto ({@code kb.teams.habilitado=false}): el
 * producto se demuestra completo sin este adaptador.
 */
@RestController
@ConditionalOnProperty(prefix = "kb.teams", name = "habilitado", havingValue = "true")
class BotController {

    private static final Logger log = LoggerFactory.getLogger(BotController.class);

    private final ValidadorTokenBotFramework validador;
    private final ProcesadorDeMensajes procesador;

    BotController(ValidadorTokenBotFramework validador, ProcesadorDeMensajes procesador) {
        this.validador = validador;
        this.procesador = procesador;
    }

    @PostMapping("/api/messages")
    ResponseEntity<Void> mensajes(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String autorizacion,
            @RequestBody Activity actividad) {
        try {
            validador.validar(autorizacion, actividad.serviceUrl());
        } catch (ValidadorTokenBotFramework.TokenInvalidoException e) {
            log.warn("Activity rechazada, token invalido: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        if (Activity.TIPO_MENSAJE.equals(actividad.type())
                && actividad.text() != null
                && !actividad.text().isBlank()) {
            procesador.procesarAsync(actividad);
        }
        return ResponseEntity.ok().build();
    }
}
