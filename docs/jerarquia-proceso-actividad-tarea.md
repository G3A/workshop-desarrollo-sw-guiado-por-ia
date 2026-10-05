# Jerarquía del trabajo en un modelo de calidad

Proceso, actividad, tarea, paso, acción, instructivo de trabajo y fase no forman una sola escalera.
Responden a dos preguntas distintas:

1. **Cómo se descompone el trabajo:** proceso → actividad → tarea → paso → acción.
2. **Cómo se documenta ese trabajo:** manual → procedimiento → **instructivo de trabajo** →
   registro.

Las **fases** son un tercer eje: cortan el tiempo, no el trabajo.

## 1. La descomposición del trabajo

| Nivel | Qué es | Quién o qué lo caracteriza | Pregunta que responde |
|---|---|---|---|
| **Proceso** | Conjunto de actividades relacionadas que convierten entradas en un resultado con valor (definición de ISO 9000) | Tiene dueño, propósito, indicadores, entradas y salidas | ¿Qué transformamos y para qué? |
| **Actividad** | Grupo de tareas que produce un resultado intermedio identificable | Suele estar a cargo de un **rol** | ¿Qué bloque de trabajo hay que hacer? |
| **Tarea** | Unidad de trabajo que se puede asignar, estimar y dar por terminada | La hace **una persona**, con inicio y fin | ¿Qué le toca a alguien hoy? |
| **Paso** | Cada momento de la secuencia que forma una tarea | Tiene orden: 1, 2, 3… | ¿En qué orden se hace? |
| **Acción** | Lo más atómico: un verbo concreto y observable | No se descompone más | ¿Qué hago físicamente? |

Para saber en qué nivel estás, pregúntate quién lo hace y qué sale de ahí:

- Si sale un **resultado para el cliente o el negocio**, es un proceso.
- Si sale un **producto intermedio** a cargo de un rol, es una actividad.
- Si **una persona puede decir "terminé"**, es una tarea.
- Si ya **no tiene sentido asignarlo por separado**, es un paso o una acción.

## 2. Las fases son un eje aparte

Una **fase** (o etapa) divide el ciclo de vida **en el tiempo** y cierra con un hito o entregable
(un *gate*). No está "entre" proceso y actividad: se cruza con ellos.

```text
                 Fase 1       Fase 2        Fase 3
                 Inicio       Construcción  Transición
Proceso A        ██░░         ████          ░░
(Requisitos)
Proceso B        ░░           ████████      ██
(Desarrollo)
Proceso C                     ██            ████
(Pruebas)
```

Un mismo proceso aparece en varias fases con distinta intensidad. RUP es el caso clásico: 4 fases
× 9 disciplinas. En modelos secuenciales (cascada), fase y proceso casi coinciden y por eso se
confunden.

## 3. El instructivo de trabajo es un documento, no un nivel

La pirámide documental típica de ISO 9001 se ve así:

```text
        Manual / Política         → el "por qué" y el "qué" del sistema
       Procedimientos             → describen PROCESOS y ACTIVIDADES (quién, qué, cuándo)
      Instructivos de trabajo     → describen TAREAS paso a paso (cómo, con qué acciones)
     Registros / formatos         → la evidencia de que se hizo
```

- El **procedimiento** dice *qué* se hace y *quién* lo hace.
- El **instructivo** dice *cómo* se hace, al nivel de pasos y acciones, para que una persona nueva
  lo ejecute sin ayuda.

## Ejemplo completo

- **Proceso:** Desarrollo de software
- **Fase:** Construcción
- **Actividad:** Revisión de código
- **Tarea:** Revisar el PR #212 (asignada a Ana, 1 hora)
- **Pasos:** 1) Leer la descripción. 2) Correr las pruebas localmente. 3) Revisar el diff.
  4) Dejar el veredicto.
- **Acciones:** abrir el PR, hacer clic en *Files changed*, escribir un comentario, pulsar
  *Approve*
- **Instructivo de trabajo:** "IT-DEV-03 · Cómo revisar un pull request", que documenta esos pasos
  y acciones
- **Registro:** la aprobación que queda en GitHub

## Ojo con los modelos

Cada norma usa su propio vocabulario, así que conviene alinear los términos con el modelo que vas
a auditar:

- **ISO/IEC 12207** e **ISO 29110:** proceso → actividad → tarea.
- **SPEM / RUP:** fase → iteración → actividad → tarea → paso.
- **CMMI:** habla de áreas de práctica y prácticas, no de esta escalera.
- **PMBOK:** "fase" es una fase del proyecto y "actividad" es lo que va en el cronograma. Ahí se
  parece más a lo que otros llaman tarea.
