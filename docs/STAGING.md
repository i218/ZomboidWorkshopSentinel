# Steamworks Workshop staging

Staging is opt-in. The downloader runs in a separate Windows x64 process with an anonymous Steam game-server session. It uses an empty Workshop cache for each request. Set up a 64-bit Python 3.12+ executable and use `steam_api64.dll` from the matching Project Zomboid installation. The helper script is embedded in the Sentinel JAR. The host needs a compatible Steam Runtime. This path has been tested on Windows with the installed Steam Runtime.

Use a private Workshop directory for this dedicated server. It must already contain `appworkshop_108600.acf` from a normal Steam startup. The server must load these items from that cache. Local mods with the same IDs can shadow Workshop copies.

```properties
dryRun=false
stagingEnabled=true
stagingDirectory=staged-workshop
stagingWorkshopDirectory=C:/PZServer/steamapps/workshop
stagingPython=C:/Python313/python.exe
stagingSteamworksLibrary=C:/PZServer/steam_api64.dll
```

`stagingPython` is absolute. The other paths may be relative to the config file. If upgrading from 0.6.0, add the two Steamworks paths. The old `stagingSteamCmd` entry is ignored. Steamworks staging on Linux is currently unavailable. Monitoring and restart scheduling can still be used there with staging disabled.

Run the pre-start step while the server is stopped, on every launch including panel and supervisor launches. Keep the script and its JAR at a stable path outside the cache being updated. The script makes a temporary copy of its JAR for self-update support.

```bat
powershell.exe -NoProfile -File "C:\PZMods\WorkshopSentinel\tools\BeforeServerStart.ps1" -Config "C:\PZData\WorkshopSentinel\servertest\WorkshopSentinel.properties" -ServerIni "C:\PZData\Server\servertest.ini" -Java "C:\PZServer\jre64\bin\java.exe"
if errorlevel 1 exit /b 1
call "C:\PZServer\StartServer64.bat" -servername servertest
```

During play, only the separate cache and queue receive content. Before launch, Sentinel checks the prepared files against their SHA-256 receipt and independently downloads the current manifest using a fresh Steamworks cache. A valid queued copy can then be applied. A different manifest, including a change within the same timestamp, forces new preparation and verification. This independent check can download the full item again. The normal Project Zomboid startup verification should remain enabled.

Sentinel compares the Steamworks install size and timestamp with the isolated ACF. The manifest ID comes from the ACF installed-item record. Downloads are limited to configured IDs. Anonymous access can be denied for individual items. A failed, timed-out or inconsistent verification prevents application. SHA-256 detects corruption in the staged copy and is not a publisher signature.

The pre-start step journals directory and ACF replacements and restores interrupted transactions on the next run. Leave `PREPARED` transaction directories in place. Prune obsolete queue generations and completed backups while the server is stopped. The file lock beside the server cache coordinates opted-in Sentinel processes. Use a private cache because other programs do not coordinate with it.

See `steamworks-last-output.txt` in the staging directory and `WorkshopSentinel-prestart-*.log` next to the config. Limit settings control the helper's runtime and item size.
