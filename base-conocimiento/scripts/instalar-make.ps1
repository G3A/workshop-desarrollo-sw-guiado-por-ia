# Instala GNU Make en Windows si no esta, para correr los comandos `make` de este repo.
#
# Uso (desde la raiz del repo, en PowerShell):
#   powershell -ExecutionPolicy Bypass -File scripts\instalar-make.ps1
#
# - Si `make` ya esta en el PATH, solo muestra la version y no instala nada.
# - Si no, lo instala con winget: el paquete `ezwinports.make` (GNU Make 4.x). No usa
#   `GnuWin32.Make`: es la 3.81 de 2006.
# - Si despues de instalar `make` no aparece en el PATH (winget no siempre lo agrega), suma su
#   carpeta `bin` al PATH del usuario. No toca el PATH del sistema ni pide administrador.
# - Avisa si falta Git for Windows: el Makefile usa su `sh.exe` como shell.
#
# Sale con 0 si al final hay un `make` utilizable y con 1 si no. Es seguro correrlo varias veces.
# El archivo es ASCII a proposito: Windows PowerShell 5.1 lee los .ps1 sin BOM como cp1252.

$ErrorActionPreference = 'Stop'

$PaqueteWinget = 'ezwinports.make'

function Escribir-Paso([string]$texto) {
    Write-Host "==> $texto" -ForegroundColor Cyan
}

function Actualizar-PathDeLaSesion {
    # Lo que instala winget queda en el PATH de la maquina o del usuario, no en esta sesion.
    $maquina = [Environment]::GetEnvironmentVariable('Path', 'Machine')
    $usuario = [Environment]::GetEnvironmentVariable('Path', 'User')
    $env:Path = (@($maquina, $usuario) | Where-Object { $_ }) -join ';'
}

function Buscar-Make {
    return Get-Command make -CommandType Application -ErrorAction SilentlyContinue |
        Select-Object -First 1
}

function Mostrar-Version($comando) {
    $primera = & $comando.Source --version | Select-Object -First 1
    Write-Host "make: $($comando.Source)"
    Write-Host "      $primera"
    if ($primera -match 'GNU Make (\d+)\.' -and [int]$Matches[1] -lt 4) {
        Write-Warning ("Esta version de make es anterior a la 4. Si algo falla, instala la de " +
            "ezwinports: winget install --id $PaqueteWinget --exact")
    }
}

function Revisar-Git {
    $candidatos = @(
        (Join-Path $env:ProgramFiles 'Git\usr\bin\sh.exe'),
        (Join-Path ${env:ProgramFiles(x86)} 'Git\usr\bin\sh.exe'),
        (Join-Path $env:LOCALAPPDATA 'Programs\Git\usr\bin\sh.exe')
    )
    $sh = $candidatos | Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
    if ($null -eq $sh) {
        Write-Warning ("No se encontro el sh.exe de Git for Windows. El Makefile lo usa como " +
            "shell; sin el, make cae a cmd.exe y casi todas las recetas fallan. " +
            "Instalalo con: winget install --id Git.Git --exact")
    } else {
        Write-Host "sh:   $sh"
    }
}

if ($env:OS -ne 'Windows_NT') {
    Write-Host 'Este script es para Windows. En Linux o macOS, make ya viene con el sistema.'
    exit 0
}

Escribir-Paso 'Buscando make en el PATH'
$make = Buscar-Make
if ($null -ne $make) {
    Mostrar-Version $make
    Revisar-Git
    Write-Host 'make ya esta instalado: no hay nada que hacer.' -ForegroundColor Green
    exit 0
}

Escribir-Paso 'make no esta instalado; se instala con winget'
if ($null -eq (Get-Command winget -ErrorAction SilentlyContinue)) {
    Write-Host ('No se encontro winget. Instala "App Installer" desde Microsoft Store ' +
        '(https://apps.microsoft.com/detail/9NBLGGH4NNS1) y vuelve a correr este script.') `
        -ForegroundColor Red
    exit 1
}

# winget escribe su progreso en la consola; el resultado se juzga por el codigo de salida.
& winget install --id $PaqueteWinget --exact --source winget `
    --accept-package-agreements --accept-source-agreements --disable-interactivity
$codigo = $LASTEXITCODE
# -1978335189 (0x8A15002B): no hay actualizacion aplicable, es decir, ya estaba instalado.
if ($codigo -ne 0 -and $codigo -ne -1978335189) {
    Write-Host "winget termino con el codigo $codigo; make no se instalo." -ForegroundColor Red
    exit 1
}

Actualizar-PathDeLaSesion
$make = Buscar-Make

if ($null -eq $make) {
    Escribir-Paso 'winget no dejo make en el PATH; se agrega su carpeta al PATH del usuario'
    $paquetes = Join-Path $env:LOCALAPPDATA 'Microsoft\WinGet\Packages'
    $exe = Get-ChildItem -Path $paquetes -Filter 'make.exe' -Recurse -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -like "*$PaqueteWinget*" } |
        Select-Object -First 1
    if ($null -eq $exe) {
        Write-Host "No se encontro make.exe bajo $paquetes." -ForegroundColor Red
        exit 1
    }
    $carpeta = $exe.DirectoryName
    $pathUsuario = [Environment]::GetEnvironmentVariable('Path', 'User')
    $entradas = @($pathUsuario -split ';' | Where-Object { $_ })
    if ($entradas -notcontains $carpeta) {
        [Environment]::SetEnvironmentVariable('Path', (($entradas + $carpeta) -join ';'), 'User')
        Write-Host "Agregado al PATH del usuario: $carpeta"
    }
    Actualizar-PathDeLaSesion
    $make = Buscar-Make
}

if ($null -eq $make) {
    Write-Host 'make se instalo pero no se puede ejecutar desde esta sesion.' -ForegroundColor Red
    exit 1
}

Mostrar-Version $make
Revisar-Git
Write-Host ('make quedo instalado. Las terminales que ya estaban abiertas no ven el PATH nuevo: ' +
    'cierralas y abrelas de nuevo.') -ForegroundColor Green
exit 0
