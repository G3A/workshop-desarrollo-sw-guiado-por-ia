<#
Recorta SOLO la ventana cuyo titulo contenga -TitlePattern (no la pantalla completa) -- para
capturar una ventana local ya abierta (por ejemplo, la terminal de esta sesion de Claude Code)
sin filtrar otras ventanas del escritorio. Hermano de capture-clean.ps1: mismas APIs Win32
(EnumWindows/GetWindowRect/CopyFromScreen), pero apunta a una ventana existente en vez de abrir
Edge.
#>
param(
  [Parameter(Mandatory=$true)][string]$TitlePattern,
  [Parameter(Mandatory=$true)][string]$OutFile
)

Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Text;
using System.Collections.Generic;
using System.Runtime.InteropServices;
public class Win32Window {
  public delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);
  [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int count);
  [DllImport("user32.dll")] public static extern int GetWindowTextLength(IntPtr hWnd);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
  [StructLayout(LayoutKind.Sequential)]
  public struct RECT { public int Left; public int Top; public int Right; public int Bottom; }

  public static List<IntPtr> Matches = new List<IntPtr>();
  public static string Pattern = "";

  public static bool Callback(IntPtr hWnd, IntPtr lParam) {
    if (!IsWindowVisible(hWnd)) return true;
    int len = GetWindowTextLength(hWnd);
    if (len == 0) return true;
    StringBuilder sb = new StringBuilder(len + 1);
    GetWindowText(hWnd, sb, sb.Capacity);
    if (sb.ToString().IndexOf(Pattern, StringComparison.OrdinalIgnoreCase) >= 0) {
      Matches.Add(hWnd);
    }
    return true;
  }

  public static List<IntPtr> FindByTitle(string pattern) {
    Matches.Clear();
    Pattern = pattern;
    EnumWindows(new EnumWindowsProc(Callback), IntPtr.Zero);
    return Matches;
  }
}
"@

$found = [Win32Window]::FindByTitle($TitlePattern)
if ($found.Count -eq 0) {
  Write-Error "No se encontro ninguna ventana visible cuyo titulo contenga '$TitlePattern'. Ventanas visibles con titulo:"
  exit 1
}
$hwnd = $found[0]

[Win32Window]::SetForegroundWindow($hwnd) | Out-Null
Start-Sleep -Milliseconds 400

$rect = New-Object Win32Window+RECT
[Win32Window]::GetWindowRect($hwnd, [ref]$rect) | Out-Null
$width = $rect.Right - $rect.Left
$height = $rect.Bottom - $rect.Top

if ($width -le 0 -or $height -le 0) {
  Write-Error "Rectangulo de ventana invalido (${width}x${height}) -- la ventana puede estar minimizada."
  exit 1
}

$bmp = New-Object System.Drawing.Bitmap $width, $height
$gfx = [System.Drawing.Graphics]::FromImage($bmp)
$gfx.CopyFromScreen($rect.Left, $rect.Top, 0, 0, (New-Object System.Drawing.Size $width, $height))
$bmp.Save($OutFile, [System.Drawing.Imaging.ImageFormat]::Png)
$gfx.Dispose()
$bmp.Dispose()

Write-Output "Saved: $OutFile (${width}x${height})"
