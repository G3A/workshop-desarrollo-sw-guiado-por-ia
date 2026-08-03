package co.g3a.baseconocimiento.orquestacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import co.g3a.baseconocimiento.compartido.Dominio.Fragmento;

class FusionDeHerramientasTest {

    @Test
    @DisplayName("Deduplica por id entre herramientas, quedandose con el puntaje mas alto")
    void deduplicaPorIdConElMejorPuntaje() {
        Fragmento debil = fragmento(1, 0.3, null);
        Fragmento fuerte = fragmento(1, 0.3, 8.0);

        List<Fragmento> combinado = FusionDeHerramientas.combinar(
                List.of(List.of(debil), List.of(fuerte)), 10);

        assertThat(combinado).hasSize(1);
        assertThat(combinado.get(0).rerank()).isEqualTo(8.0);
    }

    @Test
    @DisplayName("Ordena por rerank cuando existe, y por rrf cuando la herramienta no rerankeo")
    void ordenaPorElMejorPuntajeDisponible() {
        // sinRerank (0.5) queda entre los dos con rerank porque la comparacion
        // usa el mismo numero para ambas escalas -- una aproximacion documentada
        // en FusionDeHerramientas.puntaje(), no una equivalencia real entre RRF y
        // el cross-encoder.
        Fragmento conRerankAlto = fragmento(1, 0.01, 9.0);
        Fragmento sinRerank = fragmento(2, 0.5, null);
        Fragmento conRerankBajo = fragmento(3, 0.01, 0.2);

        List<Fragmento> combinado = FusionDeHerramientas.combinar(
                List.of(List.of(conRerankBajo, conRerankAlto), List.of(sinRerank)), 10);

        assertThat(combinado).extracting(Fragmento::id).containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("Respeta el tope maximo de fragmentos")
    void respetaElTope() {
        List<Fragmento> muchos = List.of(
                fragmento(1, 0.9, null), fragmento(2, 0.8, null), fragmento(3, 0.7, null));

        List<Fragmento> combinado = FusionDeHerramientas.combinar(List.of(muchos), 2);

        assertThat(combinado).hasSize(2);
        assertThat(combinado).extracting(Fragmento::id).containsExactly(1L, 2L);
    }

    private static Fragmento fragmento(long id, double rrf, Double rerank) {
        return new Fragmento(id, id, "file:///" + id, "titulo " + id, "texto " + id, "doc_section",
                0, Instant.EPOCH, Map.of(), rrf, rerank);
    }
}
