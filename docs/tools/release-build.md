---
title: Building the mod for release
parent: DayZ tools
nav_order: 6
---

# Building the mod for release

A **DayZ Mod Build** run configuration (*Run → Edit Configurations → + → DayZ Mod Build*) builds
the `@MyMod` folder a mod ships, one configuration per mod:

```
@MyMod\
    addons\   the project's addons, binarised and signed, and third-party PBOs
    keys\     the .bikey of the mod's signing key, and the third-party ones
    mod.cpp
    LICENSE …
```

- **Mod folder:** the `@MyMod` folder to build. Its `addons\` and `keys\` are managed by the build.
- **What goes into `addons\`:**
  - *Rescan Project Addons* lists the project's `config.cpp` folders, as the
    [DayZ run configuration](running-dayz.md) does, and *Add PBO Files…* adds third-party PBOs.
  - **The project's addons** are built by Addon Builder, binarised, with each addon's include and
    exclude lists. Select an addon in the table and click *Addon Builder Lists…* to set them.
    They are saved with the addon's [*Build PBO*](building-pbos.md) options, so both builds use
    the same lists. With no include list, the plugin's own applies: see
    [What goes into the PBO](building-pbos.md). Each one is built into a folder of
    its own, then moved into `addons\` under its release name: its prefix's folders joined by `_`,
    so `MyMod\Scripts\Client` becomes `Scripts_Client.pbo`, as mod build scripts name them.
    *Lower-case PBO names* makes it `scripts_client.pbo`, signature included; switching it
    rebuilds every addon once.
  - **Third-party PBOs** are copied with the `.bisign` files next to them, under their own names.
- **Signing:**
  - Each built PBO is signed with DayZ Tools' `DSSignFile` and the *Signing key*
    (`.biprivatekey`), after it has its release name.
  - The `.bikey` of the same name next to the key goes into `keys\`, with the *Third-party keys*.
  - Then `DSCheckSignatures` checks every signature in `addons\` against `keys\`, and any that is
    wrong or has no key fails the build.
- **`mod.cpp` and extra files** (such as `LICENSE`) are copied into the mod's folder. `mod.cpp`
  starts as the one at the project's root.
- **Only what changed:** an addon is built again only when its files, its include or exclude list
  (a list file's contents included), its prefix or the signing key changed since the last build, or when its PBO or signature is missing. A copy is skipped when the
  file is the same size and date. PBOs, signatures and keys no longer part of the mod are removed;
  other files in `addons\` are left alone.
- **Not supported yet:** creating a signing key, and generating `mod.cpp`.

## Publishing to the Steam Workshop

A **DayZ Workshop Publish** run configuration uploads a built `@MyMod` folder to the Steam
Workshop with SteamCMD, Valve's command-line Steam client. DayZ Tools' Publisher has no command
line, so SteamCMD's `workshop_build_item` is the scriptable way.

- **SteamCMD** is a path of the machine, set under
  [DayZ installation](../setup/dayz-installation.md) and shared by every project, never stored in
  the run configuration, which a project can share. *Download SteamCMD…*, in the configuration or
  on that page, installs it from Valve; when it is missing, the configuration's error offers the
  same download as its *Fix*.

- **What it uploads:** the *Mod folder*, as *DayZ Mod Build* writes it. To build first, add that
  configuration under *Before launch*. Publishing is its own configuration so that it is never one
  click away from a build: an upload cannot be undone.
- **The item:** *Workshop item ID*, empty for a new item; after the first upload the new ID is
  filled in, so the next one updates it. *Visibility*, *Title*, *Description* and *Preview image*
  are sent only when set, so an update leaves the item's page as it is.
- **The change note** is one entry of the item's *Change Notes* tab on the Workshop: the *Change
  note* field, or, when it is empty, the first section of a changelog file (a `CHANGELOG.md`'s
  newest `## 1.2.0` section, its heading left out).
- **Before anything runs**, a confirmation names the item, the folder with its file count and
  size, the visibility and the change note.
- **The login:** only the *Steam account* name is stored. SteamCMD opens in a console window of its
  own, where its password and Steam Guard prompts are answered the first time; SteamCMD keeps the
  login after that. The IDE never asks for, sees or stores them.
- **The result:** the *Run* tab says when the window is closed, with the item's link or what went
  wrong. It's read from what SteamCMD adds to its `logs\workshop_log.txt` during the run, since
  SteamCMD can end a successful upload with a non-zero exit code, 7 when it has just updated
  itself. The exit code is used only when the log names no upload.
