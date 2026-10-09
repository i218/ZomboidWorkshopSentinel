# WorkshopSentinel

**English** | [Русский](README.ru.md)

A mod for **Project Zomboid Build 42 Dedicated Server** with ZombieBuddy installed. It monitors Workshop updates and decides when to request a restart. The same package provides automatic client update notifications.

**Dry-run is enabled by default: the server keeps running.**

## One mod for the client and server

**WorkshopSentinel 0.4.7 uses one folder, one mod ID, and one package.** It contains a Java server monitor, a Lua client update checker, and support for the server settings editor with MLOS. The runtime environment determines which code runs.

### Windows installation

1. Close the game or server, then run **WorkshopSentinel-Setup.exe**.
2. Select your **Zomboid data folder**, usually `%USERPROFILE%/Zomboid`. If you use `-cachedir`, select that folder. This is the data folder, not the game installation folder.
3. Choose installation for the client, server, or both. For a server, select an existing profile, such as `servertest1`.
4. Click **Install** (currently labelled **Установить**) and start the game or server again. The installer enables the client mod and adds ZombieBuddy and WorkshopSentinel to the selected server's `Mods` list automatically.

The JAR is embedded in the EXE, validated before installation, and checked by SHA-256 after extraction. No separate JAR download or copying is needed. Existing configuration is preserved; a new configuration uses `dryRun=true` and `shutdownEnabled=false`. The server name is read from `GameServer.serverName`, so no extra Java argument is required.

The selected server `.ini` and client `mods/default.txt` are updated with backups. Other mods, maps, `WorkshopItems`, and settings are preserved. Existing mod files are backed up under `<data folder>/WorkshopSentinel-installer-backups/` and replaced individually without moving the mod folder. Files changed during a failed deployment are restored; if restoration is also blocked, the installer reports the backup location.

Installation stops before replacing files if `ServerAutoUpdate_B42` is enabled or the settings cannot be parsed unambiguously. A report is saved to `<data folder>/WorkshopSentinel-installation.txt`.

If installation fails, the dialog shows the operation, exact path, and location of a `WorkshopSentinel-setup-error-*.txt` report in the system temporary folder. Close the game and server before retrying: administrator privileges do not release locks on open files.

### Manual installation and Linux

Extract **WorkshopSentinel-mod.zip** into `<data folder>/mods/`. It contains the same unified mod as the installer. Replace any old copy with the same mod ID rather than keeping both copies.

### ZombieBuddy signing

Signing is deferred; the current release is unsigned. You do not need to change your Steam profile. Approve the JAR through ZombieBuddy's normal approval prompt when requested.

An older `.jar.zbs`, if installed, is moved to a backup folder outside `mods`. The private signing key is stored separately for possible future use and is excluded from Git and distribution packages. See the [deferred signing notes](docs/SIGNING.md).

### What runs in each environment

- **Client:** the Workshop checker and settings editor support in `media/lua/client`. MLOS support keeps an existing local WorkshopSentinel mod in the saved configuration without inventing a Workshop ID. These features do not need a client Java agent.
- **Dedicated server:** the Java bootstrap at `media/java/WorkshopSentinel.jar`, loaded through ZombieBuddy, and the server Lua bridge. The monitor checks `GameServer.server` on the first server tick before starting. Client Lua does not run there.

Since 0.4.1, the JAR uses a neutral path instead of `media/java/server`. In the supplied Build 42.21.0 server log, ZombieBuddy 2.3.4 incorrectly skipped the server-only JAR during dedicated server startup. With ZombieBuddy on the client, the minimal Java bootstrap may now load and request approval there too, but it does not start monitoring, a scheduler, or shutdown. The client UI works without a Java agent.

The installer disables discovery of legacy `.WorkshopSentinel-install-*` copies with the WorkshopSentinel ID by moving only their `42/mod.info` into a backup folder. It preserves the remaining files and does not alter other mods.

For MLOS support, enable WorkshopSentinel in the main Mods menu; the client installer does this automatically. The log should show `MLOS compatibility enabled: local mod retained without Workshop ID`. MLOS files are not modified.

`clientOptional=true` removes only WorkshopSentinel from the server's advertised client requirements after the first tick. Connecting without the unified mod and Lua checksum behavior **still need verification in a real game session**. A local mod has no Workshop ID; adding a published item to `WorkshopItems` may require downloading it independently of the `Mods` list.

The package is ready for local installation. It has not been published to Steam Workshop.

## Client update checking

Checks run automatically on entering the main menu and then every **20 minutes**. New changes open a compact window with a mod list and details. There is no Check button. Dismissing a notification prevents the same changes from opening it again.

New checks and popups are deferred during gameplay until you return to the menu. The **Mod updates** menu button opens existing results without querying Steam. **Mod Options → WorkshopSentinel** lets you disable automatic popups or change the filter; no configuration is needed for normal operation.

Only active Workshop mods are checked; local mods are skipped. The window shows versions from `mod.info`, Steam state, the Steam update date, and a local `ChangeLog.txt`. It can open the item in the Steam Overlay or copy its link. English and Russian UI text are supported. The game reader loads changelogs from the mod's version folder or `common`, and the UI displays them as plain text.

The first successful check establishes a baseline. Later checks compare Steam timestamps and `mod.info` versions; Steam's `NeedsUpdate` state is shown separately. A changed timestamp can reflect Workshop metadata, so the list does not prove that code changed or that a new build has been downloaded.

Successful checks save the cache to `<data folder>/Lua/WorkshopSentinel-client-cache.txt`. Steam errors or incomplete results do not overwrite it. A new session compares against the last successful check from the previous session.

Requests use batches of up to 100 unique Workshop IDs, spaced 4 seconds apart. Failed checks and empty lists wait for the next scheduled check instead of retrying continuously. Response timeout is 30 seconds. The checker is informational: it does not download files, restart the game, or send commands to the server.

This is an independent implementation of features described by [Workshop Update Checker](https://steamcommunity.com/sharedfiles/filedetails/?id=3628835042). Its code and assets are not included. The original description specifies checks triggered by a button and about 4 seconds between batches, but no fixed automatic interval. The 20-minute interval was chosen for WorkshopSentinel. WorkshopUpdateCheck is not required; enabling both may produce duplicate notifications.

## Conflicts and load order

`mod.info` defines `loadModAfter=\ZombieBuddy,\ModLoadOrderSorter_b42` and `incompatible=\ServerAutoUpdate_B42`.

[Server AutoUpdate](https://steamcommunity.com/sharedfiles/filedetails/?id=3756814990) and [Server AutoUpdate - Update Restart](https://steamcommunity.com/sharedfiles/filedetails/?id=3781306534) both use the mod ID `ServerAutoUpdate_B42`, so one conflict entry covers both. Avoid running independent restart controllers together. Disable ServerAutoUpdate_B42 in the client and server mod lists before installing WorkshopSentinel. The installer rejects this conflict rather than silently removing it.

## Server restart logic

1. Check items from the server's `WorkshopItems` every **5 minutes**.
2. If an update is detected and the server is empty, write a restart request.
3. If players are online, check the player count every **minute**.
4. After **30 minutes** of waiting, warn players in chat and allow another **5 minutes** to leave safely.

If the server empties earlier, a restart request is made at the next player check. Steam errors do not reset the waiting period; a player-count error postpones shutdown.

## Manual server installation

On Windows, use **WorkshopSentinel-Setup.exe** to install the embedded JAR, Lua files, translations, and configuration automatically. The steps below are for manual installation and Linux. The server must already have the ZombieBuddy Java agent installed; approve the JAR through ZombieBuddy if prompted.

### 1. Extract the archive

Extract it into the server's `mods` folder:

```text
<PZ cachedir>/mods/WorkshopSentinel/
├── 42/
│   ├── mod.info
│   └── media/java/WorkshopSentinel.jar
└── common/
```

`<PZ cachedir>` is the server data folder, usually `Zomboid` in the user's home directory. Use the specified path if the server starts with `-cachedir`.

### 2. Enable the mod

Add `WorkshopSentinel` to `Mods` in `<PZ cachedir>/Server/<server name>.ini`. ZombieBuddy must also be enabled.

For a server using backslash-prefixed mod IDs:

```ini
Mods=\ZombieBuddy;\WorkshopSentinel
```

Preserve the server's other mods and ID syntax. `WorkshopItems` should contain numeric IDs for the items to monitor. Local WorkshopSentinel has no Workshop ID of its own.

ZombieBuddy must load as a Java agent when the dedicated server starts. Approve your WorkshopSentinel JAR if prompted.

### 3. Copy the configuration

Copy `config/WorkshopSentinel.properties` from the archive to:

```text
<PZ cachedir>/WorkshopSentinel/<server name>/WorkshopSentinel.properties
```

For the first run, keep:

```properties
dryRun=true
shutdownEnabled=false
provider=steam
```

The mod reads the running server name from `GameServer.serverName`. For unusual setups, you can override it with a **Java** argument:

```text
-Dworkshopsentinel.serverName=YOUR_SERVER_NAME
```

For a custom data directory, use `-Dworkshopsentinel.cachedir=PATH`. Place `-D...` arguments before the server main class.

### 4. Start the server and check the log

Expected messages include:

```text
Java bridge registered
Started dryRun=true
Workshop check completed
```

Logs are stored beside the configuration: `WorkshopSentinel-0.log` and its rotations. When an update requires a restart, `restart-marker.properties` records the request.

## Main settings

All intervals below are in **seconds**.

| Setting | Default | Purpose |
|---|---:|---|
| `checkIntervalSeconds` | `300` | Check Workshop every 5 minutes |
| `playerPollSeconds` | `60` | Check players every minute |
| `maxWaitSeconds` | `1800` | Wait 30 minutes before warning |
| `countdownSeconds` | `300` | Allow 5 minutes after the warning |
| `dryRun` | `true` | Exercise the logic without stopping the server |
| `shutdownEnabled` | `false` | Allow the shutdown adapter to run |
| `clientOptional` | `true` | Remove WorkshopSentinel from client requirements |
| `shutdownAdapter` | `marker` | Only record a restart request |
| `provider` | `steam` | Steam; use `file` for simulation or `noop` for no updates |

Restart the server process after changing configuration. Relative paths are resolved from the configuration folder. In Windows `.properties` paths, use `/` or doubled backslashes.

## Quick test without stopping the server

On a separate test server, use:

```properties
dryRun=true
shutdownEnabled=false
provider=file
workshopIds=3619862853
checkIntervalSeconds=5
playerPollSeconds=2
maxWaitSeconds=10
countdownSeconds=5
```

Start without `simulate-update.txt`. Then create that file beside the configuration and put `3619862853` in it.

- **No players:** a marker appears with `reason=empty-server`.
- **Player online:** a warning appears about 10 seconds after detection, followed 5 seconds later by a marker with `reason=forced-countdown-complete`.
- **Player leaves earlier:** the next player check records `reason=empty-server`.

The server keeps running in every case. To test another scenario, remove the simulation file and start a new JVM: the mod ends the current monitoring cycle after a restart request.

After testing, restore `provider=steam` and intervals `300`, `60`, `1800`, `300`.

## Before production use

**Steam detection:** the first successful response establishes a baseline. By default, the server monitor detects subsequent changes rather than comparing Steam with installed files. An update before the first check may be missed; Workshop description changes may also change the monitored timestamp.

**Restarting:** the default `marker` adapter only records a request. Actual shutdown requires explicitly enabling `pz-quit-experimental`; its API still needs verification on your B42 version. An external supervisor must restart the process.

**Compatibility:** builds and automated tests are checked separately from the game. Loading through ZombieBuddy, chat messages, and shutdown in a real PZ server have not yet been confirmed by a game smoke test.

See the [technical reference](docs/TECHNICAL.md) for additional settings and experimental shutdown instructions, and [VERIFICATION.md](VERIFICATION.md) for verification results. These supporting documents are currently in Russian.

## Building from source

Use **JDK 11–21** with `JAVA_HOME` configured. This is the build requirement; run the server with the Java runtime supplied with your PZ version.

From the project folder:

```powershell
.\gradlew.bat clean build modZip
```

On Linux, run `sh gradlew clean build modZip`. The first run downloads Gradle 8.7. No additional Java libraries are required.

Build outputs:

- **Windows installer:** after building the archive, run `powershell -NoProfile -File installer/build.ps1`. Output: `build/installer/WorkshopSentinel-Setup.exe`. This uses the .NET Framework 4.x compiler bundled with Windows.
- **Mod archive:** `build/distributions/WorkshopSentinel-mod.zip`.
- **JAR:** `42/media/java/WorkshopSentinel.jar`.

Both English and Russian READMEs are included in the mod archive and installer.

The build runs behavioral tests automatically. Run them separately with `gradlew.bat selfTest`. To probe the public Steam API, use `gradlew.bat steamProbe -PprobeIds=3619862853`.

For client Lua tests on Windows:

```powershell
powershell -NoProfile -File installer/test-client.ps1 -GameDirectory "PATH_TO_ProjectZomboid"
```

Set `JAVA_HOME` to JDK 11 or newer. These tests use the installed game's Kahlua runtime and standard library with Steam/UI test doubles; they do not start the game.

For an in-game check, enable WorkshopSentinel, open **Mod updates**, disable **Show changed mods only**, and inspect the list, version, changelog, and both link actions. After the first successful scan, the cache should appear in the data folder's `Lua` directory. On the next run, new timestamps or versions should be marked as changes. Normal installation does not create simulated update events.
