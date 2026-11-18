---
title: New mods and addons
parent: DayZ tools
nav_order: 7
---

# New mods and addons

*File → New* (and *New* in the *Project* view's menu) has **DayZ Mod…** and **DayZ Addon…**, both
working in the folder chosen.

## DayZ Mod

**DayZ Mod:** a folder (empty for the chosen folder itself) with a `mod.cpp` (name, author,
version, tooltip, overview and action link) and, as ticked, a `LICENSE` (MIT, or GPL-3.0 from the
plugin's own licence text), a `README.md` and a `.gitignore` for built PBOs, signatures, private
keys, logs and the IDE's folder. *Then create its first addon* opens *DayZ Addon* in the new folder.

## DayZ Addon

**DayZ Addon:** an addon folder with a `config.cpp` that is right the first time.

- **Kind**, which sets the defaults below: *Scripts* (folder `Scripts`, modules `3_Game` to
  `5_Mission`, `DZ_Data` and `DZ_Scripts`), *Data* for models, textures and item configs
  (`Data`, no module, `DZ_Data`), *GUI* (`GUI`, with `layouts` and `imagesets` folders) and
  *Language* (`LanguageCore`, with a `stringtable.csv` holding vanilla's header). The dialog
  starts on the first kind whose folder the mod does not have yet; everything stays editable.
- **Prefix:** prefilled with the folder's path on the work drive, through the drive's link to the
  project as *Rescan Project Addons* finds it (`MyMod\Scripts`). Without a link, the path from the
  folder holding the project, since a checkout is usually named after its mod.
- **`CfgPatches` class:** `<Mod>_<Folder>` (`MyMod_Scripts`); class and prefix follow the folder's
  name until edited.
- **`requiredAddons[]`:** a searchable list of every `CfgPatches` class the project indexes,
  vanilla's and the dependency mods', with `DZ_Data` ticked, and `DZ_Scripts` when a script module
  is.
- **Script modules:** `1_Core` to `5_Mission`, `3_Game` to `5_Mission` ticked. Each ticked module
  gets its folder with a first script, and a `CfgMods` entry: `class defs { class
  gameScriptModule { value = ""; files[] = { "MyMod/Scripts/3_Game" }; }; … }`, with `dir`,
  `name`, `type = "mod"`, `dependencies[]` from the modules and optional `defines[]`.
- A `$PBOPREFIX$` file, ticked by default.

## Not supported

A new IDE project (both work in a folder of the current one), logos and pictures, which are added by
hand, and editing an existing `config.cpp` through a form. For a new `.c` file in an addon, see
[New Enforce Script file](../scripts/templates.md#new-enforce-script-file).
