package co.g3a.baseconocimiento.ingesta;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ChunkerEncabezadosTest {

    @Test
    void unaSolaSeccionCuandoNoHayEncabezados() {
        var secciones = ChunkerEncabezados.trocear("solo texto plano, sin encabezados");

        assertThat(secciones).hasSize(1);
        assertThat(secciones.get(0).rutaEncabezados()).isEmpty();
    }

    @Test
    void construyeLaRutaDeEncabezadosAnidados() {
        var texto = """
                # Guia de despliegue

                Introduccion general.

                ## Prerequisitos

                Necesitas Docker.

                ### Version minima

                Docker 24 o superior.

                ## Pasos

                Ejecuta el compose.
                """;

        var secciones = ChunkerEncabezados.trocear(texto);

        assertThat(secciones).extracting(ChunkerEncabezados.Seccion::rutaEncabezados).containsExactly(
                java.util.List.of("Guia de despliegue"),
                java.util.List.of("Guia de despliegue", "Prerequisitos"),
                java.util.List.of("Guia de despliegue", "Prerequisitos", "Version minima"),
                java.util.List.of("Guia de despliegue", "Pasos"));
    }

    @Test
    void unEncabezadoDeIgualNivelCierraElAnterior() {
        // "Pasos" es h2, igual que "Prerequisitos": debe reemplazarlo en la pila,
        // no anidarse dentro de el.
        var texto = """
                # Raiz
                ## Prerequisitos
                cuerpo uno
                ## Pasos
                cuerpo dos
                """;

        var secciones = ChunkerEncabezados.trocear(texto);

        assertThat(secciones).extracting(ChunkerEncabezados.Seccion::rutaEncabezados).containsExactly(
                java.util.List.of("Raiz", "Prerequisitos"),
                java.util.List.of("Raiz", "Pasos"));
    }

    @Test
    void unaSeccionDemasiadoGrandeSeSubdivide() {
        String parrafo = "x".repeat(100);
        String cuerpoGrande = (parrafo + "\n\n").repeat(60); // > 4000 caracteres
        var texto = "# Titulo\n\n" + cuerpoGrande;

        var secciones = ChunkerEncabezados.trocear(texto);

        assertThat(secciones).hasSizeGreaterThan(1);
        assertThat(secciones).allSatisfy(s -> assertThat(s.rutaEncabezados()).containsExactly("Titulo"));
    }

    @Test
    void ignoraLineasQueParecenEncabezadosSinEspacio() {
        // "#sin-espacio" no es un encabezado valido de Markdown.
        var secciones = ChunkerEncabezados.trocear("#sin-espacio\ncontenido normal");

        assertThat(secciones).hasSize(1);
        assertThat(secciones.get(0).rutaEncabezados()).isEmpty();
        assertThat(secciones.get(0).cuerpo()).contains("#sin-espacio");
    }
}
