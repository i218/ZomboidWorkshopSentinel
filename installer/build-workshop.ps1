param([string]$Destination,[switch]$Refresh)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
$version=[regex]::Match([IO.File]::ReadAllText((Join-Path $root '42/mod.info')),'(?m)^modversion=([^\r\n]+)').Groups[1].Value.Trim()
if($version -notmatch '^\d+\.\d+\.\d+$'){throw 'Invalid mod version'}
if(!$Destination){$Destination=Join-Path $root "build/workshop/$version/WorkshopSentinel"}
$destinationFull=[IO.Path]::GetFullPath($Destination)
if(Test-Path -LiteralPath $destinationFull){
    if(!$Refresh){throw 'Package exists. Use -Refresh to archive it and preserve publication metadata.'}
    if((Get-Item -LiteralPath $destinationFull -Force).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Linked output folder is not supported'}
    if(!(Test-Path -LiteralPath (Join-Path $destinationFull 'workshop.txt'))){throw 'Existing output is not a Workshop package'}
}
$parent=Split-Path $destinationFull -Parent
New-Item -ItemType Directory -Path $parent -Force|Out-Null
$stage=Join-Path $parent ('.workshopsentinel-stage-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path (Join-Path $stage 'Contents/mods') -Force|Out-Null
function Copy-Runtime([string]$source,[string]$target){
    $pending=[Collections.Generic.Stack[string]]::new();$pending.Push($source)
    while($pending.Count -gt 0){
        $dir=$pending.Pop()
        if((Get-Item -LiteralPath $dir -Force).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Linked source forbidden'}
        foreach($file in Get-ChildItem -LiteralPath $dir -Force){
            if($file.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Linked source forbidden'}
            if($file.PSIsContainer){$pending.Push($file.FullName);continue}
            if($file.Name -eq '.gitkeep'){continue}
            if($file.Extension -notin @('.lua','.json','.info','.txt','.jar','.png','.zbs')){throw "Unexpected runtime file: $($file.Name)"}
            $rel=$file.FullName.Substring($source.Length+1)
            $to=Join-Path $target $rel
            New-Item -ItemType Directory -Path (Split-Path $to -Parent) -Force|Out-Null
            Copy-Item -LiteralPath $file.FullName -Destination $to
        }
    }
}
$core=Join-Path $stage 'Contents/mods/WorkshopSentinel'
$client=Join-Path $stage 'Contents/mods/WorkshopSentinelClient'
foreach($folder in @('42','common')){Copy-Runtime (Join-Path $root $folder) (Join-Path $core $folder)}
Copy-Runtime (Join-Path $root 'client-support/WorkshopSentinelClient') $client
New-Item -ItemType Directory -Path (Join-Path $core 'config') -Force|Out-Null
Copy-Item -LiteralPath (Join-Path $root 'config/WorkshopSentinel.properties') -Destination (Join-Path $core 'config/WorkshopSentinel.properties')
foreach($readme in @('README.md','README.ru.md')){Copy-Item -LiteralPath (Join-Path $root $readme) -Destination (Join-Path $core $readme)}
foreach($mod in @($core,$client)){
    New-Item -ItemType Directory -Path (Join-Path $mod 'common') -Force|Out-Null
    Copy-Item -LiteralPath (Join-Path $root 'workshop/icon.png') -Destination (Join-Path $mod 'common/icon.png')
    Copy-Item -LiteralPath (Join-Path $root 'workshop/preview.png') -Destination (Join-Path $mod 'common/poster.png')
    $info=Join-Path $mod '42/mod.info'
    $text=[IO.File]::ReadAllText($info).TrimEnd()+"`nicon=icon.png`nposter=poster.png`n"
    [IO.File]::WriteAllText($info,$text,[Text.UTF8Encoding]::new($false))
}
$metadata=Join-Path $root 'workshop/workshop.txt'
if(Test-Path -LiteralPath $destinationFull){$metadata=Join-Path $destinationFull 'workshop.txt'}
Copy-Item -LiteralPath $metadata -Destination (Join-Path $stage 'workshop.txt')
Copy-Item -LiteralPath (Join-Path $root 'workshop/preview.png') -Destination (Join-Path $stage 'preview.png')
& (Join-Path $PSScriptRoot 'verify-workshop.ps1') -Package $stage
if(Test-Path -LiteralPath $destinationFull){
    $archiveBase=[IO.Path]::GetFullPath((Join-Path (Split-Path $parent -Parent) 'WorkshopSentinelBackups/Workshop'))
    $archive=Join-Path $archiveBase ([guid]::NewGuid().ToString('N'))
    $archiveParent=$archiveBase+'\'
    if(![IO.Path]::GetFullPath($archive).StartsWith($archiveParent,[StringComparison]::OrdinalIgnoreCase)){throw 'Archive escaped project'}
    # Destination is the exact reviewed Workshop folder, not a generated root or wildcard.
    if(!(Test-Path -LiteralPath (Join-Path $destinationFull 'Contents/mods/WorkshopSentinel/42/mod.info'))){throw 'Not our Workshop folder'}
    New-Item -ItemType Directory -Path (Split-Path $archive -Parent) -Force|Out-Null
    Move-Item -LiteralPath $destinationFull -Destination $archive
    try{Move-Item -LiteralPath $stage -Destination $destinationFull}
    catch{if(!(Test-Path -LiteralPath $destinationFull)){Move-Item -LiteralPath $archive -Destination $destinationFull};throw}
}else{Move-Item -LiteralPath $stage -Destination $destinationFull}
Write-Output $destinationFull
