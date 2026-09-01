# Captura la excepcion que esta devolviendo kb-api, y la deja en un archivo
# listo para compartir.
#
# Para que existe: cuando /api/search responde 5xx, la causa NO esta en
# /actuator/health ni en la respuesta HTTP -- esta en el log del contenedor, y
# es lo unico que distingue "la recuperacion no encuentra" de "la recuperacion
# revienta". Los dos se ven igual desde la interfaz: "No encontre informacion
# suficientemente relevante".
#
# Uso:
#   make capturar-error
#
# O directamente, si la politica de ejecucion lo permite (ver la cabecera de
# verificar-respuesta-vacia.ps1 sobre por que puede no permitirlo):
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\capturar-error-api.ps1
#
# Opciones:
#   -Lineas 500    cuantas lineas de log mirar hacia atras (default 300)
#   -Salida ruta   donde escribir el informe (default scripts/error-api.txt)
#
# ASCII puro a proposito: Windows PowerShell 5.1 lee los .ps1 como cp1252 salvo
# que tengan BOM, y un acento en un literal revienta el parser con un error que
# no menciona el encoding.

param(
    [int]$Lineas = 300,
    [string]$Salida = "scripts/error-api.txt"
)

$codificacionPrevia = [Console]::OutputEncoding
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

# docker es un exe nativo: no se redirige 2>&1, que en 5.1 envuelve cada linea
# de stderr en un ErrorRecord y ensucia $? aunque el exe salga con codigo 0.
$contenedores = docker ps -a --format "{{.Names}}"
if ($LASTEXITCODE -ne 0) {
    Write-Host "Docker no responde. Esta Docker Desktop arriba?"
    exit 1
}
if ($contenedores -notcontains "kb-api") {
    Write-Host "No existe el contenedor kb-api. Levanta el stack con 'make up'."
    exit 1
}

$estado = docker inspect -f "{{.State.Status}} (health: {{.State.Health.Status}})" kb-api 2>$null
$logs = docker logs kb-api --tail $Lineas 2>$null

# Dos vistas del mismo log. El resumen quita los marcos "at ..." porque una traza
# de Spring sobre Netty son decenas de lineas de framework que ahogan la unica
# que dice algo; el detalle los conserva, porque a veces el frame importa.
$resumen = $logs |
    Select-String -Pattern "ERROR|Caused by|Exception" |
    Where-Object { $_ -notmatch "^\s*at " }
$detalle = $logs | Select-String -Pattern "ERROR|Caused by|Exception|^\s*at "

$informe = New-Object System.Collections.Generic.List[string]
$informe.Add("# Error de kb-api")
$informe.Add("")
$informe.Add("Generado : $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')")
$informe.Add("Contenedor: $estado")
$informe.Add("Lineas revisadas: $Lineas")
$informe.Add("")

$informe.Add("## Resumen (sin marcos de pila)")
$informe.Add("")
if ($resumen.Count -eq 0) {
    $informe.Add("Ninguna linea con ERROR/Exception en las ultimas $Lineas lineas.")
    $informe.Add("Si /api/search devuelve 5xx igualmente, sube el valor: make capturar-error LINEAS=1000")
} else {
    foreach ($l in $resumen) { $informe.Add($l.ToString()) }
}

$informe.Add("")
$informe.Add("## Detalle (con marcos de pila)")
$informe.Add("")
if ($detalle.Count -eq 0) {
    $informe.Add("(nada)")
} else {
    foreach ($l in $detalle) { $informe.Add($l.ToString()) }
}

# -Encoding utf8 explicito: Out-File y Set-Content escriben UTF-16 LE por defecto
# en 5.1, y un informe en UTF-16 se ve como basura en GitHub y en cualquier otra
# herramienta que lo lea.
$directorio = Split-Path -Parent $Salida
if ($directorio -and -not (Test-Path $directorio)) {
    New-Item -ItemType Directory -Force -Path $directorio | Out-Null
}
$informe | Out-File -FilePath $Salida -Encoding utf8

Write-Host ""
Write-Host "== Resumen =========================================================="
if ($resumen.Count -eq 0) {
    Write-Host "  Ninguna linea con ERROR/Exception en las ultimas $Lineas lineas."
    Write-Host "  Si /api/search devuelve 5xx igual, mira mas atras:"
    Write-Host "    make capturar-error LINEAS=1000"
} else {
    foreach ($l in ($resumen | Select-Object -Last 12)) { Write-Host "  $l" }
}
Write-Host ""
Write-Host "Informe completo (resumen + marcos de pila) escrito en:"
Write-Host "  $Salida"
Write-Host "Ese es el archivo a compartir."
Write-Host "===================================================================="

try { [Console]::OutputEncoding = $codificacionPrevia } catch { }
