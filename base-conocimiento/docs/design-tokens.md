# Design tokens — Base de Conocimiento

Lo que este repositorio **ya define**, leído de su código. Nada de lo que sigue es una propuesta:
si un valor falta aquí, falta en el repositorio.

## Dónde se definen

| Origen | Mecanismo | Temas |
|---|---|---|
| `src/main/resources/static/index.html` | Variables CSS dentro de `<style>` | claro (`:root`), oscuro (`@media (prefers-color-scheme: dark)`) |
| `src/main/resources/static/admin.html` | Variables CSS dentro de `<style>` | claro (`:root`), oscuro (`@media (prefers-color-scheme: dark)`) |

No hay un solo `.css`: los tokens viven dentro de los dos HTML. **Cada página define su propia
copia** — ver «Hallazgos».

## Tokens

Una fila por nombre y archivo de origen; los valores, tal como están escritos.

| Token | Claro (`:root`) | Oscuro (`prefers-color-scheme: dark`) | Origen |
|---|---|---|---|
| `--acento` | `#10a37f` | `#19c37d` | `src/main/resources/static/index.html:21,38` |
| `--acento` | `#10a37f` | `#19c37d` | `src/main/resources/static/admin.html:16,32` |
| `--acento-texto` | `#ffffff` | `#0d0d0d` | `src/main/resources/static/index.html:22,39` |
| `--acento-texto` | `#ffffff` | `#0d0d0d` | `src/main/resources/static/admin.html:17,33` |
| `--advertencia` | `#b45309` | `#f5b24a` | `src/main/resources/static/index.html:24,41` |
| `--bg-burbuja-usuario` | `#f0f0f1` | `#323232` | `src/main/resources/static/index.html:16,33` |
| `--bg-codigo` | `#f6f8fa` | `var(--bg-elevado)` (`#2a2a2a`) | `src/main/resources/static/index.html:26,43` |
| `--bg-elevado` | `#ffffff` | `#2a2a2a` | `src/main/resources/static/index.html:15,32` |
| `--bg-elevado` | `#f7f7f8` | `#2a2a2a` | `src/main/resources/static/admin.html:11,27` |
| `--bg-hover` | `#ececee` | `#2e2e2e` | `src/main/resources/static/index.html:17,34` |
| `--bg-hover` | `#ececee` | `#2e2e2e` | `src/main/resources/static/admin.html:12,28` |
| `--bg-main` | `#ffffff` | `#212121` | `src/main/resources/static/index.html:14,31` |
| `--bg-main` | `#ffffff` | `#212121` | `src/main/resources/static/admin.html:10,26` |
| `--bg-sidebar` | `#f7f7f8` | `#171717` | `src/main/resources/static/index.html:13,30` |
| `--borde` | `#e5e5e7` | `#3a3a3a` | `src/main/resources/static/index.html:18,35` |
| `--borde` | `#e5e5e7` | `#3a3a3a` | `src/main/resources/static/admin.html:13,29` |
| `--error` | `#d1352b` | `#f2867a` | `src/main/resources/static/index.html:23,40` |
| `--error` | `#d1352b` | `#f2867a` | `src/main/resources/static/admin.html:20,36` |
| `--error-bg` | `#fee2e2` | `#3a1f1c` | `src/main/resources/static/admin.html:21,37` |
| `--ok` | `#1a7f37` | `#4ade80` | `src/main/resources/static/admin.html:18,34` |
| `--ok-bg` | `#dcfce7` | `#14321f` | `src/main/resources/static/admin.html:19,35` |
| `--sombra` | `0 2px 10px rgba(0, 0, 0, 0.06)` | `0 2px 14px rgba(0, 0, 0, 0.35)` | `src/main/resources/static/index.html:25,42` |
| `--sombra` | `0 2px 10px rgba(0, 0, 0, 0.06)` | `0 2px 14px rgba(0, 0, 0, 0.35)` | `src/main/resources/static/admin.html:22,38` |
| `--texto` | `#0d0d0d` | `#ececec` | `src/main/resources/static/index.html:19,36` |
| `--texto` | `#0d0d0d` | `#ececec` | `src/main/resources/static/admin.html:14,30` |
| `--texto-atenuado` | `#8e8ea0` | `#9b9b9b` | `src/main/resources/static/index.html:20,37` |
| `--texto-atenuado` | `#8e8ea0` | `#9b9b9b` | `src/main/resources/static/admin.html:15,31` |

**`--bg-codigo` (issue #54)** es el fondo de los bloques de código del chat. En el tema oscuro no
tiene valor propio: es `var(--bg-elevado)`. Los colores del resaltado de sintaxis no son tokens de
este repositorio: vienen de las hojas `github.min.css` y `github-dark.min.css` vendorizadas en
`src/main/resources/static/vendor/highlight/`, una por tema.

## Redefiniciones contextuales

Ninguna. `index.html` tiene un `@media (max-width: 760px)`, pero no redefine ningún token.

## Hallazgos

**Los tokens están definidos dos veces, una por página, y las dos copias se separaron.** Esto se
reporta; no se elige cuál de las dos es la verdadera ni se unifica ningún valor. Decidirlo es del
equipo.

- **Solo en `index.html`:** `--advertencia`, `--bg-burbuja-usuario`, `--bg-codigo`, `--bg-sidebar`.
- **Solo en `admin.html`:** `--error-bg`, `--ok`, `--ok-bg`.
- **Mismo nombre, valor distinto:** `--bg-elevado` en el tema claro vale `#ffffff` en `index.html`
  (línea 15) y `#f7f7f8` en `admin.html` (línea 11). En el tema oscuro coinciden (`#2a2a2a`).

En total: 17 nombres distintos, 10 compartidos. Los otros 9 compartidos tienen el mismo valor en
los dos temas.

## Qué se buscó

- **Interfaz:** estáticos servidos por la aplicación en `src/main/resources/static/` (dos HTML y
  cinco JavaScript). Sin plantillas en `src/main/resources/templates/`, sin subproyecto frontend y
  sin controladores que devuelvan vistas. La suite de Playwright de `eval-100-preguntas/` también
  cuenta como señal, pero no es una raíz de interfaz y no se revisó en busca de tokens.
- **Tokens, solo en archivos versionados dentro de `src/main/resources/static/`:** variables CSS en
  `*.css` (no hay ninguno) y en bloques `<style>` de `*.html` (las dos páginas); `$var` de SCSS y
  `@var` de LESS (no hay); `tailwind.config.*`, `@theme` y `@config` (no hay); `*.tokens` /
  `*.tokens.json` (no hay); variables asignadas desde JavaScript con `setProperty('--…')` (no hay).
- **Temas:** `:root`, `@media (prefers-color-scheme: …)`, `[data-theme]`, clases `.dark`/`.light` y
  `light-dark()`. Solo aparecen `:root` y `prefers-color-scheme: dark`.

## Docs relacionados

- [Diseño](./design.md)
- [Componentes](../COMPONENTS.md)
