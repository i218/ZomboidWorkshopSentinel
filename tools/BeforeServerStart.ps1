param(
    [Parameter(Mandatory=$true)][string]$Config,
    [Parameter(Mandatory=$true)][string]$ServerIni,
    [string]$Java='java'
)
$ErrorActionPreference='Stop'
# A temporary copy keeps the bootstrap's own JAR unlocked during a self-update.
$jar=Join-Path (Split-Path $PSScriptRoot -Parent) '42/media/java/WorkshopSentinel.jar'
$copy=[IO.Path]::GetTempFileName()
try {
    Copy-Item -LiteralPath $jar -Destination $copy -Force
    & $Java -cp $copy io.workshopsentinel.StagingBootstrap $Config $ServerIni
    if($LASTEXITCODE -ne 0){throw 'Workshop pre-start validation failed. Server startup must stop.'}
} finally {Remove-Item -LiteralPath $copy -Force}
