# Contexto de Negocio

## Qué es Base de Conocimiento

Un RAG interno que responde preguntas sobre documentos, código, canales de Teams y work items, con
**citas verificables**. Reproduce la arquitectura que Cerebras describió en *How we built our
knowledge base*, con dos diferencias: dos adaptadores propios (una UI HTML/JS y un bot de Teams)
en vez de Slack, y costo cero — modelos abiertos en contenedores locales, sin APIs de pago ni nube.

## Quién paga por esto

<!-- TODO: ampliar con modelo de ingresos, tipos de contrato o precios si aplica. Hoy es un
proyecto interno/de investigación sin modelo comercial explícito en el repo. -->

## Ecosistema

Fuentes que ingiere hoy o está preparado para ingerir (ver `sources.kind` en
[el modelo de datos](data-model.md)): documentos locales, repositorios Git locales, canales de
Microsoft Teams (vía Graph) y work items/wiki de Azure DevOps. Cada fuente cae en la misma tabla
de embeddings — no hay silos por tipo de fuente.

## Voz de marca / tono

<!-- TODO: no hay guía de tono documentada — es una herramienta interna, no un producto con
voz de marca definida. Borrar esta sección si nunca llega a ser relevante. -->

## Docs relacionados

- [Usuario objetivo](./target-user.md) — quién usa el producto y qué le importa.
- [Arquitectura](./architecture.md) — cómo está construido el sistema.
