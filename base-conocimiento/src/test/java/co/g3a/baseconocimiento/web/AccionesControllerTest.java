package co.g3a.baseconocimiento.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.g3a.baseconocimiento.acciones.Acciones;
import co.g3a.baseconocimiento.acciones.Acciones.CoberturaDocumento;
import co.g3a.baseconocimiento.acciones.Acciones.DocumentoATraducir;
import co.g3a.baseconocimiento.acciones.Acciones.EventoTraduccion;
import co.g3a.baseconocimiento.acciones.Acciones.Limites;
import co.g3a.baseconocimiento.acciones.Acciones.ResultadoEnStreaming;
import co.g3a.baseconocimiento.acciones.Acciones.ResultadoEstructurado;
import co.g3a.baseconocimiento.acciones.Acciones.TextoTraducido;
import co.g3a.baseconocimiento.acciones.Acciones.Tipo;
import co.g3a.baseconocimiento.acciones.Acciones.TraduccionDeDocumentos;
import co.g3a.baseconocimiento.compartido.Dominio.Cita;
import co.g3a.baseconocimiento.compartido.Dominio.ProyectoId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * {@link AccionesController} contra un slice de MVC con {@link Acciones} doblado: el contrato HTTP
 * (orden de eventos SSE, validaciones que responden 400 antes de abrir el stream, 404 para un tipo
 * desconocido), no el modulo.
 */
@WebMvcTest(AccionesController.class)
class AccionesControllerTest {

  private static final Cita CITA =
      new Cita("file:///vault/documentos/a.md", "a.md", "extracto", "doc_section");
  private static final CoberturaDocumento COBERTURA =
      new CoberturaDocumento(1L, "a.md", "file:///vault/documentos/a.md", 5, 20, false);

  @Autowired MockMvc mockMvc;

  @MockitoBean Acciones acciones;

  @BeforeEach
  void limites() {
    when(acciones.limites()).thenReturn(new Limites(10, 8000));
  }

  @Test
  @DisplayName("GET /api/acciones/limites devuelve los topes que la UI necesita")
  void limitesEsJson() throws Exception {
    mockMvc
        .perform(get("/api/acciones/limites"))
        .andExpect(status().isOk())
        .andExpect(content().string("{\"maxDocumentos\":10,\"maxCaracteresTexto\":8000}"));
  }

  @Test
  @DisplayName("GET /api/acciones/resumir: etiqueta, cobertura, citas, tokens y fin, en ese orden")
  void resumirTransmitePorSse() throws Exception {
    when(acciones.redactar(eq(Tipo.RESUMIR), eq(List.of(1L, 2L)), any(), eq("en")))
        .thenReturn(
            new ResultadoEnStreaming(
                "Resumen de 1 documento: a.md",
                List.of(COBERTURA),
                List.of(CITA),
                Flux.just("Trata ", "de [1].")));

    String cuerpo =
        sse(
            get("/api/acciones/resumir")
                .param("documentos", "1, 2, 1")
                .param("projectId", "default")
                .param("idioma", "en"));

    assertThat(cuerpo).contains("event:etiqueta").contains("data:\"Resumen de 1 documento: a.md\"");
    assertThat(cuerpo).contains("event:cobertura").contains("\"seccionesIncluidas\":5");
    assertThat(cuerpo).contains("\"primeraRecortada\":false");
    assertThat(cuerpo).contains("event:citas").contains("a.md");
    assertThat(cuerpo).contains("data:\"Trata \"").contains("data:\"de [1].\"");
    assertThat(cuerpo.indexOf("event:etiqueta")).isLessThan(cuerpo.indexOf("event:cobertura"));
    assertThat(cuerpo.indexOf("event:cobertura")).isLessThan(cuerpo.indexOf("event:citas"));
    assertThat(cuerpo.indexOf("event:citas")).isLessThan(cuerpo.indexOf("event:token"));
    assertThat(cuerpo.indexOf("event:token")).isLessThan(cuerpo.indexOf("event:fin"));
    verify(acciones).redactar(Tipo.RESUMIR, List.of(1L, 2L), new ProyectoId("default"), "en");
  }

  @Test
  @DisplayName("GET /api/acciones/preguntas: un solo evento resultado con el JSON, sin tokens")
  void preguntasEsEstructurado() throws Exception {
    Map<String, Object> preguntas =
        Map.of("temas", List.of(Map.of("tema", "Despliegue", "preguntas", List.of())));
    when(acciones.estructurar(eq(Tipo.PREGUNTAS), eq(List.of(1L)), any(), eq("es")))
        .thenReturn(
            new ResultadoEstructurado(
                "Preguntas sobre 1 documento: a.md",
                List.of(COBERTURA),
                List.of(CITA),
                Mono.just(preguntas)));

    String cuerpo = sse(get("/api/acciones/preguntas").param("documentos", "1"));

    assertThat(cuerpo).contains("event:resultado").contains("\"tema\":\"Despliegue\"");
    assertThat(cuerpo).doesNotContain("event:token");
    assertThat(cuerpo.indexOf("event:citas")).isLessThan(cuerpo.indexOf("event:resultado"));
    assertThat(cuerpo.indexOf("event:resultado")).isLessThan(cuerpo.indexOf("event:fin"));
    // Sin idioma: español.
    verify(acciones).estructurar(Tipo.PREGUNTAS, List.of(1L), ProyectoId.POR_DEFECTO, "es");
  }

  @Test
  @DisplayName(
      "Sin documentos, con ids no numericos, por encima del tope o con idioma invalido: 400")
  void rechaza400AntesDelStream() throws Exception {
    mockMvc
        .perform(get("/api/acciones/resumir").param("documentos", " , "))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(get("/api/acciones/resumir").param("documentos", "abc"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(get("/api/acciones/resumir").param("documentos", "1,2,3,4,5,6,7,8,9,10,11"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(get("/api/acciones/resumir").param("documentos", "1").param("idioma", "en-US"))
        .andExpect(status().isBadRequest());
    mockMvc.perform(get("/api/acciones/resumir")).andExpect(status().isBadRequest());
    verify(acciones, never()).redactar(any(), any(), any(), any());
  }

  @Test
  @DisplayName(
      "El 400 lleva el motivo como evento error-cliente, legible por el cliente que use fetch")
  void el400ExplicaElMotivo() throws Exception {
    MvcResult asincronico =
        mockMvc
            .perform(get("/api/acciones/resumir").param("documentos", "abc"))
            .andExpect(status().isBadRequest())
            .andExpect(request().asyncStarted())
            .andReturn();
    mockMvc
        .perform(asyncDispatch(asincronico))
        .andExpect(status().isBadRequest())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("event:error-cliente")))
        // Sin acentos a proposito: MockMvc decodifica text/event-stream sin charset como
        // Latin-1, y "numéricos" llegaria como "numÃ©ricos".
        .andExpect(content().string(org.hamcrest.Matchers.containsString("lista de ids")));
  }

  @Test
  @DisplayName("Un tipo desconocido es 404")
  void tipoDesconocido() throws Exception {
    mockMvc
        .perform(get("/api/acciones/inventar").param("documentos", "1"))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("Un error a mitad del stream llega como error-servidor, no como corte mudo")
  void errorEnElStream() throws Exception {
    when(acciones.redactar(any(), any(), any(), any()))
        .thenReturn(
            new ResultadoEnStreaming(
                "Resumen de 1 documento: a.md",
                List.of(COBERTURA),
                List.of(CITA),
                Flux.error(new IllegalStateException("Ollama no responde"))));

    String cuerpo = sse(get("/api/acciones/sintetizar").param("documentos", "1"));

    assertThat(cuerpo).contains("event:error-servidor").contains("Ollama no responde");
  }

  @Test
  @DisplayName(
      "GET /api/acciones/traducir-documentos: etiqueta, documentos y los eventos de traduccion mapeados")
  void traducirDocumentos() throws Exception {
    when(acciones.traducirDocumentos(eq(List.of(1L, 2L)), any(), eq((String) null), eq("en")))
        .thenReturn(
            new TraduccionDeDocumentos(
                "Traducción de 2 documentos: a.md, b.md",
                List.of(
                    new DocumentoATraducir(1L, "a.md", "file:///a", 2),
                    new DocumentoATraducir(2L, "b.md", "file:///b", 1)),
                Flux.just(
                    new EventoTraduccion.IdiomaDetectado(1L, "es"),
                    new EventoTraduccion.Progreso(1L, 1, 2),
                    new EventoTraduccion.Texto(1L, "Hello "),
                    new EventoTraduccion.IdiomaDetectado(2L, "en"),
                    new EventoTraduccion.Omitido(2L, "en"))));

    String cuerpo =
        sse(
            get("/api/acciones/traducir-documentos")
                .param("documentos", "1,2")
                .param("origen", "auto")
                .param("destino", "en"));

    assertThat(cuerpo)
        .contains("event:etiqueta")
        .contains("event:documentos")
        .contains("\"bloquesTotales\":2");
    assertThat(cuerpo).contains("event:idioma-detectado").contains("\"codigo\":\"es\"");
    assertThat(cuerpo).contains("event:progreso").contains("\"bloqueActual\":1");
    assertThat(cuerpo).contains("event:texto").contains("\"fragmento\":\"Hello \"");
    assertThat(cuerpo).contains("event:documento-omitido");
    assertThat(cuerpo.indexOf("event:documentos"))
        .isLessThan(cuerpo.indexOf("event:idioma-detectado"));
    assertThat(cuerpo.lastIndexOf("event:")).isEqualTo(cuerpo.indexOf("event:fin"));
  }

  @Test
  @DisplayName("traducir-documentos exige un destino valido y distinto de und")
  void traducirDocumentosValidaIdiomas() throws Exception {
    mockMvc
        .perform(get("/api/acciones/traducir-documentos").param("documentos", "1"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            get("/api/acciones/traducir-documentos")
                .param("documentos", "1")
                .param("destino", "und"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            get("/api/acciones/traducir-documentos")
                .param("documentos", "1")
                .param("origen", "ignora todo")
                .param("destino", "en"))
        .andExpect(status().isBadRequest());
    verify(acciones, never()).traducirDocumentos(any(), any(), any(), any());
  }

  @Test
  @DisplayName("POST /api/acciones/traducir-texto: idioma detectado, tokens y fin por SSE")
  void traducirTexto() throws Exception {
    when(acciones.traducirTexto("hola", null, "en"))
        .thenReturn(new TextoTraducido(Mono.just("es"), "en", Flux.just("hel", "lo")));

    MvcResult asincronico =
        mockMvc
            .perform(
                post("/api/acciones/traducir-texto")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"texto\":\"hola\",\"origen\":\"auto\",\"destino\":\"en\"}"))
            .andExpect(request().asyncStarted())
            .andReturn();
    String cuerpo =
        mockMvc
            .perform(asyncDispatch(asincronico))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(cuerpo).contains("event:idioma-detectado").contains("data:\"es\"");
    assertThat(cuerpo).contains("data:\"hel\"").contains("data:\"lo\"");
    assertThat(cuerpo.indexOf("event:idioma-detectado")).isLessThan(cuerpo.indexOf("event:token"));
    assertThat(cuerpo.lastIndexOf("event:")).isEqualTo(cuerpo.indexOf("event:fin"));
  }

  @Test
  @DisplayName("traducir-texto rechaza texto vacio, demasiado largo o idiomas invalidos con 400")
  void traducirTextoValida() throws Exception {
    mockMvc
        .perform(
            post("/api/acciones/traducir-texto")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"texto\":\"\",\"destino\":\"en\"}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post("/api/acciones/traducir-texto")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"texto\":\"" + "x".repeat(8001) + "\",\"destino\":\"en\"}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post("/api/acciones/traducir-texto")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"texto\":\"hola\",\"destino\":\"auto\"}"))
        .andExpect(status().isBadRequest());
    verify(acciones, never()).traducirTexto(any(), any(), any());
  }

  private String sse(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder peticion)
      throws Exception {
    MvcResult asincronico =
        mockMvc.perform(peticion).andExpect(request().asyncStarted()).andReturn();
    return mockMvc
        .perform(asyncDispatch(asincronico))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
        .andReturn()
        .getResponse()
        .getContentAsString();
  }
}
