package co.g3a.baseconocimiento.teams;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import co.g3a.baseconocimiento.compartido.Dominio.Cita;
import co.g3a.baseconocimiento.compartido.Dominio.Respuesta;

class TarjetaAdaptativaTest {

    @Test
    void construyeUnaAccionOpenUrlPorCadaCita() {
        Cita cita1 = new Cita("file:///doc1", "Doc 1", "extracto 1", "doc_section");
        Cita cita2 = new Cita("file:///doc2", "Doc 2", "extracto 2", "doc_section");
        Respuesta respuesta = new Respuesta("la respuesta [1][2]", List.of(cita1, cita2), List.of(), 1234, null);

        Activity.Attachment tarjeta = TarjetaAdaptativa.desde(respuesta);

        assertThat(tarjeta.contentType()).isEqualTo(Activity.CONTENT_TYPE_ADAPTIVE_CARD);
        @SuppressWarnings("unchecked")
        Map<String, Object> contenido = (Map<String, Object>) tarjeta.content();
        assertThat(contenido.get("type")).isEqualTo("AdaptiveCard");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cuerpo = (List<Map<String, Object>>) contenido.get("body");
        assertThat(cuerpo).hasSize(1);
        assertThat(cuerpo.get(0).get("text")).isEqualTo("la respuesta [1][2]");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> acciones = (List<Map<String, Object>>) contenido.get("actions");
        assertThat(acciones).hasSize(2);
        assertThat(acciones.get(0).get("title")).isEqualTo("Doc 1");
        assertThat(acciones.get(0).get("url")).isEqualTo("file:///doc1");
    }

    @Test
    void agregaUnBloqueDeAdvertenciasCuandoLasHay() {
        Respuesta respuesta = new Respuesta("texto", List.of(), List.of("info desactualizada"), 1, null);

        Activity.Attachment tarjeta = TarjetaAdaptativa.desde(respuesta);

        @SuppressWarnings("unchecked")
        Map<String, Object> contenido = (Map<String, Object>) tarjeta.content();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cuerpo = (List<Map<String, Object>>) contenido.get("body");
        assertThat(cuerpo).hasSize(2);
        assertThat(cuerpo.get(1).get("text").toString()).contains("info desactualizada");
        assertThat(contenido).doesNotContainKey("actions");
    }
}
