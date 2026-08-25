package co.g3a.baseconocimiento.orquestacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import co.g3a.baseconocimiento.compartido.Dominio.Fragmento;
import co.g3a.baseconocimiento.compartido.Dominio.ProyectoId;

/**
 * El fan-out y el aislamiento de fallos del {@link Executor}, sin Spring ni
 * base de datos: herramientas de mentira que se comportan bien o mal a
 * propósito.
 */
class ExecutorTest {

    private static final ProyectoId PROYECTO = new ProyectoId("default");

    @Test
    @DisplayName("Corre todas las herramientas elegidas y aisla la que falla")
    void aislaElFalloDeUnaHerramienta() {
        var catalogo = new CatalogoHerramientas(List.of(
                herramientaQueDevuelve("buena", fragmento(1)),
                herramientaQueFalla("mala")));
        var executor = new Executor(catalogo);

        List<Executor.EjecucionHerramienta> resultado =
                executor.ejecutar(List.of("buena", "mala"), "consulta", PROYECTO, List.of());

        assertThat(resultado).hasSize(2);

        var buena = porNombre(resultado, "buena");
        assertThat(buena.error()).isNull();
        assertThat(buena.fragmentos()).extracting(Fragmento::id).containsExactly(1L);

        var mala = porNombre(resultado, "mala");
        assertThat(mala.error()).isNotNull();
        assertThat(mala.fragmentos()).isEmpty();
    }

    @Test
    @DisplayName("Si el plan no resuelve ninguna herramienta, cae a search_unified")
    void caeASearchUnifiedSiElPlanNoResuelveNada() {
        var catalogo = new CatalogoHerramientas(List.of(
                herramientaQueDevuelve("search_unified", fragmento(9))));
        var executor = new Executor(catalogo);

        List<Executor.EjecucionHerramienta> resultado =
                executor.ejecutar(List.of("herramienta_que_no_existe"), "consulta", PROYECTO, List.of());

        assertThat(resultado).hasSize(1);
        assertThat(resultado.get(0).nombre()).isEqualTo("search_unified");
        assertThat(resultado.get(0).fragmentos()).extracting(Fragmento::id).containsExactly(9L);
    }

    private static Executor.EjecucionHerramienta porNombre(List<Executor.EjecucionHerramienta> lista, String nombre) {
        return lista.stream().filter(e -> e.nombre().equals(nombre)).findFirst().orElseThrow();
    }

    private static Herramienta herramientaQueDevuelve(String nombre, Fragmento... fragmentos) {
        return new Herramienta() {
            @Override
            public String nombre() {
                return nombre;
            }

            @Override
            public String descripcion() {
                return "de prueba";
            }

            @Override
            public List<Fragmento> ejecutar(String consulta, ProyectoId proyecto, List<Long> documentosPermitidos) {
                return List.of(fragmentos);
            }
        };
    }

    private static Herramienta herramientaQueFalla(String nombre) {
        return new Herramienta() {
            @Override
            public String nombre() {
                return nombre;
            }

            @Override
            public String descripcion() {
                return "de prueba";
            }

            @Override
            public List<Fragmento> ejecutar(String consulta, ProyectoId proyecto, List<Long> documentosPermitidos) {
                throw new IllegalStateException("fallo simulado");
            }
        };
    }

    private static Fragmento fragmento(long id) {
        return new Fragmento(id, id, "file:///" + id, "titulo " + id, "texto " + id, "doc_section",
                0, Instant.EPOCH, Map.of(), 0.01, null);
    }
}
