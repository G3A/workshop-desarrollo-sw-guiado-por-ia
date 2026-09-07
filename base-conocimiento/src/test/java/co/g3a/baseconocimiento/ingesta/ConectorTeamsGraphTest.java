package co.g3a.baseconocimiento.ingesta;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import co.g3a.baseconocimiento.llm.Destilador;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * {@link ConectorTeamsGraph} contra Microsoft Graph doblado con WireMock y un PostgreSQL real:
 * token de aplicación, delta query, respuestas del hilo, limpieza de HTML y el gate de bursting
 * (IDF >= 4.0, >= 200 caracteres). {@link Destilador} queda doblado — no hay Ollama en esta prueba,
 * igual que el resto de la ingesta no llama al LLM real.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "kb.ingesta.worker.habilitado=false",
      "kb.recuperacion.terminos.habilitado=false",
      "kb.graph.habilitado=true",
      "kb.graph.tenant-id=test-tenant",
      "kb.graph.client-id=client-de-prueba",
      "kb.graph.client-secret=secreto",
      "kb.graph.team-id=team1",
      "kb.graph.channel-id=chan1"
    })
@Testcontainers
class ConectorTeamsGraphTest {

  @Container @ServiceConnection
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer(
          DockerImageName.parse("pgvector/pgvector:pg18-trixie")
              .asCompatibleSubstituteFor("postgres"));

  @RegisterExtension
  static WireMockExtension wireMock =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  @DynamicPropertySource
  static void propiedades(DynamicPropertyRegistry registry) {
    registry.add("kb.graph.graph-base-url", wireMock::baseUrl);
    registry.add("kb.graph.login-base-url", wireMock::baseUrl);
  }

  @Autowired ConectorTeamsGraph conector;

  @Autowired JdbcClient jdbc;

  @MockitoBean Destilador destilador;

  // Debe superar los 200 caracteres del gate de bursting; se verifico contando: mas de 250.
  private static final String TEXTO_BURST =
      "desincronizacion del cluster de kubernetes durante el ultimo despliegue de la version nueva, "
          + "afecto a varios pods y hubo que reiniciar manualmente los nodos afectados para recuperar el "
          + "servicio, y quedo documentado en el runbook interno del equipo de plataforma para la proxima vez";

  @Test
  @DisplayName("Delta + respuestas: arma el hilo, destila y aplica el gate de bursting")
  void ingiereElHiloYAplicaBursting() {
    insertarTermStatAltoPara(TEXTO_BURST);

    when(destilador.destilar(any()))
        .thenReturn(
            new Destilador.Destilado(
                "como se despliega el servicio",
                "resumen del hilo",
                "reiniciar los nodos",
                List.of("kubernetes"),
                List.of()));

    wireMock.stubFor(
        post(urlEqualTo("/test-tenant/oauth2/v2.0/token"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"access_token\":\"tok\",\"expires_in\":3600}")));

    wireMock.stubFor(
        get(urlEqualTo("/teams/team1/channels/chan1/messages/delta"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "value": [
                            {
                              "id": "msg1",
                              "createdDateTime": "2026-01-01T00:00:00Z",
                              "from": {"user": {"displayName": "Alice"}},
                              "body": {"contentType": "html", "content": "<p>Como se despliega el servicio?</p>"}
                            }
                          ],
                          "@odata.deltaLink": "%s/deltaLinkGuardado"
                        }
                        """
                            .formatted(wireMock.baseUrl()))));

    wireMock.stubFor(
        get(urlEqualTo("/teams/team1/channels/chan1/messages/msg1/replies"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "value": [
                            {
                              "id": "reply1",
                              "replyToId": "msg1",
                              "createdDateTime": "2026-01-01T00:01:00Z",
                              "from": {"user": {"displayName": "Bob"}},
                              "body": {"contentType": "html", "content": "<p>ok gracias</p>"}
                            },
                            {
                              "id": "reply2",
                              "replyToId": "msg1",
                              "createdDateTime": "2026-01-01T00:02:00Z",
                              "from": {"user": {"displayName": "Carol"}},
                              "body": {"contentType": "html", "content": "<p>%s</p>"}
                            }
                          ]
                        }
                        """
                            .formatted(TEXTO_BURST))));

    var resumen = conector.ingerir();

    assertThat(resumen.hilosVistos()).isEqualTo(1);
    assertThat(resumen.hilosActualizados()).isEqualTo(1);
    // 1 chunk 'thread' (el hilo completo destilado) + 1 'thread_burst' (solo reply2, que
    // supera el umbral de longitud y de IDF; reply1 es demasiado corto para el gate).
    assertThat(resumen.chunksCreados()).isEqualTo(2);

    List<String> tipos =
        jdbc.sql(
                """
                        SELECT c.kind FROM chunks c
                        JOIN sources s ON s.id = c.source_id
                        WHERE s.kind = 'teams_channel' ORDER BY c.ord
                        """)
            .query(String.class)
            .list();
    assertThat(tipos).containsExactly("thread", "thread_burst");

    String syncState =
        jdbc.sql("SELECT sync_state::text FROM sources WHERE kind = 'teams_channel'")
            .query(String.class)
            .single();
    assertThat(syncState).contains("deltaLinkGuardado");
  }

  private void insertarTermStatAltoPara(String texto) {
    String lexema =
        jdbc.sql(
                """
                        SELECT word FROM ts_stat(
                            'SELECT to_tsvector(''spanish'', ' || quote_literal(:texto) || ')'
                        ) ORDER BY word LIMIT 1
                        """)
            .param("texto", texto)
            .query(String.class)
            .single();
    jdbc.sql(
            """
                        INSERT INTO term_stats (term, df, idf) VALUES (:t, 1, 10.0)
                        ON CONFLICT (term) DO UPDATE SET idf = 10.0
                        """)
        .param("t", lexema)
        .update();
  }
}
