# ADR-0006: El acta de la IA se versiona en git, y el commit asistido la cita

## Estado

Propuesto — 2026-10-02, en la PR de la fase 3 del #222. Completa el punto 5 del
[ADR-0005](0005-re-ejecutar-el-registro-de-la-ia-sin-el-modelo.md), que dejó abierto qué se
versiona, y enmienda su regla de que el sensor «falla si diverge».

## Contexto

El ADR-0005 decidió que un trailer `Registro-IA:` apunta a las actas de la tarea y que un sensor del
CI re-ejecuta la curada. Dejó abierto dónde vive el acta. Había dos opciones:

1. **En git, junto al código.** El commit y su acta viajan juntos, se revisan en la misma PR y
   sobreviven a la rotación de artefactos. Cuesta tamaño en el repo y publica lo que el acta
   trae.
2. **Como artefacto del CI, con su hash en git.** El repo no crece, pero el acta vence con la
   retención de artefactos y la PR no la muestra. Además, el CI no tiene de dónde sacarla: el acta
   se compila en la máquina de quien trabajó, no en el runner.

Medido en la fase 1 de este issue: unos 50 turnos dejan una cruda de 1 MB, una curada parecida y
un pack de objetos de 260 KB.

La persona responsable del repo eligió la opción 1.

## Decisión

**El acta se versiona en git.** Por cada sesión y tarea van tres archivos en
`.ia/registros/<tarea>/`:

- la cruda y la curada;
- `<sesión>.acta.objetos.pack`, un pack de git con los árboles que la captura escribió en la
  máquina de quien trabajó, menos lo que el `headBase` ya alcanza. Sin él, un clon no puede
  verificar la curada ni armar el repo del motor;
- `indice.json`, que se reescribe con cada acta.

**No se versionan** la captura (`.ia/captura/`), que la cruda ya contiene, ni los reportes del
motor, que el CI regenera y dependen de la imagen construida.

`.ia/` sigue en `.gitignore`. El acta entra con `git add -f`, que hace
`instrumentacion-java-ia/sdlc-ia/acta/registrar-sesion.mjs`: así un `git add -A` no publica todas
las sesiones, solo la que se registra a propósito.

**El trailer.** Todo commit con `Asistido-por-IA:` lleva `Registro-IA: <ruta de la curada>` o
`Registro-IA: ninguno: <motivo>`. Como con el pie de asistencia, se exige declarar, no tener acta.
El hook `commit-msg` lo agrega solo cuando el commit lo hace Claude Code, que exporta
`CLAUDE_CODE_SESSION_ID`. El cuerpo de la PR repite cada trailer, porque el merge a `dev` es por
squash con ese cuerpo como mensaje.

**El sensor del CI** (`instrumentacion-java-ia/sdlc-ia/acta/verificar-registro-ia.mjs`) falla si:

- el acta citada no está en la PR;
- la curada no es la que sale de su cruda (invariante 8);
- el commit deja un archivo con un contenido que ningún árbol del acta tuvo;
- el motor no reproduce una acción aplicada (Edit, Write, hook, residuo) o se detiene.

**Enmienda al ADR-0005.** Un comando *ejecutado* que diverge en el contenedor se avisa y no hace
fallar el sensor. En el contenedor no hay red, ni Maven, ni Docker, y la mayoría de esas
divergencias son del entorno, no del registro. La integridad del registro la sostienen las
acciones aplicadas y la cadena de árboles, y esas sí bloquean.

**Evidencia de Playwright (#230).** Junto al acta va también `evidencias/<sha256>.<ext>`: las
capturas, los `trace` y los videos que la sesión produjo. Salen de dos fuentes:

- lo que aparece o cambia en `test-results/`, la carpeta por defecto de Playwright Test,
  mientras corre una acción. El hook lo detecta por la fecha y lo copia a
  `.ia/captura/evidencias/`, porque la corrida siguiente de Playwright borra la carpeta;
- las imágenes que devuelve una herramienta en su resultado, como el screenshot de un
  navegador. Vienen en el transcript.

El acta guarda de cada una el nombre, el tipo, el tamaño y el sha256. El archivo se versiona si
cabe en dos topes: **2 MB por archivo y 20 MB por acta**, en el orden de la sesión. Lo que no cabe
queda nombrado, con su hash y el motivo, y el visor dice «no se incluyó».

## Consecuencias

- **A favor**: la PR muestra qué hizo la IA y el CI comprueba que eso llega a su diff. Revisar una
  PR asistida deja de depender de la memoria de quien la abrió.
- **En contra**: **el acta se publica.** Este repo es público, y el acta trae prompts, salidas de
  comandos y contenido leído durante la sesión. La filtran gitleaks y la redacción de rutas y de
  variables con nombre de secreto. Ninguno de los dos filtros sabe qué es privado para una
  persona: antes de commitear un acta, quien la registra la lee.
- **En contra**: una captura no se redacta. Muestra lo que había en la pantalla, y los filtros
  de texto no la ven: quien registra el acta revisa también sus evidencias.
- **En contra**: el repo crece cerca de 2 MB por sesión larga, más las evidencias, hasta 20 MB.
  Si molesta, la opción 2 sigue disponible para las crudas, con la curada y el pack en git.
- **En contra**: la sesión tiene que registrarse antes de abrir la PR. El hook de `SessionEnd`
  llega tarde para el commit que la cita, y por eso existe `registrar-sesion.mjs`.
- **Qué haría reconsiderar**: un repo privado, donde publicar deja de ser el riesgo, o que el
  tamaño acumulado de las actas pese más que el valor de revisarlas en la PR.
