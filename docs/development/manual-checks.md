---
title: Manual checks
parent: Contributing
nav_order: 2
search_exclude: true
---

# Manual checks

What to check in the sandbox IDE (`./gradlew runIde`, and `./gradlew runIdeLatest` before a
release), feature by feature. This is the acceptance gate for anything touching indexing, library
roots or references, which the unit tests cannot exercise: see [Testing](testing.md).

## Editor basics

- Open a vanilla file such as `P:\scripts\3_game\dayzgame.c`. Confirm the **status bar / file icon**
  says Enforce Script — not just that the colours look plausible.
- Keywords, strings, numbers, `//!` doc comments and `#ifdef` lines each render distinctly.
- Open a `.c` file in an unrelated C project: it must **not** be claimed. That is the
  `FileTypeOverrider` working (see [Which files are Enforce Script](../getting-started.md#which-files-are-enforce-script)).
- `Ctrl+/` toggles `//`, `Ctrl+Shift+/` wraps `/* */`, `Ctrl+]` jumps between matching braces.
- Click the `-` in the gutter next to a multi-line class or method body → it collapses to `{...}`;
  a one-line `{}` shows no fold gutter at all. Same check on a `.layout`, folding a widget's children
  block.
- Open a server file that opens with `#ifdef SERVER` → the whole region folds
  to `#ifdef SERVER ... #endif`, and a nested `#ifdef DIAG` inside it folds independently.

## Navigation

Opening a real DayZ mod.

- A balloon reports vanilla indexed from the work drive on first open, and **Enforce Script
  Sources** and **Vanilla Configs** appear under *External Libraries*. A root in the list that
  cannot be resolved is named in a warning rather than silently ignored.
- An *Enforce Script is in an early stage* notice appears once. *Don't show again* → it never comes
  back after a restart. *Help → Report Enforce Script Issue* → GitHub's new-issue form, with the
  versions filled in.
- Ctrl+Click a type declared in the mod → its declaration.
- Ctrl+Click a vanilla type (`Math` in `Math.Clamp(...)`, `PlayerBase`) → the vanilla source. This is
  the one that proves library roots work, and it must succeed on the **first** click: a root only
  reachable after some later background refresh is the bug fixed in `EnfusionRoots.toFiles`.
- Ctrl+Click a name with several declarations → a picker whose rows are distinguishable by file path.
- Ctrl+Click a primitive (`int`, `float`) → nothing happens, and no exception.
- Ctrl+Click while indexing is still running → nothing happens, and **no `IndexNotReadyException`**;
  that is the `DumbService` guard behaving correctly.
- No red error notification on project open. A `SlowOperations` assertion there means VFS work has
  crept back onto the EDT.
- Untrusted project: copy the mod into a fresh folder outside any trusted location, give its
  `.idea/enfusion.xml` the roots `\\127.0.0.1\enfusion-probe` and `<b>bold</b>` (written
  `&lt;b&gt;bold&lt;/b&gt;` in the XML), and open it
  choosing **Preview in Safe Mode**. Nothing appears under *External Libraries*, no balloon shows,
  and Process Monitor shows no access to `\\127.0.0.1`. Trust it and reopen: the warning names both
  roots, and `<b>bold</b>` is shown as text, not as bold.
- Packed roots: in *Settings | Languages & Frameworks | Enforce Script*, add a dependency's
  `Scripts.pbo` and a large texture or GUI `.pbo` in one pick. Ctrl+Click a class declared in the
  scripts PBO reaches its unpacked source on the **first** click. Its `config.cpp` opens indented and
  locked, and typing in it is refused. `enfusion-pbo` in the sandbox's system directory holds KBs
  for the texture PBO, not its size. Remove the root and the navigation goes. Replace the `.pbo`
  with an updated copy and reopen the project: the cache is rebuilt.
- PBO folders: add a single `@Mod` folder → no question, and its classes navigate. Add `!Workshop`
  → a dialog gives the PBO count and size; *Add Folder* starts a background task with a progress bar
  naming each PBO, cancellable. Afterwards a class from any subscribed mod navigates. Subscribe to
  one more mod and reopen the project: its classes navigate too.
- PBO browser: with a `.pbo` root, *External Libraries* lists the `.pbo` next to its cache; open it
  → a table of entries, no binary placeholder; typing in the filter narrows it. Enter on a `.c` → it opens highlighted and
  read-only; if the PBO is a root, it is the unpacked file and Ctrl+Click works in it. Open
  `config.bin` → an indented `config.cpp`. In a texture PBO, open a `.paa` → the texture viewer; a
  `.p3d` → a warning balloon on its row naming the format, no tab. Open the same entry twice → one
  tab.
- Find a `modded class` with an `override` method → a green circular-arrow gutter icon appears on the method name; clicking
  it, and separately Ctrl+Clicking the method name itself, both land on the vanilla or superclass
  declaration it overrides. A method that overrides nothing shows no icon.
- `Ctrl+H` on `PlayerBase` → the Class tab lists every `modded class PlayerBase` layer plus real
  subclasses; the Supertypes tab on one of those layers leads back to the vanilla declaration. A
  layer must **not** expand back into its own siblings — that would be an endless branch.
- Ctrl+Click `GetRPCManager` in `GetRPCManager().AddRPC(...)` and `CallType` in an RPC handler's
  parameter list → both resolve into the bundled stub (**Community Framework Stubs** under *External
  Libraries*), with no real `@CF` checkout present. Add a checkout of
  [DayZ-CommunityFramework](https://github.com/Arkensor/DayZ-CommunityFramework) as a root and apply:
  **Community Framework Stubs** disappears and both now open the real source, with no second
  candidate. Remove the root again and the stub comes back.

## Layers and overrides below

Opening a real DayZ mod with a dependency mod as a root.

- Open vanilla `PlayerBase.c` → a gutter icon on the class name, *Modded by N layers, extended
  by M subclasses*; clicking it lists the dependency's and the mod's layers with their files.
- On vanilla `SetActions` in an item class the mod patches → an icon listing the layers that
  redeclare it. Typing in that file stays smooth while the icons appear.
- On the mod's own `modded class PlayerBase` → an upward icon, *Patches PlayerBase, beside N
  other layers*; clicking it lists vanilla's `PlayerBase.c` first.
- `Ctrl+Click` the name of a layer's constructor `void PlayerBase()` → vanilla's constructor
  only, not its destructor; and the other way round from `void ~PlayerBase()`.
- `Ctrl+Alt+B` on `PlayerBase` in mod code → the same list; on a method call → its overrides.
- `Ctrl+U` inside an `override` method of a `modded class` → what it overrides; on the class name
  of an ordinary class → its superclass.

## Code completion

Opening a real DayZ mod.

- Inside a method of a `modded class PlayerBase`, type `GetIdentity().` → the popup opens by itself
  on the dot and lists `PlayerIdentity`'s methods. Do the same on a local
  `array<ref PlayerBase> players;` with `players.Get(0).` → `PlayerBase`'s members, including those
  of the mod's own layers.
- `Math.` → statics such as `Clamp` and `PI`, no instance members. `CallType.` (bundled CF stub) →
  `Server` and `Client` only.
- Start a bare name with `Get` → locals and members come first, then `GetGame` and the other free
  functions from vanilla `1_core/proto`, then types. The popup must open with no visible delay on
  the full vanilla corpus; a stall here means the global or type lookup has started parsing files.
- On a line of its own directly above another assignment (`m_Menu = ...;`), type `m_Map` → the
  class's `m_Map...` fields are offered. That line briefly parses as a declaration of a local named
  `m_Menu`, so this is the case that proves completion does not trust that reading.
- At the top of a class body type `Play` → type names (`PlayerBase`, `PlayerIdentity`); then after
  `PlayerBase ` type a field name → nothing is offered.
- Accept a method with parameters → the caret lands inside `(` `)`; one without → after `()`.
- Keywords: at the start of a statement `ret` → `return`, followed by a space only when the method
  returns a value. After an `if (...) { }` block, `el` → `else`; at the top of a class body
  `ov` → `override`; at file scope `mod` → `modded`, then `cl` → `class`; and in `class X ext`
  → `extends`. Inside a call's parentheses `nu` → `null`, but `if` and `return` are not offered.
- During indexing → completion simply offers nothing, and **no `IndexNotReadyException`**.

## Parameter info

Opening a real DayZ mod.

- Type `GetGame().CreateObject(` → the hint opens by itself on the `(`, listing `string type`,
  `vector pos` and the defaulted flags, with `type` highlighted. Type `"Apple", ` → the highlight
  moves to `pos`.
- Accept a method with parameters from completion → the caret lands inside `(` `)` and the hint
  opens there too.
- On a method with overloads (e.g. `GetGame().GetWorldName(`), both are listed. After a first
  argument, the one taking no parameters is greyed out.
- In `Outer(Inner(|))` the hint shows `Inner`'s parameters; after `Inner(...)`, `,` hands it
  back to `Outer` with the second parameter highlighted.
- `new` on a mod class with a constructor → the constructor's parameters.
- With the caret before the `(` or after the `)`, `Ctrl+P` shows nothing, and no exception.
- Type the whole of `GetGame().CreateObject("Apple", "1 2 1"` quickly, without pausing → the hint
  stays open throughout, each `"` inserts its partner, and nothing turns red at any point. Type a
  stray `;` after a `,` and delete it again → the hint stays open.
- `Widget.Cast(` and `ButtonWidget.Cast(` → `Class from`: `Cast` lives on vanilla's root `Class`,
  which `Widget` reaches only implicitly.

## Override Methods

Opening a real DayZ mod, with vanilla indexed.

- In a `modded class PlayerBase`, `Ctrl+O` → the chooser lists vanilla's `PlayerBase` methods, each
  other layer as its own group with its file, then `ManBase`, `DayZPlayer` and the rest of the
  chain. Methods the layer already overrides are not listed.
- Pick `EEInit` and `OnStoreLoad` with *Call super* ticked → `override void EEInit()` with
  `super.EEInit();`, and `override bool OnStoreLoad(ParamsReadContext ctx, int version)` with
  `return super.OnStoreLoad(ctx, version);`, after the member at the caret, tab-indented.
- The generated code compiles: *Tools → DayZ → Check Scripts (Server)*.
- `Ctrl+O` again → *Call super* is remembered.

## Quick Documentation

Opening a real DayZ mod, with vanilla indexed.

- `Ctrl+Q` on `Math.RandomInt(` → the signature, the `\brief` text, the parameters with their
  text, *Returns*, and the `@code` example as code. *Class* links to `Math`.
- `Ctrl+Q` on `GetGame().CreateObjectEx(` → the signature with its defaulted flags.
- `Ctrl+Q` on a method of a `modded class` that has no comment of its own → the vanilla comment,
  with *From …* naming the vanilla declaration, and *Other layers* listing the layers. Clicking a
  link shows that declaration's documentation.
- `Ctrl+Q` on `PlayerBase` → its *Extends* chain and every *Modded by* layer, each a link.
- `Ctrl+Q` on `ECE_PLACE_ON_SURFACE` → its value in decimal and hex.
- `Ctrl+Q` on an `auto` local → *Type* with the inferred type and *(inferred)*.
- Turn on *Settings → Editor → General → Code Completion → Show the documentation popup* and
  *Show quick documentation on mouse move*; hover a vanilla call → the same popup, and hovering a
  texture path still shows the texture.
- `Ctrl` + hover a call → one line: class, signature, file.

## Inlay hints

- In a mod file, `GetGame().GetCallQueue(CALL_CATEGORY_SYSTEM).CallLater(Refresh, 1000, false)`
  → `delay:` before `1000` and `repeat:` before `false`, nothing before `Refresh`.
- `Print("x")` and `Math.Clamp(v, 0, 1)` → no hint. Alt+Enter on a hint → *Do not show hints
  for current method* hides them for that method.
- `auto p = new Param1<int>(5);` → `p: Param1<int>`; `foreach (auto x : m_Array)` over an
  `array<ref X>` field → `x: X`. Both can be switched off under *Settings → Editor → Inlay Hints*.
- On a large vanilla file such as `PlayerBase.c`, typing stays smooth with hints on.

## Navigation from string literals

Opening a real DayZ mod. The examples below use a mod called MyMod.

- Ctrl+Click a widget name such as `"RowHealthIcon"` in a `FindAnyWidget(...)` call → the matching
  `ImageWidgetClass` inside the `.layout` the enclosing class loads via `CreateWidgets(...)`.
- Ctrl+Click a layout path built by `+` concatenation (`MYMOD_PREFIX + "GUI/layouts/..."`) →
  the file. This is the one that proves `EnConstantEvaluator` folds through a `const string`.
- Ctrl+Click a bare `"STR_MYMOD_..."` (server-side, no `#`) → its row in `LanguageCore/stringtable.csv`;
  and a `"#STR_MYMOD_PARTY_..."` inside a `.layout` → the stringtable of the addon that declares
  it, such as `Party/stringtable.csv`.
- Ctrl+Click `"#menu_add"` or `"#STR_TextInputDialogRoot_WindowLabel0"` in a vanilla layout
  (`P:\gui\layouts\…`) → its row in `P:\languagecore\stringtable.csv`, though that row is spelt
  in lower case and the file is too large for the platform to index.
- Open the mod's `stringtable.csv` → a *Table* tab next to the text. In it, untranslated cells
  are tinted, and the headers show coverage. Edit a `french` cell → the text tab changes on that
  one line; `Ctrl+Z` undoes it. Change an `original` whose `english` is a copy → `english` follows,
  and a notification counts it. *Add Key…*, *Add Language…* and *Fill from Original…* each
  produce one undoable change.
- Open `P:\languagecore\stringtable.csv` (about 7,300 rows) → the table opens without a pause,
  scrolls and filters smoothly, and is read-only.
- Type `"#STR_MOD_SOMETHING_NEW"` in a script → *Alt+Enter* → *Create stringtable row* → the row
  appears at the end of the mod's stringtable with the caret in its `original` cell.
- Break a row in the text tab (delete a comma) → the row is an error, and the table's status line
  counts it.
- Ctrl+Click each half of `"set:mymod_gui image:Logo_White"` separately → the
  `ImageSetClass` and the `ImageSetDefClass` respectively.
- Ctrl+Click the RPC name in `AddRPC(RPC_MYMOD_NAMESPACE, "SetPlayerCount", this)` → the handler
  method, with **no picker** — the `this` argument pins a single target.
- Ctrl+Click a method name in `AddTimer(duration, this, "OnPlayerCountChanged", ...)` → the method on
  that state, including one inherited from a superclass.
- Open a `.layout` file: the status bar must say **Enforce Layout**, not plain text; it folds, and
  `Ctrl+F12` shows a structure view of the widget tree.
- Trigger completion inside a `FindAnyWidget("")` call → only the names from layouts the class
  actually loads, not every widget in the project.

## Config class names in script

With `P:\DZ` (or the game's `Addons` as a PBO root) in the roots.

- Ctrl+Click `"Apple"` in `CreateInInventory("Apple")` → the vanilla `CfgVehicles` class, and a
  mod's own redeclaration offered beside it. `IsKindOf("Inventory_Base")` → its declaration.
- Type `CreateInInventory("Ap` and complete → item classes, no `scope = 0` base class.
- `GetGame().ConfigGetText("CfgVehicles " + type + " displayName")` → `CfgVehicles` navigates.
- Find Usages on a mod's item class in its `config.cpp` → the scripts that spawn it.
- Without `P:\DZ`: nothing red, and typing in a spawn call stays smooth.

## config.cpp

- `Scripts/Client/config.cpp` opens as **Enforce Config** (check the status bar), folds, and shows a
  structure view of the class tree.
- Ctrl+Click a dependency's addon name (`"OtherMod_Scripts"`) in `requiredAddons[]` → the
  `CfgPatches` class in that dependency's own `config.cpp`.
- Ctrl+Click `"MyMod/Data/Inputs.xml"` → the file; `"…/Mod_Logo.paa"` → the texture.
- Ctrl+Click `"#STR_…"` and `"set:… image:…"` inside a config → the stringtable row and the imageset
  entry, resolved by the providers written for script and layouts.
- Ctrl+Click `displayName="$STR_CfgVehicles_…"` in a vanilla `P:\DZ` config → its row in the vanilla
  stringtable.
- Type `#ifdef ` in any `.c` file → the list offers the flags of the mod's `defines[]`, such as `MYMOD`
  and `MYMOD_PARTY`, alongside the engine's own flags.
- Add `P:\DZ` to the roots in the settings → a warning about the first indexing appears; *Cancel*
  leaves the list unchanged. Add it again and confirm, wait for the scan, then Ctrl+Click
  `EffectArea` in `class MyEffectArea: EffectArea` → vanilla's declaration. **Vanilla Configs**
  appears under *External Libraries*; confirm the IDE indexes configs only and does not sit on
  24 GB of `.paa`. Adding `P:\scripts` or a mod checkout shows no warning.
- A C or C++ file in an unrelated project is **not** claimed.
- `Ctrl+Alt+L` on a hand-written mod `config.cpp` with mixed indentation → class bodies and
  multi-line arrays indented with tabs, spacing and one-line arrays unchanged. On a vanilla
  `P:\DZ` config → no line moves; lines holding only tabs come out empty, unless *Keep indents on
  empty lines* is ticked. Enter after `class X: Y` and its `{` → one level deeper.
- `Ctrl+Alt+L` on a vanilla script whose names are aligned with tabs, such as the fields at the
  top of `5_mission\dayzintroscene.c` → the tabs stay tabs, and the names stay aligned.
- *Settings → Editor → Code Style → Enforce Config* exists, with tabs on.
- In a mod `config.cpp`, write `class MyApple: Apple {};` in `CfgVehicles` with no `class Apple;`
  (and vanilla configs in the roots) → a warning on `Apple`; `Alt+Enter` → *Add 'class Apple;'*
  adds it at the top of `CfgVehicles`. *Build PBO* with binarising then succeeds where it failed.
- Open a vanilla `P:\DZ` config → no such warning anywhere.

## Texture viewer

- Open `P:\gui\imagesets\dayz_gui.edds` → the atlas appears over a checkerboard, and the status
  line reads `… BGRA8 … EDDS, LZ4 + raw`. The *Project* view shows it with an image icon, and it
  opens with no *binary file* placeholder.
- `Ctrl`+wheel zooms around it; *Fit to Window* and *Actual Size* do what they say; the
  transparency toggle shows the alpha-less image.
- The mip picker steps through every level down to 1 × 1.
- Open one of the `P:\gui\fonts` BC7 atlases → the glyph sheet appears (the `sdf_*` and `*_msdf_*`
  ones look like coloured blurs: they are distance fields), and the status line names BC7.
- Re-export a mod's `.edds` while it is open → the view reloads.
- Open a mod's `Mod_Logo.paa`, then a large vanilla one such as
  `P:\DZ\characters\tops\data\gorka_upper_as.paa` → each appears, and the status line names DXT1 or
  DXT5 and `PAA, LZO`. Ctrl+Click a `picture="…/Mod_Logo.paa"` in a `config.cpp` → it opens in the
  viewer.
- Open `P:\DZ\data\data\detailmap_4444.paa` → a message naming ARGB4444, not an error.
- Open `P:\gui\imagesets\dayz_gui.edds` → an outline around each of its images, and the status line
  counts them. Hover one → its name, set and region; double-click → `dayz_gui.imageset` opens at
  that entry. The outlines follow zoom and the mip picker.
- Open a mod's atlas, add an `ImageSetDefClass` to its `.imageset` → a new outline appears in the
  open viewer. *Show Imageset Regions* hides and shows them all.

## Texture previews on hover

- Hover `"set:dayz_gui image:…"` in a `.layout` or a script → a popup with that one icon, its
  region (`… at x, y`) and the `.edds` path. The icon is sharp: the 2x atlas is the one used.
- Hover a mod's `"…/GUI/textures/….edds"` literal and a `picture="…/Mod_Logo.paa"` in a
  `config.cpp` → the whole texture, with size, format and mip count.
- Hover the name of an `ImageSetDefClass` inside an `.imageset` → the same cut-out icon.
- Hover a path to a BC7 font atlas → its preview. A broken path → a message, never an error.

## Texture icons in the gutter

- Open a mod's `.imageset` → an icon beside each `ImageSetDefClass`, each showing its own image,
  and one beside the texture `path`. Hover an icon → its name and size.
- Open a busy `.layout` → an icon on each line with an `image0 "set:…"`; click one → the
  imageset entry. Typing in the layout stays smooth while the icons fill in.
- Open `P:\gui\imagesets\dayz_gui.imageset` (hundreds of entries) → icons appear after a moment,
  and scrolling and typing do not stall.
- Open a mod's `config.cpp` with `hiddenSelectionsTextures[]` → one icon per line. Untick "Texture
  preview in configs" → they disappear from configs only.

## Packing an imageset

- Right-click a folder of PNG or TGA icons inside an addon → *Pack into Imageset…* → the texture path
  is the addon's prefix and the path to the `.edds`. *Pack* → the `.imageset` opens, with a gutter
  icon on each image showing the right picture; the `.edds` opens in the texture viewer, BGRA8 with
  its mips.
- Pack again with no change → Git shows no change to either file.
- Use one of the icons in a layout (`image0 "set:<name> image:<icon>"`) → the layout preview shows
  it. In game, the icon shows, with no neighbour bleeding into its edge.
- A folder with two `icon.png` in different subfolders → refused, naming `icon`.
- Next to an `.imageset` whose `Name` differs from its file name (`Name "my_gui"` in
  `icons.imageset`), *Pack into Imageset…* on the `icons` folder → the dialog proposes `my_gui`
  and the `.imageset`'s texture path. *Pack* → in game, the layouts' `set:my_gui` icons still show.
- Pack the same folder as *DXT5* with *Compress the mipmaps with LZ4* → a much smaller `.edds` that
  the viewer opens as DXT5 with `LZ4` mips. **In game**, use an icon from it in a layout: it shows,
  with its soft edges. Do the same with *DXT1* on opaque icons.
- Choose *DXT1* for pictures with soft edges → a warning before packing.
- Right-click a vanilla imageset such as `P:\gui\imagesets\dayz_gui.imageset` → *Extract Images…*
  → choose a folder → one PNG per image, each matching its gutter icon. Pack that folder again →
  an imageset naming the same images.

## DayZ installation

- Open *Settings → … → Enforce Script → DayZ Installation* → after a moment every field shows the
  detected folder in grey and the line at the bottom names what was found. Settings opens without a
  freeze while detection runs.
- Type a wrong folder into *DayZ* → a warning that `DayZ_x64.exe` is not there; *OK* still saves.
- Clear it again → back to the detected path. Restart the IDE → the typed paths are kept, in
  `options/enfusion-dayz.xml` of the IDE's config folder, not in `.idea`.
- On a machine without DayZ Tools → *Not detected* in its field and no error.
- The *Drive letter* field shows its whole grey hint (*Detected: P*).
- Click the folder button of an empty *DayZ* field → the chooser opens at the detected DayZ folder;
  of a filled one → at the folder typed.
- Remove every vanilla entry from the root list, keeping *Index vanilla DayZ from the work drive*
  ticked → `PlayerBase` still resolves, and *External Libraries* still lists `P:\scripts` and
  `P:\DZ`. Untick it → the vanilla roots go away after a re-index; tick it again → back.
- Untick *Also index the vanilla configs* → `P:\DZ` leaves *External Libraries*, the scripts stay.
- Set *Drive letter* to a letter with no work drive → the vanilla roots go away, with no warning.
  Clear it → back to the detected letter and its roots.

## Script modules

- In a mod's `3_Game` file, declare a field of type `PlayerBase` → a warning that it is declared in
  `4_World`. In a `4_World` file, the same line → nothing.
- Call, from `3_Game`, a method that only a `4_World` `modded class` layer adds → a warning on the
  method name, none on the class name.
- In `3_Game`, write `modded class PlayerBase {}` → a warning on `PlayerBase`. A `PlayerBase` field in
  another `3_Game` file is still reported.
- A mod whose `config.cpp` lists non-standard folders in `defs` → its files follow the config, not
  their folder names.
- Open a few vanilla `P:\scripts\3_game` files → no warnings from this rule.

## Work drive

- With `P:` mounted → the status bar shows `P: → <folder>`. *Tools → DayZ → Unmount Work Drive*
  → a notification, the status bar shows `P: not mounted`, and `P:\scripts` navigation stops.
- *Mount Work Drive* → `P:` returns, the status bar updates, and Ctrl+Click into vanilla works again
  without a restart.
- Restart the IDE with `P:` unmounted, and no vanilla entry left in the root list → a notification
  that `P:` is not mounted, offering *Mount P:*.
- *Mount* while `P:` is already mounted → a warning, nothing run.
- Rename DayZ Tools' `WorkDrive.exe` temporarily → mounting falls back to `subst`.
- Mount an empty folder as the work drive → the *Vanilla game data is not extracted* notification.
  *Use the DayZ install's PBOs instead* → the game's `Addons` folder becomes a root and vanilla
  navigation works. *Don't ask again* → it never returns. With `P:` unmounted → no such notification.
- *Tools → DayZ → Extract Game Data…* → a confirmation naming the DayZ folder and `P:\`; *Extract* →
  WorkDrive in its own console window, ending with *hit any key to exit*; a key closes it, a
  notification says the data is on the drive, and `P:\scripts` resolves.

## DayZ Tools

- *Tools → DayZ Tools* → every installed tool is listed; each one starts.
- Right-click a `.paa` → *Open in TexView 2* → TexView opens that texture. A `.p3d` → Object Builder.
- On a machine without DayZ Tools → no *DayZ Tools* menu, no *Open in …* entry, no error.
- In a mod project linked to the work drive (`P:\MyMod` → the checkout), *DayZ Installation* shows
  *Mod folder* as *Detected: P:\MyMod*. *Tools → DayZ Tools → Workbench* or the toolbar's
  *Start Workbench* → Workbench opens with the mod loaded from its unpacked files.
- In a mod project → the main toolbar's *Build All PBOs*, work-drive, *Start Workbench* and *DayZ*
  buttons. The work-drive button is pressed while the drive is mounted, and clicking it unmounts,
  then mounts. In a project without a `config.cpp` → none of them. Turn off *Show the DayZ buttons in
  the main toolbar* → they disappear, without a restart.

## Running DayZ

- *+ → DayZ* adds one configuration. *Connect to* is greyed out while *Start a server* is ticked,
  and each section is greyed out while its box is not.
- Add *DayZ*, *Start clients* only:
  - *Rescan Project Addons* → the project's `config.cpp` folders are listed as
    `P:\MyMod\…`; *-filePatching* stays as it was.
  - *Add Mods…* → the mods folder's and `!Workshop`'s `@` folders; tick `@CF` → it is added.
  - Run → the console's first line says every addon was packed. The game starts with the mod
    loaded, vanilla and mod texts translated. *Stop* closes it.
  - Run again → *Packed 0 addon(s), N unchanged*. Edit one script and run → that addon only.
- Untick an addon, *Rescan* again → it stays unticked. Delete an addon folder, *Rescan* → its row
  goes.
- *Add PBO Files…* with two PBOs and their `.bisign` → run → the console's `-mod=` has
  `…\@ExtraPBOs` where the first PBO row was, and the RPT loads them.
- Unmount `P:` and run → an error that `P:` is not mounted, nothing started.
- A configuration saved before the mod list (with `;`-separated *Mods*) → its mods appear as rows.
- *Start clients* only, an *Offline mission* and no *Connect to* → offline play on that mission.
  Both set → an error. A server's `dayzOffline.*` mission as the *Offline mission* → a warning about
  idle mode, in the configuration and at the top of the console.
- *Start a server* with an empty *Server config* → an error in the configuration; Run does not start
  it. Server and client profiles both empty → a warning.
- *Start a server* and *Start clients*, three players, Goldberg ticked, a DayZ Diag server with a
  mission:
  - One *Run* tab: `[Server]` starts, then about 5 seconds later each player's identity line about 4
    seconds apart, each line prefixed with its name.
  - All three join the server and stay connected, each with its own profile folder.
  - The server's command line has the shared `-mod=` plus `-serverMod=` for the *Server only* rows;
    the clients' have no server-only mod.
  - *Stop* closes all four.
- Tick the three *Before Launch* boxes with a server and a mission → the console's first lines say
  what was stopped, cleared and wiped; the mission's `storage_*` folders are gone.
- Two players with the same name → an error in the configuration. Several players without Goldberg
  → a warning. Neither box ticked → an error.
- *Tools → DayZ* and the toolbar's *DayZ* drop-down have no *Launch Buldozer*.

## New mods and addons

- Right-click a folder in the *Project* view → *New → DayZ Mod…* → enter a folder and a name, keep
  the defaults → the folder has `mod.cpp`, `LICENSE`, `README.md` and `.gitignore`, and `mod.cpp`
  opens. *New DayZ Addon* follows, prefilled: folder `Scripts`, class `<Folder>_Scripts`, prefix
  from the work drive's link when the project has one.
- In the addon dialog, type `DZ_` in the search field → only the matching addons stay listed, the
  ticked ones still ticked. Untick `4_World` and `5_Mission`, *OK* → `config.cpp` opens with only
  `gameScriptModule`, `files[] = { "<prefix>/3_Game" }`, `dependencies[] = { "Game" }`; the folder
  has `$PBOPREFIX$` and `3_Game\<Class>.c`.
- *Run → Edit Configurations → DayZ → Rescan Project Addons* → the new addon is listed with its
  prefix. *Build PBO* on its folder builds it.
- Choose a folder that already has a `config.cpp`, or a class name starting with a digit → the
  dialog says so and *OK* stays disabled.
- *DayZ Addon* again on the same mod → it starts on *Data*. Switch the kind to *Language* → folder
  `LanguageCore`, no module ticked, only `DZ_Data`; *OK* → a `stringtable.csv` with vanilla's
  header and a `config.cpp` with no `CfgMods`. *GUI* → `layouts` and `imagesets` folders.

## Game logs

- Open *DayZ Logs* → *Script*, *RPT* and *Crash* tabs, each naming its file; start the game → the
  tabs switch to the new run and follow it.
- Select a DayZ run configuration with a server and two players → *Server*, *Alice* and *Bob* tabs
  of each kind, on their profile folders; select another configuration → the tabs follow it.
- In the *Script* tab, click an `@"scripts/3_Game/….c,17"` link → vanilla opens at
  that line; a mod's own `…(1):` compile error → the mod's file.
- *Errors and Warnings Only* → a stack trace stays under its error. *Open Older Run…* → the chosen
  file; *Follow Newest* → back.
- With no profile folder → a message pointing to the settings, no error.

## Checking scripts

- Select your DayZ run configuration, then *Tools → DayZ → Check Scripts (Server)* on a mod that compiles →
  the *Run* tab says the addons were packed, then after a game start's time *All five script modules
  compiled*, and no `DayZDiag_x64.exe` is left running.
- Break a `3_Game` file (use an unknown type) → *The "Game" script module does not compile*, with the
  error linking to that file and line.
- Cancel while it runs → the server stops.
- *Check Scripts (Client)* → a game window opens, then closes once *All five script modules compiled
  on the client*. Break a file inside `#ifndef SERVER` → only the client check reports it.

## Building PBOs

- Right-click a mod's addon folder → *Build PBO* → the options dialog, prefilled; *Build* → a *Run*
  tab with Addon Builder's log and *Build Successful*, the PBO in the output folder, and a
  notification. *Build PBO* again → no dialog.
- Open the built PBO in the IDE, with no include list set → its scripts, layouts, imagesets,
  `stringtable.csv`, textures and data files are there beside `config.bin`. The command line in
  the *Run* tab ends with `-include=…include.lst`.
- Break `config.cpp` (delete a `}`) and build without *Pack only* → *Build failed*, an error
  notification, and the `File …, line N` line is a link to the config.
- Build with a signing key → the `.bisign` appears; with no `.bikey` in `@Mod\Keys` → a warning.
- *Tools → DayZ → Build All PBOs* in a project with several addons → one tab, built one after the other.

## Building the mod for release

- Add *DayZ Mod Build*:
  - Set *Mod folder* to an empty test folder and click *Rescan Project Addons*. Set the signing key,
    and add a third-party PBO with its key.
  - Run → one *Run* tab:
    - Addon Builder builds each addon.
    - Each PBO arrives in `addons\` as `Scripts_Client.pbo` and the like, with its
      `.MyMod.bisign`.
    - `keys\` holds `MyMod.bikey` and the third-party key, and `mod.cpp` and the extras are copied.
    - The last lines say *Signatures checked: all OK* and *Built …*.
- With the run configuration's folder deep on disk (as in a sandbox IDE) → every addon still
  builds, scripts of 60 characters' depth included. The Steam API's two lines after each addon
  are not red.
- Select an addon → *Addon Builder Lists…* → the include and exclude lists. Type `*.layout` as
  the include list of an addon with scripts and run, with no file changed → that addon alone is
  rebuilt, and its PBO holds its layouts but no scripts. *Build PBO with Options…* on the same
  addon shows `*.layout`, and *Build PBO* packs the same.
- Set the include list to a file that does not exist → that addon fails, naming the file.
- Set the include list to a file with one pattern a line (`*.c`, then `*.layout`) → the addon
  builds, with its scripts and layouts.
- Put a `LICENSE` and a `NOTICE` file in an addon with no include list → both are in its PBO.
- Clear the include list and set the exclude list to `*.edds` → the addon rebuilds, its PBO
  holds everything the default list takes but its `.edds`, and the command line has no
  `-exclude`. The same with DayZ Tools' `Bin\PboUtils\exclude.lst` as the exclude list.
- *Build PBO with Options…* with *Pack only* and an exclude list → the build stops and says
  why.
- Run again → *0 addon(s) to build*. Edit one script → that addon only.
- Untick an addon and run → its PBO and signature are removed from `addons\`.
- Start a server with `verifySignatures = 2` on the built mod → clients with it join.

### Publishing to the Workshop

With a **private test item** (or none, to create one, set to *Private*).

- Without SteamCMD: a new *DayZ Workshop Publish* → the error *SteamCMD not found* with *Fix* →
  the confirmation names `%LOCALAPPDATA%\SteamCMD` → *Download* → a progress bar, then the
  configuration's *SteamCMD* row shows `…\SteamCMD\steamcmd.exe`, and so does *Settings → … →
  DayZ Installation → Steam Workshop*.
- *Description* and *Change note* have a border, and scroll when the text is long.
- *DayZ Workshop Publish* with the built mod folder, the account name, visibility
  *Private* and a change note → *Run* → a confirmation with the folder's size and the note.
  *Cancel* → nothing runs.
- Confirm → SteamCMD's own window asks for the password and the Steam Guard code; the upload
  runs. Close the window → the *Run* tab gives the item's link, and the configuration now holds
  the item ID.
- Run again with another change note → no password asked; the item's *Change Notes* tab has both.
- Leave the change note empty and set a `CHANGELOG.md` → its newest section is the note.

## Layout preview

- Open `P:\gui\layouts\day_z_ingamemenu.layout` → text and preview side by side: the button column
  on the right, the server info at the top left. Click a button in the preview → the caret jumps to
  its `ButtonWidgetClass`. Move the caret into another widget → its outline turns blue.
- Open a mod's layout with imageset icons → the icons are drawn. Change a `size` → the preview
  follows a moment later. *Hidden widgets* → the `visible 0` ones appear dashed.
- Switch the resolution → proportional widgets scale, pixel ones keep their size. The list runs from
  1280 × 720 to 3840 × 2160, with 1920 × 1080 selected.
- In `day_z_ingamemenu`, the column above *Respawn* holds *Exit*, *Feedback* and *Options* at full
  height, with no gap for the hidden *Restart*, and stays above *Respawn* rather than over it. The
  vignette darkens the edges of the screen, as in Workbench, and its blue outline (caret on
  `vignette`) is the screen, nudged by under a pixel.
- Pick *french* in the language list → *Continue* reads *Continuer*; *Keys* → `#main_menu_continue`.
  A mod layout using its own `STR_…` keys shows the mod's stringtable text.
- Open `P:\gui\layouts\day_z_map.layout` → `BackPanelWidget` (`style rover_sim_black`) is dark,
  framed as in Workbench, not light grey; the `toolbar_bg` band (`style ToolbarWidget`) matches
  Workbench too. *External Libraries* lists `P:\gui\looknfeel`.
- Open a layout with `DayZDefaultButton` or `MenuDefault` buttons → each button has its frame. A
  `style` no `.styles` file declares → a panel keeps its plain colour.
- Open `P:\gui\layouts\day_z_hud_cars.layout` → the dials are grey arcs, not filled boxes; the RPM
  dial's last fifth is red, as are the low ends of the fuel and temperature dials.
- In `day_z_ingamemenu`, the modded-version warning wraps over three lines at the size Workbench
  shows. In `day_z_hud_cars`, the RPM needle points left.
- At 3840 × 2160 every outline is still visible. Scroll the wheel over a button → it zooms in around
  the pointer; drag → the view pans; a click without dragging still selects. *Fit* → back to the
  whole screen; *100%* → one layout pixel per screen pixel.

## Colour swatches

- Open a vanilla `.layout` → a swatch beside each `color` and `"text color"` line, the colour
  the preview shows. Click one in a mod layout, pick another colour → the four numbers change
  (the colour's own fractions, three decimals) and the preview updates.
- In a script, `ARGB(255, 242, 38, 19)` → a red swatch; pick a colour → the four arguments change.
  `ARGB(m_Alpha, 242, 199, 68)` → a yellow swatch; pick a colour → `m_Alpha` stays.
  `ARGBF(1, 0, 0.5, 1)` → light blue. `const int COLOR_RED = 0xFFF22613;` → red, and a
  `const int` flag such as `0x00000400` → no swatch.
- In a config, `"#(argb,8,8,3)color(0.5,0.5,0.5,1,co)"` → grey.
- Open vanilla `gui/looknfeel/dayzwidgets.styles` → it opens as XML, with a swatch beside each
  `Color="…"`.
- **In game**, an `ImageWidget` with `color 0.141 0.141 0.141 1` beside one given
  `SetColor(ARGB(255, 36, 36, 36))` and one given `ARGB(255, 105, 105, 105)` → the first two are
  the same `#242424`, as their swatches show; the third is lighter. Measured on 2026-11-18.

## Stringtable texts in place of keys

- Open a mod script with `"#STR_…"` strings → each shows its text, collapsed; moving the caret
  into one shows the key again. Vanilla keys such as `"#menu_add"` fold too.
- A layout's `text "#STR_…"` and a config's `displayName="$STR_…"` fold the same way.
- Pick *french* in a layout preview's language list → the folds read French after the next
  reparse (type a character).
- Untick *Code Folding → Enforce Script → Stringtable keys as their text* → the keys are back.
- In a large vanilla file (`missiongameplay.c`), typing stays smooth.

## Rename and inspections

- `Shift+F6` on a method in a `modded class` that overrides vanilla → **refused**, with a message
  naming the script root. This is the guard, and it is the one that matters: without it the rename
  would rewrite `P:\scripts`, which is a library here but is an ordinary writable file on disk.
- `Shift+F6` on a class declared inside the mod → the declaration, every usage, its constructor and
  destructor, and any `modded class` layer of the same name all change together.
- `Shift+F6` on a widget name in a `.layout` → every `FindAnyWidget("…")` naming it updates too.
- `Shift+F6` to an illegal name (`class`, `2Fast`) → the dialog refuses it.
- Open a server-side script file and check the inspection warnings are **few and true**.
  Watch particularly for *"Variable named after a type"*: it is the one rule here with no corpus
  measurement behind it, so a wave of them on ordinary code means it is too eager and should be off
  by default.
- Break something on purpose — misspell the handler in `AddTimer(…, this, "OnTikc")`, or drop the
  `override` from a method in a `modded class` — and confirm each is flagged, then that `Alt+Enter`
  repairs it.
- In a `modded class PlayerBase`, write `override void SetActions() {}` → a weak warning on the
  name, and `Alt+Enter` → *Add super call* inserts `super.SetActions();`. The same override in an
  ordinary subclass is not reported.
