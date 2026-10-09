param(
    [Parameter(Mandatory=$true)][string]$Jar,
    [Parameter(Mandatory=$true)][string]$SteamId,
    [Parameter(Mandatory=$true)][string]$PrivateKeyFile,
    [string]$OpenSSL = 'C:\Program Files\Git\usr\bin\openssl.exe'
)
$ErrorActionPreference = 'Stop'
if ($SteamId -notmatch '^\d{17}$') { throw 'SteamID64 must contain 17 digits' }
if (!(Test-Path -LiteralPath $PrivateKeyFile -PathType Leaf)) { throw 'Signing key does not exist' }
$jarPath = (Resolve-Path -LiteralPath $Jar).Path
$temp = Join-Path ([IO.Path]::GetDirectoryName($jarPath)) ('zbs-work-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $temp | Out-Null
try {
    $digest = [Security.Cryptography.SHA256]::Create()
    $input = [IO.File]::OpenRead($jarPath)
    try { $hash = ([BitConverter]::ToString($digest.ComputeHash($input))).Replace('-','').ToLowerInvariant() }
    finally { $input.Dispose(); $digest.Dispose() }
    $payload = Join-Path $temp 'payload.txt'
    $signature = Join-Path $temp 'signature.bin'
    $public = Join-Path $temp 'public.der'
    [IO.File]::WriteAllText($payload, "ZBS:${SteamId}:$hash", [Text.UTF8Encoding]::new($false))
    & $OpenSSL pkey -inform DER -in $PrivateKeyFile -pubout -outform DER -out $public
    if ($LASTEXITCODE -ne 0) { throw 'Could not derive public key' }
    $bytes = [IO.File]::ReadAllBytes($public)
    $hex = ([BitConverter]::ToString($bytes)).Replace('-','').ToLowerInvariant()
    if ($hex.Length -ne 88 -or !$hex.StartsWith('302a300506032b6570032100')) { throw 'Expected an Ed25519 PKCS#8 private key' }
    & $OpenSSL pkeyutl -sign -rawin -keyform DER -inkey $PrivateKeyFile -in $payload -out $signature
    if ($LASTEXITCODE -ne 0) { throw 'JAR signing failed' }
    & $OpenSSL pkeyutl -verify -rawin -pubin -keyform DER -inkey $public -in $payload -sigfile $signature
    if ($LASTEXITCODE -ne 0) { throw 'Signature verification failed' }
    $sig = [IO.File]::ReadAllBytes($signature)
    if ($sig.Length -ne 64) { throw 'Invalid signature length' }
    [IO.File]::WriteAllText($jarPath + '.zbs', "ZBS`nSteamID64:$SteamId`nSignature:" + ([BitConverter]::ToString($sig)).Replace('-','').ToLowerInvariant() + "`n", [Text.Encoding]::ASCII)
    [IO.File]::WriteAllText($jarPath + '.public-key.txt', 'JavaModZBS:' + $hex.Substring(24) + "`n", [Text.Encoding]::ASCII)
    Write-Output ('Public key: JavaModZBS:' + $hex.Substring(24))
    Write-Output ('Signed: ' + $jarPath + '.zbs')
} finally {
    # Only this invocation's exact temporary directory may be removed.
    $parent = [IO.Path]::GetDirectoryName($jarPath)
    if ([IO.Path]::GetFullPath($temp).StartsWith($parent + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $temp -Recurse -Force
    }
}
