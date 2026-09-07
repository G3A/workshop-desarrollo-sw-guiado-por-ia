package co.g3a.baseconocimiento.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.g3a.baseconocimiento.compartido.Dominio.Cita;
import co.g3a.baseconocimiento.compartido.Dominio.IdiomaRespuesta;
import co.g3a.baseconocimiento.orquestacion.Consultar;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * {@link ChatController} contra un slice de MVC: {@link Consultar} queda doblado, así que no hace
 * falta Postgres ni Ollama para verificar el contrato HTTP — eso ya lo cubren las pruebas de {@code
 * orquestacion} y {@code recuperacion}.
 */
@WebMvcTest(ChatController.class)
class ChatControllerTest {

  @Autowired MockMvc mockMvc;

  @MockitoBean Consultar consultar;

  @Test
  @DisplayName("POST /api/preview delega en Consultar.previsualizar y devuelve las citas")
  void previsualizarDelegaEnConsultar() throws Exception {
    Cita cita = new Cita("file:///doc1", "Doc 1", "extracto", "doc_section");
    when(consultar.previsualizar(any(), any(), anyInt(), any())).thenReturn(List.of(cita));

    mockMvc
        .perform(
            post("/api/preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"q\":\"como se despliega\",\"projectId\":\"default\"}"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Doc 1")));
  }

  @Test
  @DisplayName("POST /api/preview rechaza una pregunta vacia")
  void previsualizarRechazaPreguntaVacia() throws Exception {
    mockMvc
        .perform(
            post("/api/preview").contentType(MediaType.APPLICATION_JSON).content("{\"q\":\"\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("GET /api/chat transmite las citas y los tokens por SSE, en ese orden")
  void chatTransmiteCitasYTokensPorSse() throws Exception {
    Cita cita = new Cita("file:///doc1", "Doc 1", "extracto", "doc_section");
    when(consultar.responderEnStreaming(any(), any(), any(), any(), any()))
        .thenReturn(
            new Consultar.RespuestaEnStreaming(
                List.of(cita), Flux.just("Hola ", "mundo"), null, Mono.just(42L), List.of()));

    MvcResult resultadoAsincronico =
        mockMvc
            .perform(get("/api/chat").param("q", "como se despliega"))
            .andExpect(request().asyncStarted())
            .andReturn();

    mockMvc
        .perform(asyncDispatch(resultadoAsincronico))
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
  @DisplayName("GET /api/chat manda el evento queryLogId, despues de los tokens y antes de fin")
  void chatMandaElEventoQueryLogIdDespuesDeLosTokens() throws Exception {
    Cita cita = new Cita("file:///doc1", "Doc 1", "extracto", "doc_section");
    when(consultar.responderEnStreaming(any(), any(), any(), any(), any()))
        .thenReturn(
            new Consultar.RespuestaEnStreaming(
                List.of(cita), Flux.just("Hola"), null, Mono.just(7L), List.of()));

    MvcResult resultadoAsincronico =
        mockMvc
            .perform(get("/api/chat").param("q", "como se despliega"))
            .andExpect(request().asyncStarted())
            .andReturn();

    String cuerpo =
        mockMvc
            .perform(asyncDispatch(resultadoAsincronico))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(cuerpo.indexOf("event:token")).isLessThan(cuerpo.indexOf("event:queryLogId"));
    assertThat(cuerpo.indexOf("event:queryLogId")).isLessThan(cuerpo.indexOf("event:fin"));
    assertThat(cuerpo).contains("data:7");
  }

  @Test
  @DisplayName(
      "GET /api/chat no manda el evento queryLogId si el Mono nunca emite "
          + "(stream terminado en error/cancelado antes de completar)")
  void chatNoMandaQueryLogIdSiElMonoNuncaEmite() throws Exception {
    Cita cita = new Cita("file:///doc1", "Doc 1", "extracto", "doc_section");
    when(consultar.responderEnStreaming(any(), any(), any(), any(), any()))
        .thenReturn(
            new Consultar.RespuestaEnStreaming(
                List.of(cita), Flux.just("Hola"), null, Mono.empty(), List.of()));

    MvcResult resultadoAsincronico =
        mockMvc
            .perform(get("/api/chat").param("q", "como se despliega"))
            .andExpect(request().asyncStarted())
            .andReturn();

    mockMvc
        .perform(asyncDispatch(resultadoAsincronico))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("event:queryLogId"))))
        .andExpect(content().string(containsString("event:fin")));
  }

  @Test
  @DisplayName(
      "GET /api/chat manda el evento reformulacion, antes de los tokens, "
          + "solo cuando el Reformulador cambio la consulta")
  void chatMandaElEventoReformulacionCuandoAplica() throws Exception {
    Cita cita = new Cita("file:///doc1", "Doc 1", "extracto", "doc_section");
    when(consultar.responderEnStreaming(any(), any(), any(), any(), any()))
        .thenReturn(
            new Consultar.RespuestaEnStreaming(
                List.of(cita),
                Flux.just("Respuesta."),
                "boxing conversion",
                Mono.just(1L),
                List.of()));

    MvcResult resultadoAsincronico =
        mockMvc
            .perform(get("/api/chat").param("q", "que es el autoboxing"))
            .andExpect(request().asyncStarted())
            .andReturn();

    mockMvc
        .perform(asyncDispatch(resultadoAsincronico))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("event:reformulacion")))
        .andExpect(content().string(containsString("data:\"boxing conversion\"")));
  }

  @Test
  @DisplayName(
      "GET /api/chat con proponer=true: si hay reformulaciones propuestas, manda solo el evento "
          + "reformulaciones (arreglo JSON) y fin, sin citas ni tokens")
  void chatMandaLasReformulacionesPropuestasYCorta() throws Exception {
    when(consultar.responderEnStreaming(any(), any(), any(), any(), any()))
        .thenReturn(
            new Consultar.RespuestaEnStreaming(
                List.of(),
                Flux.empty(),
                null,
                Mono.empty(),
                List.of("boxing conversion", "autoboxing Java")));

    MvcResult resultadoAsincronico =
        mockMvc
            .perform(get("/api/chat").param("q", "que es el autoboxing").param("proponer", "true"))
            .andExpect(request().asyncStarted())
            .andReturn();

    String cuerpo =
        mockMvc
            .perform(asyncDispatch(resultadoAsincronico))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(cuerpo).contains("event:reformulaciones");
    assertThat(cuerpo).contains("data:[\"boxing conversion\",\"autoboxing Java\"]");
    assertThat(cuerpo).contains("event:fin");
    assertThat(cuerpo).doesNotContain("event:citas").doesNotContain("event:token");
    assertThat(cuerpo.indexOf("event:reformulaciones")).isLessThan(cuerpo.indexOf("event:fin"));

    ArgumentCaptor<Consultar.Preferencias> preferencias =
        ArgumentCaptor.forClass(Consultar.Preferencias.class);
    verify(consultar).responderEnStreaming(any(), any(), any(), any(), preferencias.capture());
    assertThat(preferencias.getValue().reformulacion())
        .isInstanceOf(Consultar.ModoReformulacion.Proponer.class);
  }

  @Test
  @DisplayName(
      "GET /api/chat con busqueda e idioma=original: pide buscar con la consulta elegida y "
          + "responder en el idioma del corpus")
  void chatTraduceBusquedaEIdiomaAPreferencias() throws Exception {
    when(consultar.responderEnStreaming(any(), any(), any(), any(), any()))
        .thenReturn(
            new Consultar.RespuestaEnStreaming(
                List.of(), Flux.just("Boxing..."), "boxing conversion", Mono.just(3L), List.of()));

    MvcResult resultadoAsincronico =
        mockMvc
            .perform(
                get("/api/chat")
                    .param("q", "que es el autoboxing")
                    .param("busqueda", "boxing conversion")
                    .param("idioma", "original")
                    // busqueda manda sobre proponer: ya eligio, no hay que volver a proponer.
                    .param("proponer", "true"))
            .andExpect(request().asyncStarted())
            .andReturn();
    mockMvc.perform(asyncDispatch(resultadoAsincronico)).andExpect(status().isOk());

    ArgumentCaptor<Consultar.Preferencias> preferencias =
        ArgumentCaptor.forClass(Consultar.Preferencias.class);
    verify(consultar).responderEnStreaming(any(), any(), any(), any(), preferencias.capture());
    assertThat(preferencias.getValue().reformulacion())
        .isEqualTo(new Consultar.ModoReformulacion.Elegida("boxing conversion"));
    assertThat(preferencias.getValue().idioma()).isEqualTo(IdiomaRespuesta.ORIGINAL_DEL_CORPUS);
  }

  @Test
  @DisplayName("GET /api/chat sin proponer ni busqueda usa las preferencias por defecto")
  void chatSinParametrosNuevosUsaLasPreferenciasPorDefecto() throws Exception {
    when(consultar.responderEnStreaming(any(), any(), any(), any(), any()))
        .thenReturn(
            new Consultar.RespuestaEnStreaming(
                List.of(), Flux.just("Hola"), null, Mono.just(1L), List.of()));

    MvcResult resultadoAsincronico =
        mockMvc
            .perform(get("/api/chat").param("q", "como se despliega"))
            .andExpect(request().asyncStarted())
            .andReturn();
    mockMvc.perform(asyncDispatch(resultadoAsincronico)).andExpect(status().isOk());

    verify(consultar)
        .responderEnStreaming(any(), any(), any(), any(), eq(Consultar.Preferencias.POR_DEFECTO));
  }

  @Test
  @DisplayName("GET /api/chat rechaza una pregunta vacia")
  void chatRechazaPreguntaVacia() throws Exception {
    mockMvc.perform(get("/api/chat").param("q", "")).andExpect(status().isBadRequest());
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
    when(consultar.responderEnStreaming(any(), any(), any(), any(), any()))
        .thenReturn(
            new Consultar.RespuestaEnStreaming(
                List.of(cita), Flux.just("Hola", " mundo"), null, Mono.just(2L), List.of()));

    MvcResult resultadoAsincronico =
        mockMvc
            .perform(get("/api/chat").param("q", "como se despliega"))
            .andExpect(request().asyncStarted())
            .andReturn();

    mockMvc
        .perform(asyncDispatch(resultadoAsincronico))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data:\"Hola\"")))
        .andExpect(content().string(containsString("data:\" mundo\"")));
  }

  @Test
  @DisplayName("GET /api/chat/estado devuelve el estado de la conversacion cuando existe")
  void estadoDevuelveElEstadoCuandoExiste() throws Exception {
    Cita cita = new Cita("file:///doc1", "Doc 1", "extracto", "doc_section");
    Consultar.EstadoStream estado =
        new Consultar.EstadoStream(
            "completo", "que es un enum", "default", "Un enum es...", List.of(cita), null, 9L);
    when(consultar.estadoDeStream(7L)).thenReturn(Optional.of(estado));

    mockMvc
        .perform(get("/api/chat/estado").param("conversacionId", "7"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("\"estado\":\"completo\"")))
        .andExpect(content().string(containsString("Un enum es...")))
        .andExpect(content().string(containsString("\"queryLogId\":9")));
  }

  @Test
  @DisplayName("GET /api/chat/estado devuelve 404 cuando la conversacion nunca pregunto nada")
  void estadoDevuelve404CuandoNoHayRegistro() throws Exception {
    when(consultar.estadoDeStream(7L)).thenReturn(Optional.empty());

    mockMvc
        .perform(get("/api/chat/estado").param("conversacionId", "7"))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("POST /api/feedback delega en Consultar.registrarFeedback y devuelve 200")
  void feedbackDelegaEnConsultar() throws Exception {
    when(consultar.registrarFeedback(anyLong(), anyBoolean(), anyString())).thenReturn(true);

    mockMvc
        .perform(
            post("/api/feedback")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"queryLogId\":9,\"util\":false,\"comentario\":\"la cita no aplicaba\"}"))
        .andExpect(status().isOk());

    verify(consultar).registrarFeedback(9L, false, "la cita no aplicaba");
  }

  @Test
  @DisplayName(
      "POST /api/feedback devuelve 400 cuando Consultar.registrarFeedback dice que no existe")
  void feedbackDevuelve400CuandoElQueryLogIdNoExiste() throws Exception {
    when(consultar.registrarFeedback(anyLong(), anyBoolean(), anyString())).thenReturn(false);

    mockMvc
        .perform(
            post("/api/feedback")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"queryLogId\":999999,\"util\":true}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @DisplayName("POST /api/feedback rechaza un payload sin queryLogId o sin util")
  void feedbackRechazaPayloadInvalido() throws Exception {
    mockMvc
        .perform(
            post("/api/feedback")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"util\":true}"))
        .andExpect(status().isBadRequest());
  }
}
