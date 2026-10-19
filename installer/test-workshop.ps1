param([Parameter(Mandatory=$true)][string]$GameDirectory,[string]$Package,[string]$JavaHome=$env:JAVA_HOME)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
if(!$Package){$version=[regex]::Match([IO.File]::ReadAllText((Join-Path $root '42/mod.info')),'(?m)^modversion=([^\r\n]+)').Groups[1].Value.Trim();$Package=Join-Path $root "build/workshop/$version/WorkshopSentinel"}
$Package=(Resolve-Path -LiteralPath $Package).Path
& (Join-Path $PSScriptRoot 'verify-workshop.ps1') -Package $Package
$classes=Join-Path $root 'build/workshop-tests'
New-Item -ItemType Directory -Force -Path $classes|Out-Null
& (Join-Path $JavaHome 'bin/javac.exe') --release 11 -d $classes (Join-Path $PSScriptRoot 'WorkshopCheck.java')
if($LASTEXITCODE -ne 0){throw 'Workshop runner compilation failed'}
Push-Location $GameDirectory
try{
    & (Join-Path $GameDirectory 'jre64/bin/java.exe') -cp "$classes;$GameDirectory/projectzomboid.jar;$GameDirectory/*" WorkshopCheck $Package
    if($LASTEXITCODE -ne 0){throw 'Native Workshop validation failed'}
}finally{Pop-Location}
