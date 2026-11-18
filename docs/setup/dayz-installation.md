---
title: DayZ installation
parent: Project setup
nav_order: 3
---

# DayZ installation

**Settings → Languages & Frameworks → Enforce Script → DayZ Installation** holds where things are on
this machine: the DayZ, DayZ Server and DayZ Tools folders, the work-drive source folder and letter,
the client and server profile folders, and SteamCMD's folder. The [tooling features](../tools/index.md) build on them.

- **Detected, not typed.** A field left empty uses the detected location, shown in grey, and its
  folder button opens there, ready to pick a folder nearby. The DayZ,
  DayZ Server and DayZ Tools folders come from Steam's own records (`libraryfolders.vdf` and each
  app's `appmanifest_*.acf`), so a game installed in another Steam library is found too. The
  work-drive folder and letter come from DayZ Tools' `settings.ini`, as its launcher sets them, and
  the client profile is `%LOCALAPPDATA%\DayZ`. The server profile is never detected: each server
  chooses its own with `-profiles=`. SteamCMD has no installer and no record, so it is found only
  in `%LOCALAPPDATA%\SteamCMD`, where *Download SteamCMD…* puts it. *Detect Again* re-reads
  everything, for instance after installing DayZ Tools.
- **Checked as typed.** A folder that does not hold what it should (`DayZ_x64.exe`, `DayZServer_x64.exe`,
  DayZ Tools' `Bin`) gets a warning under the field. It is a warning, not an error, so a drive that
  is not connected right now does not stop the settings from being saved.
- **Per machine.** These paths are saved in the IDE's own settings (`enfusion-dayz.xml`), which do
  not roam with *Settings Sync*, and never in the project. The one exception is *Server profile →
  For this project*, saved in `.idea/enfusion.xml` because a project's server is its own; like the
  [root list](library-roots.md#trusted-projects), it is ignored while the project is untrusted.
- **Download SteamCMD…**, under *Steam Workshop*, fetches Valve's `steamcmd.zip` from
  `steamcdn-a.akamaihd.net` after a confirmation naming the folder: the one typed, or
  `%LOCALAPPDATA%\SteamCMD`. It unpacks only `steamcmd.exe`, which downloads the rest of itself
  on its first start. [Publishing to the Workshop](../tools/release-build.md#publishing-to-the-steam-workshop)
  needs it.
- **Not supported:** detection outside Windows, and DayZ installs that Steam does not know about
  (a copied game folder): set those by hand.

The same page holds Workbench's *Mod folder* (see
[Workbench on the project's mod](../tools/dayz-tools.md#workbench-on-the-projects-mod)) and the
toolbar switch (see [Toolbar](../tools/dayz-tools.md#toolbar)).
