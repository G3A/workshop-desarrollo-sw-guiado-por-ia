package co.g3a.baseconocimiento.ingesta;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChunkerCodigoTest {

    @Test
    @DisplayName("Sin ninguna clase detectable, cae a ventanas sobre el archivo entero")
    void sinClaseCaeAVentanas() {
        String script = "def saluda():\n    print('hola')\n";

        var bloques = ChunkerCodigo.trocear(script);

        assertThat(bloques).hasSize(1);
        assertThat(bloques.get(0).ruta()).isNull();
        assertThat(bloques.get(0).cuerpo()).contains("saluda");
    }

    @Test
    @DisplayName("Grano grueso: una clase chica queda en un solo bloque, con su declaracion como ruta")
    void claseChicaQuedaEnUnSoloBloque() {
        String java = """
                public class Calculadora {
                    int sumar(int a, int b) {
                        return a + b;
                    }
                }
                """;

        var bloques = ChunkerCodigo.trocear(java);

        assertThat(bloques).hasSize(1);
        assertThat(bloques.get(0).ruta()).contains("class Calculadora");
        assertThat(bloques.get(0).cuerpo()).contains("sumar");
    }

    @Test
    @DisplayName("Dos clases en el mismo archivo producen dos bloques independientes")
    void dosClasesProducenDosBloques() {
        String java = """
                class Uno {
                    void metodoUno() {
                    }
                }
                class Dos {
                    void metodoDos() {
                    }
                }
                """;

        var bloques = ChunkerCodigo.trocear(java);

        assertThat(bloques).hasSize(2);
        assertThat(bloques.get(0).cuerpo()).contains("metodoUno").doesNotContain("metodoDos");
        assertThat(bloques.get(1).cuerpo()).contains("metodoDos").doesNotContain("metodoUno");
    }

    @Test
    @DisplayName("Grano fino: una clase que excede el maximo se subdivide por metodo")
    void claseGrandeSeSubdividePorMetodo() {
        StringBuilder relleno = new StringBuilder();
        relleno.append("public class Grande {\n");
        for (int i = 0; i < 200; i++) {
            relleno.append("    // linea de relleno para forzar el tamano maximo de la clase\n");
        }
        relleno.append("""
                    void metodoA() {
                        int x = 1;
                    }

                    void metodoB() {
                        int y = 2;
                    }
                }
                """);

        var bloques = ChunkerCodigo.trocear(relleno.toString());

        assertThat(bloques.size()).isGreaterThan(1);
        assertThat(bloques).anySatisfy(b -> {
            assertThat(b.ruta()).contains("class Grande").contains("metodoA");
            assertThat(b.cuerpo()).contains("int x = 1");
        });
        assertThat(bloques).anySatisfy(b -> {
            assertThat(b.ruta()).contains("class Grande").contains("metodoB");
            assertThat(b.cuerpo()).contains("int y = 2");
        });
    }
}
