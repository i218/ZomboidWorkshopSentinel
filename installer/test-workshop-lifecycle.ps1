$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$package=Join-Path $root ('build/workshop-lifecycle/'+[guid]::NewGuid().ToString('N')+'/WorkshopSentinel')
& (Join-Path $PSScriptRoot 'build-workshop.ps1') -Destination $package
$metadata=Join-Path $package 'workshop.txt'
$text=[regex]::Replace([IO.File]::ReadAllText($metadata),'(?m)^visibility=[^\r\n]*','visibility=private')+"id=123`n"
[IO.File]::WriteAllText($metadata,$text,[Text.UTF8Encoding]::new($false))
$expected=$text.Replace('visibility=private','visibility=public')
& (Join-Path $PSScriptRoot 'build-workshop.ps1') -Destination $package -Refresh
if([IO.File]::ReadAllText($metadata) -ne $expected){throw 'Default refresh must fix stale privacy and preserve all other metadata'}
& (Join-Path $PSScriptRoot 'verify-workshop.ps1') -Package $package -ExpectedVisibility public
& (Join-Path $PSScriptRoot 'build-workshop.ps1') -Destination $package -Refresh -Visibility friendsOnly
if([IO.File]::ReadAllText($metadata) -ne $expected.Replace('visibility=public','visibility=friendsOnly')){throw 'Explicit visibility override damaged metadata'}
& (Join-Path $PSScriptRoot 'build-workshop.ps1') -Destination $package -Refresh
if([IO.File]::ReadAllText($metadata) -ne $expected){throw 'Default refresh inherited an old non-public override'}
$bad=Join-Path $package 'Contents/mods/WorkshopSentinel/credentials.txt'
[IO.File]::WriteAllText($bad,'test fixture only')
$rejected=$false
try{& (Join-Path $PSScriptRoot 'verify-workshop.ps1') -Package $package}catch{$rejected=$true}
if(!$rejected){throw 'Sensitive upload filename was accepted'}
Remove-Item -LiteralPath $bad
& (Join-Path $PSScriptRoot 'verify-workshop.ps1') -Package $package
Write-Output 'PASS default public visibility repairs stale privacy; explicit override works; ID/descriptions preserved; sensitive filename rejected'
