# legacy-test-harness

## Qué es

Acondiciona un repositorio legacy — arquitectura desconocida, pruebas escasas o ausentes — para
hacer crecer **pruebas reales sobre código real que ya está en producción**, una capa y un tramo
por vez. Mapea las costuras primero, al estilo Feathers, antes de tocar nada: una costura que solo
se puede cortar editando código de producción se propone y se archiva como issue aparte, nunca se
aplica inline.

Dos reglas se sostienen en toda la skill: el sujeto de cada prueba generada es una clase o función
que ya está en producción (un esqueleto autocontenido que nunca toca código real no cuenta como
capa generada), y cero cambios de producción sin aprobación explícita.

## Cómo se invoca

```
/sdlc-ia:legacy-test-harness [path] [capa,...]
```

Ambos argumentos son opcionales — sin `path`, opera sobre el repo completo (pero igual acota el
tramo en la Fase 3); sin capas, pregunta cuáles generar en esta corrida.

## Las cinco capas

Colaboración/unidad, contrato, aceptación, rendimiento y seguridad — el mismo vocabulario de capas
que usan `pruebas-de-unidad`, `pruebas-de-contrato`, `pruebas-de-aceptacion`,
`pruebas-de-rendimiento-k6` y `pruebas-de-seguridad` en este mismo taller, aplicado acá a un
repositorio que todavía no las tiene.

## Fases principales

1. **Huella** — detecta el/los stack(s), la herramienta de build, y cualquier directorio/framework
   de pruebas ya en uso. Si ya existe una estrategia de pruebas real, esta es una corrida
   **incremental** — la extiende, no la reemplaza.
2. **Mapear las costuras** — recorre el/los módulo(s) objetivo con la lente de Feathers (puntos de
   inyección por constructor, llamadas estáticas, singletons, `new` dentro del método bajo
   prueba). Clasifica cada costura: **cortable desde la prueba** (reflexión, una subclase de
   prueba, un wrapper que la prueba controla — se usa) o **requiere una edición de producción**
   (no se edita — se registra para la Fase 6 con el motivo en una línea).
3. **Acotar el tramo** — presenta el mapa de costuras y pregunta qué capa(s) generar en esta
   corrida y sobre qué subconjunto de módulos/clases. Nunca asume "las cinco capas, todo el repo"
   por defecto.
4. **Generar, por capa** — para cada capa elegida, sigue su sección de referencia: cómo es una
   prueba real (no un scaffold) para esa capa, el framework por defecto según el stack, y el gate
   propio de la capa. No toca código de producción — donde la Fase 2 encontró una costura que lo
   requiere, genera la prueba rodeando la costura tal como se propuso (por ejemplo, vía
   reflexión).
5. **Gate de realidad** — antes de reportar una capa como generada, confirma que cada prueba
   nueva apunta a una clase/función bajo la raíz de código fuente de producción del stack (no un
   doble solo-de-test), falla cuando se revierte el comportamiento de producción que apunta, y
   corre en verde en el resto de los casos. Una prueba que pasa incondicionalmente, o que nunca
   importa código de producción, es scaffolding — no cuenta.
6. **Reportar y cerrar** — reporta, por capa generada: archivos agregados, qué prueban, y el
   resultado del gate de realidad. Reporta, por separado, cada costura que encontró que requiere
   una edición de producción — como candidato a título de issue más el motivo en una línea. No
   abre el issue por su cuenta salvo que se lo pidan; esa decisión es del usuario (o de
   `github-plan-build`, si esta corrida alimenta un ciclo de esa skill).

## Cuándo aplica (y cuándo no)

Es la skill del nodo `bt` del proceso operacional — el camino que se toma cuando "crear estrategia
de pruebas" (nodo `GT`) resuelve "no". Un repo que ya tiene una estrategia de pruebas sólida (como
`base-conocimiento`, con ArchUnit, Testcontainers, Mockito, AssertJ, jqwik y WireMock ya
instalados) resuelve `GT` en "sí" y nunca llega a esta skill — no hace falta correrla ahí. Su caso
de uso real es un repositorio legacy sin ese arnés todavía.

## Qué archivos toca o crea

Archivos de prueba nuevos por cada capa generada, bajo la convención de test del stack detectado.
**Nunca edita código de producción** — donde una costura lo requeriría, la registra como candidato
a issue en vez de tocarla. **Nunca hace `commit`** — el lote queda para que el usuario lo revise,
igual que el resto de las skills del paquete.

## Decisiones de diseño a tener en cuenta

- **Mapear antes de tocar.** Un mapa de costuras armado sin correr nada es barato de tener mal; una
  edición de producción no lo es.
- **Un tramo por vez.** Los repos legacy son grandes por definición — generar todas las capas de
  todo el repo en una sola pasada produce un diff que nadie puede revisar.
- **Una capa generada es una capa probada.** Cada prueba que esta skill escribe tiene que correr, y
  tiene que fallar si se revierte el código de producción que apunta — el gate de realidad de la
  Fase 5 no es opcional.
