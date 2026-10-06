# ADR-0007: El acta de la IA viaja con el plugin

## Estado

Propuesto en la PR del #247.

## Contexto

Hasta el #247 el acta (ADR-0005 y ADR-0006) vivía en `scripts/acta/` y la registraban hooks de
`.claude/settings.json` de este monorepo. El plugin `sdlc-ia`, que es lo que otros repos instalan,
solo llevaba sus skills. En un repo que usa el plugin no se escribía ninguna acta: el registro de lo
que hizo la IA quedaba en el repo que desarrolla la herramienta y no en el repo donde la IA trabajó,
que es el que alguien tiene que auditar.

Un plugin de Claude Code puede traer hooks en `hooks/hooks.json`, con scripts empaquetados que se
llaman con `${CLAUDE_PLUGIN_ROOT}`, y Claude Code les exporta `CLAUDE_PROJECT_DIR`. El código del
acta no importa nada de fuera de su carpeta y solo usa módulos de Node.

Opciones evaluadas:

1. **Copiar `scripts/acta/` al plugin al liberar**, con un sensor que exija que las dos copias sean
   iguales. Dos copias que un sensor mantiene iguales siguen siendo dos lugares donde editar, y el
   sensor solo avisa después.
2. **Que `instrument-agent-java` copie el acta en cada repo de destino.** Cada repo carga con el
   código y su versión queda congelada el día que se instrumentó; un arreglo como el del #244 no
   llegaría a ningún repo ya instrumentado.
3. **Publicar el acta como paquete npm.** Exige un registro y un paso de instalación más, cuando el
   plugin ya es el canal de distribución.
4. **Mover el acta al plugin**: una sola copia, que se actualiza con el plugin.

## Decisión

**Opción 4.** El acta vive en `instrumentacion-java-ia/sdlc-ia/acta/`.

1. **Captura.** `sdlc-ia/hooks/hooks.json` registra la captura en los seis eventos. Este monorepo
   conserva sus hooks en `.claude/settings.json`, que apuntan a la copia del árbol de trabajo para
   probar el acta que se está desarrollando, y el hook del plugin cede cuando el proyecto ya declara
   su propia captura: un evento se registra una sola vez.
2. **Huella.** En este monorepo la huella lee los instructivos de git, como hasta ahora. En un repo
   de destino el plugin no está en git: la huella los lee de `${CLAUDE_PLUGIN_ROOT}` y los escribe
   como blobs con `git hash-object -w`. La conformidad los encuentra por hash igual que aquí, y el
   pack de objetos del acta los lleva junto a ella.
3. **Lo que se escribe en el repo de destino** es lo mismo que aquí: `.ia/captura/` y
   `.ia/registros/<tarea>/`. Versionarla, citarla en el commit y verificarla en el CI siguen el
   ADR-0006.

## Consecuencias

- **A favor**: el acta queda en el repo donde trabajó la IA, y un arreglo del acta llega a todos los
  repos con la siguiente versión del plugin.
- **En contra**: el código del acta pasa a ser parte del plugin, que se documenta en inglés, y queda
  en español. El sensor de ancho la mantiene en su alcance de forma explícita.
- **En contra**: el repo de destino necesita Node >= 18, git y gitleaks. Sin gitleaks el acta no se
  escribe (ADR-0005).
- **Qué haría reconsiderar**: que el plugin se distribuya a entornos que no instalan hooks de
  plugins, como claude.ai, o que el acta crezca hasta pedir dependencias de npm.
