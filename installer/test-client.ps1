param(
    [Parameter(Mandatory=$true)][string]$GameDirectory,
    [string]$JavaHome = $env:JAVA_HOME
)
$ErrorActionPreference = 'Stop'
if (!$JavaHome) { throw 'Set JAVA_HOME to a JDK 11+ directory' }
$project = Split-Path $PSScriptRoot -Parent
$classes = Join-Path $project 'build\client-tests'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
& (Join-Path $JavaHome 'bin\javac.exe') -d $classes (Join-Path $PSScriptRoot 'KahluaClientTests.java')
if ($LASTEXITCODE -ne 0) { throw 'Test runner compilation failed' }
# Supply translation strings as UTF-8 byte escapes, avoiding Lua's Unicode-literal bug.
$fixture = 'testTranslations={}' + "`n"
foreach ($language in @('EN','RU')) {
    $translations = Get-Content -Raw -Encoding UTF8 (Join-Path $project "client-support\WorkshopSentinelClient\42\media\lua\shared\Translate\$language\UI.json") | ConvertFrom-Json
    $fixture += "testTranslations.$language={}`n"
    foreach ($entry in $translations.PSObject.Properties) {
        $bytes = [Text.Encoding]::UTF8.GetBytes([string]$entry.Value)
        $escaped = ($bytes | ForEach-Object { '\' + $_.ToString('000') }) -join ''
        $fixture += 'testTranslations.' + $language + '["' + $entry.Name + '"]="' + $escaped + '"' + "`n"
    }
}
$translationFixture = Join-Path $classes 'translations.lua'
[IO.File]::WriteAllText($translationFixture, $fixture, [Text.Encoding]::ASCII)
$files = @(
    (Join-Path $GameDirectory 'stdlib.lua'),
    (Join-Path $PSScriptRoot 'client-tests-bootstrap.lua'),
    (Join-Path $project 'client-support\WorkshopSentinelClient\42\media\lua\client\WorkshopSentinelClientModel.lua'),
    (Join-Path $PSScriptRoot 'client-model-tests.lua'),
    $translationFixture,
    (Join-Path $PSScriptRoot 'client-ui-bootstrap.lua'),
    (Join-Path $project 'client-support\WorkshopSentinelClient\42\media\lua\client\WorkshopSentinelClientUI.lua'),
    (Join-Path $PSScriptRoot 'client-ui-tests.lua'),
    (Join-Path $PSScriptRoot 'mlos-bootstrap.lua'),
    (Join-Path $project 'client-support\WorkshopSentinelClient\42\media\lua\client\WorkshopSentinelMLOSCompat.lua'),
    (Join-Path $PSScriptRoot 'mlos-tests.lua'),
    (Join-Path $PSScriptRoot 'server-events-bootstrap.lua'),
    (Join-Path $project '42\media\lua\server\WorkshopSentinel.lua'),
    (Join-Path $PSScriptRoot 'server-events-tests.lua')
)
& (Join-Path $GameDirectory 'jre64\bin\java.exe') -cp ($classes + ';' + (Join-Path $GameDirectory 'projectzomboid.jar')) KahluaClientTests @files
if ($LASTEXITCODE -ne 0) { throw 'Client Lua tests failed' }
