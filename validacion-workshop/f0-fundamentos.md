# F0 — Fundamentos

Validación de que este monorepo ya cumple los fundamentos de colaboración antes de instrumentar
un agente de IA sobre él: control de versiones con historia legible y mensajes que explican el
porqué de cada cambio (no solo el qué).

## Evidencia real — `git log --oneline -10` (ejecutado en este repo, 2026-08-25)

```
dde0f47 Reestructura el repo en monorepo: base-conocimiento, proceso-operacional-con-ia e instrumentacion-java-ia
11c6296 Ignora artefactos de node_modules, pesos de modelos de IA y binarios
0dea23f Agrega sintesis estructurada a ocho candidatos descartados y compara contra texto libre (sesiones 25-26)
f62c73f Agrega el visor modal de citas del vault y lo endurece contra los hallazgos del code review
ed6ba3f Deriva el project_id de documentos locales de la subcarpeta bajo vault/documentos
6955562 Limita consultas concurrentes y persiste el stream en curso para poder reconectar tras un F5
2f9ec3b Agrega boton de copiar a los fragmentos y a la pregunta del usuario
b468ed0 Agrega documentos activos por conversacion, historial y borrado de archivos; corrige FTS, reformulador y redirect de /index.html
a35e453 Documenta la sesion 18: qwen3.5:4b descartado por thinking obligatorio (hallazgos 73-77)
e2e3305 Agrega comandos por perfil de modelo y migra Ministral de llama-server a Ollama
```

## Lectura de la evidencia

- Cada commit describe una decisión o un resultado ("agrega", "limita y persiste", "deriva"), no
  una lista de archivos tocados — es exactamente lo que un agente de IA necesita para entender el
  *porqué* de un cambio pasado sin tener que releer el diff completo.
- El commit `dde0f47` (el reorg a monorepo, hecho como paso previo de esta misma validación) sigue
  la misma disciplina: un commit atómico, mensaje explicando el motivo, sin mezclar con trabajo no
  relacionado.
- Conclusión de la etapa: **F0 ya está satisfecho** en este repo — no hace falta ninguna
  intervención antes de pasar a F1 (preparar la máquina).
