package co.g3a.baseconocimiento.modelos;

/**
 * Embeddings densos para la señal 2 (recuperación vectorial).
 *
 * <p>Ancla siempre en lo que la ingesta destiló, nunca en el texto crudo — ver
 * {@code V1__esquema.sql} y el comentario de {@code chunks.embedding}.
 */
public interface Embeddings {

    /** Dimensión del vector. Debe coincidir con {@code vector(1024)} en el esquema. */
    int DIMENSIONES = 1024;

    /**
     * Embebe un texto ya destilado.
     *
     * @throws IllegalStateException si el modelo configurado no produce vectores
     *                                de {@link #DIMENSIONES} dimensiones
     */
    float[] embeber(String texto);
}
