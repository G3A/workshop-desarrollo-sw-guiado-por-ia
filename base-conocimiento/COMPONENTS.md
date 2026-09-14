# COMPONENTS.md — Base de Conocimiento

Los componentes de interfaz que este repositorio **ya tiene**. Léelo antes de escribir UI nueva:
reutilizar lo que existe va primero.

## Componentes

<!-- TODO: no se encontró estructura de componentes (ver «Qué se buscó»). Si el equipo la adopta,
     este archivo es donde se registra. -->

La interfaz es HTML y JavaScript planos en `src/main/resources/static/`, sin fragmentos, plantillas,
componentes ni historias: no hay componentes que registrar. Ese pendiente es la salida correcta, no
una falla del descubrimiento.

## Qué se buscó

Solo en archivos versionados dentro de `src/main/resources/static/`, la única raíz de interfaz (la
detección de interfaz ya había confirmado que no existe `src/main/resources/templates/`):

- Fragmentos Thymeleaf (`th:fragment`): no hay.
- Plantillas JTE (`*.jte`): no hay.
- Componentes de Angular (`*.component.ts`), React (`*.tsx`, `*.jsx`) y Vue (`*.vue`): no hay.
- Historias de Storybook (`*.stories.*`): no hay.

## Docs relacionados

- [Diseño](docs/design.md)
- [Design tokens](docs/design-tokens.md)
