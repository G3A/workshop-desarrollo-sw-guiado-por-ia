# instrument-project-java

## Qué es

Instala la capa de **instrumentación determinística** en un repositorio Java/Maven: un conjunto
de controles que una máquina puede verificar por sí sola, en milisegundos y sin ambigüedad, antes
de que una persona revise el cambio. Cubre doce controles — desde builds reproducibles hasta un
pipeline de CI, el escaneo de dependencias vulnerables, la cobertura del código nuevo y la
separación de suites — y prueba que cada uno realmente falla cuando debería fallar antes de dar la
corrida por terminada.

Es el complemento de `instrument-agent-java`: esta skill instala lo que una computadora puede
decidir sola (¿compila con warnings?, ¿el formato es correcto?, ¿hay un secreto en el commit?);
la otra instala lo que requiere criterio del equipo (qué puede tocar el agente, qué archivos
puede abrir).

## Cómo se invoca

```
/sdlc-ia:instrument-project-java
```

No recibe argumentos.

## Los doce controles

| # | Control | Qué instala | Qué evita |
|---|---------|--------------|-----------|
| 1 | Entradas reproducibles | Maven Wrapper fijado, versiones gestionadas por BOM | Que dos máquinas resuelvan un árbol de dependencias distinto |
| 2 | Build estricto | `-Werror` en el compilador, más **Error Prone** | Que un warning llegue a `main`, y que un bug de tipo compile limpio |
| 3 | Estilo | `.editorconfig`, Spotless, Checkstyle | Ruido de formato y nombres inconsistentes en cada diff |
| 4 | Punto de entrada único | Un `Makefile` (parchado, no reemplazado) | Que nadie sepa cómo se verifica el repositorio |
| 5 | Shift-left | Hooks de pre-commit/pre-push con Lefthook | Que los errores aparezcan recién en la revisión |
| 6 | Escaneo de secretos | gitleaks | Que una credencial llegue al historial de git |
| 7 | Pruebas de arquitectura | ArchUnit / verificación de Spring Modulith | Que la regla de dependencias se rompa en silencio |
| 8 | CI | Workflow de GitHub Actions (la única plataforma de CI que escribe la skill) | Que los controles locales se salteen |
| 9 | Dependencias vulnerables (SCA) | OWASP Dependency-Check detrás de `make sca` (falla `make ci` con CVSS ≥ 7), archivo de supresiones con motivo, y Dependabot en GitHub | Que una dependencia con un CVE conocido llegue a producción sin que nadie lo vea |
| 10 | Cobertura de pruebas | JaCoCo con una regla **solo sobre el código nuevo**, que falla en CI y no en `make check` | Que el código recién escrito llegue sin una sola prueba, sin que nadie lo note |
| 11 | Patrones de bug | SpotBugs (opt-in), que falla `make check` | Defectos que compilan y pasan el estilo |
| 12 | Separación de suites | Failsafe para `*IT`, más `make test` y `make verify` | Que las pruebas rápidas y las lentas corran juntas, y por eso no se pueda exigir ninguna de las dos |

## Fases principales

1. **Descubrimiento silencioso** — revisa a fondo el proyecto (Maven o Gradle, grafo de módulos,
   JDK objetivo, versiones gestionadas por BOM vs. sueltas, configuración de pruebas, plataforma
   de CI, documentación existente) y reporta, control por control, si ya está `presente`,
   `parcial` o `ausente`. Un control parcial es más peligroso que uno ausente, porque el equipo
   cree que ya está cubierto.
2. **Prerrequisitos** — verifica qué herramientas ya están instaladas (JDK, Maven, Lefthook,
   `make`, gitleaks) según el sistema operativo, pero no instala nada por su cuenta.
3. **Acordar el alcance y aplicar** — antes de escribir nada confirma que el árbol de trabajo
   está limpio. Pregunta solo lo que el descubrimiento no pudo resolver: qué controles instalar,
   qué formateador usar (`google-java-format` o `palantir-java-format`), si reformatear todo el
   repositorio de una vez o solo lo tocado desde la rama base, si escribir el workflow de CI, si
   activar el escaneo de secretos, si instalar un hook de mensajes de commit con Conventional
   Commits (solo si el historial ya sigue esa convención) y si activar el escaneo de
   dependencias vulnerables (apagado por defecto, como los secretos: cuesta una clave gratuita
   de la NVD o una primera corrida lenta, y un archivo de supresiones que hay que curar). Luego
   instala los controles en el orden que uno depende del anterior, verificando cada uno antes de
   seguir con el siguiente.
4. **Verificar rompiendo** — para cada control, provoca deliberadamente una falla real (por
   ejemplo: agrega un import sin usar, intenta un commit con una credencial de prueba, reordena
   imports) y confirma que el control efectivamente lo detiene. Deshace cada cambio de prueba
   después. No se reporta éxito con ningún control en rojo.
5. **Documentar y reportar** — actualiza `AGENTS.md`/`CLAUDE.md` si ya existen, agregando una
   sección de "Checks to run" y el detalle de las reglas de arquitectura ahora vigentes. No crea
   el paquete de documentación desde cero: si no existe, reporta el hueco y sugiere correr
   `/sdlc-ia:agent-context-java` primero. Al final reporta el árbol de archivos tocados, las
   versiones resueltas, la salida real (en verde) del comando de verificación, y cada excepción
   dejada con su motivo.

## Qué archivos toca o crea

- `.mvn/wrapper/*`, cambios en `pom.xml` (BOMs, plugin del compilador, Spotless, Checkstyle,
  ArchUnit).
- `.editorconfig`.
- `Makefile` (parchado si ya existe, nunca reemplazado).
- `lefthook.yml`.
- Configuración de gitleaks (si se activa).
- Plugin `dependency-check-maven` en `pom.xml`, `dependency-check-suppressions.xml` y
  `.github/dependabot.yml` (si se activa el control 9).
- Clases de test de arquitectura (ArchUnit).
- Workflow de CI (`.github/workflows/ci.yml`). Si el repositorio ya tiene su CI en otra
  plataforma, la skill lo reporta como fuera de alcance y le indica el target `make ci` que ese
  pipeline puede invocar; no escribe pipelines para otras plataformas.
- Secciones de `AGENTS.md`/`CLAUDE.md`, si ya existen.

Nunca hace `commit` ni `push`: los únicos cambios de git que ejecuta son los de romper y
restaurar durante la fase de verificación, y quedan deshechos antes de terminar. El diff final
queda para que el usuario lo revise.

## Decisiones de diseño a tener en cuenta

- **Nunca fija versiones a mano.** Lee la versión de JDK y Maven directamente del wrapper y del
  POM del repositorio, y resuelve la versión de gitleaks o de las acciones de marketplace en el
  momento de instalar, no de memoria.
- **Codifica lo que el repositorio ya hace, no lo que "debería" hacer.** Cada regla de
  arquitectura tiene que pasar en el momento en que se escribe: si nace en rojo, es una propuesta
  de refactor, no un sensor válido.
- **En un repositorio como `base-conocimiento`, varios controles ya están cumplidos** — por
  ejemplo, ya tiene pruebas de arquitectura con ArchUnit instaladas. En ese caso la skill no las
  reinstala desde cero: las lee, confirma que siguen pasando, y reporta qué dejan sin cubrir (por
  ejemplo, un paquete que ninguna regla protege todavía) en vez de agregar reglas nuevas por
  cuenta propia.
- **Fusiona, nunca reemplaza.** Un `Makefile` que ya existe recibe un parche, no una reescritura
  completa.
- **Toda excepción queda documentada.** Cualquier supresión de Checkstyle o regla de arquitectura
  con `allowEmptyShould` lleva un comentario explicando por qué existe — una excepción sin
  comentario es deuda invisible.
- **El hook de Conventional Commits no se propone por defecto.** Solo se instala si el historial
  de commits del repositorio ya sigue esa convención; imponerla donde nadie la usa es una
  decisión de equipo, no una corrección de instrumentación.
- **Todo lo que la skill escribe (configuración, comentarios, nombres de pasos de CI) queda en
  inglés**, salvo que esté editando texto que ya existía en otro idioma (por ejemplo, un
  `AGENTS.md` en español) — ahí respeta el idioma del archivo que edita.
