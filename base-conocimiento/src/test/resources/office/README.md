# Fixtures de Office

Archivos reales del set de pruebas de [Docling](https://github.com/docling-project/docling)
(`tests/data/docx/sources` y `tests/data/pptx/sources`), con licencia MIT, copyright The Docling
Contributors. Los usa `ExtractorOfficeTest` (ADR-0014); cada uno cubre un caso que falló en el
prototipo:

| Archivo | Qué prueba |
|---|---|
| `equations.docx` | 27 ecuaciones de Word (OMML) que deben salir en LaTeX |
| `word_image_anchors.docx` | imágenes incrustadas que Tika escribía como `# image1.png` |
| `powerpoint_sample.pptx` | tabla, diapositiva sin título y notas del orador |
| `powerpoint_bad_text.pptx` | título de portada (`CENTERED_TITLE`), que `getTitle()` de POI ignora |
