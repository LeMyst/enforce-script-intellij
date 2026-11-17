---
title: Config files
parent: Writing scripts
nav_order: 7
---

# Config files

**`config.cpp` support** — a third language, covering `config.cpp`, `config.cpp.disabled` and
`mod.cpp` (claimed by exact file name, so it never competes with C++ over `.cpp`). Highlighting,
folding, brace matching, structure view, Find Usages and rename, plus the navigation that makes a
config worth opening:

- `class X : Y` resolves to Y's declaration in any indexed config;
- `requiredAddons[]` jumps to that addon's `CfgPatches` class — the mod load order, which exists
  nowhere else as a link;
- `inputs`, `picture` and `files[]` resolve to the file or folder they name.

`displayName="$STR_…"` (or `"#STR_…"`) and `ghostIcon="set:… image:…"` work through the
*existing* stringtable and imageset providers (see [String references](string-references.md)).

Vanilla's own configs are opt-in: add the unpacked game data (`P:\DZ`) to the roots and only its 207
configs are indexed, but they sit inside 24 GB of unpacked game that must be walked once, so the
settings page warns before adding it. See [Library roots](../setup/library-roots.md).

The `defines[]` of the project's configs feed `#ifdef` completion: see
[Completion](completion.md#ifdef-flags).
