#!/usr/bin/env bash
# Actualiza el plugin sdlc-ia en este equipo a lo que hay en esta carpeta (macOS / Linux / Git Bash).
# Mismo comportamiento que update.ps1:
#   ./update.sh             git pull --ff-only y reinstala
#   ./update.sh --no-pull   reinstala lo que ya esta en disco
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
[ "${1:-}" = "--no-pull" ] || git -C "$ROOT" pull --ff-only

VERSION=$(sed -n 's/.*"version": *"\([^"]*\)".*/\1/p' "$ROOT/sdlc-ia/.claude-plugin/plugin.json" | head -1)
echo "sdlc-ia $VERSION en $ROOT"

# Marketplace apuntando a esta carpeta
REGISTRO="$HOME/.claude/plugins/known_marketplaces.json"
RUTA=""
if [ -f "$REGISTRO" ]; then
  RUTA=$(python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print(d.get("sdlc-ia",{}).get("source",{}).get("path",""))' "$REGISTRO" 2>/dev/null || true)
fi
if [ -n "$RUTA" ] && [ "${RUTA%/}" != "${ROOT%/}" ]; then
  echo "el marketplace sdlc-ia apuntaba a $RUTA; se vuelve a registrar sobre esta carpeta"
  claude plugin marketplace remove sdlc-ia
  RUTA=""
fi
[ -n "$RUTA" ] || claude plugin marketplace add "$ROOT"

# Refrescar e instalar; si ya estaba instalado, reinstalar ('install' nunca refresca un plugin
# instalado, ni aunque plugin.json haya subido de version)
claude plugin marketplace update sdlc-ia
SALIDA=$(claude plugin install sdlc-ia@sdlc-ia -y)
echo "$SALIDA"
if echo "$SALIDA" | grep -q 'already installed'; then
  echo "ya estaba instalado: la CLI no refresca un plugin instalado, se reinstala"
  claude plugin uninstall sdlc-ia@sdlc-ia
  claude plugin install sdlc-ia@sdlc-ia -y
fi

# Fuente == cache
CACHE="$HOME/.claude/plugins/cache/sdlc-ia/sdlc-ia/$VERSION"
[ -d "$CACHE" ] || { echo "no aparecio la cache en $CACHE" >&2; exit 1; }
if ! diff -rq "$ROOT/sdlc-ia" "$CACHE" >/dev/null; then
  diff -rq "$ROOT/sdlc-ia" "$CACHE" || true
  echo "la fuente y la cache difieren" >&2; exit 1
fi
echo "OK: sdlc-ia $VERSION instalado; la cache es identica a la fuente. Aplica a sesiones nuevas de Claude Code."
