## Qué cambia y por qué

<!-- Una o dos frases. El "por qué" importa más que el "qué": el diff ya dice el qué. -->

## Qué se decidió sin que se note en el diff

<!-- Trade-offs, la alternativa descartada, lo que quedó peor para que esto quedara listo.
     Si no hay nada, escribe "nada" — es una respuesta válida y ahorra la pregunta en la revisión. -->

## Revisión humana

Los criterios completos, con su porqué, están en [`REVIEW.md`](../REVIEW.md). Marca lo que
revisaste de verdad; una casilla marcada sin mirar es peor que una vacía.

- [ ] **Alucinaciones de API** — métodos, firmas y claves de configuración que existen en la versión fijada
- [ ] **Fidelidad al proyecto** — resuelve como este repositorio resuelve, sin abrir una segunda forma de hacer lo mismo
- [ ] **Tests de la spec** — verifican el requisito y fallan si se revierte el cambio
- [ ] **Deuda técnica** — lo que quedó peor está nombrado; ninguna supresión sin motivo
- [ ] **Trade-offs** — la alternativa descartada está dicha
- [ ] **Corrí el cambio yo mismo** — no solo vi el CI en verde

<!-- ¿Dijiste algo a mano por tercera vez en una revisión? Eso no es indisciplina de quien
     escribe: es un criterio que falta en REVIEW.md. Agrégalo en esta misma PR. -->

<!-- El merge a dev es squash con este cuerpo como mensaje: el párrafo final llega tal cual al
     commit. Refs #N, o Closes #N si la PR completa el issue; borra la línea del trailer si
     ningún modelo asistió. -->

Refs #

Asistido-por-IA: <modelo>
