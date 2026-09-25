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

## Las 7 fases del método

**Cada caja del diagrama está pintada con el color de su fase** — las mismas siete del
[`playbook-sdlc-ia`](../playbook-sdlc-ia/) — y lleva además un chip oscuro con su sigla, para que
la fase se lea aunque dos colores se parezcan. La leyenda usa exactamente el mismo color que la
caja, así que emparejar las dos es mirar, no recordar:

| Chip | Color | Fase | Qué agrupa |
|---|---|---|---|
| `F0` | ámbar | Fundamentos | Instalar y conectar el agente, permisos, gestos base, modelos y costos. |
| `F1` | violeta | Cultura de IA | Las 4 prácticas de equipo y el permiso escrito para experimentar y fallar. |
| `F2` | turquesa | Preparación del terreno | Contexto, sensores deterministas, el juez de CI, hooks y MCP. |
| `···` | gris | Antes del primer issue | El puente opcional: deuda triada y pruebas sobre código heredado. |
| `F3` | azul | Ciclo por feature | Spec, plan, implementación, verificación en 4 capas y merge. |
| `F4` | verde | Retroalimentación | La lección de la vuelta, enrutada a donde pertenece. |
| `F5` | magenta | Métricas y reporte | Medir el período del historial, y cerrar el sprint con fecha. |
| `F6` | coral | Adopción por etapas | Solo el cierre cae acá: el ciclo por issue no amplía el alcance. |

El color de la caja y el del carril son **dos ejes distintos**: la banda de fondo dice *dónde*
pasa el paso (tu máquina, el agente, Actions…) y el relleno de la caja dice *a qué parte del
método* pertenece. Los colores viven en tres sitios que tienen que coincidir: `bioc:fill` y
`bioc:stroke` de cada figura del `.bpmn`, las variables `--f0…--f6` del visor, y `FASE_CHIP`.

«Antes del primer issue» no lleva número porque no es una fase: es el tramo que el playbook dibuja
entre el terreno listo y la primera funcionalidad, y solo corre si el repositorio lo necesita.

Este visor y el playbook son el mismo mapa visto de dos lados: el playbook dice, caja por caja,
**quién ejecuta cada paso hoy** (una skill, tú a mano, o nadie todavía); este visor da el **paso a
paso operativo**, con comandos copiables. Si una fase cambia en uno, cambia en el otro.

## Qué hay acá

- `proceso-operacional-con-ia.html` — el visor (bpmn-js + panel de detalle por paso).
- `proceso-operacional-con-ia.bpmn` — el diagrama del proceso (BPMN 2.0): 6 carriles, ~50 pasos.
- `comandos.json` — el contenido de cada paso (por qué importa, cómo hacerlo, comandos copiables).
- `img/` — las imágenes del panel "Acerca de" (retrato de Deming, el ciclo PDCA), de dominio
  público vía Wikimedia Commons.

## Qué guarda y qué no

El progreso se guarda en el `localStorage` del navegador, separado por repositorio y por issue
activo — nada se envía a ningún servidor. La exportación de bitácora (Markdown/JSON) tampoco
incluye secretos: solo los valores de perfil que tú mismo/a completaste (organización, ruta local).

### Qué cuentan las pastillas de progreso

Las cuatro pastillas —Fundamentos, Máquina, Proyecto, Ciclo— cuentan los pasos de `CANON`, que no
son todos los nodos del diagrama. Quedan fuera dos cosas, y por razones distintas:

- **Los gateways.** Una pregunta no es un paso.
- **Las ramas que son un desvío**, porque vuelven a un nodo que sí cuenta y contarlas sería contar
  dos veces la misma etapa: `v0`/`vw` vuelven a `c3`, `c4p` a `c2`, `pf` a `p1`, `ac` a `cr`, `df` a
  `cd1`, `bt` a `GT2` y `bi2` a `bo1`.

Un caso no entra en ninguna de las dos: cuando las ramas de un gateway son **la misma etapa
resuelta de dos maneras** y no se rejuntan en un tercer nodo contable. Ahí van como **un solo
casillero con varias opciones**, hecho con cualquiera de ellas. Hoy pasa una vez, en las dos salidas
de «¿Existe archivo de contexto?» (`G2`): «Generar contexto del repo» (`b1`) y «Completar solo el
andamiaje» (`bi`) corren el mismo comando y se hace una u otra, nunca las dos.

Si agregas una rama a un gateway, mira a dónde desemboca antes de tocar `CANON`: si vuelve a un
paso contado, déjala fuera; si es una alternativa que no se rejunta, súmala como opción del
casillero, no como paso nuevo. Un paso que nadie puede completar deja su pastilla clavada para
siempre.
