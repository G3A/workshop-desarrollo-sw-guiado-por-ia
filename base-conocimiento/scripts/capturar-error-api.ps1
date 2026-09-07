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

# kb-api es el CLIENTE. Cuando el error dice "running the model", lo que fallo
# ocurrio DENTRO de Ollama -- su proceso runner murio a media peticion, y eso
# solo se ve en `docker logs kb-ollama`. Sin esta seccion el informe daba mil
# vueltas sobre el mismo "unexpected EOF" del lado del cliente, que no dice por
# que: costo una vuelta entera de diagnostico contra un equipo real.
#
# Las lineas que importan de Ollama no llevan la palabra ERROR, asi que el filtro
# es distinto al de kb-api: se buscan las senales de que el runner se cayo o no
# cabe en memoria.
$hayOllama = $contenedores -contains "kb-ollama"
$informe.Add("")
$informe.Add("## Ollama (kb-ollama)")
$informe.Add("")
if (-not $hayOllama) {
    $informe.Add("El contenedor kb-ollama no esta corriendo.")
} else {
    $logsOllama = docker logs kb-ollama --tail $Lineas 2>$null
    $patronOllama = "error|failed|panic|signal|killed|out of memory|OOM|no space|EOF|" +
                    "unable to|cannot|not enough|memory|offload|gpu layers|llama runner"
    $trazaOllama = $logsOllama |
        Select-String -Pattern $patronOllama |
        Select-Object -Last 40
    if ($trazaOllama.Count -eq 0) {
        $informe.Add("Nada relevante en las ultimas $Lineas lineas de kb-ollama.")
    } else {
        foreach ($l in $trazaOllama) { $informe.Add($l.ToString()) }
    }

    # El reparto real del modelo: cuanto quedo en GPU y cuanto en CPU. Un
    # "100% CPU" o un reparto raro explica por si solo un runner que muere.
    $informe.Add("")
    $informe.Add("### ollama ps (reparto del modelo cargado)")
    $ps = docker exec kb-ollama ollama ps 2>$null
    if ($LASTEXITCODE -eq 0 -and $null -ne $ps) {
        foreach ($l in $ps) { $informe.Add($l.ToString()) }
    } else {
        $informe.Add("(no respondio)")
    }
}

# La memoria es la causa mas comun de que el runner muera, y ninguna de las dos
# mitades se ve desde dentro del contenedor: la VRAM de la tarjeta y el limite de
# RAM que Docker Desktop le da a la VM de WSL2 (%USERPROFILE%\.wslconfig, el paso
# manual que documenta el README).
$informe.Add("")
$informe.Add("### Memoria")
$informe.Add("")
$vram = nvidia-smi --query-gpu=name,memory.used,memory.total --format=csv,noheader 2>$null
if ($LASTEXITCODE -eq 0 -and $null -ne $vram) {
    foreach ($l in $vram) { $informe.Add("GPU: " + $l.ToString()) }
} else {
    $informe.Add("GPU: nvidia-smi no respondio (sin tarjeta, o fuera del PATH)")
}
$memDocker = docker info --format "{{.MemTotal}}" 2>$null
if ($LASTEXITCODE -eq 0 -and $null -ne $memDocker) {
    $gb = [math]::Round([double]$memDocker / 1GB, 1)
    $informe.Add("RAM disponible para Docker (VM de WSL2): $gb GB")
    $informe.Add("  Si parece poca, revisa %USERPROFILE%\.wslconfig -- ver wslconfig.example.")
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
