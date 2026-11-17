---
title: Packed .pbo roots
parent: Project setup
nav_order: 2
---

# Packed `.pbo` roots

A root can also be a packed **`.pbo` file**: a dependency that exists only as a Workshop mod, with
no sources on disk. The chooser accepts several at once.

## Folders of PBOs

A **folder** root brings the `.pbo` files under it too, so one entry can cover a mod (`@Mod`), a
whole `!Workshop` folder, Steam's workshop content folder or a game install's `addons`. The scan
follows links (each `@Mod` under `!Workshop` is one), goes four levels deep, and runs again each time
the project opens, so a newly subscribed mod is picked up then.

A folder holding more than 20 PBOs asks first, with the count and size: the first unpack of a
thousand PBOs takes about a minute, shown as a background task. The folder's own files stay a root
as well, ahead of its PBOs, so a checkout that also holds a packed build of itself still navigates to
its sources.

## What is unpacked

- **Only the navigable part is unpacked:** scripts (`.c`), layouts, imagesets, stringtables, other
  small text files, and `config.bin`, which is rebuilt as a readable, indented `config.cpp` and then
  gets the full config support. Models, textures, sounds and everything else stay in the PBO, so a
  multi-gigabyte texture PBO costs only its header. Entries over 16 MB are skipped.
- The files go to `enfusion-pbo` in the IDE's system directory, under the PBO's prefix, so packaged
  asset paths resolve as they do for a source checkout. Navigation reaches them like any other root.
  They open **read-only**: they are a cache, rebuilt whenever the PBO changes.
- A PBO is unpacked when it is added, and re-checked each time the project opens. A Workshop update
  is picked up then, not while the project is open. A PBO found in a folder that cannot be read is
  skipped and logged; only a `.pbo` named in the list itself is reported as missing.
- **Not supported:** encrypted `.ebo` content, and PBOs whose headers are deliberately obfuscated.
  Encrypted entries are skipped; a header that does not hold together is reported as a missing root.
  The unpacked game data (`P:\DZ`) is never scanned for PBOs.

## Browsing a PBO

**Browsing a PBO.** Under *External Libraries* a `.pbo` root shows twice: as its unpacked cache,
which holds only the navigable files, and as the `.pbo` itself. A PBO found in a folder root shows
under its folder. Opening a `.pbo` from there, or from *Go to File* or *File | Open*, shows a table
of its entries: name, size and packing, with the prefix, entry count and total size
above, and a filter. Only the header is read; an entry is read when it is opened, so browsing a
multi-gigabyte texture PBO costs nothing. **Enter** or a double-click opens the selected entries,
read-only:

- An entry the PBO cache already unpacked (the PBO is a root) opens as that file, with full
  navigation.
- Anything else opens as an in-memory copy: scripts with the script language, layouts and
  imagesets with theirs, `config.bin` and other binarised configs (`.rvmat`, …) rebuilt as indented
  text with the config language, `.paa` and `.edds` in the texture viewer, other text as plain text.
- Models, sounds and other binary formats are refused with a message naming the format, not dumped
  as bytes, in a balloon on the entry's row; so are encrypted entries and entries over 128 MB.
- The browser does not hold the PBO open, so Steam can update it meanwhile; the list reloads when
  the file changes.
