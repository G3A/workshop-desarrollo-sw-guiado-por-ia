# Presentación — Siete miradas, un issue, un acta

Una charla de menos de diez minutos, en primera persona, sobre el #253: una mesa redonda de siete
perfiles elige un issue de `G3A/base-conocimiento-sandbox`, la skill `github-plan-build` del plugin
`sdlc-ia` lo lleva de punta a punta, y el acta de la IA queda en la PR como pide el
[ADR-0006](../docs/adrs/0006-el-acta-de-la-ia-se-versiona-en-git.md).

- [`mesa-redonda.md`](mesa-redonda.md) — las siete propuestas, el voto y por qué ganó la del issue
  [sandbox#52](https://github.com/G3A/base-conocimiento-sandbox/issues/52).
- `contenido.md` — las diapositivas, con sus notas para el presentador.
- `index.html` — el shell de reveal.js, que lee `contenido.md` con `RevealMarkdown`.
- `dist/` — reveal.js 6.0.1 vendorizado, solo lo que usa: el núcleo, el tema `black` y los
  plugins de Markdown y de notas. La copia completa está en `presentacion-validacion-workshop/`.

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

Las notas suman unas 1000 palabras: entre 7,7 y 8,7 minutos leídas en voz alta. Para medirlo otra
vez, cuenta las palabras después de cada `Note:` y divide por 115 a 130 palabras por minuto.
