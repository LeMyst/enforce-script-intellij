---
title: Game logs
parent: DayZ tools
nav_order: 3
---

# Game logs

The **DayZ Logs** tool window follows the game's logs: one tab each for `script_*.log`, the `.RPT`
and `crash_*.log` of every profile folder the game writes to.

- **Which profiles:** those of the selected [DayZ run configuration](running-dayz.md), named after
  their process (*Server*, then each player, or *Client* for a single unnamed one), as its *Profile*
  fields and players work them out. The tabs change with the selected configuration. With no DayZ
  configuration selected, the client profile (`%LOCALAPPDATA%\DayZ` unless set otherwise) and the
  server profile of the [DayZ Installation](../setup/dayz-installation.md) settings.
- Each tab shows the newest file of its kind and keeps up with it once a second; when the game
  starts again and writes a new file, the tab switches to it. *Open Older Run…* shows an earlier
  file instead, and *Follow Newest* goes back. A file over 8 MB opens on its last 8 MB.
- Errors are red and warnings amber. *Errors and Warnings Only* hides the rest, keeping the report
  that follows an error (the *Can't compile* summary, a stack trace) with it. *Collapse Repeated
  Lines* shows a line repeated back to back once, with a count, timestamps aside. The console's own
  search (`Ctrl+F`) works as usual.
- The three shapes of source location the game writes — `@"scripts/3_Game/human.c,17"`,
  `MyMod/Scripts/3_Game/x.c(1):` and the stack-trace `MyMod/Scripts/5_Mission/x.c:276 Function …` —
  are links. The path is the game's, from a PBO prefix or the work drive, so it is matched from the
  right against the project and its roots, like an asset path: a mod's own file opens in the
  project, a vanilla one under `P:\scripts`, and a dependency's in its PBO cache.
- **Not supported:** a *Problems* list of the last run's errors, and attaching to a running DayZ
  run configuration's output (the game writes nothing useful to it).
