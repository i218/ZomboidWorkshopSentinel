$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$package=Join-Path $root ('build/workshop-lifecycle/'+[guid]::NewGuid().ToString('N')+'/WorkshopSentinel')
& (Join-Path $PSScriptRoot 'build-workshop.ps1') -Destination $package
$metadata=Join-Path $package 'workshop.txt'
$text=[IO.File]::ReadAllText($metadata).Replace('visibility=private','visibility=friendsOnly')+"id=123`n"
[IO.File]::WriteAllText($metadata,$text,[Text.UTF8Encoding]::new($false))
$before=(Get-FileHash -LiteralPath $metadata).Hash
& (Join-Path $PSScriptRoot 'build-workshop.ps1') -Destination $package -Refresh
if((Get-FileHash -LiteralPath $metadata).Hash -ne $before){throw 'Refresh changed publication metadata'}
$bad=Join-Path $package 'Contents/mods/WorkshopSentinel/credentials.txt'
[IO.File]::WriteAllText($bad,'test fixture only')
$rejected=$false
try{& (Join-Path $PSScriptRoot 'verify-workshop.ps1') -Package $package}catch{$rejected=$true}
if(!$rejected){throw 'Sensitive upload filename was accepted'}
Remove-Item -LiteralPath $bad
& (Join-Path $PSScriptRoot 'verify-workshop.ps1') -Package $package
Write-Output 'PASS refresh preserves publication ID/visibility/description; sensitive upload filename rejected'
