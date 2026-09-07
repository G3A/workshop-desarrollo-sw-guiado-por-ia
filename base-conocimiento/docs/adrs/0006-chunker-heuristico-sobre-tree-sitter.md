# ADR-0006: Chunker heurístico para código, no tree-sitter

## Estado

Aceptado (F1, extendido en F6).

## Contexto

`search_code` y el conector de repos Git locales necesitan trocear archivos de código en unidades
recuperables (clase → método → bloque). La opción "correcta" en la mayoría de herramientas de este
estilo (incluido CocoIndex, que el artículo de Cerebras usa) es un parser real por lenguaje vía
tree-sitter, que entiende la gramática exacta de cada lenguaje y no se confunde con, por ejemplo,
una llave de cierre dentro de un string.

El binding de tree-sitter para Java no tiene, al día del diseño, un soporte sólido y mantenido
comparable al de sus bindings de Node o Rust — es la pieza que hizo descartar esa ruta acá.

## Decisión

Un chunker heurístico basado en patrones de texto (indentación, palabras clave de declaración de
clase/método por lenguaje) que trocea de grano grueso (clase) a fino (método, bloque) cuando un
fragmento excede el tamaño objetivo, sin construir un AST real.

## Consecuencias

- **A favor**: cero dependencias nativas nuevas, funciona hoy en Java puro, y es suficiente para el
  caso de uso real (recuperar la sección de código relevante para una pregunta, no refactorizar
  código de forma segura).
- **En contra**: es frágil frente a código genuinamente irregular (un `}` dentro de un string
  multilínea, macros, generación de código) de una forma en que un parser real no lo sería. Cubierto
  con pruebas por lenguaje (`ChunkerCodigoTest`) sobre los casos reales que sí importan, no sobre
  la gramática completa de cada lenguaje.
- Si el binding de tree-sitter para Java madura, esta es la pieza más aislada para reemplazar: el
  chunker no tiene otra responsabilidad ni depende de nada fuera de `ingesta`.
