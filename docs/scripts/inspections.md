---
title: Inspections
parent: Writing scripts
nav_order: 6
---

# Inspections

**Inspections** for the mistakes the engine reports only at build time, or not at all. They are
under *Settings → Editor → Inspections → Enforce Script*:

| Inspection | Reports |
|---|---|
| *Redeclared method without 'override'* | a redeclared method missing `override` |
| *'override' with no base declaration* | the reverse: an `override` that overrides nothing |
| *Variable already declared in this method* | a name declared twice in one method — an error here even across disjoint `if` branches |
| *'modded class' patching nothing* | a `modded class` that patches nothing |
| *Variable named after a type* | a variable named after a type |
| *Dispatch string naming no method* | an `AddTimer` / `AddRPC` string naming no method on its target |
| *Localization key with no stringtable row* | a `STR_…` key with no stringtable row; see [Stringtables](../game-files/stringtables.md#from-the-code) |
| *Name from a later script module* | see [Script modules](#script-modules) below |
| *Malformed stringtable row* | see [Stringtables](../game-files/stringtables.md#malformed-rows) |
| *Base class not declared in this scope* | a `config.cpp` class whose base Addon Builder would not find; see [Config files](config-files.md#undeclared-base-classes) |

The two `override` ones carry quick fixes. Each is gated so that a class whose parent the plugin
never indexed produces no report at all, and the duplicate-local rule is measured against 120
vanilla files to prove it stays silent on code that compiles.

## Script modules

**Script modules** — DayZ compiles scripts in five modules, `1_Core` to `5_Mission`, and each one
sees only itself and the modules before it. *Name from a later script module* reports a type,
function, field or method used from a module that cannot see it, such as `PlayerBase`
(`4_World`) named from `3_Game`, which the game otherwise reports only at launch as *Can't compile
"Game" script module*.

A file's module comes from `CfgMods` → `defs` → `gameScriptModule` (and the other four) →
`files[]` in the addon's `config.cpp`, and otherwise from the standard folder names (`3_Game`,
`scripts/4_world`, ...).

One visible declaration is enough: a class declared in `3_Game` and modded in `4_World` can be named
from `3_Game`, but the members only the `4_World` layer adds cannot. A `modded class` is reported
too when only a later module declares its class (`modded class PlayerBase` in `3_Game`); such a
layer does not compile, so it does not make the class visible from its own module either.

`proto` declarations are not checked, since the engine's own classes are known to every module
(vanilla `1_core/proto/proto.c` returns `Object`, a `3_Game` class). Measured silent on the 2,838
vanilla files of `1_Core` to `4_World` and on 632 files of three large mods.

**Not supported:** a file in no known module (outside the five folders and not listed in any
`files[]`) is not checked, and completion still offers everything, visible or not.

## Checking with the engine

The engine itself can compile the project's scripts too, which catches what no inspection can: see
[Checking scripts](../tools/check-scripts.md).
