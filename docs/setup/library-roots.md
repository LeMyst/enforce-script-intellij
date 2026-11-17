---
title: Library roots
parent: Project setup
nav_order: 1
---

# Library roots

**Library roots** — the vanilla DayZ script tree, its `gui/layouts`, `gui/imagesets`,
`gui/looknfeel` and `languagecore` data directories, and any dependency mod sources are indexed, so
navigation reaches `PlayerBase`, `Widget`, `array`, vanilla layouts and the stringtable. Without
them fewer than half of all type references in a typical mod can resolve.

They are set under **Settings → Languages & Frameworks → Enforce Script**, and appear under
*External Libraries* in the *Project* view.

## Vanilla from the work drive

**Vanilla comes from the work drive.** On first open of a DayZ addon (a project containing a
`config.cpp`), the plugin indexes vanilla DayZ from the work drive: `P:\scripts`, the layouts,
imagesets and stringtable that string literals point at (`P:\gui\layouts`, `P:\gui\imagesets`,
`P:\languagecore`), and the configs in the unpacked game data (`P:\DZ`). The letter is the one in
[DayZ Installation](dayz-installation.md), so it follows the machine rather than the project. A
tree that is not there is skipped without a warning, since the drive may simply not be mounted, and
`P:\scripts` must hold `3_game` to count.

Two checkboxes at the top of the page turn this off, or leave out `P:\DZ`: only its `config.cpp`
files are indexed, but the first indexing walks the whole unpacked game once. An unrelated project
never gets the vanilla tree.

Mounting the drive and extracting the game data onto it: see [Work drive](work-drive.md).

## The root list

The **root list** below is for everything else: dependency mods, and a vanilla tree kept somewhere
other than the work drive. Dependency mod checkouts found next to the project are offered rather
than added unasked. The work drive comes first, then the list in its order, and that order is the
tiebreak when the same class name is declared in more than one root. A root the work drive already
covers, left over from an earlier version, counts once. The settings page warns before a game data
folder is added to the list by hand.

A root can also be a packed `.pbo` file, or a folder of them such as `!Workshop`: see
[Packed `.pbo` roots](pbo-roots.md).

## Community Framework stub

**A bundled Community Framework stub** — `GetRPCManager()`, `CallType` and `RPCManager`'s five
methods resolve without a real `@CF` checkout, which most machines never have (it is a Steam
Workshop dependency, not a plain source checkout). Deliberately narrow: it stubs only the symbols
mod code commonly references, taken verbatim from CF's real source, declared `proto` rather than
reproducing real bodies. It steps aside by itself when one of the roots holds CF's real sources (a
checkout of the repository at any level, or a folder containing one), so the two never offer
competing declarations of the same names.

## Trusted projects

The root list is saved in the project (`.idea/enfusion.xml`), so a cloned repository can ship one.
In a project opened in **Safe Mode** the plugin therefore uses none of it: no root is indexed and
auto-detection does not run, so navigation into vanilla and dependency sources stays empty. Trust the
project and reopen it to enable them.
