# F7 — Retrospectiva

Cierre de la validación real de `instrumentacion-java-ia` (4 skills del plugin `sdlc-ia`) sobre
`base-conocimiento/`, F0 a F6. Todo lo citado acá es real: pasó en esta corrida, con evidencia en
`validacion-workshop/f0-fundamentos.md` a `f6-merge-cd.md`.

## Qué funcionó

- **La revisión adversarial de `github-plan-build` (Step D) ganó su lugar de verdad.** El plan
  inicial de F3 tocaba 1 archivo. Las 3 lentes en paralelo (convenciones, corrección, alcance)
  encontraron, coincidiendo de forma independiente, que faltaba `seguridad/package-info.java` — una
  decisión de arquitectura real que el issue nunca mencionó y que ninguna lectura superficial del
  plan habría atrapado. El plan final terminó en 5 archivos. Sin esa revisión, el PR habría cerrado
  el issue dejando un TODO explícito de `docs/java.md` sin resolver.
- **Las dos rondas de `AskUserQuestion` (Step A y el follow-up post-Step D) preguntaron lo que
  hacía falta preguntar, ni más ni menos.** Ninguna pregunta tenía respuesta ya escrita en el
  issue; ninguna era trivial. La segunda ronda, disparada por un hallazgo real de la revisión
  adversarial, es exactamente el caso que el skill documenta como excepción a "una sola ronda".
- **El umbral de "más de ~3 archivos → plan mode" de Step E se disparó solo, correctamente**,
  cuando el alcance creció de 1 a 5 archivos tras Step D — sin que nadie tuviera que forzarlo.
- **La resolución de `STATUS` (labels vs. Projects v2) no se trabó.** Ningún mecanismo era
  confirmable en este repo (labels sin convención in-progress/in-review; Projects v2 sin el scope
  `read:project`); el skill permite saltar el paso y documentarlo, en vez de bloquear o forzar un
  `gh auth refresh` interactivo a mitad de una corrida autónoma.
- **El propio hallazgo de F2 (allowEmptyShould obsoleto) fue detectado por `agent-context-java` y
  documentado sin corregir, tal como está diseñado** — y ese mismo hallazgo alimentó de verdad el
  ciclo completo F3→F6 de otro skill del mismo plugin. La cadena entre skills funcionó como una
  tubería real, no como una demo.

## Qué ajustaría en `instrumentacion-java-ia`

1. **`allowEmptyShould(true)` como bootstrap sin mecanismo de caducidad.** El patrón que
   `agent-context-java` deja instalado (una regla ArchUnit que nace permisiva "hasta que el paquete
   exista") no tiene ninguna forma de avisar cuando ya caducó — quedó como comentario muerto hasta
   que una corrida completa de `github-plan-build` lo convirtió en issue. Si el discovery de
   `agent-context-java` (o de una corrida posterior) detectara `allowEmptyShould(true)` sobre un
   paquete que ya tiene clases reales, podría sugerir automáticamente el issue en vez de dejarlo
   solo en un doc que alguien tiene que volver a leer.
2. **`github-plan-build` Fase 2 asume una única rama por defecto sin otro trabajo en curso.** Este
   repo tenía un PR previo sin mergear (`#1`) cuya rama era, en la práctica, el punto de partida
   correcto — no `main` (la rama por defecto real) ni `dev` (la rama de integración, pero sin la
   instrumentación de CI todavía). El skill no tiene forma de detectar "hay una rama hermana con
   trabajo en curso que este ticket debería continuar" — hubo que decidirlo a mano y documentarlo
   como desviación. La consecuencia de segundo orden (F6: mergear el PR nuevo absorbió todo el PR
   viejo y lo dejó redundante) es exactamente el tipo de sorpresa que una detección explícita de
   "¿hay un PR abierto que se solape con este trabajo?" en Fase 1 o 2 evitaría, o al menos
   anticiparía.
3. **`/code-review` en Step G, invocado sobre una rama de vida larga con historia acumulada,
   revisó el diff completo contra `main` (338 archivos) en vez de aislar el cambio puntual del
   ticket.** Encontró cero hallazgos, así que no cambió el resultado esta vez, pero diluye la
   atención exactamente donde Step G la necesita más concentrada. Convendría que Step G indicara
   explícitamente comparar contra la punta anterior de la propia rama (o el working tree), no
   contra la rama por defecto, cuando el ticket se resuelve sobre una rama ya adelantada.
4. **Fricción de entorno Windows, menor pero repetida.** `gh` y `gitleaks`, instalados por
   `winget`, no aparecían en el `PATH` de la sesión de shell hasta abrir una terminal nueva — ya
   documentado un patrón hermano en F2 con `make`/`./mvnw`. No es un defecto del skill, pero una
   nota en `instrument-agent-java`/`instrument-project-java` sobre "winget necesita una sesión de
   shell nueva para actualizar el PATH del usuario" ahorraría el mismo tropiezo la próxima vez.

## Costo real de la exhaustividad

La revisión adversarial de 3 agentes tomó entre 150 y 220 segundos cada uno (~70k tokens) para un
fix que terminó siendo 5 archivos chicos — encontró un hallazgo real que valía la pena, pero es
lícito preguntarse si ese costo escala igual para un ticket todavía más pequeño. No se ajustó nada
acá (el skill no expone un nivel de esfuerzo configurable en `github-plan-build`); queda como
observación, no como acción.

## Lo que aprendimos del flujo, no de la skill

Encadenar una rama de trabajo sobre la punta de un PR todavía no mergeado (en vez de sobre la rama
de integración) es un patrón real y común — pero tiene una consecuencia de segundo orden que nadie
planeó de antemano: mergear el hijo absorbe también todo el contenido del padre, dejando el PR
original redundante. Ver el detalle completo en `validacion-workshop/f6-merge-cd.md`. Ninguna de
las 4 skills de `instrumentacion-java-ia` decide esto por el usuario — es, correctamente, una
decisión de quien opera el repo.
