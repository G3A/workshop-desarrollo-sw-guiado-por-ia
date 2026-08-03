package co.g3a.baseconocimiento.recuperacion;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code kb.recuperacion.*}: pesos de RRF, su {@code k}, los topes de
 * diversidad y candidatos, y la vida media del decaimiento por antigüedad.
 *
 * <p>Registrado vía {@code @ConfigurationPropertiesScan} en
 * {@code BaseConocimientoApplication}, no con {@code @Component}: anotar este
 * record también con {@code @Component} hace que el contenedor intente
 * resolver {@code Pesos} y {@code Decaimiento} como beans propios antes de que
 * el binding de propiedades corra, y falla al arrancar.
 */
@ConfigurationProperties(prefix = "kb.recuperacion")
record RecuperacionPropiedades(
        Pesos pesos,
        int rrfK,
        int maxCandidatos,
        int topePorDocumento,
        int topRerank,
        int candidatosPorSenal,
        Decaimiento decaimiento) {

    record Pesos(double fts, double vector, double idf, double decaimiento) {
    }

    record Decaimiento(double lambdaDias) {
    }
}
