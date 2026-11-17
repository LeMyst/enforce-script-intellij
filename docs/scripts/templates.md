---
title: Templates
parent: Writing scripts
nav_order: 9
---

# Templates

## Live templates

**Live templates** (type the abbreviation, then `Tab`) in an *Enforce Script* group, each offered
only where it makes sense. They are under *Settings → Editor → Live Templates → Enforce Script*,
where they can be edited and new ones added with the same scopes.

| Abbreviation | Where | Expands to |
|---|---|---|
| `modc` | file scope | `modded class` with the caret on the class name, completing class names |
| `menu` | file scope | a `UIScriptedMenu`: `Init` creating its layout and returning `layoutRoot`, `OnShow`, `OnHide`, `Update` and `OnClick`, each calling `super` |
| `weh` | file scope | a `ScriptedWidgetEventHandler` set on a root widget, with `OnClick` returning `bool` |
| `onrpc` | class body | an `OnRPC(PlayerIdentity sender, int rpc_type, ParamsReadContext ctx)` override with its `super` call and a `switch` on the RPC type |
| `ifs` / `ifc` | anywhere | `#ifdef SERVER` / `#ifndef SERVER` … `#endif`; also in *Surround With* (`Ctrl+Alt+T`) |

The scopes are *File scope*, *Class body* and *Statement*, under the *Enforce Script* context, and
never inside a comment or a string. The scope is read from the code before the caret rather than
from the parse, so a half-written member, which the grammar cannot recover from, still counts as
inside its class.

The code follows vanilla's style (tabs, Allman braces, `m_` fields) and is written from the
signatures alone, nothing copied from vanilla bodies. For overriding a given method, *Override
Methods* (`Ctrl+O`) writes its signature exactly.

## New Enforce Script file

*File → New → Enforce Script File* (and *New* in the *Project* view's menu) creates a `.c` file
from a template: a *Class* with its constructor and destructor, a *Modded class*, or a *Scripted
menu* whose layout path starts as `<addon prefix>/GUI/layouts/<class_name>.layout`.

- It is offered only in a folder inside an addon (with a `config.cpp` or `$PBOPREFIX$` above it),
  since anywhere else a `.c` file is C.
- A file created in no script module (not under `1_Core` to `5_Mission`, nor listed in the addon's
  `CfgMods` `files[]`) gets a warning: the game never compiles it.
- The templates are under *Settings → Editor → File and Code Templates → Files*, as *Enforce
  Script Class*, *Enforce Script Modded Class* and *Enforce Script Scripted Menu*.
