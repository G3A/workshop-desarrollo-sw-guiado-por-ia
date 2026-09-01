package co.g3a.baseconocimiento.recuperacion;

/**
 * Las cuatro señales del artículo, idénticas: texto completo, densa, IDF y decaimiento por
 * antigüedad. La quinta (dispersa de BGE-M3) queda fuera porque choca con el límite de 1000 valores
 * no-nulos de pgvector.
 */
enum Senal {
  FTS,
  VECTOR,
  IDF,
  DECAIMIENTO
}
