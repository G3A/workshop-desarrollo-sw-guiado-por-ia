# ADR-0005: El registro de la IA se re-ejecuta sin el modelo, en un sandbox

## Estado

Aceptado — 2026-10-01. Propuesto en la PR #214 y aceptado tras su revisión. El modelo de datos
que este ADR usa como referencia está en
[`docs/modelo-conceptual-registro-ia.md`](../modelo-conceptual-registro-ia.md).

## Contexto

Hoy el trailer `Asistido-por-IA:` declara **que** hubo IA en un commit, pero no **qué hizo**.
`scripts/agent-hooks/audit-log.sh` deja una bitácora local de cada `PreToolUse`, pero no guarda el
prompt, ni el resultado de las herramientas, ni el estado de git. Además está en `.gitignore`, rota
a los 5 MiB y corre en `async`, así que el orden de las líneas no está garantizado. Con eso no se
puede reconstruir una sesión ni auditar una PR asistida.

Se busca un registro de todas las acciones de la IA, del orquestador y de sus subagentes, que se
pueda re-ejecutar desde cero de forma determinista. La restricción que decide la forma es que
**un LLM no es determinista**: ni la temperatura, ni la versión del modelo, ni lo que devuelven la
red y los MCP quedan fijos entre corridas.

Opciones evaluadas para **re-ejecutar**:

1. **Reenviar los prompts al modelo** (`claude -p` con el modelo fijado). No reproduce: produce otra
   sesión parecida. Sirve para medir deriva, no para verificar un registro.
2. **Solo reproducir el texto grabado**, sin ejecutar nada. Es determinista, pero tautológico: el
   registro se compara consigo mismo y nunca toca el código.
3. **Ejecutar los comandos grabados en un worktree del host.** Es real, pero un `git reset` o un
   `rm` de la sesión corre contra la máquina, y el hook de JGit commitea en el repo real desde un
   worktree enlazado.
4. **Ejecutar los comandos grabados dentro de un contenedor**, con lo externo servido desde el
   registro. Es real, aislado y repetible.

## Decisión

**Opción 4.** La re-ejecución no depende de Claude: depende de los comandos y los resultados que
el orquestador y sus subagentes dejaron en la sesión. Las decisiones del modelo ya quedaron
materializadas en esas acciones.

1. **Fuente.** Un hook `SessionEnd` compila el transcript principal y los de cada subagente
   (`<session>/subagents/agent-*.jsonl`) en un **acta cruda**. Los hooks agregan lo que el
   transcript no tiene: el hash del árbol antes y después de cada acción, y la huella de contexto
   (commit, ruta y hash de cada instructivo, versiones del plugin y de Claude Code, modelo, digest
   de la imagen). El hash es del índice (`git write-tree`), no del árbol de trabajo, para que
   `core.autocrlf` no cambie el resultado entre Windows y Linux.
2. **Cuatro clases de determinismo**, una por acción:

   | Clase | Ejemplos | En la re-ejecución |
   |---|---|---|
   | `pura` | `Edit`, `Write`, residuos, hooks como `format-on-edit` | Se aplica y se compara el hash |
   | `local` | `mvn test`, sensores, `git diff`, `Read`, `Grep` | Se ejecuta y se compara la salida normalizada |
   | `externa_lectura` | `WebSearch`, `WebFetch`, MCP de consulta | Se sirve el resultado grabado como fixture |
   | `efecto_externo` | `git push`, `gh pr create`, MCP que escribe | Nunca se ejecuta; su veredicto es `no_verificable` |

3. **Acta cruda y acta curada.** La cruda guarda todo, incluidos los fallos, y no se edita ni se
   borra. La curada se deriva de ella con una función determinista y contiene solo las acciones
   exitosas, del orquestador y de los subagentes. Cuatro reglas la hacen confiable:
   - Un fallo que cambió el árbol entra como acción derivada con su diff, o detiene la curación.
   - Solo entran los subagentes cuyo trabajo llegó al árbol final.
   - Las lecturas exitosas quedan como puntos de control.
   - Antes de emitirla, se aplican en memoria sus acciones puras y se comprueba que lleguen al
     árbol final de la sesión.

   Los rojos esperados, como el de TDD o los sembrados por `REVIEW.md` §3, van al anexo
   `verificaciones_negativas`: la curada los conserva y el motor no los ejecuta.
4. **Sandbox.** El motor (`scripts/reejecutar-acta.mjs`) corre la curada en un contenedor:
   - Imagen fijada por digest.
   - Repo copiado con `git bundle`, sin volúmenes del host con escritura.
   - `--network none`, usuario no root y límites de memoria, CPU, procesos y tiempo.

   Reporta un veredicto por acción (`igual`, `equivalente`, `diverge`, `no_verificable`) y la
   primera divergencia. Las acciones que levantan infraestructura (Testcontainers, `make up`) solo
   corren con `--con-infra`; no se monta el socket de Docker del host.
5. **Trazabilidad en git.** Un trailer `Registro-IA:` apunta a las actas de la tarea. Un sensor del
   CI re-ejecuta la curada y falla si diverge.
6. **El visor del acta** es un HTML autocontenido que reproduce el texto grabado e incrusta las
   capturas de Playwright. Nunca dice «ejecutado» cuando solo reprodujo un fixture: sin reporte del
   motor, el estado visible es «reproducción del registro, sin verificar».
7. **El acta se exporta sin pérdida** a W3C PROV-O y a OCEL 2.0, siguiendo la correspondencia del
   modelo conceptual.

## Consecuencias

- **A favor**: el diff de una PR asistida pasa a ser verificable: se sabe qué acción, de qué actor,
  produjo cada línea, y el CI comprueba que el acta llega a ese árbol. Los fallos dejan de
  desaparecer, porque la cruda los conserva aunque la curada los excluya. El contenedor elimina la
  pregunta de qué comando es destructivo.
- **En contra**: la re-ejecución **reproduce, no replica**. Prueba la integridad del registro y la
  reproducibilidad de lo local. No prueba que el instructivo alcance para que otra persona u otro
  agente llegue a un resultado equivalente (PC-17). Esa medición queda fuera de este arnés y, si se
  hace, va como experimento en `EXPERIMENTS.md`.
- **En contra**: el compilador depende del formato del transcript de Claude Code, que puede
  cambiar entre versiones. Por eso la huella guarda la versión de Claude Code, y un cambio de
  formato rompe el compilador de forma visible, no silenciosa.
- **En contra**: las actas pueden contener secretos leídos durante la sesión, y pesar varios MB.
  Pasan por gitleaks y por una redacción de rutas antes de escribirse. Si el tamaño molesta en el
  repo, el acta completa pasa a ser un artefacto del CI y en git queda solo su hash.
- **En contra**: los comandos grabados en Windows PowerShell 5.1 que no existen en `pwsh` de Linux
  se marcan como no re-ejecutables en vez de fallar.
- **Qué haría reconsiderar**: que el proveedor del modelo ofrezca ejecuciones deterministas
  verificables, lo que haría viable la opción 1 para replicar. También, que el costo de mantener el
  compilador frente a los cambios del transcript supere el valor de auditar las PR asistidas.
