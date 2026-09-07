package co.g3a.baseconocimiento.llm;

import java.util.List;

/**
 * Destila contenido conversacional ruidoso — un hilo de Teams — en campos estructurados antes de
 * indexarlo: la pieza que el artículo de Cerebras señala como decisiva. {@code
 * ingesta.package-info} explica por qué esto NO aplica a documentos ni código ya estructurados
 * (esos se "destilan" heurísticamente, sin LLM).
 *
 * <p>Igual que {@link Planificador} y {@link Sintetizador}: recibe texto plano, sin saber qué es un
 * hilo de Teams ni un chunk.
 */
public interface Destilador {

  /**
   * @param searchableQuestion pregunta buscable que resume de qué trata el hilo
   * @param summary resumen breve del hilo completo
   * @param resolution cómo se resolvió, o vacío si el hilo no llegó a una
   * @param systemsMentioned sistemas o herramientas mencionados
   * @param codeReferences rutas de archivo, clases o funciones referenciadas
   */
  record Destilado(
      String searchableQuestion,
      String summary,
      String resolution,
      List<String> systemsMentioned,
      List<String> codeReferences) {}

  Destilado destilar(String textoDelHilo);
}
