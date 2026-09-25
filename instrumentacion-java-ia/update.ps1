# Actualiza el plugin sdlc-ia en este equipo a lo que hay en esta carpeta.
# Windows PowerShell 5.1 y PowerShell 7. ASCII-only a proposito (5.1 lee los .ps1 como ANSI).
#
#   .\update.ps1            trae la ultima version del repo (git pull --ff-only) y verifica la instalacion
#   .\update.ps1 -NoPull    verifica la instalacion de lo que ya esta en disco, sin tocar git
#
# El plugin NO se copia a una cache: 'source' es una ruta relativa dentro de un marketplace agregado
# desde una carpeta local, y en ese caso la CLI lo carga en su lugar, desde esta carpeta, en cada
# inicio de sesion y con /reload-plugins, diga lo que diga su version.
# https://code.claude.com/docs/en/plugins/loading#in-place-and-copied-plugins
# Comprobado con Claude Code 2.1.282 (issue #209). El installPath que registra la CLI sigue
# apuntando a ~/.claude/plugins/cache/...: ese campo no dice que copia corre.
#
# Que hace, en orden:
#   1. git pull --ff-only sobre este clon (salvo -NoPull).
#   2. Registra el marketplace 'sdlc-ia' apuntando a ESTA carpeta si falta o apunta a otra ruta.
#   3. claude plugin install (instala si falta; si ya esta, no hace nada) y claude plugin update, que
#      solo re-registra la version para que 'claude plugin list' diga la de plugin.json.
#   4. Verifica lo que corre: la CLI tiene que decir que carga en su lugar desde ESTA carpeta, y la
#      version registrada tiene que ser la de plugin.json. Si la CLI no lo dice, falla: no hay camino
#      de respaldo de desinstalar y reinstalar.
# Aplica en la proxima sesion de Claude Code, o en una abierta con /reload-plugins.

param([switch]$NoPull)

$ErrorActionPreference = 'Stop'
$Root = $PSScriptRoot
# La salida de la CLI trae caracteres no ASCII (la ruta puede traerlos); 5.1 la leeria como OEM.
[Console]::OutputEncoding = [Text.Encoding]::UTF8
function Assert-Exit([string]$Que) { if ($LASTEXITCODE -ne 0) { throw "$Que fallo con codigo $LASTEXITCODE" } }
function Normalize-Ruta([string]$Ruta) { $Ruta.Replace('/', '\').TrimEnd('\').ToLowerInvariant() }

if (-not $NoPull) {
  git -C $Root pull --ff-only; Assert-Exit 'git pull'
}

$Manifiesto = Join-Path $Root 'sdlc-ia\.claude-plugin\plugin.json'
$Version = (Get-Content -Raw -Encoding UTF8 $Manifiesto | ConvertFrom-Json).version
"sdlc-ia $Version en $Root"

# 2) Marketplace apuntando a esta carpeta
$Registro = Join-Path $HOME '.claude\plugins\known_marketplaces.json'
$RutaRegistrada = $null
if (Test-Path $Registro) {
  $Conocidos = Get-Content -Raw -Encoding UTF8 $Registro | ConvertFrom-Json
  if ($Conocidos.PSObject.Properties.Name -contains 'sdlc-ia') { $RutaRegistrada = $Conocidos.'sdlc-ia'.source.path }
}
if ($RutaRegistrada -and ((Normalize-Ruta $RutaRegistrada) -ne (Normalize-Ruta $Root))) {
  "el marketplace sdlc-ia apuntaba a $RutaRegistrada; se vuelve a registrar sobre esta carpeta"
  claude plugin marketplace remove sdlc-ia; Assert-Exit 'marketplace remove'
  $RutaRegistrada = $null
}
if (-not $RutaRegistrada) {
  claude plugin marketplace add $Root; Assert-Exit 'marketplace add'
}

# 3) Instalar si falta y re-registrar la version
claude plugin install sdlc-ia@sdlc-ia -y; Assert-Exit 'plugin install'
$Update = (claude plugin update sdlc-ia@sdlc-ia -y --json) -join "`n"
Assert-Exit 'plugin update'
$VersionRegistrada = ($Update | ConvertFrom-Json).newVersion
if ($VersionRegistrada -ne $Version) {
  $Update
  throw "la CLI registra sdlc-ia $VersionRegistrada y plugin.json dice $Version"
}

# 4) Lo que corre: la CLI tiene que decir que carga en su lugar desde esta carpeta. Se pide con el
# plugin ya instalado porque solo entonces el mensaje lo dice; una instalacion fresca no.
$Install = (claude plugin install sdlc-ia@sdlc-ia -y --json) -join "`n"
Assert-Exit 'plugin install'
$Mensaje = ($Install | ConvertFrom-Json).message
$Esperada = Join-Path $Root 'sdlc-ia'
if ($Mensaje -notmatch 'loads in place from (.+?), so edits') {
  $Cli = (claude --version) -join ' '
  throw ("la CLI ($Cli) no confirma que sdlc-ia carga en su lugar. Mensaje: $Mensaje`n" +
    "Verificado con Claude Code 2.1.282; si la tuya es anterior, actualizala. Si es posterior y " +
    "cambio la redaccion, confirmalo a mano: claude -p --debug-file <archivo> 'ok' y busca en el " +
    "archivo 'Attempting to load skills from plugin sdlc-ia' seguido de $Esperada\skills")
}
$Carga = $Matches[1]
if ((Normalize-Ruta $Carga) -ne (Normalize-Ruta $Esperada)) {
  throw "sdlc-ia carga desde $Carga, no desde $Esperada"
}
"OK: sdlc-ia $Version carga en su lugar desde $Carga. Aplica en la proxima sesion de Claude Code o con /reload-plugins."
