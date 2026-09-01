package co.g3a.baseconocimiento.ingesta;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
 * {@link ConectorReposLocales} contra un repo Git real (JGit lo crea en un directorio temporal) y
 * un PostgreSQL real vía Testcontainers — el criterio de salida de F6 pide chunks consultables, no
 * un doble de la ingesta.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "kb.ingesta.worker.habilitado=false",
      "kb.recuperacion.terminos.habilitado=false"
    })
@Testcontainers
class ConectorReposLocalesTest {

  @Container @ServiceConnection
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer(
          DockerImageName.parse("pgvector/pgvector:pg18-trixie")
              .asCompatibleSubstituteFor("postgres"));

  @TempDir static Path vaultRoot;

  @DynamicPropertySource
  static void propiedades(DynamicPropertyRegistry registry) {
    registry.add("kb.ingesta.vault-dir", () -> vaultRoot.toString());
  }

  @Autowired ConectorReposLocales conector;

  @Autowired JdbcClient jdbc;

  @Test
  @DisplayName("Ingiere un repo con un commit, trocea el archivo y encola el embebido")
  void ingiereUnRepoNuevo() throws Exception {
    crearRepoConUnArchivo(
        "mi-repo",
        "Saludo.java",
        """
                public class Saludo {
                    void saludar() {
                        System.out.println("hola");
                    }
                }
                """);

    var resumen = conector.ingerir();

    assertThat(resumen.reposActualizados()).isGreaterThanOrEqualTo(1);
    assertThat(resumen.chunksCreados()).isGreaterThan(0);
    // Repos de otros tests en esta misma clase (base y directorio temporal
    // compartidos) ya estan al dia y no aportan chunks nuevos en esta corrida:
    // los nuevos son exactamente los de "mi-repo".
    assertThat(chunksDe("mi-repo")).isEqualTo((long) resumen.chunksCreados());
  }

  @Test
  @DisplayName("Una segunda corrida sin cambios no reingiere el repo")
  void segundaCorridaSinCambiosNoReingiere() throws Exception {
    crearRepoConUnArchivo("repo-estable", "A.java", "class A {\n}\n");

    conector.ingerir();
    long chunksTrasPrimeraCorrida = chunksDe("repo-estable");
    var segunda = conector.ingerir();

    assertThat(segunda.reposSinCambios()).isGreaterThanOrEqualTo(1);
    assertThat(chunksDe("repo-estable")).isEqualTo(chunksTrasPrimeraCorrida);
  }

  private void crearRepoConUnArchivo(String nombreRepo, String archivo, String contenido)
      throws Exception {
    Path repoPath = vaultRoot.resolve("repos").resolve(nombreRepo);
    Files.createDirectories(repoPath);
    try (Git git = Git.init().setDirectory(repoPath.toFile()).call()) {
      Files.writeString(repoPath.resolve(archivo), contenido);
      git.add().addFilepattern(".").call();
      git.commit()
          .setMessage("inicial")
          .setAuthor("Test", "test@example.com")
          .setSign(false)
          .call();
    }
  }

  private long chunksDe(String nombreFuente) {
    return jdbc.sql(
            """
                        SELECT count(*) FROM chunks c
                        JOIN sources s ON s.id = c.source_id
                        WHERE s.kind = 'local_git' AND s.name = :n
                        """)
        .param("n", nombreFuente)
        .query(Long.class)
        .single();
  }
}
