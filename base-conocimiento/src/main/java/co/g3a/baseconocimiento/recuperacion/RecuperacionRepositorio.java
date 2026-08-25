package co.g3a.baseconocimiento.recuperacion;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.pgvector.PGvector;

/**
 * El SQL de las cuatro señales, más el recálculo por lote de {@code term_stats}
 * que alimenta la señal 3. Cada método de búsqueda ya devuelve su lista
 * ordenada por puntaje descendente — {@link RrfFusion} solo necesita el rango.
 *
 * <p>Deliberadamente sencillo, no el SQL más rápido posible: a la escala de un
 * taller (miles de chunks, no millones) un plan sin exotismos es más fácil de
 * auditar que uno optimizado a ciegas para un tamaño que este proyecto no
 * tiene.
 */
@Repository
class RecuperacionRepositorio {

    private final JdbcClient jdbc;

    RecuperacionRepositorio(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Señal 1: texto completo. {@code ts_rank_cd} sobre el GIN, configuración española.
     *
     * <p>{@code plainto_tsquery} combina sus lexemas con AND: alcanza con que uno
     * solo no comparta stem con el corpus (frecuente en preguntas en lenguaje
     * natural con verbos conjugados, p. ej. "despliega" stemea distinto que
     * "desplegar") para que la consulta entera devuelva cero filas. Relajar
     * directo a OR sobrecorrige: en una pregunta larga alcanza con matchear el
     * término más común del corpus (p. ej. "java" en un corpus de la propia
     * especificación de Java) para inundar de resultados sin relación real con
     * la pregunta (visto en vivo con "cuáles son los tipos primitivos en
     * java" — sin ningún chunk sobre tipos primitivos en el top, solo ruido
     * que comparte la palabra "java"). Por eso se exige cobertura: al menos
     * {@code N-1} de los {@code N} lexemas de la consulta (no todos, como
     * antes; no cualquiera, como una relajación plana) — suficiente para
     * absorber un solo término que no stemeó igual, sin abrir la puerta a
     * coincidencias de un único término genérico. {@code ts_rank_cd} sigue
     * rankeando arriba a los que matchean más lexemas.
     */
    List<CandidatoSenal> buscarPorFts(
            String consulta, String projectId, List<String> tipos, List<Long> documentos, int limite) {
        return jdbc.sql("""
                        WITH consulta AS (
                            SELECT plainto_tsquery('spanish', :consulta) AS q_and
                        ), terminos AS (
                            SELECT q_and,
                                   ARRAY(SELECT (regexp_matches(q_and::text, '''([^'']+)''', 'g'))[1]) AS lex
                            FROM consulta
                        ), relajado AS (
                            SELECT lex, to_tsquery('spanish', replace(q_and::text, ' & ', ' | ')) AS q,
                                   GREATEST(1, array_length(lex, 1) - 1) AS minimo
                            FROM terminos
                        )
                        SELECT c.id, c.document_id, d.uri, d.title, c.text, c.kind, c.ord, c.source_updated_at,
                               ts_rank_cd(c.fts, r.q) AS puntaje
                        FROM chunks c
                        JOIN documents d ON d.id = c.document_id
                        CROSS JOIN relajado r
                        WHERE c.project_id = :projectId AND c.fts @@ r.q %s %s
                          AND (SELECT count(*) FROM unnest(tsvector_to_array(c.fts)) w WHERE w = ANY (r.lex))
                              >= r.minimo
                        ORDER BY puntaje DESC
                        LIMIT :limite
                        """.formatted(filtroTipo(tipos), filtroDocumento(documentos)))
                .param("consulta", consulta).param("projectId", projectId).param("limite", limite)
                .param("tipos", tipos).param("documentos", documentos)
                .query(RecuperacionRepositorio::mapear).list();
    }

    /** Señal 2: densa. Distancia coseno sobre el HNSW; puntaje = similitud (1 - distancia). */
    List<CandidatoSenal> buscarPorVector(
            float[] embeddingConsulta, String projectId, List<String> tipos, List<Long> documentos, int limite) {
        var vector = new PGvector(embeddingConsulta);
        return jdbc.sql("""
                        SELECT c.id, c.document_id, d.uri, d.title, c.text, c.kind, c.ord, c.source_updated_at,
                               1 - (c.embedding <=> :embedding) AS puntaje
                        FROM chunks c
                        JOIN documents d ON d.id = c.document_id
                        WHERE c.project_id = :projectId AND c.embedding IS NOT NULL %s %s
                        ORDER BY c.embedding <=> :embedding
                        LIMIT :limite
                        """.formatted(filtroTipo(tipos), filtroDocumento(documentos)))
                .param("embedding", vector).param("projectId", projectId).param("limite", limite)
                .param("tipos", tipos).param("documentos", documentos)
                .query(RecuperacionRepositorio::mapear).list();
    }

    /**
     * Señal 3: supresión de relleno por IDF. Los léxemas de la consulta se
     * obtienen con {@code ts_stat} sobre {@code to_tsvector('spanish', ...)} de
     * la propia consulta — así quedan estemizados exactamente igual que los de
     * {@code chunks.fts} y {@code term_stats.term}, sin volver a pasarlos por
     * {@code to_tsquery('spanish', ...)} una segunda vez (ver el hallazgo de F0
     * sobre el stemmer: reestemizar un léxemo ya estemizado no es idempotente).
     * El texto de la consulta entra a {@code ts_stat} vía {@code quote_literal},
     * el mecanismo propio de Postgres para incrustar un valor en SQL dinámico
     * sin riesgo de inyección — no concatenación de cadenas en Java.
     *
     * <p>El cruce final usa {@code plainto_tsquery('simple', ti.term)}, NO
     * {@code to_tsquery('simple', ti.term)}: el parser por defecto de Postgres
     * tokeniza patrones tipo "palabra.palabra:numero" (rutas de archivo,
     * referencias de linea de codigo — reales en un corpus con fragmentos de
     * codigo, p. ej. la especificacion de Java) como un solo lexema literal que
     * conserva el ":". {@code to_tsquery} interpreta ese ":" como el operador
     * de peso de su propia sintaxis y lanza {@code syntax error in tsquery} en
     * cuanto {@code term_stats} contiene un lexema asi — tumbando esta señal (y
     * con ella {@code search_unified} entero) para CUALQUIER consulta, no solo
     * las que mencionan ese termino. {@code plainto_tsquery} trata la entrada
     * como texto plano, no como sintaxis de operadores, y no falla.
     *
     * <p>Puntaje = suma de IDF de los léxemos de la consulta que el chunk
     * contiene: castiga el relleno (términos comunes, IDF bajo) y premia los
     * términos informativos.
     */
    List<CandidatoSenal> buscarPorIdf(
            String consulta, String projectId, List<String> tipos, List<Long> documentos, int limite) {
        return jdbc.sql("""
                        WITH terminos_consulta AS (
                            SELECT word FROM ts_stat(
                                'SELECT to_tsvector(''spanish'', ' || quote_literal(:consulta) || ')'
                            )
                        ),
                        terminos_idf AS (
                            SELECT ts.term, ts.idf
                            FROM term_stats ts
                            JOIN terminos_consulta tc ON tc.word = ts.term
                        )
                        SELECT c.id, c.document_id, d.uri, d.title, c.text, c.kind, c.ord, c.source_updated_at,
                               SUM(ti.idf) AS puntaje
                        FROM chunks c
                        JOIN documents d ON d.id = c.document_id
                        JOIN terminos_idf ti ON c.fts @@ plainto_tsquery('simple', ti.term)
                        WHERE c.project_id = :projectId %s %s
                        GROUP BY c.id, c.document_id, d.uri, d.title, c.text, c.kind, c.ord, c.source_updated_at
                        ORDER BY puntaje DESC
                        LIMIT :limite
                        """.formatted(filtroTipo(tipos), filtroDocumento(documentos)))
                .param("consulta", consulta).param("projectId", projectId).param("limite", limite)
                .param("tipos", tipos).param("documentos", documentos)
                .query(RecuperacionRepositorio::mapear).list();
    }

    /**
     * Señal 4: decaimiento exponencial por antigüedad. No depende de la
     * consulta — es un prior de frescura que RRF combina con las otras tres.
     * {@code lambdaDias} es la vida media: a esos días el puntaje cae a la mitad.
     */
    List<CandidatoSenal> buscarPorDecaimiento(
            String projectId, List<String> tipos, List<Long> documentos, double lambdaDias, int limite) {
        return jdbc.sql("""
                        SELECT c.id, c.document_id, d.uri, d.title, c.text, c.kind, c.ord, c.source_updated_at,
                               exp(
                                   -ln(2) / :lambdaDias
                                   * (extract(epoch FROM (now() - c.source_updated_at)) / 86400.0)
                               ) AS puntaje
                        FROM chunks c
                        JOIN documents d ON d.id = c.document_id
                        WHERE c.project_id = :projectId %s %s
                        ORDER BY c.source_updated_at DESC
                        LIMIT :limite
                        """.formatted(filtroTipo(tipos), filtroDocumento(documentos)))
                .param("projectId", projectId).param("lambdaDias", lambdaDias).param("limite", limite)
                .param("tipos", tipos).param("documentos", documentos)
                .query(RecuperacionRepositorio::mapear).list();
    }

    /**
     * Recalcula {@code term_stats} por lote a partir del corpus actual. Usa
     * {@code ts_stat}, la función que Postgres ya trae para esto: le pasa un
     * texto de consulta FIJO (sin interpolar nada del usuario), así que no hay
     * superficie de inyección aquí — a diferencia de {@link #buscarPorIdf}, que
     * sí construye SQL dinámico pero con {@code quote_literal}.
     *
     * <p>Se recalcula por lote y nunca dentro de una búsqueda: hacerlo en cada
     * consulta escanearía toda {@code chunks} por cada pregunta.
     */
    void recalcularEstadisticasTerminos() {
        jdbc.sql("""
                        INSERT INTO term_stats (term, df, idf, computed_at)
                        SELECT s.word, s.ndoc,
                               ln(1.0 * GREATEST(total.n, 1) / GREATEST(s.ndoc, 1))::real,
                               now()
                        FROM ts_stat('SELECT fts FROM chunks') s
                        CROSS JOIN (SELECT count(*) AS n FROM chunks) total
                        ON CONFLICT (term) DO UPDATE SET
                            df = EXCLUDED.df, idf = EXCLUDED.idf, computed_at = EXCLUDED.computed_at
                        """)
                .update();

        // Léxemos que ya no aparecen en ningún chunk (contenido borrado o
        // reemplazado): sin este barrido quedarían con un IDF obsoleto para
        // siempre.
        jdbc.sql("""
                        DELETE FROM term_stats
                        WHERE term NOT IN (SELECT word FROM ts_stat('SELECT fts FROM chunks'))
                        """)
                .update();
    }

    /**
     * {@code tipos} vacío = sin filtro (todos los {@code kind}). El marcador
     * {@code :tipos} solo se agrega al SQL cuando hay algo que filtrar — pasarlo
     * igual como parámetro cuando no aparece en la plantilla es inofensivo,
     * {@code JdbcClient} simplemente lo ignora.
     */
    private static String filtroTipo(List<String> tipos) {
        return tipos.isEmpty() ? "" : "AND c.kind IN (:tipos)";
    }

    /**
     * {@code documentos} vacío = sin restricción (todo el corpus del proyecto).
     * Es el filtro "activar/desactivar documentos por conversación" de la UI web
     * (ver {@code Dominio.Filtros.documentosPermitidos}); mismo patrón que
     * {@link #filtroTipo}.
     */
    private static String filtroDocumento(List<Long> documentos) {
        return documentos.isEmpty() ? "" : "AND c.document_id IN (:documentos)";
    }

    private static CandidatoSenal mapear(ResultSet rs, int n) throws SQLException {
        return new CandidatoSenal(
                rs.getLong("id"),
                rs.getLong("document_id"),
                rs.getString("uri"),
                rs.getString("title"),
                rs.getString("text"),
                rs.getString("kind"),
                rs.getInt("ord"),
                rs.getTimestamp("source_updated_at").toInstant(),
                rs.getDouble("puntaje"));
    }
}
