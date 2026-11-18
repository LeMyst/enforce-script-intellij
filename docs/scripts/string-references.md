---
title: String references
parent: Writing scripts
nav_order: 3
---

# String references

**Go to Declaration from string literals** — the other half of how this language is wired together.
Ctrl+Click resolves:

- a widget name in `FindAnyWidget(...)` to its declaration in whichever `.layout` the enclosing
  class loads;
- a `.layout` / `.imageset` / asset path (including ones built by `+` concatenation through
  `const string` constants) to the file;
- a stringtable key (`"#STR_MYMOD_..."`, any other `"#key"` such as vanilla's `"#menu_add"`, the
  bare server-side `"STR_…"`, or a config's `"$STR_…"`) to its row in `stringtable.csv`, ignoring
  case as the game does, the vanilla stringtable included although it is too large for the platform
  to index;
- each half of `"set:x image:y"` to the `ImageSetClass` and `ImageSetDefClass` respectively;
- a dispatch-by-name string (`AddRPC`, `SendRPC`, `AddTimer`, `GameScript.Call`, ...) to the target
  method, inferring the receiving class where the call shape carries one;
- a keybind action name (`UA...`, always behind a `const string`) to its `<input name="...">`
  declaration in `Inputs.xml`;
- and an item class name to its config class: see [Config class names](#config-class-names) below.

Completion inside these strings is scoped the same way (e.g. widget names only from the layouts the
class actually loads).

The same strings carry more:

- Hovering a texture path or an imageset image shows the image, and a gutter icon marks the line:
  see [Textures](../game-files/textures.md).
- A stringtable key with no row is reported, with a quick fix that creates the row: see
  [Stringtables](../game-files/stringtables.md#from-the-code).
- A dispatch string naming no method is reported: see [Inspections](inspections.md).
- Renaming a widget in a `.layout` updates every `FindAnyWidget("…")` that names it: see
  [Rename](rename.md).
- In `config.cpp` the same stringtable and imageset strings resolve: see
  [Config files](config-files.md).

## Config class names

Item class names are the main link between scripts and configs, and that link is a string. They
navigate now:

- **The class-name argument** of `CreateObject`, `CreateObjectEx`, `CreateInInventory`,
  `CreateInHands`, `CreateEntityInCargo(Ex)`, `CreateAttachment(Ex)`, the `SpawnEntity…` helpers,
  and both arguments of `IsKindOf`, resolve to the top-level class of that name in `CfgVehicles`,
  `CfgWeapons` or `CfgMagazines`. Every declaration is offered, since mods override an item by
  declaring it again; a class only forward-declared (`class Apple;`) resolves to that.
- **Config paths** in `ConfigGetFloat`, `ConfigGetInt`, `ConfigGetText`, `ConfigIsExisting` and the
  other `ConfigGet…` calls resolve word by word: `"CfgAmmo Bullet_9x19 hit"` gives the section,
  the class in it, then the property or subclass, found in the class or one of its bases. A path
  built with `+` resolves the words of its first literal, so `"CfgVehicles " + type + " weight"`
  gives the section only. Names ignore case, as the engine does (vanilla writes `"cfgAmmo "`).
- **Completion** in those strings offers the classes of the sections, from an index rather than
  by reading every config. In a spawn call it leaves out the classes that state `scope` 0 or 1,
  which cannot be spawned.
- **Find Usages and rename** of a config class include these strings.

Vanilla's configs are opt-in (`P:\DZ`, or the game's `Addons` folder as a PBO root): without them
the vanilla item names resolve to nothing, and nothing is reported. **Not covered:** a class name
held in a `const string` (the reference sits on the constant's literal, which is not a call
argument), and an inspection for names with no config class.
