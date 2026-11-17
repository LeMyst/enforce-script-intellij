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
- and a keybind action name (`UA...`, always behind a `const string`) to its `<input name="...">`
  declaration in `Inputs.xml`.

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
