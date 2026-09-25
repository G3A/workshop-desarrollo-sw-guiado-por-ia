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
| **A mano** | La haces tú, y el repositorio te enseña cómo: un nodo del visor `proceso-operacional-con-ia`, un manual de `manuales/`, o la propia página de la fase cuando el paso es una conversación y no un comando. |
| **Hueco** | Nadie la cubre acá: ni skill, ni visor, ni manual. |
| **Fuera de alcance** | Frontera declarada del plugin, no un olvido: Azure DevOps, memoria semántica entre sesiones, agentes especialistas por stack — y una derivada de la segunda, los hooks `type: mcp_tool`. |

Cobertura al momento de escribir esto: **43 skill · 2 parcial · 19 a mano · 9 hueco · 3 fuera de
alcance**, sobre 76 cajas con badge.

## El segundo eje: determinista o no determinista

El badge dice **quién ejecuta** la caja. Hay un segundo eje, independiente, que dice **qué tipo de
instrumentación es**: un control es *determinista* solo si el disparo y la decisión quedan los dos
fuera del razonamiento del modelo, y basta que una de las dos dependa del modelo para que sea *no
determinista*. El eje entero está escrito en la caja **«El eje de la instrumentación»**, en la
página de referencia.

Los dos ejes conviven en la misma caja del diagrama, cada uno en su canal: el **relleno** dice
quién ejecuta y el **contorno** de qué tipo es, azul determinista y violeta no determinista. Tienen
que ir separados porque el azul de determinista y el de *a mano* son el mismo color, y porque se
cruzan de verdad: `DHH` y `NHP` son los dos **huecos**, así que salen del mismo rojo, y sin embargo
son de tipos opuestos.

El contorno lo llevan **solo las cajas de hooks y MCP**, que es el único grupo que mezcla los dos
tipos; en los otros dos lo dice el título del grupo, y un contorno más sería ruido sin información.

En las páginas de fase el tipo va en **cada sección**, con una pastilla del mismo color, y las del
grupo de hooks y MCP cierran con una línea **«quién dispara / quién decide»**. Ahí sí lo llevan
todas, y no el encabezado del grupo, por una razón concreta: al llegar desde el diagrama la página
entra en modo foco y el encabezado no se ve.

Dos cajas rompen la intuición de «hooks determinista, MCP no determinista» y están marcadas como
excepción: `DMH`, un hook `type: mcp_tool`, es determinista aunque llame a un servidor MCP porque
el modelo no eligió llamarlo; y `NHP`, un hook `type: prompt`, es no determinista aunque sea un
hook porque la decisión sale de inferencia.

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

**Los códigos de dos o tres letras** — `RD`, `DCJ`, `N5T`, `X4` — son el identificador de cada
caja, no siglas que signifiquen algo. Son el mismo en el diagrama y en la pastilla gris de la
esquina de su sección, y existen solo para saltar entre los dos: si una sección te habla de `DMP`,
esa es la caja que tienes que buscar en el diagrama, y al revés.

**No intentes decodificarlos.** La primera letra suele recordar el grupo —`X` contexto, `D` y `N`
las dos instrumentaciones de la Fase 2, `S` especificación, `V` verificación, `K` métricas, `E`
etapas— pero no es un sistema: `R` está usada a la vez para la referencia (`RD`), para el
requisito de la Fase 3 (`RQ`) y para la retroalimentación de la Fase 4 (`R1`, `R2`, `R3`).

Por eso, **en prosa se nombra la caja por su título** y el código se deja para la pastilla. Un
código suelto no le dice nada a quien llega de nuevo, que es justo lo que pasó con `RD`.

> **Si personalizaste enlaces alguna vez en este navegador**, lo guardado gana sobre lo que trae
> el archivo. Para comprobarlo, abre la consola con el diagrama abierto y ejecuta
> `localStorage.getItem('playbook-sdlc-ia-links-v1')`. Si devuelve `null`, no hay nada guardado.

## De dónde sale cada afirmación

Este playbook **no duplica documentación: enruta**. Cada caja con badge *Skill* o *Parcial* cita
el `SKILL.md` del que sale; cada caja *A mano* nombra el nodo concreto del visor. Las piezas:

| Pieza | Qué aporta |
|---|---|
| `instrumentacion-java-ia/` | El plugin `sdlc-ia` y sus nueve skills. |
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
  03-preparacion-del-terreno.html  Fase 2 — contexto, 13 controles, el juez, hooks y MCP.
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

**Esta regla la verifica un sensor, no la memoria:** `node playbook-sdlc-ia/verificar-cobertura.mjs`
(igual en PowerShell y en bash, sin dependencias) falla si, para alguna caja, no coinciden la clase
del diagrama, su dato `B(...)`, la clase y el badge de su sección, o si un contador del índice, de
este README o de la barra de una fase no sale del diagrama. Corre en el CI, en el job `check`.
