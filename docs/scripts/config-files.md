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

## Undeclared base classes

*Base class not declared in this scope* reports `class MyApple: Apple {}` when no `class Apple`
comes before it where Addon Builder looks. Navigation resolves `Apple` through every indexed
config, so nothing looks wrong, but CfgConvert binarises one `config.cpp` at a time and refuses
it: *Undefined base class 'Apple'*. Its lookup, checked by binarising a config for each case:

- a class of that name, full or external (`class Apple;`), declared **before** the derived class in
  the same body; one declared after it is not seen;
- else the same in each enclosing body, before the enclosing class, up to the top of the file; a
  sibling section (`CfgWeapons` for a class in `CfgVehicles`) is never searched;
- else a class an enclosing class inherits from its base, so `class DamageSystem: DamageSystem`
  works inside `class MyApple: Apple` when `Apple` declares a `DamageSystem`, and fails when `Apple`
  is only `class Apple;`;
- names ignore case.

*Add 'class Apple;'* inserts the external declaration at the top of the section, after the ones
already there; applied to the whole file, it adds every missing one. It is offered only for a class
directly in a top-level section such as `CfgVehicles`:

- **Deeper**, the missing base is usually a class the enclosing class's base was meant to provide.
  `class Apple;` added there would hide it rather than fix it.
- **At the top of the file**, `class Apple;` would name a top-level `Apple` that nothing declares.
  The class belongs in a section.

Only `config.cpp` is
checked, and only a base some indexed config declares: one found nowhere may be a dependency
outside the roots. **Not checked:** what the game does with an unbinarised `config.cpp` under file
patching.
