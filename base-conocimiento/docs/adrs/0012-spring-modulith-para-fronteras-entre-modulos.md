# ADR-0012: Spring Modulith para fronteras entre módulos

## Estado

Aceptado

## Contexto

El proyecto es un solo módulo Maven (sin `<modules>` hijos), pero el diseño depende de que ciertos
paquetes se comporten como "núcleo" (`orquestacion`, `recuperacion`, `ingesta`, `modelos`, `llm`) y
otros como "adaptadores reemplazables" (`web`, `teams`) que **no pueden** conocer el retrieval —
es la promesa central del proyecto ("los adaptadores son piel", ver
[architecture.md](../architecture.md)). Sin un mecanismo que lo verifique, esa frontera es solo una
intención documentada: se erosiona en la práctica en cuanto alguien importa una clase de
`recuperacion` desde `web` para ahorrarse una vuelta.

Opciones consideradas:
- **Convención sin verificación** (solo documentar la regla) — descartada: no sobrevive a la
  presión de un deadline, y nadie se entera de la violación hasta que ya está en producción.
- **Multi-módulo Maven real** (un `pom.xml` por paquete) — descartada por ahora: el proyecto es
  joven y de un tamaño donde el overhead de módulos Maven separados (más `pom.xml`, más ciclos de
  build) no se justifica todavía frente al beneficio.
- **Spring Modulith** — un test (`ApplicationModules.of(...).verify()`) que analiza el bytecode y
  falla el build si un paquete cruza una frontera declarada con `@ApplicationModule`, sin exigir
  separación física en módulos Maven distintos.

## Decisión

Adoptar Spring Modulith: cada paquete de primer nivel bajo `co.g3a.baseconocimiento` lleva
`@ApplicationModule` en su `package-info.java`, y `ArquitecturaTest` corre
`ApplicationModules.of(BaseConocimientoApplication.class).verify()` más 3 reglas ArchUnit
explícitas (`noClasses().that()...`) en el mismo ciclo que el resto de las pruebas (`make test`).

## Consecuencias

- **A favor**: la promesa "los adaptadores son piel" pasa de intención a gate del build — un PR
  que la viole no pasa CI (una vez que exista CI, ver
  [F2 de la validación](../../validacion-workshop/)). Un solo módulo Maven sigue siendo simple de
  compilar y desplegar (un solo jar por capas).
- **En contra**: la frontera solo se verifica en tiempo de test, no en tiempo de compilación — un
  desarrollador puede escribir el código que la viola y solo se entera al correr `make test`. Las
  reglas necesitan mantenimiento activo: dos de ellas quedaron con `allowEmptyShould(true)` desde
  antes de que `web`/`teams` existieran, y ese flag ya no refleja el estado real del código (ver
  gotcha en [docs/java.md](../java.md#gotchas--hotspots)) — es un ejemplo concreto de que un gate
  de arquitectura necesita revisión cuando el código que protege cambia de forma, no solo cuando se
  crea.
