# Presentación — Validación real de `instrumentacion-java-ia`

Cierre en reveal.js de la validación real de las 4 skills del plugin `sdlc-ia` sobre
`base-conocimiento/` (etapas F0–F7). Todo local, sin build ni dependencias de red — se sirve como
archivos estáticos.

## Cómo verla localmente

El `index.html` carga `contenido.md` con `fetch` (plugin `RevealMarkdown`), así que **necesita un
servidor HTTP** — abrirlo con doble clic (`file://`) no funciona por CORS.

Desde esta carpeta (`presentacion-validacion-workshop/`):

```bash
npx serve -l 8123 .
```

y abrir `http://localhost:8123/index.html`. Cualquier otro servidor estático sirve igual
(`python -m http.server 8123`, `php -S localhost:8123`, etc.), siempre parado en esta carpeta —
`index.html` referencia `./dist` y `./contenido.md` en el mismo nivel, no con `../`.

### Atajos una vez abierta

| Tecla | Acción |
|---|---|
| `→` / `Espacio` | Siguiente slide |
| `←` | Slide anterior |
| `S` | Vista de orador (abre una ventana nueva con las notas de presentador) |
| `Esc` | Vista de overview de todas las slides |
| `F` | Pantalla completa |

## Estructura

```
index.html      # shell de reveal.js; carga contenido.md vía data-markdown
contenido.md     # el contenido real: una sección --- por etapa (F0..F7) + portada + cierre
dist/            # reveal.js@6.0.1 vendorizado desde npm (core + temas + plugins)
assets/shots/    # 4 capturas headless reales (issue #2, PR #3, CI verde, PR #1 cerrado)
```

`dist/` se vendorizó con `npm pack reveal.js@6.0.1` y se extrajo tal cual — sin `package.json` ni
`node_modules`, mismo patrón que `tutorial-revealjs-con-markdown-externo`. En la versión 6 los
plugins viven en `dist/plugin/*.js` (UMD, exponen globals como `RevealMarkdown`), a diferencia de
versiones más viejas que traían una carpeta `plugin/` separada en la raíz.

Separadores de `contenido.md`: `---` entre slides horizontales, `--` para slides verticales
(no se usaron en este deck), `Note:` para las notas de presentador de cada slide.

## Origen del contenido

Cada comando, salida de terminal y captura de este deck es real — ejecutado sobre este mismo
monorepo durante la validación. El detalle completo de cada etapa está en `validacion-workshop/`,
en la raíz del monorepo (`f0-fundamentos.md` a `f7-retrospectiva.md`).
