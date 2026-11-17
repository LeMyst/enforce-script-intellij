---
title: DayZ Tools and Workbench
parent: DayZ tools
nav_order: 1
---

# DayZ Tools and Workbench

*Tools → DayZ Tools* lists the desktop tools installed under DayZ Tools' `Bin` folder — the DayZ
Tools launcher, Workbench, Object Builder, Terrain Builder, TexView 2, ImageToPAA, the Material
Editor, Addon Builder, Publisher, the Central Economy Editor, BankRev and DSUtils — and starts the
one chosen. A tool that is not installed is not listed, and without DayZ Tools the menu is hidden.

On a file, the *Project* view and the editor tab menus offer *Open in TexView 2* (`.paa`, `.tga`,
`.png`; TexView cannot read `.edds`), *Open in Object Builder* (`.p3d`) and *Open in Material Editor* (`.rvmat`), which
start the tool with the file. The built-in [texture viewer](../game-files/textures.md) stays the
default for textures.

The installs are detected in the background when a project opens, so the menu is ready without
visiting the settings page.

## Workbench on the project's mod

**Workbench on the project's mod.** Workbench starts from its own folder, which it needs as its
current directory, as `WorkbenchApp.exe -filePatching "-mod=P:\MyMod\"`: `-filePatching` makes it
read the mod's unpacked files, and `-mod=` is the mod's folder on the work drive. That folder is
*Mod folder* under *Workbench* in the [DayZ Installation](../setup/dayz-installation.md) settings,
kept per project in `.idea/enfusion.xml`. Left empty, it is the project's own folder when the
project lies on the work drive. Otherwise it is the project's folder through the entry at the
drive's root that is that folder or holds it: the `P:\MyMod` link to a checkout elsewhere, which mod
setup scripts create. With neither, Workbench starts with `-filePatching` only.

Workbench mounts that folder at the root of the game's files, as the game does with an unpacked
mod. An addon in a `Scripts` folder directly at the mod's root therefore puts its `config.cpp` over
the game's `scripts\config.cpp`, and Workbench crashes as it starts, every time. In that case
the plugin shows a notification instead of starting it: it names the folder, asks for it to be
renamed, and offers *Start Workbench anyway*. `Scripts\Client\` is fine.

## Toolbar

**Toolbar.** In a project with an addon, the main toolbar (the navigation bar in the classic UI)
gets four buttons:

- *Build All PBOs* (see [Building PBOs](building-pbos.md)).
- The work drive: a toggle, pressed while it is mounted, that mounts or unmounts it. It follows the
  status bar's last check rather than checking at every update. See
  [Work drive](../setup/work-drive.md).
- *Start Workbench*: Workbench on the project's mod (see
  [above](#workbench-on-the-projects-mod)).
- *DayZ*: a drop-down with the installed tools and the rest of *Tools → DayZ*.

They are hidden in projects without an addon and on machines without Windows. *Show the DayZ buttons
in the main toolbar* in *Settings → Languages & Frameworks → Enforce Script → DayZ Installation*
hides them everywhere. *Customize Toolbar* removes a single one. Launching the game or a server is
the Run widget's job, through the [DayZ run configurations](running-dayz.md).
