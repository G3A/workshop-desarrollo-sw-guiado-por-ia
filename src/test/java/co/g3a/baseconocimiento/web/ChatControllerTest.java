package co.g3a.baseconocimiento.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import co.g3a.baseconocimiento.compartido.Dominio.Cita;
import co.g3a.baseconocimiento.orquestacion.Consultar;

import reactor.core.publisher.Flux;

/**
 * {@link ChatController} contra un slice de MVC: {@link Consultar} queda
 * doblado, así que no hace falta Postgres ni Ollama para verificar el
 * contrato HTTP — eso ya lo cubren las pruebas de {@code orquestacion} y
 * {@code recuperacion}.
 */
@WebMvcTest(ChatController.class)
class ChatControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    Consultar consultar;

    @Test
    @DisplayName("POST /api/preview delega en Consultar.previsualizar y devuelve las citas")
    void previsualizarDelegaEnConsultar() throws Exception {
        Cita cita = new Cita("file:///doc1", "Doc 1", "extracto", "doc_section");
        when(consultar.previsualizar(any(), any(), anyInt(), any())).thenReturn(List.of(cita));

        mockMvc.perform(post("/api/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"q\":\"como se despliega\",\"projectId\":\"default\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Doc 1")));
    }

    @Test
    @DisplayName("POST /api/preview rechaza una pregunta vacia")
    void previsualizarRechazaPreguntaVacia() throws Exception {
        mockMvc.perform(post("/api/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"q\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/chat transmite las citas y los tokens por SSE, en ese orden")
    void chatTransmiteCitasYTokensPorSse() throws Exception {
        Cita cita = new Cita("file:///doc1", "Doc 1", "extracto", "doc_section");
        when(consultar.responderEnStreaming(any(), any(), any(), any()))
                .thenReturn(new Consultar.RespuestaEnStreaming(List.of(cita), Flux.just("Hola ", "mundo"), null));

        MvcResult resultadoAsincronico = mockMvc.perform(get("/api/chat").param("q", "como se despliega"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(resultadoAsincronico))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(containsString("event:citas")))
                .andExpect(content().string(containsString("Doc 1")))
                .andExpect(content().string(containsString("event:token")))
                .andExpect(content().string(containsString("Hola")))
                .andExpect(content().string(containsString("event:fin")))
                .andExpect(content().string(not(containsString("event:reformulacion"))));
    }

    @Test
    @DisplayName("GET /api/chat manda el evento reformulacion, antes de los tokens, "
            + "solo cuando el Reformulador cambio la consulta")
    void chatMandaElEventoReformulacionCuandoAplica() throws Exception {
        Cita cita = new Cita("file:///doc1", "Doc 1", "extracto", "doc_section");
        when(consultar.responderEnStreaming(any(), any(), any(), any()))
                .thenReturn(new Consultar.RespuestaEnStreaming(
                        List.of(cita), Flux.just("Respuesta."), "boxing conversion"));

        MvcResult resultadoAsincronico = mockMvc.perform(get("/api/chat").param("q", "que es el autoboxing"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(resultadoAsincronico))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("event:reformulacion")))
                .andExpect(content().string(containsString("data:\"boxing conversion\"")));
    }

    @Test
    @DisplayName("GET /api/chat rechaza una pregunta vacia")
    void chatRechazaPreguntaVacia() throws Exception {
        mockMvc.perform(get("/api/chat").param("q", ""))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/chat manda cada token como string JSON, no como texto crudo")
    void chatMandaTokensComoStringJson() throws Exception {
        // El estandar SSE le quita al valor de un campo `data:` un unico espacio
        // inicial (la convencion del delimitador "data: "). Un token que
        // realmente empieza con un espacio (" servicio", tipico de un LLM antes
        // de cada palabra nueva) perderia ese espacio si viajara crudo -- por
        // eso va como string JSON (`data:" servicio"`, con la comilla como
        // primer caracter, no el espacio), y el cliente hace JSON.parse.
        Cita cita = new Cita("file:///doc1", "Doc 1", "extracto", "doc_section");
        when(consultar.responderEnStreaming(any(), any(), any(), any()))
                .thenReturn(new Consultar.RespuestaEnStreaming(List.of(cita), Flux.just("Hola", " mundo"), null));

        MvcResult resultadoAsincronico = mockMvc.perform(get("/api/chat").param("q", "como se despliega"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(resultadoAsincronico))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data:\"Hola\"")))
                .andExpect(content().string(containsString("data:\" mundo\"")));
    }

    @Test
    @DisplayName("GET /api/chat/estado devuelve el estado de la conversacion cuando existe")
    void estadoDevuelveElEstadoCuandoExiste() throws Exception {
        Cita cita = new Cita("file:///doc1", "Doc 1", "extracto", "doc_section");
        Consultar.EstadoStream estado = new Consultar.EstadoStream(
                "completo", "que es un enum", "default", "Un enum es...", List.of(cita), null);
        when(consultar.estadoDeStream(7L)).thenReturn(Optional.of(estado));

        mockMvc.perform(get("/api/chat/estado").param("conversacionId", "7"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"estado\":\"completo\"")))
                .andExpect(content().string(containsString("Un enum es...")));
    }

    @Test
    @DisplayName("GET /api/chat/estado devuelve 404 cuando la conversacion nunca pregunto nada")
    void estadoDevuelve404CuandoNoHayRegistro() throws Exception {
        when(consultar.estadoDeStream(7L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/chat/estado").param("conversacionId", "7"))
                .andExpect(status().isNotFound());
    }
}
