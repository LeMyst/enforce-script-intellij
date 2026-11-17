---
title: Getting started
nav_order: 2
---

# Getting started

## Install

The plugin supports **IntelliJ IDEA 2025.2 and newer**. Open *Settings → Plugins → Marketplace*,
search for **Enforce Script** and install it, or install it from its
[Marketplace page](https://plugins.jetbrains.com/plugin/35712-enforce-script).

The DayZ tooling (the work drive, DayZ Tools, running the game) is detected from Steam and DayZ
Tools, so it works on Windows only. The language support works everywhere.

## Open a mod

Open the folder of a mod as a project. A project containing a `config.cpp` is a DayZ addon to the
plugin: on first open it indexes vanilla DayZ from the work drive, `P:\scripts` and the game data
next to it, and a balloon says so. Navigation into vanilla needs that tree:

1. When the work drive is not mounted, a notification at project open offers *Mount P:*. See
   [Work drive](setup/work-drive.md).
2. When the drive is mounted but holds no extracted game data, a notification offers
   *Extract Game Data…*, or *Use the DayZ install's PBOs instead*. See
   [Extracting the game data](setup/work-drive.md#extracting-the-game-data).
3. Add the dependency mods the mod builds on, as source checkouts, packed `.pbo` files or a whole
   Workshop folder. See [Library roots](setup/library-roots.md) and
   [Packed `.pbo` roots](setup/pbo-roots.md).

Then Ctrl+Click a vanilla class such as `PlayerBase`: its declaration opens under `P:\scripts`.

The plugin reads the library roots from the project only once the project is trusted. In
**Safe Mode** no root is indexed; see [Library roots](setup/library-roots.md#trusted-projects).

## Which files are Enforce Script

`.c` is also C. This plugin registers only `.es` by extension and claims `.c` **contextually** — a
`.c` file is treated as Enforce Script only when an ancestor directory contains a `config.cpp` or
`$PBOPREFIX$` marker. Ordinary C projects are untouched. This is not configurable yet.

The other two languages are claimed the same careful way:

- `.layout` and `.imageset` files are **Enforce Layout**. See [Layouts](game-files/layouts.md).
- `config.cpp`, `config.cpp.disabled` and `mod.cpp` are **Enforce Config**, claimed by exact file
  name, so the plugin never competes with C++ over `.cpp`. See [Config files](scripts/config-files.md).

The status bar names the language of the file in the editor.

## Next steps

- [DayZ installation](setup/dayz-installation.md): where DayZ, DayZ Server and DayZ Tools are, and
  the profile folders.
- [Running DayZ](tools/running-dayz.md): a server and clients from one run configuration.
- [New mods and addons](tools/new-mod.md): a `config.cpp` that is right the first time.
