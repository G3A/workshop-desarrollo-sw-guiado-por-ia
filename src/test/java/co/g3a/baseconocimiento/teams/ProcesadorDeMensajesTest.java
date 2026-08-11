package co.g3a.baseconocimiento.teams;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import co.g3a.baseconocimiento.compartido.Dominio.Cita;
import co.g3a.baseconocimiento.compartido.Dominio.Filtros;
import co.g3a.baseconocimiento.compartido.Dominio.Pregunta;
import co.g3a.baseconocimiento.compartido.Dominio.ProyectoId;
import co.g3a.baseconocimiento.compartido.Dominio.Respuesta;
import co.g3a.baseconocimiento.orquestacion.Consultar;

/**
 * {@link ProcesadorDeMensajes#procesar} corre sincrónico a propósito (ver su
 * Javadoc): esta prueba lo llama directo, sin pasar por el hilo virtual real
 * de {@code procesarAsync}, ni por HTTP, ni por Postgres/Ollama.
 */
class ProcesadorDeMensajesTest {

    private static final Activity ACTIVIDAD = new Activity(
            "message", "activity1", null, "https://smba.example.com/", "test",
            new Activity.ChannelAccount("user1", "Usuario"),
            new Activity.ConversationAccount("conv1"),
            new Activity.ChannelAccount("bot1", "Bot"),
            "como se despliega el servicio?", null, null);

    @Test
    void enElCaminoFelizEnviaEscribiendoYLuegoLaTarjeta() {
        Consultar consultar = mock(Consultar.class);
        ClienteConectorBotFramework conector = mock(ClienteConectorBotFramework.class);
        Respuesta respuesta = new Respuesta(
                "se despliega con docker compose", List.of(new Cita("file:///readme", "Readme", "extracto", "doc")),
                List.of(), 42, null);
        when(consultar.responder(any(), any(), any())).thenReturn(respuesta);

        new ProcesadorDeMensajes(consultar, conector).procesar(ACTIVIDAD);

        verify(conector).enviarEscribiendo(ACTIVIDAD);
        verify(consultar).responder(new Pregunta("como se despliega el servicio?"), ProyectoId.POR_DEFECTO, Filtros.NINGUNO);
        verify(conector).responderConTarjeta(ACTIVIDAD, respuesta);
    }

    @Test
    void siConsultarFallaEnviaUnaRespuestaDeTextoDeFallback() {
        Consultar consultar = mock(Consultar.class);
        ClienteConectorBotFramework conector = mock(ClienteConectorBotFramework.class);
        when(consultar.responder(any(), any(), any())).thenThrow(new IllegalStateException("Ollama no responde"));

        new ProcesadorDeMensajes(consultar, conector).procesar(ACTIVIDAD);

        verify(conector).enviarEscribiendo(ACTIVIDAD);
        verify(conector, never()).responderConTarjeta(any(), any());
        verify(conector).responderTexto(eq(ACTIVIDAD), any());
    }
}
