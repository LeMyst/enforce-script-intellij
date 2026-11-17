# Enforce Script for IntelliJ

[![Build](https://github.com/LeMyst/enforce-script-intellij/actions/workflows/build.yml/badge.svg?branch=master)](https://github.com/LeMyst/enforce-script-intellij/actions/workflows/build.yml)
[![JetBrains Marketplace](https://img.shields.io/jetbrains/plugin/v/35712)](https://plugins.jetbrains.com/plugin/35712-enforce-script)

IntelliJ IDEA language support for **Enforce Script**, the scripting language of DayZ mods
(`.c` files), focused on code navigation: go-to-declaration, find usages, `modded class` hierarchy,
structure view, and navigation from string literals into layouts, stringtables, imagesets and
inputs.

Enforce Script has no `#include` and no namespaces: every symbol lives in one flat global namespace
spread across a mod, the ~2,800-file vanilla corpus and every dependency mod. The plugin indexes
all of them, so finding where a symbol is really declared takes one Ctrl+Click.

**[Read the documentation →](https://lemyst.github.io/enforce-script-intellij/)**

## Install

In IntelliJ IDEA 2025.2 or newer, open *Settings → Plugins → Marketplace* and search for
**Enforce Script**. Then open a mod and follow
[Getting started](https://lemyst.github.io/enforce-script-intellij/getting-started.html). The DayZ
tooling works on Windows, where DayZ Tools runs.

## Features

- **[Navigation](https://lemyst.github.io/enforce-script-intellij/scripts/navigation.html)**
  across every `modded class` layer and the superclass chain: types, members, locals, overrides,
  the class hierarchy and Find Usages.
- **[Completion and parameter info](https://lemyst.github.io/enforce-script-intellij/scripts/completion.html)**
  answered by the same type inference, with keywords offered by position and `#ifdef` flags from
  the project's `defines[]`.
- **[String references](https://lemyst.github.io/enforce-script-intellij/scripts/string-references.html)**:
  widget names into `.layout` files, stringtable keys, imageset images, RPC and timer names, and
  keybind actions navigate.
- **[Inspections](https://lemyst.github.io/enforce-script-intellij/scripts/inspections.html)**
  for what the engine reports only at build time or not at all, such as a missing `override`, a
  dispatch string naming no method, or a name from a later script module.
- **[Rename](https://lemyst.github.io/enforce-script-intellij/scripts/rename.html)** that
  keeps `modded class` layers, overrides and `FindAnyWidget` strings in step, and never rewrites
  vanilla.
- **[`config.cpp`](https://lemyst.github.io/enforce-script-intellij/scripts/config-files.html)**
  as a language of its own: `requiredAddons[]`, class inheritance and file paths navigate.
- **[Layout preview](https://lemyst.github.io/enforce-script-intellij/game-files/layouts.html)**
  beside the text, drawn in the game's widget styles.
- **[Texture viewer](https://lemyst.github.io/enforce-script-intellij/game-files/textures.html)**
  for `.edds` and `.paa`, with previews on hover and in the gutter, and
  [imageset packing](https://lemyst.github.io/enforce-script-intellij/game-files/imagesets.html).
- **[Stringtable editor](https://lemyst.github.io/enforce-script-intellij/game-files/stringtables.html)**
  that shows what each language still needs translated.
- **[Dependencies from PBOs](https://lemyst.github.io/enforce-script-intellij/setup/pbo-roots.html)**:
  a packed `.pbo` or a whole Workshop folder navigates like a source checkout.
- **DayZ tooling:**
  [the work drive](https://lemyst.github.io/enforce-script-intellij/setup/work-drive.html),
  [DayZ Tools and Workbench](https://lemyst.github.io/enforce-script-intellij/tools/dayz-tools.html),
  [a server and clients from one run configuration](https://lemyst.github.io/enforce-script-intellij/tools/running-dayz.html),
  [game logs](https://lemyst.github.io/enforce-script-intellij/tools/game-logs.html),
  [checking scripts with the engine](https://lemyst.github.io/enforce-script-intellij/tools/check-scripts.html),
  [building PBOs](https://lemyst.github.io/enforce-script-intellij/tools/building-pbos.html),
  [the release build](https://lemyst.github.io/enforce-script-intellij/tools/release-build.html)
  and [new mods and addons](https://lemyst.github.io/enforce-script-intellij/tools/new-mod.html).

## Feedback

Bug reports and feature requests are welcome in the
[issues](https://github.com/LeMyst/enforce-script-intellij/issues). In the IDE, *Help → Report
Enforce Script Issue* opens one with your plugin, IDE and OS versions filled in. See
[Feedback and error reports](https://lemyst.github.io/enforce-script-intellij/feedback.html) for
what an error report sends.

## Contributing

[AGENTS.md](AGENTS.md) holds the development rules and the release process.
[Contributing](https://lemyst.github.io/enforce-script-intellij/development/) covers building,
testing and the manual checks. The documentation is the [`docs/`](docs) folder: a change to
user-visible behaviour updates its page in the same pull request.

## License

Copyright © the enforce-script-intellij contributors.

This plugin is free software: you can redistribute it and/or modify it under the terms of the
GNU General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version. It is distributed in the hope that it will be
useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
FOR A PARTICULAR PURPOSE. See [LICENSE](LICENSE) for the full text (`GPL-3.0-or-later`).

**Third-party material.** The bundled Community Framework stub
(`src/main/resources/stubs/cf/RPCManager.c`) is derived from
[DayZ-CommunityFramework](https://github.com/Arkensor/DayZ-CommunityFramework) and remains under
that project's GNU Lesser General Public License v2.1.

Unofficial community project, not affiliated with or endorsed by Bohemia Interactive. DayZ and
Enfusion are trademarks of Bohemia Interactive a.s. No game files are included in this repository
or in the plugin.
