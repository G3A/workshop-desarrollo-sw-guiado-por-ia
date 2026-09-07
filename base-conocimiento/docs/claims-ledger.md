# Registro de afirmaciones

Generado originalmente por la skill `agent-context-java`. Registra las afirmaciones factuales clave
de la documentación, su fuente en el repositorio y si fueron confirmadas por una persona. Vuelve a
ejecutar la skill para regenerarlo.

**Última verificación a mano: 2026-08-31**, contra el código, después de la sincronización con
`base-conocimiento-sandbox`. Esa sincronización invalidó cinco afirmaciones de este registro que
seguían dadas por buenas; se marcan como `obsoleta` o `corregida` en vez de borrarlas, porque saber
qué dejó de ser cierto vale tanto como saber qué lo es.

## Vigentes

| Afirmación | Fuente | Confianza | Estado |
|---|---|---|---|
| El proyecto apunta a JDK 25 sobre Spring Boot 4.1.0. | `pom.xml` (`<java.version>`) | alta | confirmada |
| El JDK del `Dockerfile` coincide con el declarado en `pom.xml`: ambos 25. | `pom.xml` (`<java.version>25`), `Dockerfile` (`eclipse-temurin:25` en deps, build y runtime) | alta | confirmada |
| La persistencia es `JdbcClient` a mano sobre Postgres, no JPA/Hibernate. | `pom.xml` (sin `data-jpa` ni `hibernate-core`), `recuperacion/package-info.java` | alta | confirmada |
| Las migraciones de Flyway corren al arrancar la app (autoconfig de `spring-boot-flyway`). | `pom.xml` (`spring-boot-flyway`) | alta | confirmada |
| `spring-boot-starter-actuator` expone `health`/`info`/`metrics`, sin acotar por perfil. | `application.yml` (`include: health,info,metrics`) | alta | confirmada → issue #5, TODO resuelto en `infrastructure.md` |
| No hay field injection en `src/main`. | `grep -rn "@Autowired\|@Inject" src/main` → 0 resultados; los usos de `@Value` son todos parámetro de constructor | alta | confirmada → issue #7 |
| Formato, estilo, arquitectura y CI bloquean el build; SpotBugs/PMD y SonarQube siguen ausentes. | `pom.xml` (Spotless, Checkstyle `failOnViolation=true`), `.github/workflows/ci.yml` en la raíz del monorepo | alta | confirmada (2026-08-31) |
| `ArquitecturaTest` tiene 5 pruebas: 4 de ArchUnit (5 `noClasses()`) más `ApplicationModules.verify()`. | `ArquitecturaTest.java` | alta | confirmada (2026-08-31) |
| `seguridad` está cubierto en las dos direcciones y tiene además su frontera lateral con `web`/`teams`. | `ArquitecturaTest.seguridadNoSeMezclaConLosOtrosAdaptadores` | alta | confirmada (2026-08-31) |
| `jqwik` está fijado en 1.9.3 a propósito: 1.10.x imprime una inyección de prompt contra agentes en cada corrida. | `pom.xml` (comentario de `<jqwik.version>`), <https://lwn.net/Articles/1075317/> | alta | confirmada (2026-08-31) |
| El `Makefile` fija su propio `SHELL` en Windows (el `sh.exe` de Git for Windows) y le antepone su directorio al `PATH` cuando el `PATH` viene en formato Windows. | `Makefile` (bloque `ifeq ($(OS),Windows_NT)`) | alta | confirmada (2026-08-31) — sin eso, `make` desde PowerShell cae a `cmd.exe` y casi ninguna receta funciona |
| El reparto de la GPU se deriva de `nvidia-smi` (VRAM, Compute Capability, driver), no de constantes. | `Makefile` (`GPU_PLAN`), `make gpu-check` | alta | confirmada (2026-08-31) |
| `docling-serve` no libera la VRAM entre conversiones y `GET /v1/clear/converters` no la recupera; solo reiniciar el proceso. | Medido: 2053 MiB antes y después del endpoint; sesión 27 de `investigacion-vram-y-modelo-llm.md` | alta | confirmada (2026-08-31) |
| Hay 10 `compose.*.yml` de perfil de modelo; 7 tienen target `up-`/`down-`/`pull-`. | `ls compose.*.yml`, `grep "^up-" Makefile` | alta | confirmada (2026-08-31) |
| El despliegue es Docker Compose en una VM/máquina propia y el paso a producción sigue siendo manual. | usuario | alta | matizada (2026-08-31): hay CI (build, test, lint, secretos en cada push/PR), pero **no** hay CD |

## Invalidadas por cambios posteriores

Se conservan porque documentan el estado del repositorio cuando se escribieron, y porque un agente
que las encuentre citadas en otro documento necesita saber que ya no valen.

| Afirmación (ya no vigente) | Qué la invalidó |
|---|---|
| «ArchUnit es el único quality gate presente; no hay Checkstyle, Spotless, SonarQube ni CI.» | La sincronización con `base-conocimiento-sandbox` trajo Spotless sobre todo el código y Checkstyle bloqueando, y el CI ya existía en la raíz del monorepo. SonarQube y SpotBugs/PMD siguen ausentes. |
| «Las reglas `losAdaptadoresNoConocenElNucleo` y `elNucleoNoConoceALosAdaptadores` tienen `allowEmptyShould(true)` con un comentario desactualizado.» | Hoy usan `allowEmptyShould(false)` explícito; solo `compartidoEsHoja` conserva `true`. |
| «El paquete `seguridad` no está cubierto por ninguna de las 4 reglas de `ArquitecturaTest`.» | La fusión de las dos versiones del test lo incorporó a las reglas de adaptadores y núcleo, y le agregó `seguridadNoSeMezclaConLosOtrosAdaptadores`. |
| «El paquete `seguridad` no tiene `@ApplicationModule` ni Javadoc en su `package-info.java`.» | `seguridad/package-info.java` existe, con `@ApplicationModule(displayName = "Seguridad")` y su Javadoc. |
| «El camino a producción es manual, **sin CI**.» | El CI existe y bloquea; lo que no hay es despliegue continuo. Ver la fila matizada arriba. |
