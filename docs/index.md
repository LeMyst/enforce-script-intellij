---
title: Home
nav_order: 1
---

# Enforce Script for IntelliJ

IntelliJ IDEA language support for **Enforce Script**, the scripting language of DayZ mods
(`.c` files), focused on code navigation: go-to-declaration, find usages, `modded class` hierarchy,
structure view, and navigation from string literals into layouts, stringtables, imagesets and
inputs.

## Why this exists

Enforce Script has **no `#include` and no namespaces**. Every symbol lives in one flat global
namespace assembled from whole directories in a fixed module order (`1_Core` → `5_Mission`), ordered
between addons by `requiredAddons[]` in `config.cpp`. Finding where a symbol is really declared means
searching a mod's sources *plus* the ~3,000-file vanilla corpus *plus* every dependency mod. That is
what this plugin is for.

## Install

In IntelliJ IDEA 2025.2 or newer, open *Settings → Plugins → Marketplace*, search for
**Enforce Script** and install it. The plugin's page is on the
[JetBrains Marketplace](https://plugins.jetbrains.com/plugin/35712-enforce-script). Then see
[Getting started](getting-started.md).

## What is in this documentation

- [Getting started](getting-started.md): opening a mod, and which files the plugin claims.
- [Writing scripts](scripts/index.md): navigation and Quick Documentation, completion and inlay
  hints, string references, editing, generated code and templates, rename, inspections and
  `config.cpp`.
- [Game files](game-files/index.md): the layout preview, the texture viewer, imageset packing and
  extraction, and the stringtable editor.
- [Project setup](setup/index.md): where vanilla and the dependency mods come from, packed `.pbo`
  roots, the DayZ installation and the work drive.
- [DayZ tools](tools/index.md): DayZ Tools and Workbench, running a server and clients, game logs,
  checking scripts, building PBOs, the release build and Workshop publishing, new mods and addons.
- [Feedback and error reports](feedback.md).
- [Contributing](development/index.md): building, testing and the manual checks.
