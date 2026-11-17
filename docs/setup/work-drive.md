---
title: Work drive
parent: Project setup
nav_order: 4
---

# Work drive

*Tools → DayZ → Mount Work Drive* and *Unmount Work Drive* mount the work-drive folder as its
letter (`P:` unless DayZ Tools says otherwise), and a status-bar entry shows `P: → <folder>` or
`P: not mounted`, with both actions in its popup. It hides itself when no work-drive folder is found.

- With DayZ Tools installed, its own `Bin\WorkDrive\WorkDrive.exe /Mount <folder>` and
  `/Dismount` do the work, so the result is what the DayZ Tools launcher would do; WorkDrive takes
  the letter from DayZ Tools' settings. Without DayZ Tools, `subst` does the same.
- A letter that is already a disk or a network drive is never mounted over or unmounted.
- After mounting, every open project's roots are refreshed, so `P:\scripts` resolves again without a
  restart.
- When the project indexes vanilla from the work drive, or a configured root is on it, and the
  drive is not mounted, a notification at project open says so and offers *Mount P:*. It stays
  quiet on a machine with no work-drive folder to mount.
- A mount belongs to the Windows session that made it: a drive mounted from an elevated process is
  invisible to a normal one, and the reverse. If the IDE runs elevated and DayZ Tools does not (or
  the other way round), each sees its own.
- **Not supported:** choosing the letter for WorkDrive.exe, which reads it from DayZ Tools' settings.

The folder and the letter come from the [DayZ installation](dayz-installation.md) settings.

## Extracting the game data

**Extracting the game data.** The vanilla roots (`P:\scripts`, `P:\DZ`, `P:\gui`) exist only once
DayZ Tools has extracted the game onto the work drive. *Tools → DayZ → Extract Game Data…* runs
DayZ Tools' own `WorkDrive.exe /extractGameData <DayZ folder> P:\`, after a confirmation naming the
source and the target, in a console window of its own (WorkDrive ends by waiting for a key, which it
can only read from a console). When that window closes, every open project's roots are refreshed and
a notification says whether `scripts`, `DZ` and `gui` are now on the drive.

When a project opens, and after mounting, a mounted drive without `scripts`, `DZ` or `gui` gets a
notification offering *Extract Game Data…*, *Use the DayZ install's PBOs instead* (adds the game's
`Addons` folder as a root, which navigates into vanilla without extracting anything) and *Don't ask
again*. It stays quiet while the drive is not mounted.

**Not supported:** noticing that the extracted data is older than the installed game.
