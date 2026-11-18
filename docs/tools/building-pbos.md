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
  `*.xml`, `*.json`, `*.txt`, `*.html`, `*.cfg`, `*.jpg`), and the `LICENSE` and `NOTICE` files
  that licences such as Apache 2.0 require third-party code to ship with. Addon Builder's own
  window has a shorter default, without stringtables. An include list you set replaces the plugin's; *pack
  only* stores every file and needs none.
- **The lists** take patterns typed straight in, separated by `;` or `,` as in Addon Builder's
  window (`*.c;*.layout`), or the path of a list file such as DayZ Tools' `exclude.lst`. A value
  with `*`, `?` or `;` is read as patterns, anything else as a file. A list file holds one or more
  patterns a line, and a line starting with `;` is a comment. Addon Builder gets the patterns on
  one line, in a copy: it fails with a bare *Build failed* on an include list of several lines. A
  list file that does not exist stops the build with its path, since Addon Builder would pack on
  without it.
  - **Matching:** a pattern with a folder (`source\*`) matches the path inside the addon, its
    subfolders included; one without (`*.psd`, `notes.txt`) matches the file name in any folder.
    Letter case is ignored.
  - **The exclude list is applied by the plugin.** Addon Builder 1.0.240639 crashes on its own
    `-exclude` before building anything, whatever the list holds. So the plugin lists the
    addon's files that the include list matches, leaves out those the exclude list matches, and
    passes the rest to Addon Builder as the include list, one path each. It works on the files
    copied as they are; what Addon Builder binarises, such as `config.cpp`, is not on either
    list.
  - **Not supported:** an exclude list on a *pack only* build, which stores every file whatever its
    include list says. The build stops and says so.
- **Path length:** Addon Builder copies each file into its temp folder before packing, and the PBO
  to its output folder after. A copy longer than Windows' 260 characters fails with a bare *Build
  failed*, and the *Run* tab then names the copy and its length. The release build keeps both
  folders short, under `%TEMP%`.
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
