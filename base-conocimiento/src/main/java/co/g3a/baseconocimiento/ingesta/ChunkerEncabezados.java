package co.g3a.baseconocimiento.ingesta;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Trocea Markdown por encabezados: cada sección (desde un {@code #} hasta el
 * siguiente de igual o menor nivel) es un chunk, con su ruta de encabezados
 * como contexto.
 *
 * <p>Es la contraparte de "Confluence pages chunked around headings" del
 * artículo de Cerebras — sin llamar al LLM, porque el documento ya viene
 * estructurado por quien lo escribió.
 */
final class ChunkerEncabezados {

    private static final Pattern ENCABEZADO = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");

    /** Si una sección supera esto, se subdivide con {@link ChunkerVentanas}. */
    private static final int MAX_CARACTERES_SECCION = 4_000;

    private ChunkerEncabezados() {
    }

    /**
     * @param rutaEncabezados encabezados desde la raíz hasta este, en orden; vacío
     *                        si el texto no tenía ningún encabezado
     */
    record Seccion(List<String> rutaEncabezados, String cuerpo) {
    }

    static List<Seccion> trocear(String texto) {
        List<Seccion> secciones = new ArrayList<>();
        // pila[i] = encabezado de nivel i+1 vigente en este punto del documento
        List<String> pila = new ArrayList<>();
        StringBuilder cuerpo = new StringBuilder();

        for (String linea : texto.split("\\R", -1)) {
            Matcher m = ENCABEZADO.matcher(linea);
            if (m.matches()) {
                cerrarSeccion(secciones, pila, cuerpo);
                int nivel = m.group(1).length();
                while (pila.size() >= nivel) {
                    pila.removeLast();
                }
                pila.add(m.group(2).trim());
            } else {
                cuerpo.append(linea).append('\n');
            }
        }
        cerrarSeccion(secciones, pila, cuerpo);

        return secciones.isEmpty() ? List.of(new Seccion(List.of(), texto)) : secciones;
    }

    private static void cerrarSeccion(List<Seccion> secciones, List<String> pila, StringBuilder cuerpo) {
        String texto = cuerpo.toString().strip();
        cuerpo.setLength(0);
        if (texto.isEmpty()) {
            return;
        }
        List<String> ruta = List.copyOf(pila);
        if (texto.length() <= MAX_CARACTERES_SECCION) {
            secciones.add(new Seccion(ruta, texto));
        } else {
            // Seccion demasiado grande para un solo chunk: se subdivide por
            // ventanas, conservando la misma ruta de encabezados en cada trozo.
            for (String ventana : ChunkerVentanas.trocear(texto, MAX_CARACTERES_SECCION)) {
                secciones.add(new Seccion(ruta, ventana));
            }
        }
    }
}
