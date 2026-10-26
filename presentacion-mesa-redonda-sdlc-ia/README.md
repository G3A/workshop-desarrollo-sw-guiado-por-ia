# Presentación — Del curso a la práctica

Una charla de menos de diez minutos, en primera persona, para el profesor del curso (#255): qué
aprendí en el taller y cómo lo llevo a la práctica con el proceso de
[`proceso-operacional-con-ia/`](../proceso-operacional-con-ia/), sobre un issue visual de
`G3A/base-conocimiento-sandbox`:
[#54](https://github.com/G3A/base-conocimiento-sandbox/issues/54), el chat que muestra el código
con resaltado, números de línea y copiar sin los números. Lo trabajó la skill
`/sdlc-ia:github-plan-build` en una sesión interactiva, y se integró por la
[PR #55](https://github.com/G3A/base-conocimiento-sandbox/pull/55).

- `contenido.md` — las diapositivas, con sus notas para el presentador.
- `assets/` — capturas reales: la app antes y después del cambio, el visor del proceso, el
  playbook, las páginas del issue y la PR en GitHub, y las evidencias de la verificación visual
  que la sesión dejó en el acta del sandbox (`.ia/registros/54/evidencias/`).
- [`mesa-redonda.md`](mesa-redonda.md) — la mesa de siete perfiles que priorizó el issue anterior,
  [sandbox#52](https://github.com/G3A/base-conocimiento-sandbox/issues/52) (#253).
- `index.html` — el shell de reveal.js, que lee `contenido.md` con `RevealMarkdown`.
- `dist/` — reveal.js 6.0.1 vendorizado, solo lo que usa: el núcleo, el tema `black` (con los
  colores sobrescritos a un tema claro) y los plugins de Markdown y de notas.

## Cómo verla

`index.html` carga `contenido.md` con `fetch`, así que necesita un servidor HTTP; abierta con
doble clic (`file://`) no funciona por CORS. Desde esta carpeta:

```bash
npx serve -l 8124 .
```

y abre `http://localhost:8124/index.html`.

| Tecla | Acción |
|---|---|
| `→` / `Espacio` | Siguiente diapositiva |
| `S` | Vista del presentador, con las notas y el reloj |
| `Esc` | Vista general |
| `F` | Pantalla completa |

## Duración

Las notas suman unas 880 palabras: entre 6,7 y 7,6 minutos leídas en voz alta. Para medirlo otra
vez, cuenta las palabras después de cada `Note:` y divide por 115 a 130 palabras por minuto.
