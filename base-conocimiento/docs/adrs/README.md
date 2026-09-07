# Registros de Decisiones de Arquitectura (ADRs)

Este directorio contiene **ADRs** — registros cortos y fechados de decisiones arquitectónicamente significativas. Explican *por qué* el código se ve como se ve, lo cual es más difícil de inferir leyendo la fuente que *qué* hace.

## Cuándo escribir uno

Escribe un ADR cuando:
- Elijas entre dos o más opciones viables (build tool, framework de persistencia, patrón, herramienta).
- Aceptes un trade-off que futuros contribuidores querrán revisitar.
- Adoptes una convención que no está reforzada por tooling (o que sí lo está, como un límite entre módulos de Spring Modulith — vale la pena dejar por escrito por qué existe).

No escribas uno para detalles de implementación reversibles.

## Formato

Usa `adr-template.md` como punto de partida. Cada ADR tiene cuatro secciones:

1. **Estado** — Propuesto / Aceptado / Reemplazado.
2. **Contexto** — el problema, las opciones consideradas y las restricciones.
3. **Decisión** — qué elegimos.
4. **Consecuencias** — qué se vuelve más fácil (**A favor**), qué se vuelve más difícil (**En contra**).

Mantén los ADRs cortos (1 página). Nómbralos `NNNN-slug-corto.md` con un contador con ceros a la izquierda.

## Índice

- `0001-tabla-unica-de-embeddings` — todas las fuentes caen en la misma tabla `chunks`.
- `0002-rrf-sobre-normalizacion` — fusión de señales por rango (RRF), no por normalización de puntaje.
- `0003-no-embeber-texto-crudo` — el embedding ancla en campos destilados, no en el texto crudo.
- `0004-senal-dispersa-descartada` — por qué no se sumó una quinta señal dispersa.
- `0005-protocolo-bot-connector` — Bot Connector implementado directo, sin SDK.
- `0006-chunker-heuristico-sobre-tree-sitter` — chunking heurístico de código en vez de Tree-sitter.
- `0007-acl-por-fuente-pendiente` — control de acceso por fuente, pendiente de implementación completa.
- `0008-umbral-de-relevancia-antes-de-sintesis` — corte explícito sin evidencia suficiente.
- `0009-bonsai-8b-integracion-pospuesta` — por qué Bonsai 8B quedó pospuesto frente a Gemma3:4b.
- `0010-docling-reemplaza-pdfbox` — Docling para extracción de PDF/DOCX/PPTX.
- `0011-vault-unificado` — unificación del vault de ingesta.
- `0012-spring-modulith-para-fronteras-entre-modulos` — por qué Spring Modulith y no paquetes sueltos por convención.
