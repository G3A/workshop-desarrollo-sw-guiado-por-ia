# impact-metrics

## Qué es

La **Fase 5 del método**: mide qué cambió de verdad con la entrega asistida por IA y escribe el
reporte que lee liderazgo. Produce tres artefactos —el agregado de métricas, el agregado de la
encuesta y el reporte— abre la pull request y se detiene.

Es también la caja detrás de la cual espera el resto del backlog: **dos de los cuatro criterios de
avance de la Fase 6 se evalúan con estos números**.

## Cómo se invoca

```
/sdlc-ia:impact-metrics
/sdlc-ia:impact-metrics 2026-Q3
```

El período es opcional; sin él, pregunta, y el default es el último trimestre completo.

## Dos métricas de las cuatro, y por qué

No es dificultad: las cuatro salen de git y de `gh`. Es **certeza de la definición**.

| Métrica | v1 | Qué la frena |
|---|---|---|
| % de PRs asistidas por IA | **Entra** | Nada |
| Lead time (mediana) | **Entra** | Nada |
| Retrabajo | Fuera | La definición y el tamaño de muestra |
| Cobertura | Condicional | Solo si el repositorio tiene el reporte de JaCoCo |

Contar commits con un marcador es contar, y «de abierto a merge» significa lo mismo para todos. Las
otras dos exigen acordar algo antes. **Un primer reporte con un número discutible desacredita los
otros tres**: la reunión se vuelve sobre metodología en vez de sobre el trabajo.

**Lo que falta se reporta como faltante, con su motivo — nunca se omite.** Una omisión se lee como
«no importaba».

## Los comandos, verificados contra un repositorio real

Los dos que entran son **idénticos en PowerShell 5.1, PowerShell 7 y bash**: sin tuberías, sin
`&&`, sin sustitución de comandos. Están en `references/metric-definitions.md` con su salida real.

Dos detalles que la skill trata como reglas:

- **`--first-parent` no es opcional.** La rama de integración recibe por squash, así que una
  entrada de primer padre equivale a una PR. Sin esa bandera, en el repositorio de prueba el total
  pasa de 26 a 93.
- **`--grep` y no el lector de trailers de git.** El marcador está en el mensaje, pero queda en un
  párrafo propio y git solo reconoce como trailers el último bloque contiguo. `--grep` lo encuentra
  esté donde esté, y funciona retroactivamente sobre todo el historial.

**Cero no es un resultado.** Un repositorio que lleva tiempo usando el ciclo y reporta `0 %` está
diciendo que el marcador no se está escribiendo, no que ninguna PR usó IA. La skill dice cuál de
las dos cosas es.

**Se reporta la mediana, no el promedio.** En el período de prueba: 14 horas de mediana contra 62
de promedio, arrastrado por una sola PR de 172. El promedio describe el peor caso disfrazado de
caso típico.

## La encuesta

El plugin **no es una plataforma de encuestas**. Posee tres cosas: el cuestionario, el requisito de
anonimato y el formato de agregación. La captura es del equipo, con la herramienta de formularios
que ya tenga.

La plantilla trae el procedimiento completo, incluida **qué casilla hay que apagar en cada
herramienta** —en Microsoft Forms, «Registrar nombre» viene activada por defecto en cuentas de
trabajo, que es la trampa más común— y el paso de comprobarlo respondiendo uno mismo antes de
compartirlo.

**Solo se versiona el agregado**, una fila por pregunta. Quitar el nombre de una fila por persona
no da anonimato: con un equipo chico, el patrón de cuatro respuestas identifica igual, y git es
permanente. Por debajo del piso de muestra acordado no se publica distribución: se reporta
«muestra insuficiente».

## El reporte: genera y no envía

Escribe `docs/metricas/reporte-<periodo>.md`, abre la PR y **se detiene**. Enviarlo es de una
persona — ni correo, ni chat, ni Discussion.

Es coherente con todo el paquete, que nunca hace merge, nunca despliega y nunca manda una
comunicación real: `github-plan-build` escala explícitamente en «comunicaciones reales a clientes»,
y un reporte a liderazgo es eso.

Y la PR no es trámite: pone los números delante de alguien antes de que salgan del equipo, y deja
un enlace permanente, que es lo que de verdad se comparte.

## Decisiones de diseño a tener en cuenta

- **El reporte se deriva del agregado, nunca al revés.** El CSV se escribe primero, para que el
  período siguiente se compare contra un archivo y no contra un párrafo.
- **Cada cifra lleva de dónde salió.** Un reporte cuyas cifras no se pueden recalcular es una
  anécdota con números.
- **Sin período anterior, se llama línea base y se dice.** Es el criterio de avance de la Etapa 1, y
  decirlo vale más que inventar una tendencia.
- **La definición de retrabajo ya está acordada y escrita** —PRs de corrección o revert que
  referencian una PR integrada en los últimos 14 días— aunque no se mida todavía, para que la v2 no
  sea un debate nuevo. Cambiarla después rompería la comparación con el período anterior.
- **La métrica más importante es la que peor se comporta con poca muestra.** Con 26 PRs al
  trimestre y nueve asistidas, comparar grupos de nueve y diecisiete sobre una medida ruidosa
  produce un número que se mueve solo y se lee como señal.
