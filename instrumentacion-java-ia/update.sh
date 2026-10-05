#!/usr/bin/env bash
# Actualiza el plugin sdlc-ia en este equipo a lo que hay en esta carpeta (macOS / Linux / Git Bash).
# Mismo comportamiento que update.ps1, que explica por que el plugin no se copia a una cache:
#   ./update.sh             git pull --ff-only y verifica la instalacion
#   ./update.sh --no-pull   verifica la instalacion de lo que ya esta en disco
# Sin python ni jq: el JSON de la CLI y del registro se lee con sed. Bash 3.2 (macOS) incluido.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
[ "${1:-}" = "--no-pull" ] || git -C "$ROOT" pull --ff-only

# La CLI escribe las rutas en la forma del sistema: en Git Bash, D:\... y no /d/...
if command -v cygpath >/dev/null 2>&1; then ROOT_CLI=$(cygpath -w "$ROOT"); SEP='\'; else ROOT_CLI=$ROOT; SEP=/; fi
normalizar() { printf '%s' "$1" | tr '/' '\\' | sed 's/\\*$//' | tr '[:upper:]' '[:lower:]'; }
# Una cadena JSON trae las barras invertidas escapadas: D:\\GitHub -> D:\GitHub
desescapar() { sed 's/\\\\/\\/g'; }
falla() { echo "$1" >&2; exit 1; }

VERSION=$(sed -n 's/.*"version": *"\([^"]*\)".*/\1/p' "$ROOT/sdlc-ia/.claude-plugin/plugin.json" | head -1)
echo "sdlc-ia $VERSION en $ROOT_CLI"

# Marketplace apuntando a esta carpeta
REGISTRO="$HOME/.claude/plugins/known_marketplaces.json"
RUTA=""
if [ -f "$REGISTRO" ]; then
  RUTA=$(tr -d '\n' < "$REGISTRO" \
    | sed -n 's/.*"sdlc-ia": *{ *"source": *{[^}]*"path": *"\([^"]*\)".*/\1/p' | desescapar)
fi
if [ -n "$RUTA" ] && [ "$(normalizar "$RUTA")" != "$(normalizar "$ROOT_CLI")" ]; then
  echo "el marketplace sdlc-ia apuntaba a $RUTA; se vuelve a registrar sobre esta carpeta"
  claude plugin marketplace remove sdlc-ia
  RUTA=""
fi
[ -n "$RUTA" ] || claude plugin marketplace add "$ROOT_CLI"

# Instalar si falta y re-registrar la version
claude plugin install sdlc-ia@sdlc-ia -y
if ! UPDATE=$(claude plugin update sdlc-ia@sdlc-ia -y --json); then
  echo "$UPDATE" >&2; falla "plugin update fallo"
fi
REGISTRADA=$(printf '%s' "$UPDATE" | sed -n 's/.*"newVersion": *"\([^"]*\)".*/\1/p')
if [ "$REGISTRADA" != "$VERSION" ]; then
  echo "$UPDATE" >&2; falla "la CLI registra sdlc-ia $REGISTRADA y plugin.json dice $VERSION"
fi

# Lo que corre: la CLI tiene que decir que carga en su lugar desde esta carpeta. Se pide con el
# plugin ya instalado porque solo entonces el mensaje lo dice; una instalacion fresca no.
if ! INSTALL=$(claude plugin install sdlc-ia@sdlc-ia -y --json); then
  echo "$INSTALL" >&2; falla "plugin install fallo"
fi
ESPERADA="$ROOT_CLI${SEP}sdlc-ia"
CARGA=$(printf '%s' "$INSTALL" | sed -n 's/.*loads in place from \(.*\), so edits.*/\1/p' | desescapar)
if [ -z "$CARGA" ]; then
  echo "$INSTALL" >&2
  falla "la CLI ($(claude --version)) no confirma que sdlc-ia carga en su lugar.
Verificado con Claude Code 2.1.282; si la tuya es anterior, actualizala. Si es posterior y cambio la
redaccion, confirmalo a mano: claude -p --debug-file <archivo> 'ok' y busca en el archivo
'Attempting to load skills from plugin sdlc-ia' seguido de $ESPERADA${SEP}skills"
fi
if [ "$(normalizar "$CARGA")" != "$(normalizar "$ESPERADA")" ]; then
  falla "sdlc-ia carga desde $CARGA, no desde $ESPERADA"
fi
echo "OK: sdlc-ia $VERSION carga en su lugar desde $CARGA. Aplica en la proxima sesion de Claude Code o con /reload-plugins."
