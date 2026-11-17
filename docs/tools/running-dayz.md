---
title: Running DayZ
parent: DayZ tools
nav_order: 2
---

# Running DayZ

A **DayZ** run configuration (*Run → Edit Configurations → + → DayZ*) starts a server, clients,
or both, with **one mod list**: a multiplayer mod is tested with the server and its clients loading
the same mods. It uses the folders in the [DayZ Installation](../setup/dayz-installation.md)
settings, and everything it starts runs in one *Run* tab, where *Stop* stops it all.

## What to start

*Start a server* and *Start clients*, either or both.

- With both, the server starts first. The clients follow *Start the clients … seconds after the
  server* later (5 by default) and join it on `127.0.0.1` at *Port*.
- Each line of output starts with `[Server]` or the player's name.

## The editor

- **At the top, what both use:** *Use DayZ Diag*, the mods folder, the mod list,
  `-filePatching`, *Port* and *Extra arguments*.
- **Clients:** the players, their profiles, `-window`, *Connect to* and *Password* (to join a
  server the run does not start), *Offline mission* (to play without a server), Goldberg and the
  delay.
- **Server:** its config, its profile, its mission (DayZ Diag) and the storage wipe.
- Fields that cannot apply are greyed out: *Connect to* and *Offline mission* while the run
  starts a server, and each section while its box is unticked.
- The browse buttons pick a `.cfg` for the server config and folders for the profiles and
  missions.

## Checks before starting

- A server needs its config: without `-config=` DayZ counts down ten seconds and quits, so an
  empty *Server config* is an error.
- A warning when the server and a client would write to the same profile folder.
- A warning when the *Offline mission* is a server mission. Its `init.c` defines
  `CreateCustomMission` and never makes a `MissionGameplay`, as every `dayzOffline.*` mission
  returns `new CustomMission()`. Offline, such a mission never gives the client a character, and
  it sits on *IDLE MODE ACTIVE*. An offline test mission returns `new MissionGameplay()` when not
  on a multiplayer server. The warning is also the first line of the run's console.
- A configuration that reads from a drive that is not mounted (the mods folder, a mod, the profile
  or the mission on `P:`) refuses to start and says so, rather than starting the game without its
  mods.

## Command lines

- **Server:** the dedicated `DayZServer_x64.exe`, or `DayZDiag_x64.exe -server` with DayZ Diag.
  It gets `-config=`, `-port=`, `-profiles=` (the server profile from the settings when empty),
  `-mod=`, `-serverMod=`, `-filePatching`, and `-mission=` for a Diag server.
- **Clients:** `DayZDiag_x64.exe` (or `DayZ_x64.exe` with *Use DayZ Diag* off). Each gets
  `-mod=`, `-profiles=`, `-name=`, `-filePatching` and `-window`, then either `-connect=` /
  `-port=` / `-password=` to join a server, or, with no server to join, `-mission=` to play
  offline.

## The mod list

**The mod list** is a table in load order. Each row has a checkbox: an unticked mod is kept but
not passed, so it can be turned off and on again. *Server only* rows go to the server's
`-serverMod=` and to no client. The toolbar adds three kinds of row.

A configuration saved with the older `;`-separated *Mods* and *Server mods* fields opens with
them as rows. Extra arguments are split on spaces outside quotes.

### Mods

- *Add Mods…* lists the `@` folders of the *Mods folder* and of the game's `!Workshop` (where
  the DayZ launcher links subscribed Workshop mods), one checkbox each.
- *Add Folder…* takes a mod folder anywhere.
- A bare name such as `@CF` is taken from the *Mods folder*.

### The project's addons

*Rescan Project Addons* adds every `config.cpp` folder of the project, as *Build All PBOs* finds
them, and its `@` folders.

- **Packed at launch:** the ticked addons are packed into a generated `@<Project>` mod, which
  takes the first addon row's place in `-mod=`.
  - Each addon is one PBO named after its folder (`Scripts\Client` → `Scripts_Client.pbo`).
  - Files are stored as they are, as Addon Builder's *pack only* does. Sources, backups, logs,
    `.png`, `.psd`, `.tga` and `source` folders are left out, as mod build scripts do.
  - The mod's `mod.cpp` is copied beside the PBOs.
- **Only what changed:** a manifest of each addon's files (path, size, date) is kept, and an
  addon is packed again only when a file was added, removed or edited, or its PBO is missing.
  PBOs of addons no longer ticked are removed. The console's first line says what was packed.
- **Prefixes:** an addon's prefix is the one its `$PBOPREFIX$` declares, or else its path on
  the work drive, through the link at the drive's root that mod setup scripts create
  (`P:\MyMod` → the checkout): `P:\MyMod\Scripts` is packed as `MyMod\Scripts`. Without such a
  link the row keeps its own path, the prefix is the folder's name, and the configuration
  warns.
- **Why packed:** the game mounts an unpacked `-mod=` folder at the root of its file system.
  There, a mod's `LanguageCore\stringtable.csv` or `Scripts\4_World\…\WeaponManager.c`
  replaces vanilla's file of the same path, so vanilla texts go untranslated or vanilla classes
  disappear. Packed, everything sits under its prefix.
- **File patching** is optional. With `-filePatching`, DayZ Diag reads loose files on the
  work drive over their packed copy, so a script edit needs no repack. A server must allow it
  (`allowFilePatching = 1` in its config), or it refuses the clients that use it ("The server
  does not accept the client's current filePatching setting"). The configuration warns when it
  can read a server config that does not allow it.
- **Rescan:** a rescan adds new addons, ticked, and drops those whose folder is gone. It never
  re-ticks a row you unticked, and leaves the other rows alone.

### PBOs

*Add PBO Files…* adds loose `.pbo` files, such as third-party PBOs a mod ships inside its own.

- The game loads PBOs only from a mod's `Addons` folder, so at launch they are copied, with
  their `.bisign` files, into a generated `@ExtraPBOs\Addons` in the IDE's system folder, which
  takes the first PBO row's place in `-mod=`.
- Only changed files are copied again, and files no longer listed are removed.
- A server checking signatures also needs their `.bikey` files in its `keys` folder; the plugin
  does not copy those.

## Before launch

**Before launch**, each off by default: stop every running DayZ, DayZ Diag and DayZ Server
(including a server another configuration started); clear the profile's `.log`, `.rpt`, `.mdmp`
and `.ADM` files; for a server, delete the mission's `storage_*` folders so that the loot spawns
afresh. Only the folder's own files are deleted, never its subfolders, and a drive root or the
home folder is refused. What was done is printed at the top of the console.

## Several local clients

**Several local clients:** the *Players* table has one row per client: its name (`-name=`), its
Goldberg Steam ID, and its profile.

- **Starting:** one run starts every row, one after the other.
- **Profiles:** each client needs its own profile. An empty one is a folder of the player's name
  under *Profiles*, or under the game's default profile.
- **Steam identity:** with the real Steam client every local client is the same Steam user, and
  the server kicks one when the next joins.
  - *Write a Goldberg Steam emulator identity for each player* writes each player's name and Steam
    ID to `%APPDATA%\Goldberg SteamEmu Saves\settings` (`account_name.txt`, `user_steam_id.txt`)
    just before that client starts, as mod launch scripts do.
  - Each client waits until the previous one has had 4 seconds to read its identity.
  - An empty Steam ID is derived from the name.
  - The emulator's `steam_api64.dll` in the game's folder is yours to install; the plugin only
    writes the identity.

## Stopping and limits

Stopping kills the process. The password is saved with the configuration in plain text.

**Not supported:** binarising (configs stay `config.cpp`, models and terrains are not
binarised) and signing the packed addons; for those, build with Addon Builder (see
[Building PBOs](building-pbos.md) and [Building the mod for release](release-build.md)). Starting
Buldozer (Object Builder's viewer) is left for later.

The run's profile folders feed the [DayZ Logs](game-logs.md) tool window, and its mod list is the
one [Checking scripts](check-scripts.md) compiles.
