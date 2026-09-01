# Diagnostica, en orden, por que el sistema responde "No encontre informacion
# suficientemente relevante en la base de conocimiento" a todo.
#
# Ese mensaje (Orquestador.MENSAJE_SIN_INFORMACION) sale de DOS puntos que no
# significan lo mismo, y ninguno deja rastro de error en ningun log:
#
#   Orquestador.java:369  la recuperacion puntuo INSUFICIENTE -- el LLM nunca
#                         vio el contenido. Problema de BUSQUEDA.
#   Orquestador.java:388  puntuo AMBIGUO y VerificadorGrounding dictamino que el
#                         contexto no responde -- el LLM SI lo vio y lo rechazo.
#                         Problema de JUICIO.
#
# A eso se suman dos causas mas prosaicas que se ven identicas desde la interfaz:
# que no haya nada ingerido, y que si lo haya pero sin embeber todavia. Los pasos
# de abajo las separan, del mas barato al mas caro, y cada uno imprime que
# significa su resultado.
#
# Uso (con el stack arriba):
#   .\scripts\verificar-respuesta-vacia.ps1
#   .\scripts\verificar-respuesta-vacia.ps1 "otra pregunta"
#
# Si la politica de ejecucion lo bloquea, sin cambiarla de forma permanente:
#   powershell -ExecutionPolicy Bypass -File .\scripts\verificar-respuesta-vacia.ps1
#
# OJO -- este archivo es ASCII puro a proposito, sin acentos ni guiones largos.
# Windows PowerShell 5.1 lee los .ps1 como cp1252 salvo que tengan BOM, y un
# acento en un literal lo hace fallar con un error de parser que no menciona el
# encoding por ningun lado. El equivalente en Bash vivia en la version .sh de
# este script, retirada: aqui la shell es PowerShell.

param(
    [string]$Pregunta = "cuales son los tipos primitivos en Java"
)

$ErrorActionPreference = "Continue"
$puerto = if ($null -eq $env:KB_PORT -or $env:KB_PORT -eq "") { "8080" } else { $env:KB_PORT }

function Titulo($texto) {
    Write-Host ""
    Write-Host "== $texto"
    Write-Host "-------------------------------------------------------------------"
}

# --------------------------------------------------------------------------
Titulo "0. Los contenedores estan arriba?"

# docker es un exe nativo: nada de 2>&1 aqui. En PowerShell 5.1 cada linea de
# stderr de un exe nativo se envuelve en un ErrorRecord y ensucia $?, aunque el
# exe haya salido con codigo 0.
$contenedores = docker ps --format "{{.Names}}"
if ($LASTEXITCODE -ne 0) {
    Write-Host "  Docker no responde. Esta Docker Desktop arriba?"
    exit 1
}
if ($contenedores -notcontains "kb-db") {
    Write-Host "  kb-db no esta corriendo. Levanta el stack con 'make up' y repite."
    exit 1
}
docker ps --filter "name=kb-" --format "  {{.Names}}`t{{.Status}}"

# --------------------------------------------------------------------------
Titulo "1. El arreglo de recuperacion esta EN EL CONTENEDOR?"
Write-Host "Esperado: TOPE=20 y EXPANDIR_VECINOS=false. Si sale 3, o no sale nada,"
Write-Host "el contenedor es viejo aunque el repo este al dia: make down; make up"

$envApi = docker exec kb-api env
$interesan = @()
if ($LASTEXITCODE -eq 0) {
    $interesan = $envApi | Select-String -Pattern "KB_RECUPERACION_TOPE_POR_DOCUMENTO|KB_EXPANDIR_VECINOS|KB_UMBRAL_RELEVANCIA_TECHO_CONFIANZA"
}
if ($interesan.Count -eq 0) {
    Write-Host "  SIN DATO: kb-api no responde o no tiene esas variables."
    Write-Host "  Si el contenedor esta arriba, es un contenedor viejo: make down; make up"
    Write-Host "  Si no lo esta, revisa: docker logs kb-api"
} else {
    $interesan | ForEach-Object { Write-Host "  $_" }
}

# --------------------------------------------------------------------------
Titulo "2. Hay contenido, y esta embebido?"
Write-Host "chunks sin embeber => no es recuperacion, es el worker (TrabajadorEmbebido)."
Write-Host "chunks = 0 => no hay corpus: 'make seed' (ver vault-init en el Makefile)."

$sqlCobertura = @'
SELECT d.id,
       left(d.title, 34)                AS documento,
       count(c.id)                      AS chunks,
       count(c.embedding)               AS embebidos,
       count(c.id) - count(c.embedding) AS pendientes
FROM documents d LEFT JOIN chunks c ON c.document_id = d.id
GROUP BY d.id, d.title ORDER BY chunks DESC;
'@
docker exec kb-db psql -U kb -d baseconocimiento -q -c $sqlCobertura | ForEach-Object { Write-Host "  $_" }

# --------------------------------------------------------------------------
Titulo "3. RECUPERACION aislada del LLM  --  /api/search"
Write-Host "Este es el paso que decide. /api/search devuelve los candidatos crudos:"
Write-Host "sin planificador, sin verificador de grounding y sin sintesis."
Write-Host ""
Write-Host "  Vuelven fragmentos del documento que responde -> la recuperacion esta"
Write-Host "  bien y el problema es de JUICIO (paso 4, y mira techo-confianza)."
Write-Host "  Vuelve vacio -> el problema es de BUSQUEDA: sigue en la recuperacion."
Write-Host ""
Write-Host "  Pregunta: $Pregunta"

# 90 segundos, no 30: /api/search embebe la consulta y corre el cross-encoder,
# ambos en CPU en el perfil por defecto. Con los modelos frios la primera llamada
# tarda bastante mas que las siguientes, y un timeout corto se confunde con un
# fallo de la api. Medido en vivo: con 30s daba "sin respuesta" teniendo
# /actuator/health en 200.
$cuerpo = @{ q = $Pregunta } | ConvertTo-Json -Compress
$resultados = $null
$errorHttp = $null
try {
    $resultados = Invoke-RestMethod -Method Post -TimeoutSec 90 `
        -Uri "http://localhost:$puerto/api/search" `
        -ContentType "application/json" -Body $cuerpo
} catch {
    $errorHttp = $_
}

if ($null -ne $errorHttp) {
    Write-Host "  SIN RESPUESTA util de /api/search:"
    Write-Host "    $($errorHttp.Exception.Message)"
    Write-Host "  Ojo: que /actuator/health responda NO descarta esto -- /api/search"
    Write-Host "  embebe la consulta, y si el modelo de embeddings no esta, se cuelga."
    Write-Host "  Modelos que reporta la api:"
    try {
        $salud = Invoke-RestMethod -TimeoutSec 15 -Uri "http://localhost:$puerto/actuator/health"
        $ollama = $salud.components.ollama.details
        Write-Host "    disponibles: $($ollama.modelosDisponibles -join ', ')"
        Write-Host "    faltantes  : $($ollama.modelosFaltantes -join ', ')"
        if ($null -ne $ollama.accion) { Write-Host "    accion     : $($ollama.accion)" }
    } catch {
        Write-Host "    (tampoco respondio /actuator/health)"
    }
} elseif ($null -eq $resultados -or @($resultados).Count -eq 0) {
    Write-Host "  fragmentos devueltos: 0"
    Write-Host "  -> RECUPERACION VACIA. El problema es de BUSQUEDA, no de juicio."
    Write-Host "     Revisa el paso 2: si hay chunks embebidos del documento correcto,"
    Write-Host "     el candidato existe pero no sobrevive la fusion RRF."
} else {
    $lista = @($resultados)
    Write-Host "  fragmentos devueltos: $($lista.Count)"
    Write-Host ""
    Write-Host ("  {0,-28} {1,7} {2,7}" -f "documento", "rerank", "rango")
    foreach ($r in $lista) {
        $t = $r.fragmento.titulo
        if ($null -eq $t) { $t = $r.fragmento.uri }
        if ($t.Length -gt 28) { $t = $t.Substring(0, 28) }
        $rr = $r.fragmento.rerank
        if ($null -eq $rr) { $rr = "-" } else { $rr = $rr.ToString("F2", [cultureinfo]::InvariantCulture) }
        Write-Host ("  {0,-28} {1,7} {2,7}" -f $t, $rr, $r.rangoFinal)
    }
    Write-Host ""
    Write-Host "  -> Llego material. Compara el rerank con techo-confianza (paso 1):"
    Write-Host "     por debajo del techo la respuesta cae en AMBIGUO y la decide"
    Write-Host "     VerificadorGrounding, que es donde se rechaza contenido valido."
}

# --------------------------------------------------------------------------
Titulo "4. Que decidio el sistema en tus consultas reales"
Write-Host "candidatos > 0 con citas = 0 => el material llego y algo lo rechazo:"
Write-Host "es Orquestador:388, VerificadorGrounding. El perfil base usa"
Write-Host "techo-confianza 8.0; Bonsai lo bajo a 6.0 justo por esto (un match de"
Write-Host "rerank 7.9 caia en AMBIGUO y se rechazaba). Prueba entonces:"
Write-Host "  `$env:KB_UMBRAL_RELEVANCIA_TECHO_CONFIANZA='6.0'; make up"

$sqlLog = @'
SELECT id,
       left(question, 38)             AS pregunta,
       jsonb_array_length(candidates) AS candidatos,
       jsonb_array_length(citations)  AS citas,
       left(answer, 34)               AS respuesta,
       latency_ms
FROM query_log ORDER BY created_at DESC LIMIT 5;
'@
docker exec kb-db psql -U kb -d baseconocimiento -q -c $sqlLog | ForEach-Object { Write-Host "  $_" }

Write-Host ""
Write-Host "==================================================================="
Write-Host "Si el paso 3 devuelve el documento correcto y el 4 muestra candidatos"
Write-Host "con 0 citas, NO toques la recuperacion: el problema es el umbral."
Write-Host "==================================================================="
