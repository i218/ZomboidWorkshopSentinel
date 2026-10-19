$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Drawing
$root=Split-Path $PSScriptRoot -Parent
function Brush($hex) { [Drawing.SolidBrush]::new([Drawing.ColorTranslator]::FromHtml($hex)) }
function Draw-Art([int]$size,[bool]$icon,[string]$file) {
    $image=[Drawing.Bitmap]::new($size,$size)
    $g=[Drawing.Graphics]::FromImage($image)
    $g.SmoothingMode=[Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.TextRenderingHint=[Drawing.Text.TextRenderingHint]::AntiAliasGridFit
    $g.ScaleTransform($size/512.0,$size/512.0)
    $background=Brush '#121c21';$panel=Brush '#22323b';$accent=Brush '#e6bb65';$white=Brush '#edf3f2';$muted=Brush '#a1b7bc';$green=Brush '#86c8a1'
    $title=[Drawing.Font]::new('Segoe UI',52,[Drawing.FontStyle]::Bold,[Drawing.GraphicsUnit]::Pixel)
    $small=[Drawing.Font]::new('Segoe UI',18,[Drawing.FontStyle]::Bold,[Drawing.GraphicsUnit]::Pixel)
    $label=[Drawing.Font]::new('Segoe UI',24,[Drawing.FontStyle]::Bold,[Drawing.GraphicsUnit]::Pixel)
    $pen=[Drawing.Pen]::new([Drawing.ColorTranslator]::FromHtml('#e6bb65'),8)
    try {
        $g.FillRectangle($background,0,0,512,512)
        if(!$icon) {
            $g.FillRectangle($accent,32,36,5,104)
            $g.DrawString('WORKSHOP',$label,$muted,51,36)
            $g.DrawString('SENTINEL',$title,$white,45,66)
            $g.DrawString('Updates watched. Restarts planned.',$small,$muted,36,154)
        }
        $top=if($icon){115}else{219}
        for($i=0;$i -lt 3;$i++) {
            $y=$top+$i*56
            $g.FillRectangle($panel,78,$y,356,44)
            $g.FillRectangle($accent,96,($y+13),6,18)
            $g.FillRectangle($muted,118,($y+16),153,4)
            $g.FillRectangle($muted,118,($y+26),94,3)
            $g.FillEllipse($green,388,($y+14),16,16)
        }
        if($icon) {
            $g.DrawString('WS',$title,$white,176,35)
            $g.DrawArc($pen,166,300,180,150,210,280)
            $g.DrawLine($pen,256,330,256,376);$g.DrawLine($pen,256,376,293,392)
        } else {
            $g.DrawString('BUILD 42',$label,$accent,36,425)
            $g.DrawString('SERVER + CLIENT',$small,$muted,270,432)
            $g.DrawString('PROJECT ZOMBOID',$small,$muted,36,463)
        }
        $image.Save($file,[Drawing.Imaging.ImageFormat]::Png)
    } finally {
        $pen.Dispose();$title.Dispose();$small.Dispose();$label.Dispose()
        foreach($b in @($background,$panel,$accent,$white,$muted,$green)){$b.Dispose()}
        $g.Dispose();$image.Dispose()
    }
}
Draw-Art 512 $false (Join-Path $root 'workshop/preview.png')
Draw-Art 256 $true (Join-Path $root 'workshop/icon.png')
