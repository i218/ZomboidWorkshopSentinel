$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
$build = Join-Path $project 'build\installer'
$compiler = Join-Path $env:WINDIR 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
$tests = Join-Path $build 'SetupTests.exe'
$payload = Join-Path $build 'payload.zip'
& $compiler /nologo /target:exe /main:SetupTests "/out:$tests" /reference:System.Windows.Forms.dll /reference:System.Drawing.dll /reference:System.IO.Compression.dll /reference:System.IO.Compression.FileSystem.dll "/resource:$payload,payload.zip" (Join-Path $PSScriptRoot 'Setup.cs') (Join-Path $PSScriptRoot 'SetupTests.cs')
if ($LASTEXITCODE -ne 0) { throw 'Installer test compilation failed' }
& $tests (Join-Path (Split-Path $project -Parent) 'work')
if ($LASTEXITCODE -ne 0) { throw 'Installer integration tests failed' }
