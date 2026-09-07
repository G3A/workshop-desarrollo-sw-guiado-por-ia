# F1 — Preparar la máquina

Instalación real del agente de código con IA y sus herramientas: GitHub CLI (para todo el ciclo
issue → PR → CI) y el plugin `sdlc-ia` de `instrumentacion-java-ia`.

## Evidencia real — instalación de GitHub CLI

`gh` no estaba instalado en la máquina. Se instaló con winget:

```
> winget install --id GitHub.cli -e --accept-package-agreements --accept-source-agreements
Encontrado GitHub CLI [GitHub.cli] Versión 2.98.0
Descargando https://github.com/cli/cli/releases/download/v2.98.0/gh_2.98.0_windows_amd64.msi
El hash del instalador se verificó correctamente
Instalado correctamente
```

## Evidencia real — autenticación (`gh auth login --web`, dispositivo, completada por la persona
usuaria en su navegador)

```
$ gh auth status
github.com
  ✓ Logged in to github.com account G3A (keyring)
  - Active account: true
  - Git operations protocol: https
  - Token: gho_************************************
  - Token scopes: 'gist', 'read:org', 'repo'
```

## Evidencia real — instalación del plugin `sdlc-ia`

```
$ claude plugin marketplace add "D:\GitHub_public\workshop-desarrollo-sw-guiado-por-ia\instrumentacion-java-ia"
Adding marketplace…✔ Successfully added marketplace: sdlc-ia (declared in user settings)

$ claude plugin install sdlc-ia
Installing plugin "sdlc-ia"...✔ Successfully installed plugin: sdlc-ia@sdlc-ia (scope: user)

$ claude plugin list
  ❯ sdlc-ia@sdlc-ia
    Version: 0.1.0
    Scope: user
    Status: ✔ enabled
```

## Chequeo de salud (nodo `G1` del BPMN: `gh auth status` OK)

`gh auth status` devuelve ✓ — el gateway "¿Chequeo de salud OK?" queda satisfecho. Las 4 skills
quedan disponibles como `/sdlc-ia:agent-context-java`, `/sdlc-ia:instrument-project-java`,
`/sdlc-ia:instrument-agent-java` y `/sdlc-ia:github-plan-build`.

Conclusión de la etapa: **F1 completado con evidencia 100% real**, sin pasos simulados.
