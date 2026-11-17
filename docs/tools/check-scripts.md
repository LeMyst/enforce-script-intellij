---
title: Checking scripts
parent: DayZ tools
nav_order: 4
---

# Checking scripts with the engine

*Tools → DayZ → Check Scripts (Server)* and *Check Scripts (Client)* (also in the toolbar's *DayZ*
drop-down) let DayZ itself compile the project's scripts, which catches what the plugin cannot,
such as a symbol the engine does not know. The server compiles with `SERVER` defined, so code in
`#ifndef SERVER`, most of a mod's UI, is checked only by the client.

- **What they start:** DayZ Diag on the mod list of a [DayZ run configuration](running-dayz.md): the
  selected one, or else the project's first. The project's addons are packed into PBOs as a run
  packs them, and the loose PBOs staged; *-filePatching* and the mods are the configuration's,
  *Server only* ones on the server only.
  - **Server:** `DayZDiag_x64.exe -server`, no game window, with a config written into a throwaway
    profile folder, on port 2402. Its mission names nothing that exists: the server is stopped once
    the scripts compile, before it would load one, so no mission's storage is written to.
  - **Client:** `DayZDiag_x64.exe -window` with a throwaway profile, joining nothing and playing no
    mission. A game window opens for about a game start's time and closes once the scripts compile.
- **The verdict:** as soon as the script log says the last module (`Mission`) compiled, or that a
  module could not be compiled, the game is stopped. The verdict appears in a *Run* tab and a
  notification, with each error as a link to its file and line. The profile folder is deleted.
- It takes about as long as a game start, so it is an explicit action. Cancelling the task stops the
  game, and it gives up after five minutes. Without a DayZ run configuration it says to create one.

**Not supported:** checking without DayZ Diag, both sides in one action, showing the errors as
annotations in open files, and telling apart an error in the project from one in a dependency mod
(both are listed).

The plugin's own checks, which run as you type, are its [inspections](../scripts/inspections.md).
