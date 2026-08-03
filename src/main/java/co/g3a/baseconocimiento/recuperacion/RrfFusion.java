package co.g3a.baseconocimiento.recuperacion;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fusión por rango (Reciprocal Rank Fusion), como en el artículo:
 * {@code score(d) = Σ peso / (k + rango)}, SIN normalizar los puntajes crudos
 * de cada señal — sus escalas no son comparables entre sí (un {@code ts_rank_cd}
 * no es una similitud coseno), pero sus RANGOS sí lo son. Un chunk ausente de
 * una señal aporta 0 a esa señal, sin penalizarlo.
 *
 * <p>Deliberadamente sin estado y sin dependencias de Spring: los pesos, la
 * {@code k} y los topes los decide quien llama ({@code Recuperador}, que sí
 * lee configuración), no esta clase. Así se puede probar por propiedades sin
 * levantar un contexto.
 */
final class RrfFusion {

    private RrfFusion() {
    }

    record Pesos(double fts, double vector, double idf, double decaimiento) {
        double de(Senal senal) {
            return switch (senal) {
                case FTS -> fts;
                case VECTOR -> vector;
                case IDF -> idf;
                case DECAIMIENTO -> decaimiento;
            };
        }
    }

    /**
     * Fusiona las listas de cada señal (ya ordenadas desc por puntaje) y
     * devuelve candidatos diversos ordenados por RRF desc: como mucho
     * {@code topePorDocumento} por documento, y como mucho {@code maxCandidatos}
     * en total.
     */
    static List<CandidatoFusionado> fusionar(
            Map<Senal, List<CandidatoSenal>> resultadosPorSenal,
            Pesos pesos,
            int k,
            int topePorDocumento,
            int maxCandidatos) {

        Map<Long, Acumulador> acumuladores = new LinkedHashMap<>();

        resultadosPorSenal.forEach((senal, candidatos) -> {
            double peso = pesos.de(senal);
            for (int i = 0; i < candidatos.size(); i++) {
                CandidatoSenal candidato = candidatos.get(i);
                int rango = i + 1;
                double contribucion = peso / (k + rango);
                acumuladores
                        .computeIfAbsent(candidato.chunkId(), id -> new Acumulador(candidato))
                        .acumular(senal, candidato.puntaje(), contribucion);
            }
        });

        List<CandidatoFusionado> ordenados = acumuladores.values().stream()
                .map(Acumulador::aCandidato)
                .sorted((a, b) -> Double.compare(b.rrf(), a.rrf()))
                .toList();

        return limitarPorDiversidad(ordenados, topePorDocumento, maxCandidatos);
    }

    private static List<CandidatoFusionado> limitarPorDiversidad(
            List<CandidatoFusionado> ordenados, int topePorDocumento, int maxCandidatos) {
        List<CandidatoFusionado> diversos = new ArrayList<>();
        Map<Long, Integer> porDocumento = new HashMap<>();

        for (CandidatoFusionado candidato : ordenados) {
            if (diversos.size() >= maxCandidatos) {
                break;
            }
            int usados = porDocumento.getOrDefault(candidato.documentoId(), 0);
            if (usados >= topePorDocumento) {
                continue;
            }
            diversos.add(candidato);
            porDocumento.put(candidato.documentoId(), usados + 1);
        }
        return diversos;
    }

    /** Acumula, por chunk, su puntaje crudo en cada señal y su RRF total. */
    private static final class Acumulador {
        private final CandidatoSenal origen;
        private final Map<Senal, Double> puntajesPorSenal = new EnumMap<>(Senal.class);
        private double rrf;

        Acumulador(CandidatoSenal origen) {
            this.origen = origen;
        }

        void acumular(Senal senal, double puntaje, double contribucion) {
            puntajesPorSenal.put(senal, puntaje);
            rrf += contribucion;
        }

        CandidatoFusionado aCandidato() {
            return new CandidatoFusionado(
                    origen.chunkId(), origen.documentoId(), origen.uri(), origen.titulo(),
                    origen.texto(), origen.tipo(), origen.ord(), origen.actualizadoEn(),
                    Map.copyOf(puntajesPorSenal), rrf);
        }
    }
}
