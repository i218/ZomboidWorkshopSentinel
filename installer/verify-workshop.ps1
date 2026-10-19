param([Parameter(Mandatory=$true)][string]$Package)
$ErrorActionPreference='Stop'
function Get-Sha([string]$Path){
    $hash=[Security.Cryptography.SHA256]::Create()
    $stream=[IO.File]::OpenRead($Path)
    try{return [BitConverter]::ToString($hash.ComputeHash($stream))}finally{$stream.Dispose();$hash.Dispose()}
}
$root=Split-Path $PSScriptRoot -Parent
$folder=(Resolve-Path -LiteralPath $Package).Path
$required=@('workshop.txt','preview.png','Contents/mods/WorkshopSentinel/42/mod.info','Contents/mods/WorkshopSentinel/42/media/java/WorkshopSentinel.jar','Contents/mods/WorkshopSentinel/config/WorkshopSentinel.properties','Contents/mods/WorkshopSentinelClient/42/mod.info','Contents/mods/WorkshopSentinelClient/42/media/lua/client/WorkshopSentinelClientUI.lua')
foreach($name in $required){if(!(Test-Path -LiteralPath (Join-Path $folder $name) -PathType Leaf)){throw "Missing $name"}}
$files=@(Get-ChildItem -LiteralPath $folder -Recurse -Force)
foreach($file in $files){
    if($file.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Linked upload entries forbidden'}
    if($file.PSIsContainer){
        if($file.Name -in @('.git','build','installer','src','tests','secrets','private','.codex','.gradle')){throw 'Development directory in upload'}
        continue
    }
    if($file.Name -match '(?i)(\.local\.|^\.env|private|secret|credential)' -or $file.Extension -notin @('.lua','.json','.info','.txt','.png','.jar','.zbs','.properties','.md')){throw "Unexpected upload file: $($file.Name)"}
    $rel=$file.FullName.Substring($folder.Length+1).Replace('\','/')
    if($rel -notin @('workshop.txt','preview.png') -and !$rel.StartsWith('Contents/mods/WorkshopSentinel/') -and !$rel.StartsWith('Contents/mods/WorkshopSentinelClient/')){throw 'Unexpected upload location'}
}
$jar=Join-Path $folder 'Contents/mods/WorkshopSentinel/42/media/java/WorkshopSentinel.jar'
if((Get-Sha $jar) -ne (Get-Sha (Join-Path $root 'build/libs/WorkshopSentinel.jar'))){throw 'Workshop JAR differs from current build'}
$coreLua=Join-Path $folder 'Contents/mods/WorkshopSentinel/42/media/lua'
if(Get-ChildItem -LiteralPath $coreLua -File -Recurse|Where-Object{$_.FullName -match '[\\/](client|shared)[\\/]'.ToString()}){throw 'Client/shared Lua in server core'}
$metadata=Get-Content -LiteralPath (Join-Path $folder 'workshop.txt') -Encoding utf8
foreach($key in @('version','title','tags','visibility')){if(@($metadata|Where-Object{$_.StartsWith($key+'=')}).Count -ne 1){throw "Missing/duplicate $key"}}
if('version=1' -notin $metadata){throw 'Unsupported Workshop metadata version'}
$ids=@($metadata|Where-Object{$_.StartsWith('id=')});[UInt64]$id=0
if($ids.Count -gt 1 -or ($ids.Count -eq 1 -and ($ids[0].Substring(3) -notmatch '^[1-9][0-9]{0,19}$' -or ![UInt64]::TryParse($ids[0].Substring(3),[ref]$id)))){throw 'Invalid Workshop ID'}
Add-Type -AssemblyName System.Drawing
$preview=[Drawing.Image]::FromFile((Join-Path $folder 'preview.png'))
try{if($preview.Width -ne 512 -or $preview.Height -ne 512){throw 'Expected 512x512 preview'}}finally{$preview.Dispose()}
if((Get-Item -LiteralPath (Join-Path $folder 'preview.png')).Length -ge 1024000){throw 'Preview exceeds game size limit'}
Write-Output "PASS clean Workshop package: $($files.Count) entries, current JAR, separate client component, metadata and preview"
