package co.g3a.baseconocimiento.ingesta;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * {@link ConectorAzureDevOps} contra la REST API de Azure DevOps doblada con WireMock: WIQL + work
 * items batch, y el árbol completo de páginas de wiki con su contenido, croceado por {@code
 * ChunkerEncabezados}.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "kb.ingesta.worker.habilitado=false",
      "kb.recuperacion.terminos.habilitado=false",
      "kb.azdo.habilitado=true",
      "kb.azdo.org=myorg",
      "kb.azdo.proyecto=myproj",
      "kb.azdo.pat=mypat",
      "kb.azdo.wiki=mywiki"
    })
@Testcontainers
class ConectorAzureDevOpsTest {

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
    registry.add("kb.azdo.base-url", wireMock::baseUrl);
  }

  @Autowired ConectorAzureDevOps conector;

  @Autowired JdbcClient jdbc;

  @Test
  @DisplayName("Work items por WIQL + paginas de wiki: ambos quedan como chunks consultables")
  void ingiereWorkItemsYWiki() {
    wireMock.stubFor(
        post(urlPathEqualTo("/myorg/myproj/_apis/wit/wiql"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"workItems": [{"id": 1, "url": "https://dev.azure.com/myorg/_apis/wit/workItems/1"}]}
                        """)));

    wireMock.stubFor(
        post(urlPathEqualTo("/myorg/_apis/wit/workitemsbatch"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "count": 1,
                          "value": [
                            {
                              "id": 1,
                              "fields": {
                                "System.Title": "Bug de login",
                                "System.Description": "<p>El login falla en <b>ciertos</b> casos</p>"
                              }
                            }
                          ]
                        }
                        """)));

    wireMock.stubFor(
        get(urlPathEqualTo("/myorg/myproj/_apis/wiki/wikis/mywiki/pages"))
            .withQueryParam("path", equalTo("/"))
            .withQueryParam("recursionLevel", equalTo("Full"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"path": "/", "content": "", "subPages": [{"path": "/Intro", "subPages": []}]}
                        """)));

    wireMock.stubFor(
        get(urlPathEqualTo("/myorg/myproj/_apis/wiki/wikis/mywiki/pages"))
            .withQueryParam("path", equalTo("/"))
            .withQueryParam("includeContent", equalTo("true"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {"path": "/", "content": ""}
                        """)));

    wireMock.stubFor(
        get(urlPathEqualTo("/myorg/myproj/_apis/wiki/wikis/mywiki/pages"))
            .withQueryParam("path", equalTo("/Intro"))
            .withQueryParam("includeContent", equalTo("true"))
            .willReturn(
                aResponse()
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        """
                        {
                          "path": "/Intro",
                          "content": "# Introduccion\\n\\nEsta wiki explica como desplegar el servicio en produccion.\\n\\n## Requisitos\\n\\nNecesitas Docker y acceso a la base de datos.\\n"
                        }
                        """)));

    var resumen = conector.ingerir();

    assertThat(resumen.workItemsVistos()).isEqualTo(1);
    assertThat(resumen.workItemsActualizados()).isEqualTo(1);
    assertThat(resumen.paginasWikiVistas()).isEqualTo(2);
    assertThat(resumen.paginasWikiActualizadas()).isEqualTo(1);
    assertThat(resumen.chunksCreados()).isEqualTo(3);

    List<String> textosWorkItem =
        jdbc.sql(
                """
                        SELECT c.text FROM chunks c
                        JOIN sources s ON s.id = c.source_id
                        WHERE s.kind = 'azure_devops' AND s.name = 'work_items'
                        """)
            .query(String.class)
            .list();
    assertThat(textosWorkItem).hasSize(1);
    assertThat(textosWorkItem.get(0)).contains("Bug de login").contains("ciertos casos");

    List<String> tiposWiki =
        jdbc.sql(
                """
                        SELECT c.kind FROM chunks c
                        JOIN sources s ON s.id = c.source_id
                        WHERE s.kind = 'azure_devops' AND s.name = 'wiki:mywiki'
                        ORDER BY c.ord
                        """)
            .query(String.class)
            .list();
    assertThat(tiposWiki).containsExactly("wiki_section", "wiki_section");
  }
}
