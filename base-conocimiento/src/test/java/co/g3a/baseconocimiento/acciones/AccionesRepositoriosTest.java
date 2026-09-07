package co.g3a.baseconocimiento.acciones;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * El SQL propio del modulo contra un PostgreSQL real, mismo esquema que {@code
 * OrquestacionRepositoriosTest}: las secciones de los documentos elegidos, en el orden en que
 * fueron troceadas, y solo las del proyecto pedido.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "kb.ingesta.worker.habilitado=false",
      "kb.recuperacion.terminos.habilitado=false"
    })
@Testcontainers
class AccionesRepositoriosTest {

  @Container @ServiceConnection
  static PostgreSQLContainer postgres =
      new PostgreSQLContainer(
          DockerImageName.parse("pgvector/pgvector:pg18-trixie")
              .asCompatibleSubstituteFor("postgres"));

  @Autowired JdbcClient jdbc;

  @Autowired SeccionesRepositorio repo;

  @Test
  @DisplayName(
      "Trae todas las secciones de los documentos pedidos, por documento y ord; ignora los que no existen")
  void seccionesDeLosDocumentosElegidos() {
    long fuente = crearFuente("local_docs", "docs-acciones");
    long docA = crearDocumento(fuente, "acciones-a");
    long docB = crearDocumento(fuente, "acciones-b");
    long docAjeno = crearDocumento(fuente, "acciones-ajeno");
    insertarChunkEnDocumento(docA, fuente, 1, "a uno");
    insertarChunkEnDocumento(docA, fuente, 0, "a cero");
    insertarChunkEnDocumento(docB, fuente, 0, "b cero");
    insertarChunkEnDocumento(docAjeno, fuente, 0, "no pedido");

    List<SeccionesRepositorio.Seccion> secciones =
        repo.seccionesDe(List.of(docB, docA, 999_999L), "default");

    assertThat(secciones)
        .extracting(
            SeccionesRepositorio.Seccion::documentoId,
            SeccionesRepositorio.Seccion::ord,
            SeccionesRepositorio.Seccion::texto)
        .containsExactly(
            tuple(docA, 0, "a cero"), tuple(docA, 1, "a uno"), tuple(docB, 0, "b cero"));
    assertThat(secciones.get(0).titulo()).isEqualTo("acciones-a");
    assertThat(secciones.get(0).uri()).isEqualTo("file:///acciones-a");
    assertThat(secciones.get(0).tipo()).isEqualTo("doc_section");
  }

  @Test
  @DisplayName("Filtra por proyecto: un id de otro proyecto no aparece aunque exista")
  void filtraPorProyecto() {
    long fuente = crearFuente("local_docs", "docs-acciones-proyecto");
    long doc = crearDocumento(fuente, "acciones-proyecto");
    insertarChunkEnDocumento(doc, fuente, 0, "texto");

    assertThat(repo.seccionesDe(List.of(doc), "otro-proyecto-inexistente")).isEmpty();
  }

  @Test
  @DisplayName("Con la lista vacia devuelve vacio sin consultar la base")
  void listaVacia() {
    assertThat(repo.seccionesDe(List.of(), "default")).isEmpty();
  }

  private long crearFuente(String kind, String nombre) {
    return jdbc.sql("INSERT INTO sources (kind, name) VALUES (:kind, :nombre) RETURNING id")
        .param("kind", kind)
        .param("nombre", nombre)
        .query(Long.class)
        .single();
  }

  private long crearDocumento(long fuenteId, String externalId) {
    return jdbc.sql(
            """
            INSERT INTO documents (source_id, external_id, uri, title, raw_text, content_hash)
            VALUES (:fuenteId, :e, 'file:///' || :e, :e, 'crudo', :e)
            RETURNING id
            """)
        .param("fuenteId", fuenteId)
        .param("e", externalId)
        .query(Long.class)
        .single();
  }

  private void insertarChunkEnDocumento(long documentoId, long fuenteId, int ord, String texto) {
    jdbc.sql(
            """
            INSERT INTO chunks (document_id, source_id, ord, kind, text, distilled)
            VALUES (:documentoId, :fuenteId, :ord, 'doc_section', :texto, '{}'::jsonb)
            """)
        .param("documentoId", documentoId)
        .param("fuenteId", fuenteId)
        .param("ord", ord)
        .param("texto", texto)
        .update();
  }
}
