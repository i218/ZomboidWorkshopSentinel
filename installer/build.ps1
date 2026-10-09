$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$project = Split-Path $PSScriptRoot -Parent
$build = Join-Path $project 'build\installer'
New-Item -ItemType Directory -Path $build -Force | Out-Null
$payload = Join-Path $build 'payload.zip'
$stream = [IO.File]::Open($payload, [IO.FileMode]::Create)
$zip = New-Object IO.Compression.ZipArchive($stream, [IO.Compression.ZipArchiveMode]::Create)
try {
        $source = [IO.Compression.ZipFile]::OpenRead((Join-Path $project 'build\distributions\WorkshopSentinel-mod.zip'))
        try {
            foreach ($required in @('WorkshopSentinel/42/media/java/WorkshopSentinel.jar','WorkshopSentinel/42/mod.info','WorkshopSentinel/config/WorkshopSentinel.properties')) {
                if (!$source.GetEntry($required)) { throw "Installer payload is missing $required" }
            }
            foreach ($entry in $source.Entries) {
                if ($entry.FullName.EndsWith('/')) { continue }
                $dest = $zip.CreateEntry($entry.FullName)
                $input = $entry.Open(); $output = $dest.Open()
                try { $input.CopyTo($output) } finally { $input.Dispose(); $output.Dispose() }
            }
        } finally { $source.Dispose() }
} finally { $zip.Dispose(); $stream.Dispose() }
$compiler = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
$exe = Join-Path $build 'WorkshopSentinel-Setup.exe'
& $compiler /nologo /target:winexe /optimize+ "/out:$exe" /reference:System.Windows.Forms.dll /reference:System.Drawing.dll /reference:System.IO.Compression.dll /reference:System.IO.Compression.FileSystem.dll "/resource:$payload,payload.zip" (Join-Path $PSScriptRoot 'Setup.cs')
if ($LASTEXITCODE -ne 0) { throw 'Installer compilation failed' }
Write-Output $exe
