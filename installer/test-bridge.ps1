param([Parameter(Mandatory=$true)][string]$GameDirectory,
      [Parameter(Mandatory=$true)][string]$ZombieBuddyJar,
      [string]$JavaHome=$env:JAVA_HOME)
$ErrorActionPreference='Stop'
$project=Split-Path $PSScriptRoot -Parent
$classes=Join-Path $project 'build\bridge-tests'
New-Item -ItemType Directory -Force -Path $classes | Out-Null
$gameJar=Join-Path $GameDirectory 'projectzomboid.jar'
$modJar=Join-Path $project 'build\libs\WorkshopSentinel.jar'
$compilePath=$gameJar+';'+$ZombieBuddyJar+';'+$modJar
& (Join-Path $JavaHome 'bin\javac.exe') --release 11 -d $classes (Join-Path $PSScriptRoot 'BridgeExposureTests.java') (Join-Path $project 'src\test\java\zombie\network\GameServer.java')
if($LASTEXITCODE -ne 0) {throw 'Bridge integration test compilation failed'}
& (Join-Path $GameDirectory 'jre64\bin\java.exe') -cp ($classes+';'+$compilePath) BridgeExposureTests
if($LASTEXITCODE -ne 0) {throw 'Bridge integration test failed'}
