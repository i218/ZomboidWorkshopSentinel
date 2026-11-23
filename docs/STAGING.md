# Isolated Workshop downloads (optional)

Public defaults are unchanged: `stagingEnabled=false`, `dryRun=true`. No Steam downloads, active-content replacements or server launches happen by default. This feature is server-side and does not require client installation. It does not grant trust to Java mods or change ZombieBuddy approvals.

## Setup

1. Use a **private Workshop directory for this dedicated server**, normally its `steamapps/workshop` directory. The server must have completed a normal Workshop startup once, so `appworkshop_108600.acf` exists. The server must load these items from that cache: local mods with the same IDs can shadow Workshop copies and are not replaced by this feature. Do not use a desktop Steam library or a Workshop directory shared with other running servers. All starts of this server must use the pre-start step below; an uncooperative Steam client or external server cannot be protected by our advisory file lock.
2. Install [Valve SteamCMD](https://developer.valvesoftware.com/wiki/SteamCMD) in `<config-directory>/staged-workshop/steamcmd` and initialize it with `+quit` once. This must be a separate SteamCMD installation, not the tool/cache used by the live server. SteamCMD is not bundled. On Linux, install its required runtime libraries and make its script executable.
3. In the existing Sentinel configuration, set:

```properties
dryRun=false
stagingEnabled=true
stagingDirectory=staged-workshop
stagingSteamCmd=
stagingWorkshopDirectory=C:/PZServer/steamapps/workshop
```

`stagingSteamCmd` is relative to `stagingDirectory`; empty chooses `steamcmd/steamcmd.exe` on Windows or `steamcmd/steamcmd.sh` elsewhere. Its parent must be exactly `<stagingDirectory>/steamcmd`. The staging and live Workshop roots must not overlap. `stagingWorkshopDirectory` is the directory containing the ACF, **not** `content/108600`. Relative paths other than `stagingSteamCmd` resolve against the configuration directory. Existing settings and unknown options are preserved during config upgrades. Shutdown/restart settings remain independent.

4. Run the supplied pre-start check **before the normal server JVM on every launch**, including launches made by a panel/supervisor. It deliberately does not start or restart the server. A failed check must stop your startup sequence.

Windows batch example (replace paths for your installation):

```bat
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "C:\PZMods\WorkshopSentinel\tools\BeforeServerStart.ps1" -Config "C:\PZData\WorkshopSentinel\servertest\WorkshopSentinel.properties" -ServerIni "C:\PZData\Server\servertest.ini" -Java "C:\PZServer\jre64\bin\java.exe"
if errorlevel 1 exit /b 1
call "C:\PZServer\StartServer64.bat" -servername servertest
```

Linux startup example:

```sh
JAVA=/opt/pzserver/jre64/bin/java sh /opt/pzmods/WorkshopSentinel/tools/before-server-start.sh.txt \
  /srv/pzdata/WorkshopSentinel/servertest/WorkshopSentinel.properties \
  /srv/pzdata/Server/servertest.ini || exit 1
exec /opt/pzserver/start-server.sh -servername servertest
```

Use a stable path to the tools in this mod's folder, outside the server Workshop cache that will be replaced. The scripts temporarily copy the bootstrap JAR before executing it, so a staged update of Sentinel itself does not replace a JAR still held by the pre-start JVM. This does not solve a server's missing automatic relaunch: that remains the supervisor's responsibility. Do not disable the game's own startup Workshop verification.

The Workshop package uses `.sh.txt` because the game's upload validator rejects `.sh`; invoking it with `sh` works without renaming it. Repository sources keep the `.sh` extension.

## Verification and race handling

- The normal check interval is retained. The optional worker compares public Steam timestamps with installed ACF timestamps, not just the current JVM's baseline. Changes already pending when the JVM starts are therefore still noticed. Downloads go to the dedicated SteamCMD cache and immutable staged generations, never the active content.
- Each receipt records the Workshop ID, Steam `time_updated`, opaque content manifest ID, UTC download/verification times, and SHA-256 of every file. Manifest IDs are compared for **equality**, not numerically sorted. Local UTC times are an audit trail, not a source of version ordering or proof of authenticity.
- SteamCMD uses anonymous login and `workshop_download_item 108600 ID validate`. Nonzero exit codes, missing success confirmations, inaccessible items, missing/inconsistent ACF entries, linked paths, oversized/empty content and changed hashes are rejected. Some items cannot be downloaded anonymously. SteamCMD's first initialization/self-update can return a nonzero exit code: finish initialization and retry; we never treat that as successful installation.
- On every pre-start, all currently configured IDs are freshly validated again with SteamCMD, including items already staged. A second native manifest check detects a newer publication even when two revisions have the same timestamp. Files are verified against their receipt before/after copying. A newer installed timestamp cannot be overwritten by an older staged timestamp. Already identical content is left in place; corrupt installed content is replaced from freshly validated content.
- Public metadata is queried before and after downloads and again around the swap. A detected publication race retries up to three times; an outage or persistent change refuses startup. An update published **after the last check** cannot be predicted. The game's own next startup verification remains the final check; keep it enabled.
- Replacements of item directories and the corresponding per-item ACF entries form one journalled transaction. Unknown ACF fields and other items are retained. Old directories and original ACF are retained for rollback. Ordinary I/O failure rolls back; after a process interruption the next pre-start recovers before proceeding. Corrupt ACF backups refuse recovery rather than silently restoring bad metadata. Per-file writes are flushed and renames are atomic on the same filesystem; this is not a guarantee against hardware failure or loss of filesystem durability.
- A shared lock in the live Workshop root excludes other Sentinel staging instances and is held while the opted-in server runs, including after a rejected shutdown request. This is not a lock respected by desktop Steam, unmodified PZ servers or arbitrary external programs.

SHA-256 detects accidental changes against our own receipt; it is not a publisher signature or protection from an attacker who can rewrite the staging directory and receipts. Restrict staging/Workshop directories and configuration to the server account and trusted administrators. No shell commands or arbitrary item IDs are taken from Steam metadata; downloads are limited to the configured `WorkshopItems`/explicit allowlist.

## Logs, recovery and storage

Use `WorkshopSentinel-prestart-*.log` next to the configuration and `<stagingDirectory>/steamcmd-last-output.txt` (bounded to 2 MiB) for download diagnostics. Runtime messages stay in Sentinel's own log. Defaults: 30-minute SteamCMD timeout per batch, 100 IDs per batch, 20 GiB per item, 100,000 files per item. Do not run SteamCMD manually in the staging directory while Sentinel owns it.

Snapshots under `ready`, failed preparations and `.workshopsentinel-txn-*` backups are intentionally retained; monitor free space. They are not automatically pruned in this release. After a verified normal startup and while the server is stopped, administrators may archive/remove completed or rolled-back transaction directories and obsolete staged generations. Never remove a `PREPARED` transaction; let the pre-start check recover it. Do not disable staging halfway through recovery. Do not put the runtime staging directory into the public Workshop upload folder.

Tests use real files, atomic renames and file locks with simulated Steam revisions, plus a separate real anonymous SteamCMD download/apply probe in a disposable directory. They do not establish a successful live server restart or a live self-update of a Java mod. The Linux wrapper is supplied but requires deployment testing on Linux.
