package co.g3a.baseconocimiento.recuperacion;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.springframework.stereotype.Component;

import co.g3a.baseconocimiento.compartido.Dominio.Fragmento;
import co.g3a.baseconocimiento.modelos.Embeddings;
import co.g3a.baseconocimiento.modelos.Reranker;

/**
 * Orquesta el pipeline de recuperación de F2: las cuatro señales en paralelo,
 * {@link RrfFusion}, y el cross-encoder que corta a los mejores.
 *
 * <p>No es la fachada {@code Consultar} de F3 — esto es una pieza más abajo:
 * dada una pregunta ya decidida por buscar, devuelve fragmentos con toda la
 * traza. La expone públicamente como {@link Buscador} para que las
 * herramientas {@code search_unified} y {@code search_docs} de {@code orquestacion}
 * la llamen, y el controller de este mismo módulo la expone tal cual por
 * {@code /api/search} para que F2 fuera demostrable por su propia cuenta.
 */
@Component
class Recuperador implements Buscador {

    private final RecuperacionRepositorio repositorio;
    private final Embeddings embeddings;
    private final Reranker reranker;
    private final RecuperacionPropiedades propiedades;

    Recuperador(RecuperacionRepositorio repositorio, Embeddings embeddings, Reranker reranker,
            RecuperacionPropiedades propiedades) {
        this.repositorio = repositorio;
        this.embeddings = embeddings;
        this.reranker = reranker;
        this.propiedades = propiedades;
    }

    @Override
    public List<Fragmento> buscarPalabraClave(
            String consulta, String projectId, List<Long> documentosPermitidos, int limite) {
        return repositorio.buscarPorFts(consulta, projectId, List.of(), documentosPermitidos, limite).stream()
                .map(c -> new Fragmento(
                        c.chunkId(), c.documentoId(), c.uri(), c.titulo(), c.texto(), c.tipo(), c.ord(),
                        c.actualizadoEn(), Map.of(Senal.FTS.name(), c.puntaje()), c.puntaje(), null))
                .toList();
    }

    @Override
    public List<ResultadoBusqueda> buscar(
            String consulta, String projectId, List<String> tiposPermitidos, List<Long> documentosPermitidos) {
        Map<Senal, List<CandidatoSenal>> porSenal = ejecutarSenalesEnParalelo(
                consulta, projectId, tiposPermitidos, documentosPermitidos, propiedades.candidatosPorSenal());

        List<CandidatoFusionado> fusionados = RrfFusion.fusionar(
                porSenal, pesos(), propiedades.rrfK(), propiedades.topePorDocumento(),
                propiedades.maxCandidatos());
        Map<Long, Integer> rangoRrf = rangoPorChunk(fusionados);

        List<Reordenado> reordenados = fusionados.stream()
                .map(candidato -> new Reordenado(candidato, reranker.puntuar(consulta, candidato.texto())))
                .sorted((a, b) -> Double.compare(b.rerank(), a.rerank()))
                .limit(propiedades.topRerank())
                .toList();

        List<ResultadoBusqueda> resultados = new ArrayList<>(reordenados.size());
        for (int i = 0; i < reordenados.size(); i++) {
            Reordenado r = reordenados.get(i);
            resultados.add(new ResultadoBusqueda(
                    aFragmento(r.candidato(), r.rerank()), rangoRrf.get(r.candidato().chunkId()), i + 1));
        }
        return resultados;
    }

    private record Reordenado(CandidatoFusionado candidato, double rerank) {
    }

    private RrfFusion.Pesos pesos() {
        var p = propiedades.pesos();
        return new RrfFusion.Pesos(p.fts(), p.vector(), p.idf(), p.decaimiento());
    }

    /**
     * Las cuatro señales son independientes entre sí (tres consultas a
     * Postgres y una llamada a Ollama para el embedding de la consulta): no
     * hay razón para serializarlas. Hilos virtuales, sin pool que dimensionar,
     * igual que el fan-out del executor de F3.
     */
    private Map<Senal, List<CandidatoSenal>> ejecutarSenalesEnParalelo(
            String consulta, String projectId, List<String> tipos, List<Long> documentos, int limite) {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<List<CandidatoSenal>> fts = executor.submit(
                    () -> repositorio.buscarPorFts(consulta, projectId, tipos, documentos, limite));
            Future<List<CandidatoSenal>> idf = executor.submit(
                    () -> repositorio.buscarPorIdf(consulta, projectId, tipos, documentos, limite));
            Future<List<CandidatoSenal>> decaimiento = executor.submit(() -> repositorio.buscarPorDecaimiento(
                    projectId, tipos, documentos, propiedades.decaimiento().lambdaDias(), limite));
            Future<List<CandidatoSenal>> vector = executor.submit(() -> repositorio.buscarPorVector(
                    embeddings.embeber(consulta), projectId, tipos, documentos, limite));

            Map<Senal, List<CandidatoSenal>> resultado = new EnumMap<>(Senal.class);
            resultado.put(Senal.FTS, obtener(fts));
            resultado.put(Senal.VECTOR, obtener(vector));
            resultado.put(Senal.IDF, obtener(idf));
            resultado.put(Senal.DECAIMIENTO, obtener(decaimiento));
            return resultado;
        }
    }

    private static <T> T obtener(Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrumpido esperando una senal de recuperacion", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Fallo al ejecutar una senal de recuperacion", e.getCause());
        }
    }

    private static Map<Long, Integer> rangoPorChunk(List<CandidatoFusionado> ordenadosPorRrf) {
        Map<Long, Integer> rangos = new HashMap<>();
        for (int i = 0; i < ordenadosPorRrf.size(); i++) {
            rangos.put(ordenadosPorRrf.get(i).chunkId(), i + 1);
        }
        return rangos;
    }

    private static Fragmento aFragmento(CandidatoFusionado candidato, double rerank) {
        return new Fragmento(
                candidato.chunkId(), candidato.documentoId(), candidato.uri(), candidato.titulo(),
                candidato.texto(), candidato.tipo(), candidato.ord(), candidato.actualizadoEn(),
                aClavesTexto(candidato.puntajesPorSenal()), candidato.rrf(), rerank);
    }

    private static Map<String, Double> aClavesTexto(Map<Senal, Double> puntajesPorSenal) {
        Map<String, Double> resultado = new LinkedHashMap<>();
        puntajesPorSenal.forEach((senal, puntaje) -> resultado.put(senal.name(), puntaje));
        return resultado;
    }
}
