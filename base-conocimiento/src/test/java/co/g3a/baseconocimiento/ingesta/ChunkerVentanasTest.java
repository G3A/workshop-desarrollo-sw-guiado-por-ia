package co.g3a.baseconocimiento.ingesta;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ChunkerVentanasTest {

    @Test
    void agrupaParrafosCortosEnUnaSolaVentana() {
        var texto = "Parrafo uno.\n\nParrafo dos.\n\nParrafo tres.";

        var ventanas = ChunkerVentanas.trocear(texto, 1_000);

        assertThat(ventanas).hasSize(1);
        assertThat(ventanas.get(0)).contains("Parrafo uno.", "Parrafo dos.", "Parrafo tres.");
    }

    @Test
    void abreNuevaVentanaAlSuperarElMaximo() {
        String parrafo = "y".repeat(60);
        // Tres parrafos de 60 caracteres con maximo 100: ninguno cabe con otro.
        var texto = String.join("\n\n", parrafo, parrafo, parrafo);

        var ventanas = ChunkerVentanas.trocear(texto, 100);

        assertThat(ventanas).hasSize(3);
        assertThat(ventanas).allSatisfy(v -> assertThat(v.length()).isLessThanOrEqualTo(100));
    }

    @Test
    void unParrafoUnicoMasGrandeQueElMaximoSeCortaPorCaracteres() {
        // Simula lo tipico de un PDF: un solo bloque largo sin saltos de linea reales.
        String textoSinSaltos = "z".repeat(250);

        var ventanas = ChunkerVentanas.trocear(textoSinSaltos, 100);

        assertThat(ventanas).hasSize(3); // 100 + 100 + 50
        assertThat(String.join("", ventanas)).hasSize(250);
    }

    @Test
    void textoVacioProduceUnaVentanaVacia() {
        var ventanas = ChunkerVentanas.trocear("", 100);

        assertThat(ventanas).hasSize(1);
        assertThat(ventanas.get(0)).isEmpty();
    }
}
