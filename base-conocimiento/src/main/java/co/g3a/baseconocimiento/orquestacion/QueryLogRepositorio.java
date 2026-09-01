package co.g3a.baseconocimiento.orquestacion;

import co.g3a.baseconocimiento.compartido.Dominio.Cita;
import co.g3a.baseconocimiento.compartido.Dominio.Fragmento;
import co.g3a.baseconocimiento.llm.Planificador.PlanDeHerramientas;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Etapa 7: el registro de auditoría. Sin esto no se depuran respuestas malas a escala — el artículo
 * lo señala explícitamente, y es la razón de que {@code query_log} exista desde el esquema de F0.
 */
@Repository
class QueryLogRepositorio {

  private final JdbcClient jdbc;

  QueryLogRepositorio(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  long registrar(
      String pregunta,
      String projectId,
      PlanDeHerramientas plan,
      List<Executor.EjecucionHerramienta> herramientas,
      List<Fragmento> fragmentosUsados,
      String respuesta,
      List<Cita> citas,
      long latenciaMs) {

    List<Map<String, Object>> toolsRun =
        herramientas.stream()
            .map(
                h ->
                    Map.<String, Object>of(
                        "nombre",
                        h.nombre(),
                        "fragmentos",
                        h.fragmentos().size(),
                        "duracionMs",
                        h.duracionMs(),
                        "error",
                        h.error() == null ? "" : h.error()))
            .toList();

    List<Map<String, Object>> candidatos =
        fragmentosUsados.stream()
            .map(
                f ->
                    Map.<String, Object>of(
                        "id",
                        f.id(),
                        "uri",
                        f.uri(),
                        "titulo",
                        f.titulo() == null ? "" : f.titulo(),
                        "rrf",
                        f.rrf(),
                        "rerank",
                        f.rerank() == null ? -1.0 : f.rerank()))
            .toList();

    // adapter='web': este controller es operativo dentro de `orquestacion` (igual que
    // IngestaController en `ingesta`), pero ya expone la misma respuesta HTTP que
    // consumira el adaptador web de F4 -- el esquema solo admite 'web' o 'teams'.
    return jdbc.sql(
            """
                        INSERT INTO query_log
                            (question, project_id, adapter, plan, tools_run, candidates, answer,
                             citations, latency_ms)
                        VALUES
                            (:question, :projectId, 'web', CAST(:plan AS jsonb), CAST(:toolsRun AS jsonb),
                             CAST(:candidates AS jsonb), :answer, CAST(:citations AS jsonb), :latencyMs)
                        RETURNING id
                        """)
        .param("question", pregunta)
        .param("projectId", projectId)
        .param("plan", Json.escribir(plan))
        .param("toolsRun", Json.escribir(toolsRun))
        .param("candidates", Json.escribir(candidatos))
        .param("answer", respuesta)
        .param("citations", Json.escribir(citas))
        .param("latencyMs", latenciaMs)
        .query(Long.class)
        .single();
  }
}
