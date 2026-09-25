# ADR-0004: el método exige el despliegue a producción, no la herramienta

## Estado

Aceptado

## Contexto

El ciclo por issue desplegaba a staging, validaba esa corrida y liberaba `dev` → `main`. Después de
eso no había nada: la palabra «producción» no aparecía en ningún nodo del visor, ni había caja del
playbook o ADR que la nombrara. El único camino de recuperación era «Arreglar hacia adelante», que
opera sobre `dev` y cuyo propio texto admite que hay otra opción —revertir— que el diagrama nunca
dibujó.

Un lector honesto no podía saber si «Liberar dev a main» *es* desplegar, o si `main` es solo un tag
y el despliegue vive fuera de este mapa. Y el camino de vuelta siempre termina existiendo: si no
está escrito, se improvisa el día que algo se rompió, que es el día en que nadie quiere improvisar.

La restricción que decide la forma es el alcance declarado del método: **no asume ninguna
organización, empresa ni herramienta interna**. Quien lo siga puede desplegar en un PaaS, en
Kubernetes o en un servidor propio, así que el proceso no puede traer el comando de nadie.

Las opciones evaluadas:

1. **Declarar que el proceso termina en staging** y que el despliegue es de cada organización.
   Barato —un ADR y una línea—, pero deja fuera el gate más caro del ciclo y no resuelve la
   reversa, que es de donde vino el problema.
2. **Prescribir una herramienta** y enseñar el tramo completo con sus comandos. Concreto y
   copiable, y rompe el alcance del método en la primera línea: el ejemplo se lee como requisito.
3. **Exigir las propiedades y dejar abierto el mecanismo**, que es lo que el método ya hace en
   «Levantar vista previa efímera» («el mecanismo exacto depende de tu stack») y en «CD automático
   a staging», que no trae ningún comando.

## Decisión

Se toma la opción 3. El método **exige tres propiedades** del tramo de producción y **no prescribe
ninguna herramienta**:

1. **El despliegue lo dispara la liberación**, no una persona con la terminal.
2. **La aprobación queda registrada**, con nombre y hora. El mecanismo sí es de GitHub, porque el
   método ya lo asume entero: un **Environment con revisores requeridos**, que detiene la corrida
   hasta que alguien aprueba.
3. **Existe un camino de vuelta, y está ensayado.** Un rollback que nadie ejecutó nunca es una
   promesa, no un camino — la misma doctrina con la que «Confirmar hooks del agente» exige ver
   disparar cada hook en vez de comprobar que el archivo existe.

Sobre la reversa, el proceso enseña **el criterio y no el comando**. Dos familias, ninguna
prescrita: volver por el mecanismo del proveedor —swap de slots, redesplegar el artefacto anterior,
`kubectl rollout undo`, mover un alias—, que es el camino de minutos; o revertir en git y liberar,
que cuesta una liberación entera y deja la historia coherente. Con la regla que las ata: **si
vuelves por el mecanismo del proveedor, `main` queda describiendo algo que ya no corre**; revertir
el código deja de ser urgente, pero no deja de ser obligatorio.

## Consecuencias

- **A favor**: el tramo más caro del ciclo deja de ser implícito, y la reversa deja de ser una frase
  dentro de otro nodo. El método sigue siendo reproducible en cualquier repositorio de GitHub sin
  importar dónde despliegue el equipo.
- **A favor**: al exigir propiedades en vez de comandos, el tramo no envejece cuando cambie la
  herramienta de nadie.
- **En contra**: tres cajas que ninguna skill puede cubrir, porque el comando es del equipo. En el
  playbook quedan como trabajo a mano, y ahí seguirán mientras el método no prescriba herramienta
  —que es justamente lo que este ADR decide no hacer—.
- **En contra**: un paso sin comando copiable es más fácil de saltarse que uno con comando. Se
  compensa con las preguntas concretas del nodo (qué dispara el despliegue, quién aprueba, cuándo
  se ensayó la vuelta), que son verificables aunque el mecanismo no esté escrito acá.
- **Qué haría reconsiderarlo**: que el taller adopte una plataforma de despliegue propia. Ahí el
  ejemplo dejaría de ser ejemplo y pasaría a ser el camino, y este ADR se reemplazaría por uno que
  lo diga.
