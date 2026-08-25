package co.g3a.baseconocimiento.ingesta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El candado por tipo de fuente, sin Spring ni base de datos: los cuatro
 * conectores quedan doblados. Esto es lo único que {@link RelevadorDeFuentes}
 * necesita para verificar su contrato — a qué conector delega cada tipo, que
 * un fallo no se propaga, y que dos relevos del mismo tipo no corren a la vez.
 */
class RelevadorDeFuentesTest {

    @Test
    @DisplayName("relevarTodas corre los cuatro conectores, uno por tipo conocido")
    void relevarTodasCorreLosCuatroConectores() {
        var documentos = mock(ConectorDocumentosLocales.class);
        var repos = mock(ConectorReposLocales.class);
        var teams = mock(ConectorTeamsGraph.class);
        var azdo = mock(ConectorAzureDevOps.class);
        when(documentos.ingerir()).thenReturn(new ConectorDocumentosLocales.Resumen(1, 1, 0, 0, 1));
        when(repos.ingerir()).thenReturn(new ConectorReposLocales.Resumen(0, 0, 0, 0));
        when(teams.ingerir()).thenReturn(new ConectorTeamsGraph.Resumen(0, 0, 0));
        when(azdo.ingerir()).thenReturn(new ConectorAzureDevOps.Resumen(0, 0, 0, 0, 0));
        var relevador = new RelevadorDeFuentes(documentos, repos, teams, azdo);

        var resultados = relevador.relevarTodas();

        assertThat(resultados).hasSize(4);
        assertThat(resultados).extracting(RelevadorDeFuentes.ResultadoRelevo::tipo)
                .containsExactly("local_docs", "local_git", "teams_channel", "azure_devops");
        assertThat(resultados).allMatch(RelevadorDeFuentes.ResultadoRelevo::ejecutado);
    }

    @Test
    @DisplayName("Un conector que falla no tumba el relevo: el error queda en el resultado")
    void unConectorQueFallaNoTumbaElRelevo() {
        var documentos = mock(ConectorDocumentosLocales.class);
        when(documentos.ingerir()).thenThrow(new IllegalStateException("disco no disponible"));
        var relevador = new RelevadorDeFuentes(
                documentos, mock(ConectorReposLocales.class),
                mock(ConectorTeamsGraph.class), mock(ConectorAzureDevOps.class));

        var resultado = relevador.relevar("local_docs");

        assertThat(resultado.ejecutado()).isFalse();
        assertThat(resultado.error()).contains("disco no disponible");
    }

    @Test
    @DisplayName("Dos relevos concurrentes del mismo tipo no corren a la vez")
    void dosRelevosDelMismoTipoNoCorrenALaVez() throws Exception {
        var documentos = mock(ConectorDocumentosLocales.class);
        CountDownLatch primerRelevoEnCurso = new CountDownLatch(1);
        CountDownLatch dejarTerminarPrimerRelevo = new CountDownLatch(1);
        when(documentos.ingerir()).thenAnswer(invocacion -> {
            primerRelevoEnCurso.countDown();
            dejarTerminarPrimerRelevo.await(5, TimeUnit.SECONDS);
            return new ConectorDocumentosLocales.Resumen(0, 0, 0, 0, 0);
        });
        var relevador = new RelevadorDeFuentes(
                documentos, mock(ConectorReposLocales.class),
                mock(ConectorTeamsGraph.class), mock(ConectorAzureDevOps.class));

        ExecutorService hilos = Executors.newSingleThreadExecutor();
        try {
            var primerRelevo = hilos.submit(() -> relevador.relevar("local_docs"));
            assertThat(primerRelevoEnCurso.await(5, TimeUnit.SECONDS)).isTrue();

            var segundoRelevo = relevador.relevar("local_docs");
            assertThat(segundoRelevo.ejecutado()).isFalse();
            assertThat(segundoRelevo.error()).contains("ya hay un relevo");

            dejarTerminarPrimerRelevo.countDown();
            assertThat(primerRelevo.get(5, TimeUnit.SECONDS).ejecutado()).isTrue();
        } finally {
            hilos.shutdownNow();
        }
    }

    @Test
    @DisplayName("Un tipo desconocido no lanza excepcion: queda como error en el resultado")
    void unTipoDesconocidoQuedaComoError() {
        var relevador = new RelevadorDeFuentes(
                mock(ConectorDocumentosLocales.class), mock(ConectorReposLocales.class),
                mock(ConectorTeamsGraph.class), mock(ConectorAzureDevOps.class));

        var resultado = relevador.relevar("fuente_inventada");

        assertThat(resultado.ejecutado()).isFalse();
        assertThat(resultado.error()).contains("Tipo de fuente desconocido");
    }
}
