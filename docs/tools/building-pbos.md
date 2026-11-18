---
title: Building PBOs
parent: DayZ tools
nav_order: 5
---

# Building PBOs

*Build PBO* on an addon folder in the *Project* view (or on any file inside one: the nearest folder
holding a `config.cpp`) packs it with DayZ Tools' Addon Builder. *Build PBO with Options…* changes
the options first, and *Tools → DayZ → Build All PBOs* builds every addon of the project in turn.

- The first build of an addon asks for Addon Builder's options: output folder (prefilled with the
  `Addons` folder of the project's `@Mod` folder when there is exactly one), prefix (empty takes
  `$PBOPREFIX$`, shown in grey), signing key, *pack only*, *clear temp folder*, and under *More
  Options* the temp folder and exclude/include lists. They are saved per addon in
  `.idea/enfusion-builds.xml` and reused; the signing key is stored as a path only. While the project
  is untrusted, saved options are not used and the dialog asks again.
- **What goes into the PBO:** when binarising, Addon Builder's command line packs only what it
  binarises, such as `config.bin`, plus the files the include list names. With no include list
  set, the plugin passes its own: scripts (`*.c`), layouts, imagesets, styles, textures (`*.edds`,
  `*.paa`), materials (`*.rvmat`, `*.emat`), particles, sounds, fonts, and data files (`*.csv`,
  `*.xml`, `*.json`, `*.txt`, `*.html`, `*.cfg`, `*.jpg`). Addon Builder's own window has a
  shorter default, without stringtables. An include list you set replaces the plugin's; *pack
  only* stores every file and needs none.
- The build runs in the background, with Addon Builder's output in a *Run* tab and `File …, line N`
  config errors as links. Cancelling the task stops it.
- Addon Builder exits with code 0 even when a build fails, so the result is read from its own last
  line (`Build Successful` / `Build failed`), and the `.pbo` must exist.
- A signed build is checked for its `.bisign` next to the PBO and for the matching `.bikey` in the
  mod's `Keys` folder next to `Addons`, which servers need; a missing one is a warning.
- **Not supported:** a native packer that works without DayZ Tools (Addon Builder is required), and
  building before a run configuration starts.

To build the whole `@MyMod` folder a mod ships, signed and checked, see
[Building the mod for release](release-build.md).
