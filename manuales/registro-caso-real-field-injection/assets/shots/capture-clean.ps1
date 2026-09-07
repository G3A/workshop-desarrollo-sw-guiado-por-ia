<#
Abre una URL en una ventana NUEVA de Edge en modo InPrivate (sin las demas pestañas del
navegador principal, sin restaurar sesion), la maximiza, espera a que cargue, y captura SOLO el
contenido de esa ventana (no la pantalla completa) -- para no filtrar otras pestañas ni ventanas
de escritorio en la captura.

Verificacion estricta: Start-Process no siempre devuelve el handle de la ventana real (Edge puede
reenviar la apertura a una instancia ya corriendo, y el proceso lanzado se cierra sin ventana
propia). Por eso este script NUNCA confia en $proc.MainWindowHandle ni en "la ventana en foco":
busca por enumeracion (EnumWindows) una ventana visible, de un proceso msedge, con "[InPrivate]"
en el titulo, que ademas contenga -ExpectedTitleContains y que no existiera antes de lanzar Edge
(para no agarrar una ventana InPrivate vieja que haya quedado abierta de una sesion anterior). Si
no encuentra una coincidencia inequivoca dentro del timeout, ABORTA sin guardar nada.

Captura por PrintWindow (PW_RENDERFULLCONTENT), NO por CopyFromScreen: CopyFromScreen lee los
pixeles de una region de PANTALLA, sin importar que ventana este realmente encima -- y
SetForegroundWindow puede fallar en silencio (Windows bloquea el robo de foco entre procesos
distintos), dejando otra ventana tapando esa region y filtrandola en la captura. PrintWindow, en
cambio, le pide al propio proceso dueno del handle que renderice su contenido al bitmap: no
depende del foco ni del orden de ventanas, asi que no puede volver a capturar la ventana
equivocada por este motivo.
#>
param(
  [Parameter(Mandatory=$true)][string]$Url,
  [Parameter(Mandatory=$true)][string]$OutFile,
  [Parameter(Mandatory=$true)][string]$ExpectedTitleContains,
  [int]$WaitSeconds = 12,
  [int]$ScrollPageDowns = 0
)

Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms
Add-Type @"
using System;
using System.Text;
using System.Collections.Generic;
using System.Runtime.InteropServices;
public class Win32b {
  public delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);
  [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int count);
  [DllImport("user32.dll")] public static extern int GetWindowTextLength(IntPtr hWnd);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
  [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
  [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr hWnd, IntPtr hdcBlt, uint nFlags);
  [DllImport("user32.dll")] public static extern IntPtr PostMessage(IntPtr hWnd, uint msg, IntPtr wParam, IntPtr lParam);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);
  [StructLayout(LayoutKind.Sequential)]
  public struct RECT { public int Left; public int Top; public int Right; public int Bottom; }

  public class Info { public IntPtr Handle; public string Title; public uint Pid; }

  public static List<Info> ListVisible() {
    var result = new List<Info>();
    EnumWindows(new EnumWindowsProc((hWnd, lParam) => {
      if (!IsWindowVisible(hWnd)) return true;
      int len = GetWindowTextLength(hWnd);
      if (len == 0) return true;
      StringBuilder sb = new StringBuilder(len + 1);
      GetWindowText(hWnd, sb, sb.Capacity);
      uint pid;
      GetWindowThreadProcessId(hWnd, out pid);
      result.Add(new Info { Handle = hWnd, Title = sb.ToString(), Pid = pid });
      return true;
    }), IntPtr.Zero);
    return result;
  }
}
"@

function Get-CandidateWindows($expectedSubstring, $excludeHandles) {
  $all = [Win32b]::ListVisible()
  $candidates = @()
  foreach ($w in $all) {
    if ($excludeHandles -contains $w.Handle) { continue }
    try { $proc = Get-Process -Id $w.Pid -ErrorAction Stop } catch { continue }
    if ($proc.ProcessName -ne "msedge") { continue }
    if ($w.Title -notmatch "\[InPrivate\]") { continue }
    if ($expectedSubstring -and ($w.Title -notlike "*$expectedSubstring*")) { continue }
    $candidates += $w
  }
  return $candidates
}

$edgePaths = @(
  "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
  "C:\Program Files\Microsoft\Edge\Application\msedge.exe"
)
$edge = $edgePaths | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $edge) { throw "No se encontro msedge.exe" }

$preExisting = ([Win32b]::ListVisible() | Where-Object {
  $_.Title -match "\[InPrivate\]"
}).Handle

Start-Process -FilePath $edge -ArgumentList "--inprivate", "--new-window", "--start-maximized", $Url | Out-Null

$found = $null
$deadline = (Get-Date).AddSeconds($WaitSeconds)
while ((Get-Date) -lt $deadline -and -not $found) {
  Start-Sleep -Milliseconds 500
  $candidates = Get-CandidateWindows -expectedSubstring $ExpectedTitleContains -excludeHandles $preExisting
  if ($candidates.Count -eq 1) { $found = $candidates[0] }
  elseif ($candidates.Count -gt 1) {
    throw "Ambiguo: $($candidates.Count) ventanas InPrivate nuevas coinciden con '$ExpectedTitleContains'. No se captura nada -- revisa manualmente."
  }
}

if (-not $found) {
  throw "No aparecio ninguna ventana InPrivate de msedge con '[InPrivate]' y '$ExpectedTitleContains' en el titulo dentro de $WaitSeconds s. No se captura nada -- verifica que la pagina cargo y que el titulo esperado es correcto."
}

[Win32b]::SetForegroundWindow($found.Handle) | Out-Null
Start-Sleep -Milliseconds 800

if ($ScrollPageDowns -gt 0) {
  # PostMessage al handle especifico -- no depende de cual ventana tenga el foco (a diferencia
  # de SendKeys, que manda las teclas a la ventana en foco, que puede no ser esta).
  $WM_KEYDOWN = 0x0100
  $WM_KEYUP = 0x0101
  $VK_NEXT = 0x22
  for ($i = 0; $i -lt $ScrollPageDowns; $i++) {
    [Win32b]::PostMessage($found.Handle, $WM_KEYDOWN, [IntPtr]$VK_NEXT, [IntPtr]0) | Out-Null
    [Win32b]::PostMessage($found.Handle, $WM_KEYUP, [IntPtr]$VK_NEXT, [IntPtr]0) | Out-Null
    Start-Sleep -Milliseconds 400
  }
  Start-Sleep -Milliseconds 600
}

$rect = New-Object Win32b+RECT
[Win32b]::GetWindowRect($found.Handle, [ref]$rect) | Out-Null
$width = $rect.Right - $rect.Left
$height = $rect.Bottom - $rect.Top
$PW_RENDERFULLCONTENT = 0x00000002

function Test-BitmapLooksBlank($bmp) {
  # Paginas pesadas en JS (SPA) a veces siguen cargando cuando se dispara PrintWindow y el
  # frame compuesto todavia es un rectangulo casi solido. El chrome del navegador (tabs, barra
  # de URL) siempre tiene variacion de color, asi que muestrear desde y=0 puede dar "no esta en
  # blanco" aunque el CONTENIDO de la pagina (debajo del chrome) siga vacio. Por eso el muestreo
  # arranca despues del chrome (y >= 140px) y solo mira el area de contenido real.
  $colors = New-Object System.Collections.Generic.HashSet[int]
  $yStart = [Math]::Min(140, [int]($bmp.Height * 0.3))
  for ($x = 0; $x -lt $bmp.Width; $x += [Math]::Max(1, [int]($bmp.Width / 24))) {
    for ($y = $yStart; $y -lt $bmp.Height; $y += [Math]::Max(1, [int](($bmp.Height - $yStart) / 24))) {
      $colors.Add($bmp.GetPixel($x, $y).ToArgb()) | Out-Null
    }
  }
  return $colors.Count -le 4
}

$bmp = $null
$attempt = 0
$maxAttempts = 7
do {
  $attempt++
  if ($bmp) { $bmp.Dispose() }
  $bmp = New-Object System.Drawing.Bitmap $width, $height
  $gfx = [System.Drawing.Graphics]::FromImage($bmp)
  $hdc = $gfx.GetHdc()
  $ok = [Win32b]::PrintWindow($found.Handle, $hdc, $PW_RENDERFULLCONTENT)
  $gfx.ReleaseHdc($hdc)
  $gfx.Dispose()
  if (-not $ok) {
    $bmp.Dispose()
    throw "PrintWindow fallo sobre la ventana verificada '$($found.Title)'. No se guarda nada."
  }
  $blank = Test-BitmapLooksBlank $bmp
  if ($blank -and $attempt -lt $maxAttempts) {
    Write-Output "Intento $attempt/$maxAttempts parece en blanco (pagina todavia pintando) -- reintentando..."
    # Nudge: minimizar y restaurar fuerza a Chromium a recomponer el frame -- algunas paginas
    # SPA pesadas (repo home, Actions) se quedan con un frame compuesto vacio indefinidamente
    # si nunca reciben esta señal, sin importar cuanto se espere.
    $SW_MINIMIZE = 6; $SW_RESTORE = 9
    [Win32b]::ShowWindow($found.Handle, $SW_MINIMIZE) | Out-Null
    Start-Sleep -Milliseconds 300
    [Win32b]::ShowWindow($found.Handle, $SW_RESTORE) | Out-Null
    [Win32b]::SetForegroundWindow($found.Handle) | Out-Null
    Start-Sleep -Seconds 3
  }
} while ($blank -and $attempt -lt $maxAttempts)

if ($blank) {
  $bmp.Dispose()
  throw "La captura sigue pareciendo en blanco tras $maxAttempts intentos. No se guarda nada -- la pagina puede necesitar mas tiempo o interaccion."
}

$bmp.Save($OutFile, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()

Write-Output "Saved: $OutFile (${width}x${height}, intento $attempt) -- ventana verificada: '$($found.Title)'"

Stop-Process -Id $found.Pid -Force -ErrorAction SilentlyContinue
