# REVIEW.md — qué mirar en un diff de workshop-desarrollo-sw-guiado-por-ia

Estos criterios se aplican a **todo diff**, lo haya escrito una persona o un modelo. Pesan más
cuando lo escribió un modelo, porque el código sale plausible incluso cuando está mal.

Vive en la raíz del monorepo porque el servicio de Code Review solo lee `REVIEW.md` ahí; aplica a
las cuatro piezas. Los ítems que solo valen para `base-conocimiento/` lo dicen.

Las reglas que el agente debe respetar **al generar** están en los `AGENTS.md` (el de la raíz y el
de cada pieza) y no se repiten aquí. Este archivo es solo **qué mirar en un diff que ya existe**.

Lo que una máquina puede comprobar **no está aquí**: eso son sensores (linters, pruebas, hooks,
CI). Si un ítem de esta lista se puede automatizar, súbelo a un sensor y bórralo de aquí. Esta
lista es solo para lo que exige criterio.

## 1 · Alucinaciones de API

- [ ] **Cada método, firma y constante que aparece existe de verdad** en la versión que fija el
      proyecto (`base-conocimiento/pom.xml` para la app) — no en una versión posterior, ni en otra
      librería que se llama parecido. En una skill, lo mismo con los flags de `git`, `gh` y
      `claude`.
- [ ] **Las opciones de configuración existen y hacen lo que el diff supone.** Una propiedad
      inventada no falla: se ignora en silencio, y el comportamiento por defecto se lleva la culpa
      meses después.
- [ ] **Ninguna dependencia nueva entró sin justificación.** Si el diff agrega una, la PR dice por
      qué no bastaba lo que ya había.

## 2 · Fidelidad al proyecto

- [ ] **Resuelve como este repositorio resuelve**, no como resuelve internet. Mismo patrón de
      capas, mismos nombres, misma forma de manejar errores que el código de al lado.
- [ ] **No introduce una segunda manera de hacer algo que ya se hacía de una.** Dos formas de leer
      configuración, dos de construir respuestas HTTP, dos de mapear entidades: cada una duplica
      el costo de todo cambio futuro.
- [ ] **Respeta los límites de módulo** que protege `ArquitecturaTest` en `base-conocimiento/` — y
      si los cambia, lo hace explícito en la PR en vez de aflojar la regla.

## 3 · Tests de la spec

- [ ] **Las pruebas verifican el requisito, no lo que el código hace.** Una prueba escrita mirando
      la implementación pasa siempre y no protege de nada.
- [ ] **Cada criterio de aceptación del issue tiene una prueba que lo cubre.** Si alguno no la
      tiene, la PR dice cuál y por qué.
- [ ] **Hay al menos un caso borde y un caso de error**, no solo el camino feliz.
- [ ] **Las pruebas fallan si se revierte el cambio.** Es la comprobación que separa una prueba de
      un andamio; si nadie la hizo, hazla tú en local antes de aprobar.

## 4 · Deuda técnica

- [ ] **Qué quedó peor para que esto quedara listo.** Siempre hay algo; si la PR no lo nombra,
      pregúntalo.
- [ ] **Ningún `TODO`, supresión ni excepción nueva sin un motivo escrito al lado.** Una excepción
      sin comentario es deuda invisible — incluidas las entradas nuevas de
      `checkstyle-suppressions.xml` y `.gitleaksignore`.
- [ ] *(Añadido: el repo usa Flyway.)* **Una migración nueva de `base-conocimiento/` funciona sobre
      una base con datos**, no solo sobre la vacía de las pruebas: una columna `NOT NULL` nueva trae
      valor por defecto o relleno, y un índice nuevo sobre `chunks` considera su tamaño real.

## 5 · Trade-offs

- [ ] **Qué se decidió sin decirlo.** Una decisión de diseño que no aparece en la PR ni en un ADR
      es una decisión que nadie podrá revisar cuando duela.
- [ ] **La alternativa descartada está nombrada.** No hace falta un ensayo: una frase de por qué
      no se hizo de la otra forma.

## 6 · Corre el cambio tú mismo

- [ ] **Levántalo y úsalo.** En `base-conocimiento/`: `make up`, y `make ingest` si el cambio toca
      ingesta o retrieval. En una skill del plugin: `update.ps1` o `update.sh` y una sesión nueva
      de Claude Code, porque la sesión abierta sigue con la copia vieja. Es el ítem que más se
      salta y el que más atrapa: CI en verde solo dice que las pruebas que existen pasan.

## Qué no reportar

*(Añadido para el servicio de Code Review.)*

- Lo que el CI ya bloquea: formato (Spotless), Checkstyle, warnings del compilador (`-Werror`),
  fronteras de `ArquitecturaTest`, secretos (gitleaks) y enlaces rotos en la documentación de `base-conocimiento/`.
- Archivos de terceros en `playbook-sdlc-ia/vendor/`.

---

## Cómo evoluciona esta lista

Si en una revisión dices algo a mano por **tercera vez**, no es un problema de disciplina de quien
escribe: es un criterio que falta aquí. Agrégalo.

Y al revés: si un ítem de esta lista se puede comprobar con una máquina, deja de ser texto y se
vuelve sensor — una prueba, una regla de linter o un hook. Un criterio automatizable que sigue
siendo texto es trabajo manual que alguien repite en cada PR.
