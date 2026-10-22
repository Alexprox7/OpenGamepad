# Draws the OpenGamepad icon (same shape as the Android launcher icon) and writes
# a multi-size .ico with PNG entries. Usage: powershell -File tools/make-icon.ps1 <out.ico> [<out.png>]
param([Parameter(Mandatory)] [string]$Ico, [string]$Png)
Add-Type -AssemblyName System.Drawing
# .NET resolves relative paths against the process directory, not the PowerShell location.
$Ico = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Ico)
if ($Png) { $Png = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($Png) }

function Draw([int]$size) {
    $bmp = New-Object System.Drawing.Bitmap $size, $size
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'
    $g.Clear([System.Drawing.Color]::Transparent)
    $s = $size / 66.0          # 66 = adaptive icon safe zone, offset 21
    function P([double]$x, [double]$y) { New-Object System.Drawing.PointF ([float](($x - 21) * $s)), ([float](($y - 21) * $s)) }

    # Rounded black square background
    $bg = New-Object System.Drawing.Drawing2D.GraphicsPath
    $r = $size * 0.22
    $bg.AddArc(0, 0, $r, $r, 180, 90); $bg.AddArc($size - $r - 1, 0, $r, $r, 270, 90)
    $bg.AddArc($size - $r - 1, $size - $r - 1, $r, $r, 0, 90); $bg.AddArc(0, $size - $r - 1, $r, $r, 90, 90)
    $bg.CloseFigure()
    $g.FillPath([System.Drawing.Brushes]::Black, $bg)

    # Controller body
    $body = New-Object System.Drawing.Drawing2D.GraphicsPath
    $body.AddLine((P 41 36), (P 67 36))
    $body.AddBezier((P 67 36), (P 73.5 36), (P 78 39.5), (P 79.3 45.5))
    $body.AddLine((P 79.3 45.5), (P 84.6 67))
    $body.AddBezier((P 84.6 67), (P 86.4 74.6), (P 79.2 80), (P 73.4 75.3))
    $body.AddLine((P 73.4 75.3), (P 65.6 68.5))
    $body.AddLine((P 65.6 68.5), (P 42.4 68.5))
    $body.AddLine((P 42.4 68.5), (P 34.6 75.3))
    $body.AddBezier((P 34.6 75.3), (P 28.8 80), (P 21.6 74.6), (P 23.4 67))
    $body.AddLine((P 23.4 67), (P 28.7 45.5))
    $body.AddBezier((P 28.7 45.5), (P 30 39.5), (P 34.5 36), (P 41 36))
    $body.CloseFigure()
    $accent = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(0x7C, 0x9C, 0xFF))
    $g.FillPath($accent, $body)

    # D-pad and face buttons
    $black = [System.Drawing.Brushes]::Black
    $g.FillRectangle($black, (P 40.5 43).X, (P 40.5 43).Y, 4 * $s, 11 * $s)
    $g.FillRectangle($black, (P 37 46.5).X, (P 37 46.5).Y, 11 * $s, 4 * $s)
    foreach ($c in @(@(68.1, 44.5), @(74.1, 50.5), @(68.1, 56.5), @(62.1, 50.5))) {
        $p = P ($c[0] - 2.6) ($c[1] - 2.6)
        $g.FillEllipse($black, $p.X, $p.Y, 5.2 * $s, 5.2 * $s)
    }
    $g.Dispose()
    return $bmp
}

$sizes = 16, 24, 32, 48, 64, 128, 256
$pngs = foreach ($size in $sizes) {
    $ms = New-Object System.IO.MemoryStream
    (Draw $size).Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    , $ms.ToArray()
}
if ($Png) { (Draw 512).Save($Png, [System.Drawing.Imaging.ImageFormat]::Png) }

# ICO container: header, one directory entry per size, then the PNG payloads
$out = New-Object System.IO.MemoryStream
$w = New-Object System.IO.BinaryWriter $out
$w.Write([uint16]0); $w.Write([uint16]1); $w.Write([uint16]$sizes.Count)
$offset = 6 + 16 * $sizes.Count
for ($i = 0; $i -lt $sizes.Count; $i++) {
    $dim = if ($sizes[$i] -ge 256) { 0 } else { $sizes[$i] }
    $w.Write([byte]$dim); $w.Write([byte]$dim); $w.Write([byte]0); $w.Write([byte]0)
    $w.Write([uint16]1); $w.Write([uint16]32)
    $w.Write([uint32]$pngs[$i].Length); $w.Write([uint32]$offset)
    $offset += $pngs[$i].Length
}
foreach ($p in $pngs) { $w.Write($p) }
[System.IO.File]::WriteAllBytes($Ico, $out.ToArray())
"Scritto $Ico"
