package co.g3a.baseconocimiento.recuperacion;

import java.time.Instant;

/**
 * Una fila devuelta por una sola señal, ya ordenada por {@code puntaje} desc en el propio SQL.
 * {@link RrfFusion} solo necesita el rango dentro de esta lista, no el valor de {@code puntaje} —
 * RRF fusiona por rango, no por puntaje, así que las escalas distintas de cada señal (un ts_rank_cd
 * no es comparable con una similitud coseno) nunca se mezclan directamente.
 */
record CandidatoSenal(
    long chunkId,
    long documentoId,
    String uri,
    String titulo,
    String texto,
    String tipo,
    int ord,
    Instant actualizadoEn,
    double puntaje) {}
