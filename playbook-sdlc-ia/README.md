# Playbook del plugin `sdlc-ia`

Un diagrama interactivo que recorre las **siete fases del método de desarrollo guiado por IA** y,
en cada caja, dice **quién la ejecuta hoy en este repositorio**: una skill del plugin `sdlc-ia`,
tú a mano con el visor al lado, o nadie todavía.

Es a la vez el mapa del método y el backlog honesto de cobertura del plugin. Las cajas rojas no
son un error del diagrama: son trabajo real que todavía no tiene issue.

## Cómo abrirlo

Doble clic en **`Playbook-sdlc-ia.html`**. Se abre en el navegador y funciona **100% offline** —
Mermaid y las tipografías están en `vendor/`, no hay ningún `fetch` y no hace falta servidor.

A diferencia de `proceso-operacional-con-ia/`, que carga su diagrama BPMN por `fetch` y por eso
necesita `npx serve`, este playbook trae todo el contenido dentro del HTML. Recomendado en un
navegador basado en Chromium.

## Los 5 badges

| Badge | Qué significa |
|---|---|
| **Skill** | La corre el plugin, con su comando. La caja nombra cuál. |
| **Parcial** | Hay una skill que toca el paso, pero no lo cubre entero. La sección dice qué queda afuera. |
| **A mano** | La haces tú, y el repositorio te enseña cómo: un nodo del visor `proceso-operacional-con-ia` o un manual de `manuales/`. |
| **Hueco** | Nadie la cubre acá: ni skill, ni visor, ni manual. |
| **Fuera de alcance** | Frontera declarada del plugin, no un olvido: Azure DevOps, memoria semántica entre sesiones, agentes especialistas por stack. |

Cobertura al momento de escribir esto: **37 skill · 3 parcial · 8 a mano · 21 hueco · 2 fuera de
alcance**, sobre 71 cajas con badge.

## Cómo se usa el diagrama

- **Clic en una caja** — abre su sección en la página de esa fase, en modo foco: se ve solo esa
  sección, con botones para ir a la anterior o la siguiente.
- **Pasar el mouse** — muestra el badge, el título y la nota de la caja sin salir del diagrama.
- **Arrastrar** — desplaza el diagrama. **Ctrl + rueda** — zoom centrado en el cursor.
- **Botones `−` `⤢` `+`** — alejar, ajustar al ancho, acercar.
- **`✎ Editar`** — modo edición: clic en cualquier caja para cambiar su título, nota o enlace.
  Se guarda en el `localStorage` de tu navegador, nada sale de tu máquina.
- **`⤓ Exportar` / `⤒ Importar`** — descarga o carga esa configuración como `.json`, para
  compartirla o apuntar las cajas a tu wiki.

En las páginas de fase, «← Volver al diagrama» cierra esa pestaña, porque el diagrama quedó
abierto en la original.

> **Si personalizaste enlaces alguna vez en este navegador**, lo guardado gana sobre lo que trae
> el archivo. Para comprobarlo, abre la consola con el diagrama abierto y ejecuta
> `localStorage.getItem('playbook-sdlc-ia-links-v1')`. Si devuelve `null`, no hay nada guardado.

## De dónde sale cada afirmación

Este playbook **no duplica documentación: enruta**. Cada caja con badge *Skill* o *Parcial* cita
el `SKILL.md` del que sale; cada caja *A mano* nombra el nodo concreto del visor. Las piezas:

| Pieza | Qué aporta |
|---|---|
| `instrumentacion-java-ia/` | El plugin `sdlc-ia` y sus ocho skills. |
| `proceso-operacional-con-ia/` | El visor BPMN del proceso, con comandos copiables por nodo. |
| `manuales/` | Los recorridos ya grabados, con capturas y transcripciones reales. |
| `base-conocimiento/` | La aplicación Java/Spring donde cada skill se probó de verdad. |

## Estructura de la carpeta

```
Playbook-sdlc-ia.html            El diagrama interactivo (ábrelo acá).
README.md                        Este archivo.
vendor/
  mermaid.min.js                 Librería del diagrama, local.
  fonts.css + fonts/*.woff2      Inter, Fraunces y JetBrains Mono (SIL OFL), locales.
Playbook-Fases/
  index.html                     Índice de las fases.
  styles.css                     Estilos de las páginas, con los badges de cobertura.
  nav.js                         Modo foco y cierre de pestaña.
  00-referencia-e-inicio.html    Las 9 skills, los 5 badges, el eje, las piezas del repo.
  01-fundamentos.html            Fase 0.
  02-cultura.html                Fase 1.
  03-preparacion-del-terreno.html  Fase 2 — contexto, 9 sensores, el juez, hooks y MCP.
  04-ciclo-por-feature.html      Fase 3 — spec, plan, build, verificación en 4 capas, merge.
  05-retroalimentacion.html      Fase 4.
  06-metricas-y-reporte.html     Fase 5.
  07-adopcion-por-etapas.html    Fase 6.
```

Mantén juntos el `.html`, `vendor/` y `Playbook-Fases/`: los enlaces son relativos. Puedes copiar
o mover la carpeta entera a otra máquina y seguirá funcionando offline.

## Regla de mantenimiento

La misma regla dura del monorepo, extendida a esta pieza: **si una skill cambia de comportamiento,
la caja del playbook que la cita cambia en el mismo PR.** Para encontrarla, busca el nombre de la
skill en `Playbook-sdlc-ia.html` y en `Playbook-Fases/`.

Cuando un hueco se cierre, la caja cambia de `:::hueco` a `:::skill` en el diagrama, su sección
cambia de badge, y los contadores del índice y de la fase se actualizan con ella.
