# Actualiza el plugin sdlc-ia en este equipo a lo que hay en esta carpeta.
# Windows PowerShell 5.1 y PowerShell 7. ASCII-only a proposito (5.1 lee los .ps1 como ANSI).
#
#   .\update.ps1            trae la ultima version del repo (git pull --ff-only) y reinstala
#   .\update.ps1 -NoPull    reinstala lo que ya esta en disco, sin tocar git
#
# Que hace, en orden:
#   1. git pull --ff-only sobre este clon (salvo -NoPull).
#   2. Registra el marketplace 'sdlc-ia' apuntando a ESTA carpeta si falta o apunta a otra ruta.
#   3. claude plugin marketplace update + install. Si la CLI dice "already installed", desinstala e
#      instala: 'install' nunca refresca un plugin ya instalado, ni aunque plugin.json haya subido
#      de version (comprobado con 0.1.0 -> 0.2.0). Reinstalar es la unica forma de refrescar la cache.
#   4. Verifica que la cache instalada sea identica a la fuente, archivo por archivo.
# Los cambios aplican a las sesiones nuevas de Claude Code.

param([switch]$NoPull)

$ErrorActionPreference = 'Stop'
$Root = $PSScriptRoot
function Assert-Exit([string]$Que) { if ($LASTEXITCODE -ne 0) { throw "$Que fallo con codigo $LASTEXITCODE" } }

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
if ($RutaRegistrada -and ($RutaRegistrada.TrimEnd('\') -ne $Root.TrimEnd('\'))) {
  "el marketplace sdlc-ia apuntaba a $RutaRegistrada; se vuelve a registrar sobre esta carpeta"
  claude plugin marketplace remove sdlc-ia; Assert-Exit 'marketplace remove'
  $RutaRegistrada = $null
}
if (-not $RutaRegistrada) {
  claude plugin marketplace add $Root; Assert-Exit 'marketplace add'
}

# 3) Refrescar e instalar
claude plugin marketplace update sdlc-ia; Assert-Exit 'marketplace update'
$Salida = (claude plugin install sdlc-ia@sdlc-ia -y) -join "`n"
Assert-Exit 'plugin install'
$Salida
if ($Salida -match 'already installed') {
  "ya estaba instalado: la CLI no refresca un plugin instalado, se reinstala"
  claude plugin uninstall sdlc-ia@sdlc-ia; Assert-Exit 'plugin uninstall'
  claude plugin install sdlc-ia@sdlc-ia -y; Assert-Exit 'plugin install'
}

# 4) Fuente == cache, archivo por archivo
$Fuente = Join-Path $Root 'sdlc-ia'
$Cache = Join-Path $HOME ".claude\plugins\cache\sdlc-ia\sdlc-ia\$Version"
if (-not (Test-Path $Cache)) { throw "no aparecio la cache en $Cache" }
$Distintos = 0
foreach ($f in Get-ChildItem -Recurse -File $Fuente) {
  $Relativo = $f.FullName.Substring($Fuente.Length)
  $Par = Join-Path $Cache $Relativo
  if (-not (Test-Path $Par) -or (Get-FileHash $f.FullName).Hash -ne (Get-FileHash $Par).Hash) { $Distintos++; "  difiere: $Relativo" }
}
if ($Distintos -gt 0) { throw "$Distintos archivo(s) difieren entre la fuente y la cache" }
"OK: sdlc-ia $Version instalado; la cache es identica a la fuente. Aplica a sesiones nuevas de Claude Code."
