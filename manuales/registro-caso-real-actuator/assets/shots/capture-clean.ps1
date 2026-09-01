<#
Abre una URL en una ventana NUEVA de Edge en modo InPrivate (sin las demas pestañas del
navegador principal, sin restaurar sesion), la maximiza, espera a que cargue, y captura
SOLO el rectangulo de esa ventana (no la pantalla completa) -- para no filtrar otras
pestañas ni ventanas de escritorio en la captura.
#>
param(
  [Parameter(Mandatory=$true)][string]$Url,
  [Parameter(Mandatory=$true)][string]$OutFile,
  [int]$WaitSeconds = 4,
  [int]$ScrollPageDowns = 0
)

Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Windows.Forms
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class Win32 {
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
  [StructLayout(LayoutKind.Sequential)]
  public struct RECT { public int Left; public int Top; public int Right; public int Bottom; }
}
"@

$edgePaths = @(
  "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
  "C:\Program Files\Microsoft\Edge\Application\msedge.exe"
)
$edge = $edgePaths | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $edge) { throw "No se encontro msedge.exe" }

$proc = Start-Process -FilePath $edge -ArgumentList "--inprivate", "--new-window", "--start-maximized", $Url -PassThru
Start-Sleep -Seconds $WaitSeconds

# Foreground window ya deberia ser la ventana InPrivate recien abierta.
[Win32]::SetForegroundWindow($proc.MainWindowHandle) | Out-Null
Start-Sleep -Milliseconds 400

if ($ScrollPageDowns -gt 0) {
  for ($i = 0; $i -lt $ScrollPageDowns; $i++) {
    [System.Windows.Forms.SendKeys]::SendWait("{PGDN}")
    Start-Sleep -Milliseconds 400
  }
  Start-Sleep -Milliseconds 600
}

$hwnd = [Win32]::GetForegroundWindow()
$rect = New-Object Win32+RECT
[Win32]::GetWindowRect($hwnd, [ref]$rect) | Out-Null
$width = $rect.Right - $rect.Left
$height = $rect.Bottom - $rect.Top

$bmp = New-Object System.Drawing.Bitmap $width, $height
$gfx = [System.Drawing.Graphics]::FromImage($bmp)
$gfx.CopyFromScreen($rect.Left, $rect.Top, 0, 0, (New-Object System.Drawing.Size $width, $height))
$bmp.Save($OutFile, [System.Drawing.Imaging.ImageFormat]::Png)
$gfx.Dispose()
$bmp.Dispose()

Write-Output "Saved: $OutFile (${width}x${height})"

# Cerrar la ventana InPrivate -- no dejar sesiones colgadas.
Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
