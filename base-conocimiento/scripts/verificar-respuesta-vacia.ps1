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
# Uso (con el stack arriba). Lo simple:
#
#   make verificar
#   make verificar PREGUNTA="otra pregunta"
#
# El target existe porque invocar el .ps1 a mano choca con la politica de
# ejecucion de PowerShell, que se aplica a ARCHIVOS. Con RemoteSigned (el default
# de Windows) mas la marca de descarga, o con AllSigned, el .ps1 se rechaza con:
#
#   ... no esta firmado digitalmente. No se puede ejecutar este script en el
#   sistema actual.
#
# Suena a permisos y no lo es. Las salidas, de mas simple a mas robusta:
#
#   1) Bypass por invocacion -- no cambia nada de forma permanente, pero NO
#      sirve si la politica viene por directiva de grupo:
#        powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\verificar-respuesta-vacia.ps1
#
#   2) Quitar la marca de descarga, si ese era el motivo:
#        Unblock-File .\scripts\verificar-respuesta-vacia.ps1
#
#   3) Como scriptblock -- un scriptblock creado desde texto NO es un archivo,
#      asi que no pasa por la comprobacion. Funciona incluso con AllSigned y con
#      politica de directiva de grupo. Es lo que hace `make verificar`:
#        & ([scriptblock]::Create((Get-Content -Raw '.\scripts\verificar-respuesta-vacia.ps1')))
#
# OJO -- este archivo es ASCII puro a proposito, sin acentos ni guiones largos.
# Windows PowerShell 5.1 lee los .ps1 como cp1252 salvo que tengan BOM, y un
# acento en un literal lo hace fallar con un error de parser que no menciona el
# encoding por ningun lado. El equivalente en Bash vivia en la version .sh de
# este script, retirada: aqui la shell es PowerShell.

param(
    [string]$Pregunta = ""
)

# Vacio y ausente valen lo mismo. Hace falta porque `make verificar` interpola
# $(PREGUNTA) siempre, asi que sin argumento el script recibe '' -- que NO es lo
# mismo que no recibir nada: un default de param() solo aplica cuando el
# parametro esta ausente, y una cadena vacia lo pisaria.
if ([string]::IsNullOrWhiteSpace($Pregunta)) {
    $Pregunta = "cuales son los tipos primitivos en Java"
}

# psql emite UTF-8, pero la consola de Windows suele estar en cp437/cp850 y las
# tildes salen mal en el paso 4 (mojibake). Solo afecta a como se ve, no a los
# datos, pero un diagnostico ilegible se lee mal justo cuando importa. Se
# restaura al terminar para no dejar la sesion tocada.
$codificacionPrevia = [Console]::OutputEncoding
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

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
    $codigo = $null
    if ($null -ne $errorHttp.Exception.Response) {
        $codigo = [int]$errorHttp.Exception.Response.StatusCode
    }
    Write-Host "  SIN RESPUESTA util de /api/search:"
    Write-Host "    $($errorHttp.Exception.Message)"
    Write-Host ""

    # Un 5xx NO es lo mismo que un timeout, y confundirlos costo una vuelta
    # entera de diagnostico: la primera version de este bloque solo listaba los
    # modelos de Ollama y decia "si el modelo de embeddings no esta, se cuelga".
    # Ante un 500 con todos los modelos presentes, ese mensaje mandaba a mirar
    # donde no era. La causa de un 5xx esta en la excepcion del servidor, y solo
    # se ve en los logs del contenedor.
    if ($null -ne $codigo -and $codigo -ge 500) {
        Write-Host "  HTTP $codigo -- la api RECIBIO la consulta y fallo procesandola."
        Write-Host "  No es un timeout ni un modelo ausente: es una excepcion del"
        Write-Host "  servidor. La traza esta en los logs, no en /actuator/health."
        Write-Host ""
        Write-Host "  Ultimas lineas de kb-api con pinta de excepcion:"
        # 2>$null: docker logs manda a stderr el ruido de arranque de la JVM
        # (JAVA_TOOL_OPTIONS, los WARNING de native-access), que se colaba sin
        # sangrar entre las lineas utiles. La traza que interesa va por stdout.
        $logs = docker logs kb-api --tail 200 2>$null
        # Se EXCLUYEN los marcos "\tat ...": una traza de Spring sobre Netty son
        # decenas de lineas de framework que ahogan la unica que dice algo. Nos
        # quedamos con el mensaje de ERROR y los "Caused by", que es donde esta
        # la causa raiz.
        $traza = $logs |
            Select-String -Pattern "ERROR|Caused by|Exception" |
            Where-Object { $_ -notmatch "^\s*at " } |
            Select-Object -Last 8
        if ($traza.Count -eq 0) {
            Write-Host "    (nada evidente; mira el log completo: docker logs kb-api --tail 200)"
        } else {
            $traza | ForEach-Object { Write-Host "    $_" }
        }
        Write-Host ""
    } else {
        Write-Host "  Ojo: que /actuator/health responda NO descarta esto -- /api/search"
        Write-Host "  embebe la consulta, y si el modelo de embeddings no esta, se cuelga."
    }

    # Se imprimen TODOS los componentes, no solo ollama. /api/search depende
    # ademas del reranker (cross-encoder ONNX en /models/reranker) y de la base:
    # con los modelos de Ollama completos, un reranker a medio descargar es
    # justo el tipo de causa que la version anterior no mostraba.
    Write-Host "  Estado de los componentes de la api:"
    # Cuando un componente esta DOWN, /actuator/health responde 503 -- e
    # Invoke-RestMethod trata cualquier no-2xx como excepcion. La version
    # anterior caia al catch y decia "tampoco respondio", perdiendo justo el
    # detalle que hacia falta: el cuerpo del 503 SI trae el JSON con el
    # componente caido. Se lee del stream de la respuesta.
    $salud = $null
    try {
        $salud = Invoke-RestMethod -TimeoutSec 15 -Uri "http://localhost:$puerto/actuator/health"
    } catch {
        $cuerpoSalud = $null
        if ($null -ne $_.ErrorDetails -and $null -ne $_.ErrorDetails.Message) {
            # PowerShell 7 deja el cuerpo aqui.
            $cuerpoSalud = $_.ErrorDetails.Message
        } elseif ($null -ne $_.Exception.Response) {
            # PowerShell 5.1: hay que leer el stream a mano.
            try {
                $flujo = $_.Exception.Response.GetResponseStream()
                $lector = New-Object System.IO.StreamReader($flujo)
                $cuerpoSalud = $lector.ReadToEnd()
                $lector.Close()
            } catch { }
        }
        if (-not [string]::IsNullOrWhiteSpace($cuerpoSalud)) {
            try { $salud = $cuerpoSalud | ConvertFrom-Json } catch { }
        }
    }

    if ($null -eq $salud) {
        Write-Host "    (no se pudo leer /actuator/health)"
    } else {
        Write-Host "    estado global: $($salud.status)"
        foreach ($nombre in $salud.components.PSObject.Properties.Name) {
            $comp = $salud.components.$nombre
            Write-Host ("    {0,-14} {1}" -f $nombre, $comp.status)
        }
        $ollama = $salud.components.ollama.details
        if ($null -ne $ollama) {
            Write-Host "    ollama disponibles: $($ollama.modelosDisponibles -join ', ')"
            Write-Host "    ollama faltantes  : $($ollama.modelosFaltantes -join ', ')"
        }
        $rr = $salud.components.reranker.details
        if ($null -ne $rr) {
            Write-Host "    reranker: ruta=$($rr.ruta) modelo=$($rr.modeloPresente) tokenizador=$($rr.tokenizadorPresente) accion=$($rr.accion)"
        }
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

try { [Console]::OutputEncoding = $codificacionPrevia } catch { }
