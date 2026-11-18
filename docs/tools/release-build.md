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
- **Only what changed:** an addon is built again only when its files, its prefix or the signing key
  changed since the last build, or when its PBO or signature is missing. A copy is skipped when the
  file is the same size and date. PBOs, signatures and keys no longer part of the mod are removed;
  other files in `addons\` are left alone.
- **Not supported yet:** publishing to the Steam Workshop (planned with SteamCMD), creating a
  signing key, and generating `mod.cpp`.
