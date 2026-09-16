# EXPERIMENTS.md — qué puede fallar en workshop-desarrollo-sw-guiado-por-ia, y qué pasa cuando falla

Este archivo existe porque **un equipo que solo puede usar el agente cuando está seguro de que va
a salir bien no aprende a usarlo: aprende a esconder cuándo lo usó.**

No es una política de la herramienta. Es lo que este equipo acordó, con nombre y fecha, para que
el permiso siga existiendo la semana después de la primera PR que salió mal.

---

## 1. Qué puede fallar, y dónde

<!-- TODO: el alcance del permiso. Sé concreto: qué tipo de trabajo, en qué ramas, con qué tamaño
     de cambio. "Todo" y "nada" son las dos respuestas que no sirven. -->

**Alcance acordado:** <!-- TODO -->

**Dónde:** ramas de trabajo que integran contra `dev`. <!-- TODO: confirmar o corregir -->

## 2. Qué nunca es un experimento

Esta lista es la que hace que el permiso se pueda dar sin miedo. **Es el punto de partida, y su
procedencia está declarada**: de la sección «Escalation» de `github-plan-build/SKILL.md` toma las
filas que son **límites del permiso** —no las que son gates del ciclo de entrega— **y añade una
propia**, la última, que el escalamiento no tiene. No es una copia ni un subconjunto estricto: las
dos listas contestan preguntas distintas, y forzarlas a ser idénticas es lo que separa al agente del
acuerdo.

- Escrituras en producción, despliegues y cualquier acción destructiva o irreversible.
- Comunicaciones reales a destinatarios reales.
- Trabajar alrededor de una credencial o un permiso que falta, en vez de pedirlo.
- Cambios que tocan autenticación, secretos o datos de personas. *(Este no sale del escalamiento:
  el ciclo de entrega no se detiene ante ellos, les exige `/security-review` como gate obligatorio,
  y no avanza en rojo. Eso los deja **revisados**, no permitidos — si tu equipo además quiere que
  estén fuera del permiso, dilo en la sección 1.)*
- <!-- TODO: lo que este equipo agregue. Borra lo que no aplique, pero di por qué. -->

**Lo que deliberadamente NO se copia del escalamiento**, porque son gates del ciclo y no límites del
permiso: una falla de CI ambigua y un ciclo de arreglos que no converge —los dos se disparan por el
estado del pipeline, no por lo que esté permitido intentar— y una decisión de producto sin fuente de
verdad, que además **es reversible**: lo que falla ahí es el plan, no producción. Que el agente no
decida producto por ti se acuerda escribiendo bien el alcance de la sección 1.

<!-- Procedencia, para cuando alguien compare: las tres primeras filas salen de la sección
     «Escalation» de instrumentacion-java-ia/sdlc-ia/skills/github-plan-build/SKILL.md; hay una
     segunda copia íntegra de esa lista en references/build-loop-execute.md, y son la misma. La
     cuarta es propia de la plantilla de agent-context-java: la corrida del 2026-09-15 la omitió al
     copiar el escalamiento, y se re-añadió al decidirlo en el issue #141 (2026-09-16). -->

## 3. Qué pasa cuando sale mal

<!-- TODO: qué se espera de quien lo intentó, qué se espera del equipo, y qué NO va a pasar. -->

**La cláusula que decide si esto es real: el marcador se queda.**

El trailer `Asistido-por-IA` de una PR que salió mal **no se borra, ni se omite, ni se discute**.
Es la única forma de que el permiso signifique algo: si el marcador desaparece de los intentos
fallidos, el registro mide **solo los éxitos**, y este equipo habrá aprendido exactamente lo que
este archivo existe para evitar.

> **Y se puede comprobar.** Compara la tasa de marcado en las PRs revertidas o parchadas de
> urgencia contra la tasa general. Si son distintas, el acuerdo está escrito y no se está
> cumpliendo — información bastante más útil que el número solo.

## 4. Quién lo dio, y cuándo

Un permiso sin nombre y sin fecha es un rumor: a los tres meses nadie sabe si sigue vigente, y en
la duda la gente asume que no.

| | |
|---|---|
| **Lo dio** | <!-- TODO: nombre y rol --> |
| **Fecha** | <!-- TODO: AAAA-MM-DD --> |
| **Se revisa** | <!-- TODO: una fecha o un evento. Un permiso permanente que nadie revisita termina olvidado o ilimitado, y ninguna de las dos es lo que se acordó. --> |

## 5. Qué se comparte

La lección de un experimento fallido **es el retorno del permiso**. Sin este paso, el equipo pagó
el costo del intento y no cobró nada.

- Dónde se anota: <!-- TODO: por defecto, la sala de espera `docs/lecciones.md` que propone el
  paso de cierre del ciclo de entrega. -->
- Cuándo: <!-- TODO -->
- Qué se hace con lo que se repite: promoverlo a `AGENTS.md`, a `REVIEW.md`, a un ADR, o —lo que
  más rinde— convertirlo en un sensor, para que deje de depender de que alguien se acuerde.

---

## Lo que este archivo no es

- **No es un permiso ilimitado.** La sección 2 es tan parte del acuerdo como la 1.
- **No es un escudo.** Que algo estuviera permitido no vuelve bueno el resultado; vuelve seguro
  contarlo.
- **No es un trámite.** Nadie firma nada aquí, y no hay un check de CI que lo exija. Un acuerdo que
  se convierte en casilla se marca sin leer.
