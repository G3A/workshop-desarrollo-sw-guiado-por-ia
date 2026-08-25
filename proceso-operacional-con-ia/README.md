# Proceso operacional con IA

Un visor interactivo del ciclo completo de onboarding al desarrollo operacional guiado por IA,
armado sobre GitHub y GitHub Actions: preparar la máquina, preparar el proyecto, y el ciclo por
issue (planificar → implementar → verificar → PR → CI → merge → CD → retrospectiva), presentado
como un ciclo de mejora continua (PDCA, el ciclo de Deming) que se repite issue tras issue.

No asume ninguna organización, empresa ni herramienta interna en particular — todo lo que describe
es reproducible en cualquier repositorio público o privado de GitHub, con un agente de código con
IA (se usa [Claude Code](https://claude.com/product/claude-code) como ejemplo concreto) y las
herramientas nativas de GitHub (`gh`, Issues, Projects, Rulesets, Actions).

## Cómo verlo

Es una app de una sola página que carga un diagrama BPMN vía `fetch` — necesita servirse por
http(s), no abrirse directo como archivo (`file://` rompe por CORS). Parado en esta carpeta:

```bash
npx --yes serve .
# o
python -m http.server
```

y abre la URL que te indique en el navegador.

## Qué hay acá

- `proceso-operacional-con-ia.html` — el visor (bpmn-js + panel de detalle por paso).
- `proceso-operacional-con-ia.bpmn` — el diagrama del proceso (BPMN 2.0): 6 carriles, ~46 pasos.
- `comandos.json` — el contenido de cada paso (por qué importa, cómo hacerlo, comandos copiables).
- `img/` — las imágenes del panel "Acerca de" (retrato de Deming, el ciclo PDCA), de dominio
  público vía Wikimedia Commons.

## Qué guarda y qué no

El progreso se guarda en el `localStorage` del navegador, separado por repositorio y por issue
activo — nada se envía a ningún servidor. La exportación de bitácora (Markdown/JSON) tampoco
incluye secretos: solo los valores de perfil que tú mismo/a completaste (organización, ruta local).
