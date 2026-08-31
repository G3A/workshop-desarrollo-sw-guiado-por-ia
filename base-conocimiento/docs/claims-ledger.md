# Registro de afirmaciones

Generado por la skill `agent-context-java`. Registra las afirmaciones factuales clave de la
documentación, su fuente en el repositorio y si fueron confirmadas por una persona. Vuelve a
ejecutar la skill para actualizarlo.

| Afirmación | Fuente | Confianza | Estado |
|---|---|---|---|
| El proyecto apunta a JDK 25 sobre Spring Boot 4.1.0. | `pom.xml` (`<java.version>`) | alta | confirmada |
| La persistencia es `JdbcClient` a mano sobre Postgres, no JPA/Hibernate. | `pom.xml`, `recuperacion/package-info.java` | alta | confirmada |
| Las migraciones de Flyway corren al arrancar la app (autoconfig de `spring-boot-flyway`). | `pom.xml:108-111` | alta | confirmada |
| ArchUnit es el único quality gate presente; no hay Checkstyle, Spotless, SonarQube ni CI. | `grep` directo sobre `pom.xml`; `.github/` inexistente | alta | confirmada |
| Las reglas `losAdaptadoresNoConocenElNucleo` y `elNucleoNoConoceALosAdaptadores` tienen `allowEmptyShould(true)` con un comentario desactualizado — `web` y `teams` ya existen. | `ArquitecturaTest.java:39,49,68` | alta | confirmada |
| El paquete `seguridad` no está cubierto por ninguna de las 4 reglas de `ArquitecturaTest`. | `ArquitecturaTest.java` (grep de paquetes referenciados) | alta | confirmada |
| El despliegue es Docker Compose en una VM/máquina propia; el camino a producción es manual, sin CI. | usuario | alta | confirmada |
| `spring-boot-starter-actuator` está en el classpath y expone `health`/`info`/`metrics`, sin acotar por perfil; nada los consume todavía. | `application.yml:101-108` | alta | corregida → issue #5, TODO resuelto en `infrastructure.md` |
| No se detectó field injection en la pasada de discovery. | `grep -rn "@Autowired\|@Inject" src/main` → 0 resultados; los 14 usos de `@Value` en `src/main` son todos parámetro de constructor | alta | confirmada → issue #7 |
| El JDK del `Dockerfile` coincide con el declarado en `pom.xml`: ambos 25. | `pom.xml` (`<java.version>25`), `Dockerfile` (`eclipse-temurin:25` en deps, build y runtime) | alta | confirmada |
| El paquete `seguridad` no tiene `@ApplicationModule` ni Javadoc en su `package-info.java`. | `seguridad/package-info.java` (vacío) | alta | confirmada |
