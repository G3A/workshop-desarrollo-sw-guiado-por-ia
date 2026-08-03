package co.g3a.baseconocimiento.ingesta;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Cliente HTTP hacia la REST API de Azure DevOps: autenticación Basic con un
 * Personal Access Token — a diferencia de Graph y del Bot Connector, Azure
 * DevOps no exige un intercambio {@code client_credentials} previo.
 */
@Component
class ClienteAzureDevOps {

    private final AzureDevOpsPropiedades propiedades;
    private final RestClient restClient = RestClient.create();

    ClienteAzureDevOps(AzureDevOpsPropiedades propiedades) {
        this.propiedades = propiedades;
    }

    String get(String path) {
        return restClient.get().uri(propiedades.baseUrl() + path)
                .header(HttpHeaders.AUTHORIZATION, basicAuth())
                .retrieve().body(String.class);
    }

    String post(String path, String jsonBody) {
        return restClient.post().uri(propiedades.baseUrl() + path)
                .header(HttpHeaders.AUTHORIZATION, basicAuth())
                .contentType(MediaType.APPLICATION_JSON)
                .body(jsonBody)
                .retrieve().body(String.class);
    }

    private String basicAuth() {
        String credenciales = ":" + propiedades.pat();
        return "Basic " + Base64.getEncoder().encodeToString(credenciales.getBytes(StandardCharsets.UTF_8));
    }
}
