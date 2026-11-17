# Enforce Script for IntelliJ

IntelliJ IDEA language support for **Enforce Script**, the scripting language of DayZ mods
(`.c` files), focused on code navigation: go-to-declaration, find usages, `modded class` hierarchy,
structure view, and navigation from string literals into layouts, stringtables, imagesets and
inputs.

## What works today

- **Syntax highlighting** for the whole language, including doc comments and preprocessor directives.
  Typing `"` inserts its closing quote, and a string left without one is reported on the string
  itself (*Missing closing quote*) rather than as a parse error on whatever follows it.
- **Go to Declaration** (`Ctrl+Click` / `Ctrl+B`) on type names — in declarations, parameters, return
  types, `extends` clauses, generic arguments, `new X(...)`, and on static qualifiers such as
  `Math.Clamp(...)`. A name declared several times (vanilla `class X` plus one or more
  `modded class X`) offers every layer, because the layers are typically gated by mutually exclusive
  `#ifdef`s that no plugin can evaluate.
- **Go to Declaration** on locals, fields, `obj.method()` / `obj.field` member access and file-scope
  free functions, via lightweight type inference over declarations, parameters and `new X(...)`.
  Container element types are carried through, so `m_Zones.Get(0).Activate()` resolves — vanilla
  declares `array` and `map` accessors as returning a bare type parameter (`proto T Get(int n)`),
  so the element type exists only in the variable's own declaration. A class that names no
  superclass still inherits from `Class`, vanilla's root of every class, so `Widget.Cast(w)` and
  `w.ClassName()` reach it even though `Widget` extends `Managed` and `Managed` extends nothing.
  `auto` is inferred too, both from an initializer (`auto p = new Param1<int>(...)`) and from the
  collection in `foreach (auto x : ...)`.
- **Go to Declaration and a gutter icon on `override` methods**, linking back to what they override —
  another `modded class` layer of the same class, or a superclass. A method's own name is a
  declaration rather than a reference, so without this Ctrl+Click on it did nothing at all.
- **Code completion** for identifiers, answered by the same type inference as Go to Declaration, so
  a name is offered exactly where Ctrl+Click on it would resolve. After a dot it lists the
  receiver's members from every `modded class` layer and the superclass chain, once per signature,
  so an override redeclared by four layers appears once while overloads each stay. The popup opens
  on `.` without Ctrl+Space. Through a class name (`Math.`) only statics and constants appear,
  through an enum only its constants, and `super.` in a `modded class` reaches the other layers but
  never its own. A bare name offers, innermost first: parameters and the locals declared above
  the caret in an enclosing block, then the class's members, then file-scope functions and globals
  (`GetGame`, `g_Game`), then type names. A type position, including a half-written field at the
  top of a class body, offers types only. Methods insert their parentheses.
  **Keywords** are offered by position rather than all at once: statements and local modifiers at
  the start of a statement (`else` only after an `if` without one, `case` and `default` only in
  a `switch`), `null` / `true` / `this` / `new` inside an expression, `out` / `inout` / `notnull`
  in a parameter list, member modifiers in a class body, `class` / `modded` / `enum` / `typedef`
  at file scope, `class` after `modded`, and `extends` after a class name. A keyword that is
  followed by something inserts its space; `return` does so only when the method returns a
  value. The engine-binding modifiers (`owned`, `external`) are left out, since only vanilla's
  `proto` declarations use them. **Not offered:** a generic class's own type parameters (`T`).
- **Parameter info** (`Ctrl+P`, and on its own when `(` is typed or a method is accepted from
  completion) inside a call's parentheses: the parameters of every overload the call may mean,
  with the one the caret is in highlighted and default values shown (`bool create_local =
  false`). Most of the engine API is `proto native` with no documentation beyond its signature, so
  this is usually the only place to read it without leaving the call. It covers member calls,
  `super.` calls, methods of the enclosing class and its layers, free functions, and `new X(...)`
  constructors. All overloads are listed even where Ctrl+Click narrows to one, since a call being
  typed has not chosen yet; one that cannot take the arguments typed so far is greyed out, and an
  override redeclared by several `modded class` layers is shown once.
- **Code folding** (`Ctrl+.` / the gutter `-`/`+`) on every brace-delimited block that spans more than
  one line: class bodies, method/statement bodies, enum and switch bodies, array literals in
  Enforce Script; widget declarations and their children blocks in `.layout`/`.imageset`. Also on
  `#ifdef`/`#ifndef` regions, which fold whole (not per `#else` arm) and keep the flag visible while
  collapsed — `#ifdef SERVER ... #endif`. A branch the current define set *excludes* folds too, and
  starts collapsed.
- **Go to Declaration from string literals** — the other half of how this language is wired together.
  Ctrl+Click resolves: a widget name in `FindAnyWidget(...)` to its declaration in whichever
  `.layout` the enclosing class loads; a `.layout` / `.imageset` / asset path (including ones built by
  `+` concatenation through `const string` constants) to the file; a stringtable key
  (`"#STR_BR_..."`, any other `"#key"` such as vanilla's `"#menu_add"`, the bare server-side
  `"STR_…"`, or a config's `"$STR_…"`) to its row in `stringtable.csv`, ignoring case as the game
  does, the vanilla stringtable included although it is too large for the platform to index; each half of
  `"set:x image:y"` to the `ImageSetClass` and `ImageSetDefClass` respectively; a dispatch-by-name
  string (`AddRPC`, `SendRPC`, `AddTimer`, `GameScript.Call`, ...) to the target method, inferring the
  receiving class where the call shape carries one; and a keybind action name (`UA...`, always behind
  a `const string`) to its `<input name="...">` declaration in `Inputs.xml`. Completion inside these
  strings is scoped the same way (e.g. widget names only from the layouts the class actually loads).
- **`.layout` / `.imageset` support** — a dedicated PSI language (not just syntax highlighting) with
  structure view and folding, which is what makes widget and imageset navigation resolve to real
  elements instead of a plain-text search.
- **`.edds` and `.paa` texture viewer** — opening a texture shows the image instead of binary content, with
  zoom (toolbar or `Ctrl`+wheel), fit to window, actual size, a transparency checkerboard that can be
  switched off (for textures that keep data in alpha), and a picker for every mip level. The status
  line gives size, pixel format, mip count and compression. For `.edds` it decodes uncompressed
  BGRA/BGRX/RGBA, DXT1, DXT3, DXT5 and BC7, raw or LZ4-compressed, which covers every `.edds` in
  the vanilla `gui` folder, the BC7 font atlases included. For `.paa` it decodes DXT1 to DXT5, raw
  or LZO-compressed: all but three of the 40,055 `.paa` files in the vanilla game data. **Not
  supported:** BC4, BC5 and BC6H `.edds`, which the game data does not use; the uncompressed
  `.paa` formats (ARGB4444, ARGB1555, ARGB8888, AI88), used by three vanilla detail and noise maps;
  and cube maps.
  The viewer names an unsupported format instead of showing it. It opens on the largest mip of at
  most 4096 × 4096; larger mips are in the picker. Both extensions are a binary file type, so Find
  in Files and Replace in Files skip textures instead of matching inside their bytes: there is no
  need to exclude texture folders to keep search results clean. Over an atlas, the viewer outlines
  every image that an imageset places on it, on the 1x and the 2x texture alike: an icon with no
  outline has no `ImageSetDefClass` yet, and the free space in the atlas is easy to see. Hovering
  an outline names the image and its region, and double-clicking opens its entry. *Show Imageset
  Regions* in the toolbar turns the outlines off.
- **Texture previews on hover** — hovering a texture path (`"…/loading_1.edds"`,
  `picture="…/Mod_Logo.paa"`) or an imageset image (`"set:dayz_gui image:DayZLogo"`, in script,
  layouts and configs) shows the image in the documentation popup, with its size, format and path.
  An imageset image is cut out of its atlas, so the popup shows that one icon rather than the whole
  sheet; when an imageset names a 1x and a 2x texture, the sharper one is used. Only the mip the
  preview needs is decoded, and previews are cached until the texture changes. **Not covered:**
  vanilla textures outside `gui/imagesets`, such as `P:\gui\textures`, are not in the library
  roots, so a path into them does not resolve and shows nothing. Nor do imagesets and textures in a
  folder marked **Excluded**, which the IDE does not index. After un-excluding one, if the preview
  still does not appear, *File → Invalidate Caches…* makes the IDE index it.
- **Texture icons in the gutter** — a small thumbnail beside each image of an `.imageset`, and
  beside every line of script, layout or config naming a texture or an imageset image. Clicking
  one goes to the texture or the imageset entry, and its tooltip gives the name and size. A line
  naming several textures shows one icon. Icons are computed in the background and share the hover
  previews' cache, so an imageset's atlas is decoded once for all its icons. Each can be turned off
  under *Settings → Editor → General → Gutter Icons*: "Texture preview", and "Texture preview in
  configs" for `config.cpp` and `mod.cpp` on their own. Textures that cannot be previewed, such as
  an ARGB4444 `.paa`, get no icon.
- **Packing an imageset** — *Pack into Imageset…* on a folder in the *Project* view packs its
  pictures (PNG, TGA, BMP, GIF, JPEG and TIFF, at any depth) into one `.edds` atlas and the
  `.imageset` that names them, both written next to the folder by default.
  - Each picture is an image named after its file (`arrow_left.png` → `arrow_left`; characters a
    `"set:… image:…"` string cannot hold become `_`). Two pictures with one name are refused.
    *Subfolders become groups* puts each first-level subfolder's pictures in its own
    `ImageSetGroupClass`.
  - Pictures are placed largest first with MaxRects, on the smallest power-of-two atlas that holds
    them (at most *Largest atlas side*). *Gap between pictures* is 0 by default, as DayZ's atlases
    are packed: vanilla's images are mostly 0 or 1 pixel apart and against the atlas edge, mods'
    almost all 0. The gap is only ever between pictures, never along the edge, so sixteen 64² icons
    still fill a 256² atlas. A gap of 2 keeps an icon drawn smaller, from a smaller mip, from
    blending into its neighbour; *Repeat each picture's edge* fills half the gap on each side with
    the picture's own edge pixels.
  - The atlas is BGRA8, uncompressed, with a full mip chain: the format of most vanilla UI atlases
    (13 of the 23 under `P:\gui\imagesets`), with every mip stored raw as in `dayz_gui.edds`, whose
    header it copies (most others compress their larger mips). The `.imageset` lists the images by name and names the
    atlas by its game path (the addon's prefix and the path inside it, prefilled), without a
    `{GUID}`, as vanilla's `dayz_inventory.imageset` does.
  - **Repeatable:** the same pictures and options give byte-identical files, so packing again with
    no change is no diff.
  - **Not supported yet:** block compression (DXT1, DXT5, BC7) and LZ4-compressed mips, which make
    smaller files (a 1024² atlas is 5.6 MB uncompressed); updating an existing imageset while
    keeping its images in place; cutting an atlas back into pictures; remembering the options per
    folder.
- **Stringtable editor** — a `stringtable.csv` opens as text with a *Table* tab next to it: one
  row per key and one column per language, named from the file's own header, with the key and
  `original` columns kept on the left while the languages scroll. Editing a cell edits the text, so
  undo, the text tab and version control all see the change, and only that cell's characters change.
  - **What still needs translating stands out:** an empty cell, and a cell that still holds exactly
    the `original` text (never `english`, which normally equals it), each with its own background
    and a tooltip. Each language's header shows its coverage (`french 412/430`), and *Show* lists
    only the rows missing a translation in one language. A search field filters by key or text.
  - **Add Key…** writes a correctly quoted row from one field per language. The key starts with the
    prefix most of the file's keys share and must be new in every stringtable, ignoring case. **Add
    Language…** adds a column to the header and every row, empty or filled from `original`;
    **Remove Language…** takes one out; **Fill from Original…** copies `original` into the empty
    cells of a language for a first pass.
  - **Changing `original` updates its copies:** the cells of that row that still held the old
    `original`, `english` included, follow it in the same undoable edit, while real translations are
    left alone and tinted *original changed since this was translated* for the rest of the session.
    A notification counts both. *Update Copies of Original* in the toolbar turns this off.
  - *Show in Text* opens the selected row in the text tab; switching back to *Table* selects the row
    under the caret.
  - **Malformed rows are errors** in the text: a row with more or fewer cells than the header (which
    shifts every translation after it into the wrong language), an unclosed quote, text after a
    closing quote, and a duplicate key. The table marks the key of such a row in red. Two keys
    differing only in case are not reported as duplicates, though the game takes them for one key:
    the vanilla stringtable itself has three such pairs.
  - **From the code:** *Localization key with no stringtable row* offers *Create stringtable row
    for 'STR_…'*. Only `STR_…` keys are reported: most other `"#…"` strings are keys too, but not
    surely. The row goes into one of the project's own stringtables, never a library's. When
    the file's own addon (the nearest folder with `config.cpp`) has one, that is used directly.
    Otherwise a list offers every stringtable of the project, plus a new `stringtable.csv` in the
    file's addon. It is ranked by the new key's prefix, so for `STR_AUTORUN_…` the table already
    holding `STR_AUTORUN_` keys comes first. A new stringtable gets the vanilla header. The caret
    lands in the new row's `original` cell.
  - **Not supported:** column colouring and the column name at the caret in the text tab, and
    translation hints on `"#STR_…"` strings. The *original changed* tint is not saved: it is gone
    after a restart, since the CSV has no room for it. A cell holding a line break shows it as `⏎`
    in the table and is edited on one line. The IDE writes a file back with one line separator
    throughout, so in a CRLF stringtable that has line breaks *inside* cells (the vanilla one has
    250), saving turns those into CRLF too. Library stringtables, such as the vanilla one, open
    read-only.
- **Layout preview** — a `.layout` opens as text with a read-only preview beside it (*Layout* in the
  editor's top-right switches between text, both, and preview only). The widget tree is laid out on
  a screen of the chosen size (1920 × 1080 by default) the way the vanilla layouts use the
  properties: `size` / `position` in pixels or as fractions of the parent (`hexactsize`,
  `vexactsize`, `hexactpos`, `vexactpos`), `halign` / `valign` with offsets from the matching edge,
  grid spacers in `Rows` × `Columns` cells (more rows when the children need them, sized to their
  content on an axis with *Size To Content*, `Padding` inside and `Margin` round each cell), text
  sized to its text with `"size to text h"` / `"size to text v"` (estimated: the layout has no
  fonts), and wrap spacers flowing their children (lines placed by
  `content_halign` / `content_valign`, `bottom` stacking them upwards), a `visible 0`
  child taking no slot, a wrap spacer's *Size To Content* fitting it to its children, and children
  cut off at their parent's edges unless it says `clipchildren 0`. Siblings are drawn by `priority`,
  lowest first. With `fixaspect`, a proportional
  `size` gives only a ratio: `outside` covers the parent with a box of that ratio, `inside` fits
  one in it (`size 1.6 0.9` is a full 16:9 screen), and `fixwidth` (`fixheight`) derives the width
  (height) from the other side. Panels are filled with their
  `color` (not `blank` ones; a button's colour tints its style, so buttons are not), images show their `image0` (an imageset entry or a texture) or `imageTexture`,
  through the same cache as the texture hover, stretched to their box, tinted by their `color`,
  cut by their `Mask` at their `Progress` and mirrored by `flip u` / `flip v`, and turned by their
  `rotation` about their `pivot` (the outline stays upright, as in Workbench). Text widgets show
  their `text` at `"exact text size"` pixels, or `text_proportion` of their height, wrapped to the
  box with `wrap 1` and placed by `"text halign"` / `"text valign"` (top left by default) and
  `text_offset`. Check boxes get their box, combo boxes their arrows, and scroll widgets and the
  server browser their scroll bars, drawn plainly. A texture path no root holds, such as vanilla's `Gui/textures/…`, is looked up on
  the work drive when the project indexes it; an image that is not found is only outlined. A `#key` text shows in the language chosen in the toolbar, from the stringtables that
  declare the key (the project's and the vanilla one), falling back to `original`, then to the key;
  *Keys* shows the keys themselves. The choice is kept for every layout. Every widget is outlined; *Hidden widgets* shows the ones with `visible 0`, dashed.
  Clicking a widget puts the caret on its declaration: the smallest widget under the pointer, then,
  clicking the same spot again, the next larger one, and the caret's widget is outlined in blue,
  its whole box even where its parent cuts it off. The wheel zooms around the pointer, dragging
  pans, *Fit* fits the screen to the pane again and *100%* shows it at one layout pixel per screen
  pixel; outlines stay one pixel wide at any zoom. The preview updates a moment after the text
  changes. **Approximate, not pixel-exact:** the typeface is the IDE's, not the layout's `font`;
  panel `style`s (the imageset frames of `dayzwidgets.styles`) are drawn as their plain `color`;
  `Padding` / `Margin` on wrap spacers, *Size To Content* outside spacers, scaling, the
  rotation of children and blend modes (`mode additive`) are not modelled, and script classes do
  not run. Editing in the preview (moving, resizing, a properties panel) is not
  supported.
- **Class hierarchy** (`Ctrl+H`) modelling both relationships the language has: `extends`, and
  `modded class` layering. Every layer of a class hangs off the base, which is the view that matters
  here — a `modded class X` names nothing at all, so the link to what it patches exists only because
  the names match. `Ctrl+H` on a type *reference* opens that type's hierarchy, not the enclosing
  class's.
- **Go to Class** (`Ctrl+N`) over classes, enums and typedefs; **Go to Symbol**
  (`Ctrl+Shift+Alt+N`) over methods, fields, globals and enum constants.
- **Structure view** (`Ctrl+F12`) with icons for static, const and override members.
- **Library roots** — the vanilla DayZ script tree, its `gui/layouts`, `gui/imagesets` and
  `languagecore` data directories, and any dependency mod sources are indexed, so navigation reaches
  `PlayerBase`, `Widget`, `array`, vanilla layouts and the stringtable. Without them fewer than half
  of all type references in a typical mod can resolve.
- **Find Usages** (`Alt+F7`) on any declaration — needs no resolver of its own, since every usage is
  already a real `PsiReference` and the platform's default search runs on top of that.
- **Formatter** (`Ctrl+Alt+L`) — **indentation only**, deliberately. It adds no spacing or wrapping
  rules, so Allman braces and however you spaced a line survive untouched; it defaults to tabs
  because vanilla is tab-indented without exception. One level deeper, each as vanilla writes it
  in the large majority of cases:
  - the contents of `{ }` (class and method bodies, blocks, enums, `switch`, array literals);
  - the body of an `if`, `else`, `for`, `foreach` or `while` without braces, while an `else if`
    chain stays flat;
  - the statements under a `case` label, while a `{` right after a label stays at its level;
  - a line continuing a statement (arguments, conditions, long expressions), while a lone closing
    `)` goes back to the statement's level.

  Enter follows the same rules: after `if (x)` without a brace, or after `case 1:`, the caret goes
  one level deeper.

  Two tests format vanilla code. One checks that no token ever moves between lines (150 files). The
  other checks that at least 98% of lines keep their indentation (400 files). The lines that do
  move are vanilla's minority styles, such as `break;` at its `case` label's level or parameters
  aligned to a column.

  `#ifdef` / `#else` / `#endif` are pinned to the first column, which is the one formatting choice
  the corpus does *not* settle — vanilla writes it both ways — so it is a checkbox under
  *Code Style | Enforce Script | Wrapping and Braces*, not a rule.
- **A bundled Community Framework stub** — `GetRPCManager()`, `CallType` and `RPCManager`'s five
  methods resolve without a real `@CF` checkout, which most machines never have (it is a Steam
  Workshop dependency, not a plain checkout like Expansion or COT). Deliberately narrow: it stubs
  only the symbols mod code commonly references, taken verbatim from CF's real source,
  declared `proto` rather than reproducing real bodies. It steps aside by itself when one of the
  roots holds CF's real sources (a checkout of the repository at any level, or a folder containing
  one), so the two never offer competing declarations of the same names.

- **`config.cpp` support** — a third language, covering `config.cpp`, `config.cpp.disabled` and
  `mod.cpp` (claimed by exact file name, so it never competes with C++ over `.cpp`). Highlighting,
  folding, brace matching, structure view, Find Usages and rename, plus the navigation that makes a
  config worth opening: `class X : Y` resolves to Y's declaration in any indexed config,
  `requiredAddons[]` jumps to that addon's `CfgPatches` class — the mod load order, which exists
  nowhere else as a link — and `inputs`, `picture` and `files[]` resolve to the file or folder they
  name. `displayName="$STR_…"` (or `"#STR_…"`) and `ghostIcon="set:… image:…"` work through the
  *existing* stringtable and imageset providers. Vanilla's own configs are opt-in: add the unpacked game
  data (`P:\DZ`) to the roots and only its 207 configs are indexed, but they sit inside 24 GB of
  unpacked game that must be walked once, so the settings page warns before adding it.
- **`#ifdef` completion** over the engine's flags *plus* every flag the project's `config.cpp` files
  declare in `defines[]` — `DAYZ_BATTLEROYALE`, `BR_PARTY`, `KILLFEED`. Those exist nowhere but a
  config array, and a mistyped one silently disables the code it guards with no error at build time
  or after.
- **Rename** (`Shift+F6`) on any declaration, including the three bindings that carry no reference
  and that a rename following only references would leave broken: a `modded class` and the class it
  patches, an `override` and what it overrides, and a constructor named after its class. It also
  crosses into strings — renaming a widget in a `.layout` updates every `FindAnyWidget("…")` that
  names it. A rename whose family reaches vanilla is **refused with an explanation** rather than
  half-applied, since the game's extracted source on `P:` is a library here but is an ordinary
  writable file on disk.
- **Inspections** for the mistakes the engine reports only at build time, or not at all: a redeclared
  method missing `override` and the reverse, a name declared twice in one method — an error here even
  across disjoint `if` branches — a `modded class` that patches nothing, a variable named after a
  type, an `AddTimer` / `AddRPC` string naming no method on its target, and a `STR_…` key with no
  stringtable row. The two `override` ones carry quick fixes. Each is gated so that a class whose
  parent the plugin never indexed produces no report at all, and the duplicate-local rule is measured
  against 120 vanilla files to prove it stays silent on code that compiles.
- **Script modules** — DayZ compiles scripts in five modules, `1_Core` to `5_Mission`, and each one
  sees only itself and the modules before it. *Name from a later script module* reports a type,
  function, field or method used from a module that cannot see it, such as `PlayerBase`
  (`4_World`) named from `3_Game`, which the game otherwise reports only at launch as *Can't compile
  "Game" script module*. A file's module comes from `CfgMods` → `defs` → `gameScriptModule` (and the
  other four) → `files[]` in the addon's `config.cpp`, and otherwise from the standard folder names
  (`3_Game`, `scripts/4_world`, ...). One visible declaration is enough: a class declared in
  `3_Game` and modded in `4_World` can be named from `3_Game`, but the members only the `4_World`
  layer adds cannot. A `modded class` is reported too when only a later module declares its class
  (`modded class PlayerBase` in `3_Game`); such a layer does not compile, so it does not make the
  class visible from its own module either. `proto` declarations are not checked, since the engine's own classes are known
  to every module (vanilla `1_core/proto/proto.c` returns `Object`, a `3_Game` class). Measured
  silent on the 2,838 vanilla files of `1_Core` to `4_World` and on 632 files of three large mods.
  **Not supported:** a file in no known module (outside the five folders and not listed in any
  `files[]`) is not checked, and completion still offers everything, visible or not.

## Configuration

**Settings → Languages & Frameworks → Enforce Script.**

**Vanilla comes from the work drive.** On first open of a DayZ addon (a project containing a
`config.cpp`), the plugin indexes vanilla DayZ from the work drive: `P:\scripts`, the layouts,
imagesets and stringtable that string literals point at (`P:\gui\layouts`, `P:\gui\imagesets`,
`P:\languagecore`), and the configs in the unpacked game data (`P:\DZ`). The letter is the one in
*DayZ Installation*, so it follows the machine rather than the project. A tree that is not there is
skipped without a warning, since the drive may simply not be mounted, and `P:\scripts` must hold
`3_game` to count. Two checkboxes at the top of the page turn this off, or leave out `P:\DZ`: only its
`config.cpp` files are indexed, but the first indexing walks the whole unpacked game once. An
unrelated project never gets the vanilla tree.

The **root list** below is for everything else: dependency mods, and a vanilla tree kept somewhere
other than the work drive. Dependency mod checkouts found next to the project are offered rather
than added unasked. The work drive comes first, then the list in its order, and that order is the
tiebreak when the same class name is declared in more than one root. A root the work drive already
covers, left over from an earlier version, counts once. The settings page warns before a game data
folder is added to the list by hand.

### Packed `.pbo` roots

A root can also be a packed **`.pbo` file**: a dependency that exists only as a Workshop mod, with
no sources on disk. The chooser accepts several at once.

A **folder** root brings the `.pbo` files under it too, so one entry can cover a mod (`@Mod`), a
whole `!Workshop` folder, Steam's workshop content folder or a game install's `addons`. The scan
follows links (each `@Mod` under `!Workshop` is one), goes four levels deep, and runs again each time
the project opens, so a newly subscribed mod is picked up then. A folder holding more than 20 PBOs
asks first, with the count and size: the first unpack of a thousand PBOs takes about a minute, shown
as a background task. The folder's own files stay a root as well, ahead of its PBOs, so a checkout
that also holds a packed build of itself still navigates to its sources.

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

The root list is saved in the project (`.idea/enfusion.xml`), so a cloned repository can ship one.
In a project opened in **Safe Mode** the plugin therefore uses none of it: no root is indexed and
auto-detection does not run, so navigation into vanilla and dependency sources stays empty. Trust the
project and reopen it to enable them.

### DayZ installation

**Settings → Languages & Frameworks → Enforce Script → DayZ Installation** holds where things are on
this machine: the DayZ, DayZ Server and DayZ Tools folders, the work-drive source folder and letter,
and the client and server profile folders. The tooling features build on them.

- **Detected, not typed.** A field left empty uses the detected location, shown in grey, and its
  folder button opens there, ready to pick a folder nearby. The DayZ,
  DayZ Server and DayZ Tools folders come from Steam's own records (`libraryfolders.vdf` and each
  app's `appmanifest_*.acf`), so a game installed in another Steam library is found too. The
  work-drive folder and letter come from DayZ Tools' `settings.ini`, as its launcher sets them, and
  the client profile is `%LOCALAPPDATA%\DayZ`. The server profile is never detected: each server
  chooses its own with `-profiles=`. *Detect Again* re-reads everything, for instance after
  installing DayZ Tools.
- **Checked as typed.** A folder that does not hold what it should (`DayZ_x64.exe`, `DayZServer_x64.exe`,
  DayZ Tools' `Bin`) gets a warning under the field. It is a warning, not an error, so a drive that
  is not connected right now does not stop the settings from being saved.
- **Per machine.** These paths are saved in the IDE's own settings (`enfusion-dayz.xml`), which do
  not roam with *Settings Sync*, and never in the project. The one exception is *Server profile →
  For this project*, saved in `.idea/enfusion.xml` because a project's server is its own; like the
  root list, it is ignored while the project is untrusted.
- **Not supported:** detection outside Windows, and DayZ installs that Steam does not know about
  (a copied game folder): set those by hand.

### Work drive

*Tools → DayZ → Mount Work Drive* and *Unmount Work Drive* mount the work-drive folder as its
letter (`P:` unless DayZ Tools says otherwise), and a status-bar entry shows `P: → <folder>` or
`P: not mounted`, with both actions in its popup. It hides itself when no work-drive folder is found.

- With DayZ Tools installed, its own `Bin\WorkDrive\WorkDrive.exe /Mount <folder>` and
  `/Dismount` do the work, so the result is what the DayZ Tools launcher would do; WorkDrive takes
  the letter from DayZ Tools' settings. Without DayZ Tools, `subst` does the same.
- A letter that is already a disk or a network drive is never mounted over or unmounted.
- After mounting, every open project's roots are refreshed, so `P:\scripts` resolves again without a
  restart.
- When the project indexes vanilla from the work drive, or a configured root is on it, and the
  drive is not mounted, a notification at project open says so and offers *Mount P:*. It stays
  quiet on a machine with no work-drive folder to mount.
- A mount belongs to the Windows session that made it: a drive mounted from an elevated process is
  invisible to a normal one, and the reverse. If the IDE runs elevated and DayZ Tools does not (or
  the other way round), each sees its own.
- **Not supported:** choosing the letter for WorkDrive.exe, which reads it from DayZ Tools' settings.

**Extracting the game data.** The vanilla roots (`P:\scripts`, `P:\DZ`, `P:\gui`) exist only once
DayZ Tools has extracted the game onto the work drive. *Tools → DayZ → Extract Game Data…* runs
DayZ Tools' own `WorkDrive.exe /extractGameData <DayZ folder> P:\`, after a confirmation naming the
source and the target, in a console window of its own (WorkDrive ends by waiting for a key, which it
can only read from a console). When that window closes, every open project's roots are refreshed and
a notification says whether `scripts`, `DZ` and `gui` are now on the drive. When a project opens,
and after mounting, a mounted drive without `scripts`, `DZ` or `gui` gets a notification offering
*Extract Game Data…*, *Use the DayZ install's PBOs instead* (adds the game's `Addons` folder as a
root, which navigates into vanilla without extracting anything) and *Don't ask again*. It stays
quiet while the drive is not mounted. **Not supported:** noticing that the extracted data is older
than the installed game.

### DayZ Tools

*Tools → DayZ Tools* lists the desktop tools installed under DayZ Tools' `Bin` folder — the DayZ
Tools launcher, Workbench, Object Builder, Terrain Builder, TexView 2, ImageToPAA, the Material
Editor, Addon Builder, Publisher, the Central Economy Editor, BankRev and DSUtils — and starts the
one chosen. A tool that is not installed is not listed, and without DayZ Tools the menu is hidden.

On a file, the *Project* view and the editor tab menus offer *Open in TexView 2* (`.paa`, `.tga`,
`.png`; TexView cannot read `.edds`), *Open in Object Builder* (`.p3d`) and *Open in Material Editor* (`.rvmat`), which
start the tool with the file. The built-in texture viewer stays the default for textures.

The installs are detected in the background when a project opens, so the menu is ready without
visiting the settings page.

**Workbench on the project's mod.** Workbench starts from its own folder, which it needs as its
current directory, as `WorkbenchApp.exe -filePatching "-mod=P:\MyMod\"`: `-filePatching` makes it
read the mod's unpacked files, and `-mod=` is the mod's folder on the work drive. That folder is
*Mod folder* under *Workbench* in the DayZ Installation settings, kept per project in
`.idea/enfusion.xml`. Left empty, it is the project's own folder when the project lies on the work
drive. Otherwise it is the project's folder through the entry at the drive's root that is that
folder or holds it: the `P:\MyMod` link to a checkout elsewhere, which mod setup scripts create.
With neither, Workbench starts with `-filePatching` only.

Workbench mounts that folder at the root of the game's files, as the game does with an unpacked
mod. An addon in a `Scripts` folder directly at the mod's root therefore puts its `config.cpp` over
the game's `scripts\config.cpp`, and Workbench crashes as it starts, every time. In that case
the plugin shows a notification instead of starting it: it names the folder, asks for it to be
renamed, and offers *Start Workbench anyway*. `Scripts\Client\` is fine.

**Toolbar.** In a project with an addon, the main toolbar (the navigation bar in the classic UI)
gets four buttons:

- *Build All PBOs*.
- The work drive: a toggle, pressed while it is mounted, that mounts or unmounts it. It follows the
  status bar's last check rather than checking at every update.
- *Start Workbench*: Workbench on the project's mod (see *DayZ Tools* above).
- *DayZ*: a drop-down with the installed tools and the rest of *Tools → DayZ*.

They are hidden in projects without an addon and on machines without Windows. *Show the DayZ buttons
in the main toolbar* in *Settings → Languages & Frameworks → Enforce Script → DayZ Installation*
hides them everywhere. *Customize Toolbar* removes a single one. Launching the game or a server is
the Run widget's job, through the DayZ run configurations below.

### Running DayZ

A **DayZ** run configuration (*Run → Edit Configurations → + → DayZ*) starts a server, clients,
or both, with **one mod list**: a multiplayer mod is tested with the server and its clients loading
the same mods. It uses the folders in the DayZ Installation settings, and everything it starts
runs in one *Run* tab, where *Stop* stops it all.

- **What to start:** *Start a server* and *Start clients*, either or both.
  - With both, the server starts first. The clients follow *Start the clients … seconds after the
    server* later (5 by default) and join it on `127.0.0.1` at *Port*.
  - Each line of output starts with `[Server]` or the player's name.
- **The editor:**
  - **At the top, what both use:** *Use DayZ Diag*, the mods folder, the mod list,
    `-filePatching`, *Port* and *Extra arguments*.
  - **Clients:** the players, their profiles, `-window`, *Connect to* and *Password* (to join a
    server the run does not start), *Offline mission* (to play without a server), Goldberg and the
    delay.
  - **Server:** its config, its profile, its mission (DayZ Diag) and the storage wipe.
  - Fields that cannot apply are greyed out: *Connect to* and *Offline mission* while the run
    starts a server, and each section while its box is unticked.
  - The browse buttons pick a `.cfg` for the server config and folders for the profiles and
    missions.
- **Checks before starting:**
  - A server needs its config: without `-config=` DayZ counts down ten seconds and quits, so an
    empty *Server config* is an error.
  - A warning when the server and a client would write to the same profile folder.
  - A warning when the *Offline mission* is a server mission. Its `init.c` defines
    `CreateCustomMission` and never makes a `MissionGameplay`, as every `dayzOffline.*` mission
    returns `new CustomMission()`. Offline, such a mission never gives the client a character, and
    it sits on *IDLE MODE ACTIVE*. An offline test mission returns `new MissionGameplay()` when not
    on a multiplayer server. The warning is also the first line of the run's console.
- **Command lines:**
  - **Server:** the dedicated `DayZServer_x64.exe`, or `DayZDiag_x64.exe -server` with DayZ Diag.
    It gets `-config=`, `-port=`, `-profiles=` (the server profile from the settings when empty),
    `-mod=`, `-serverMod=`, `-filePatching`, and `-mission=` for a Diag server.
  - **Clients:** `DayZDiag_x64.exe` (or `DayZ_x64.exe` with *Use DayZ Diag* off). Each gets
    `-mod=`, `-profiles=`, `-name=`, `-filePatching` and `-window`, then either `-connect=` /
    `-port=` / `-password=` to join a server, or, with no server to join, `-mission=` to play
    offline.
- **The mod list** is a table in load order. Each row has a checkbox: an unticked mod is kept but
  not passed, so it can be turned off and on again. *Server only* rows go to the server's
  `-serverMod=` and to no client. The toolbar adds three kinds of row:
  - **Mods:**
    - *Add Mods…* lists the `@` folders of the *Mods folder* and of the game's `!Workshop` (where
      the DayZ launcher links subscribed Workshop mods), one checkbox each.
    - *Add Folder…* takes a mod folder anywhere.
    - A bare name such as `@CF` is taken from the *Mods folder*.
  - **The project's addons:** *Rescan Project Addons* adds every `config.cpp` folder of the
    project, as *Build All PBOs* finds them, and its `@` folders.
    - **Packed at launch:** the ticked addons are packed into a generated `@<Project>` mod, which
      takes the first addon row's place in `-mod=`.
      - Each addon is one PBO named after its folder (`Scripts\Client` → `Scripts_Client.pbo`).
      - Files are stored as they are, as Addon Builder's *pack only* does. Sources, backups, logs,
        `.png`, `.psd`, `.tga` and `source` folders are left out, as mod build scripts do.
      - The mod's `mod.cpp` is copied beside the PBOs.
    - **Only what changed:** a manifest of each addon's files (path, size, date) is kept, and an
      addon is packed again only when a file was added, removed or edited, or its PBO is missing.
      PBOs of addons no longer ticked are removed. The console's first line says what was packed.
    - **Prefixes:** an addon's prefix is the one its `$PBOPREFIX$` declares, or else its path on
      the work drive, through the link at the drive's root that mod setup scripts create
      (`P:\MyMod` → the checkout): `P:\MyMod\Scripts` is packed as `MyMod\Scripts`. Without such a
      link the row keeps its own path, the prefix is the folder's name, and the configuration
      warns.
    - **Why packed:** the game mounts an unpacked `-mod=` folder at the root of its file system.
      There, a mod's `LanguageCore\stringtable.csv` or `Scripts\4_World\…\WeaponManager.c`
      replaces vanilla's file of the same path, so vanilla texts go untranslated or vanilla classes
      disappear. Packed, everything sits under its prefix.
    - **File patching** is optional. With `-filePatching`, DayZ Diag reads loose files on the
      work drive over their packed copy, so a script edit needs no repack. A server must allow it
      (`allowFilePatching = 1` in its config), or it refuses the clients that use it ("The server
      does not accept the client's current filePatching setting"). The configuration warns when it
      can read a server config that does not allow it.
    - **Rescan:** a rescan adds new addons, ticked, and drops those whose folder is gone. It never
      re-ticks a row you unticked, and leaves the other rows alone.
  - **PBOs:** *Add PBO Files…* adds loose `.pbo` files, such as third-party PBOs a mod ships inside
    its own.
    - The game loads PBOs only from a mod's `Addons` folder, so at launch they are copied, with
      their `.bisign` files, into a generated `@ExtraPBOs\Addons` in the IDE's system folder, which
      takes the first PBO row's place in `-mod=`.
    - Only changed files are copied again, and files no longer listed are removed.
    - A server checking signatures also needs their `.bikey` files in its `keys` folder; the plugin
      does not copy those.
- A configuration saved with the older `;`-separated *Mods* and *Server mods* fields opens with
  them as rows. Extra arguments are split on spaces outside quotes.
- **Before launch**, each off by default: stop every running DayZ, DayZ Diag and DayZ Server
  (including a server another configuration started); clear the profile's `.log`, `.rpt`, `.mdmp`
  and `.ADM` files; for a server, delete the mission's `storage_*` folders so that the loot spawns
  afresh. Only the folder's own files are deleted, never its subfolders, and a drive root or the
  home folder is refused. What was done is printed at the top of the console.
- **Several local clients:** the *Players* table has one row per client: its name (`-name=`), its
  Goldberg Steam ID, and its profile.
  - **Starting:** one run starts every row, one after the other.
  - **Profiles:** each client needs its own profile. An empty one is a folder of the player's name
    under *Profiles*, or under the game's default profile.
  - **Steam identity:** with the real Steam client every local client is the same Steam user, and
    the server kicks one when the next joins.
    - *Write a Goldberg Steam emulator identity for each player* writes each player's name and Steam
      ID to `%APPDATA%\Goldberg SteamEmu Saves\settings` (`account_name.txt`, `user_steam_id.txt`)
      just before that client starts, as mod launch scripts do.
    - Each client waits until the previous one has had 4 seconds to read its identity.
    - An empty Steam ID is derived from the name.
    - The emulator's `steam_api64.dll` in the game's folder is yours to install; the plugin only
      writes the identity.
- A configuration that reads from a drive that is not mounted (the mods folder, a mod, the profile
  or the mission on `P:`) refuses to start and says so, rather than starting the game without its
  mods.
- Stopping kills the process. The password is saved with the configuration in plain text.
  **Not supported:** binarising (configs stay `config.cpp`, models and terrains are not
  binarised) and signing the packed addons; for those, build with Addon Builder. Starting Buldozer
  (Object Builder's viewer) is left for later.

### New mods and addons

*File → New* (and *New* in the *Project* view's menu) has **DayZ Mod…** and **DayZ Addon…**, both
working in the folder chosen.

- **DayZ Mod:** a folder (empty for the chosen folder itself) with a `mod.cpp` (name, author,
  version, tooltip, overview and action link) and, as ticked, a `LICENSE` (MIT, or GPL-3.0 from the
  plugin's own licence text), a `README.md` and a `.gitignore` for built PBOs, signatures, private
  keys, logs and the IDE's folder. *Then create its first addon* opens *DayZ Addon* in the new folder.
- **DayZ Addon:** an addon folder with a `config.cpp` that is right the first time.
  - **Kind**, which sets the defaults below: *Scripts* (folder `Scripts`, modules `3_Game` to
    `5_Mission`, `DZ_Data` and `DZ_Scripts`), *Data* for models, textures and item configs
    (`Data`, no module, `DZ_Data`), *GUI* (`GUI`, with `layouts` and `imagesets` folders) and
    *Language* (`LanguageCore`, with a `stringtable.csv` holding vanilla's header). The dialog
    starts on the first kind whose folder the mod does not have yet; everything stays editable.
  - **Prefix:** prefilled with the folder's path on the work drive, through the drive's link to the
    project as *Rescan Project Addons* finds it (`MyMod\Scripts`). Without a link, the path from the
    folder holding the project, since a checkout is usually named after its mod.
  - **`CfgPatches` class:** `<Mod>_<Folder>` (`MyMod_Scripts`); class and prefix follow the folder's
    name until edited.
  - **`requiredAddons[]`:** a searchable list of every `CfgPatches` class the project indexes,
    vanilla's and the dependency mods', with `DZ_Data` ticked, and `DZ_Scripts` when a script module
    is.
  - **Script modules:** `1_Core` to `5_Mission`, `3_Game` to `5_Mission` ticked. Each ticked module
    gets its folder with a first script, and a `CfgMods` entry: `class defs { class
    gameScriptModule { value = ""; files[] = { "MyMod/Scripts/3_Game" }; }; … }`, with `dir`,
    `name`, `type = "mod"`, `dependencies[]` from the modules and optional `defines[]`.
  - A `$PBOPREFIX$` file, ticked by default.
- **Not supported:** a new IDE project (both work in a folder of the current one), logos and
  pictures, which are added by hand, editing an existing `config.cpp` through a form, and file
  templates for `.c` classes.

### Game logs

The **DayZ Logs** tool window follows the game's logs: one tab each for `script_*.log`, the `.RPT`
and `crash_*.log` of every profile folder the game writes to.

- **Which profiles:** those of the selected DayZ run configuration, named after their process
  (*Server*, then each player, or *Client* for a single unnamed one), as its *Profile* fields and
  players work them out. The tabs change with the selected configuration. With no DayZ
  configuration selected, the client profile (`%LOCALAPPDATA%\DayZ` unless set otherwise) and the
  server profile of the DayZ Installation settings.

- Each tab shows the newest file of its kind and keeps up with it once a second; when the game
  starts again and writes a new file, the tab switches to it. *Open Older Run…* shows an earlier
  file instead, and *Follow Newest* goes back. A file over 8 MB opens on its last 8 MB.
- Errors are red and warnings amber. *Errors and Warnings Only* hides the rest, keeping the report
  that follows an error (the *Can't compile* summary, a stack trace) with it. *Collapse Repeated
  Lines* shows a line repeated back to back once, with a count, timestamps aside. The console's own
  search (`Ctrl+F`) works as usual.
- The three shapes of source location the game writes — `@"scripts/3_Game/human.c,17"`,
  `MyMod/Scripts/3_Game/x.c(1):` and the stack-trace `MyMod/Scripts/5_Mission/x.c:276 Function …` —
  are links. The path is the game's, from a PBO prefix or the work drive, so it is matched from the
  right against the project and its roots, like an asset path: a mod's own file opens in the
  project, a vanilla one under `P:\scripts`, and a dependency's in its PBO cache.
- **Not supported:** a *Problems* list of the last run's errors, and attaching to a running DayZ
  run configuration's output (the game writes nothing useful to it).

### Building PBOs

*Build PBO* on an addon folder in the *Project* view (or on any file inside one: the nearest folder
holding a `config.cpp`) packs it with DayZ Tools' Addon Builder. *Build PBO with Options…* changes
the options first, and *Tools → DayZ → Build All PBOs* builds every addon of the project in turn.

- The first build of an addon asks for Addon Builder's options: output folder (prefilled with the
  `Addons` folder of the project's `@Mod` folder when there is exactly one), prefix (empty takes
  `$PBOPREFIX$`, shown in grey), signing key, *pack only*, *clear temp folder*, and under *More
  Options* the temp folder and exclude/include lists. They are saved per addon in
  `.idea/enfusion-builds.xml` and reused; the signing key is stored as a path only. While the project
  is untrusted, saved options are not used and the dialog asks again.
- The build runs in the background, with Addon Builder's output in a *Run* tab and `File …, line N`
  config errors as links. Cancelling the task stops it.
- Addon Builder exits with code 0 even when a build fails, so the result is read from its own last
  line (`Build Successful` / `Build failed`), and the `.pbo` must exist.
- A signed build is checked for its `.bisign` next to the PBO and for the matching `.bikey` in the
  mod's `Keys` folder next to `Addons`, which servers need; a missing one is a warning.
- **Not supported:** a native packer that works without DayZ Tools (Addon Builder is required), and
  building before a run configuration starts.

## Feedback and error reports

The plugin is young, so bug reports and feature requests are welcome on
[GitHub](https://github.com/LeMyst/enforce-script-intellij/issues). *Help → Report Enforce Script
Issue* opens a new issue with your plugin, IDE and OS versions filled in; *Help → Request Enforce
Script Feature* opens a feature request.

In a DayZ addon project, a notice says so once per IDE session at most. *Remind me later*, or just
closing it, brings it back after a week; *Don't show again* turns it off for every project. It can
also be muted under *Settings → Appearance & Behavior → Notifications → Enforce Script feedback*.

When the plugin throws, the IDE's *Internal Errors* dialog offers **Report to third-party plugin**.
The report (stack trace, OS, IDE and plugin versions) goes to the plugin's JetBrains Marketplace page,
where only the author sees it. Nothing is sent unless you submit it, or have turned on *Send error
reports to JetBrains automatically*. The plugin collects nothing else.

## Why this exists

Enforce Script has **no `#include` and no namespaces**. Every symbol lives in one flat global
namespace assembled from whole directories in a fixed module order (`1_Core` → `5_Mission`), ordered
between addons by `requiredAddons[]` in `config.cpp`. Finding where a symbol is really declared means
searching a mod's sources *plus* the ~2,800-file vanilla corpus *plus* every dependency mod. That is
what this plugin is for.

## Design notes worth knowing

The grammar is built on an exhaustive scan of the vanilla corpus (2,811 files / 417k lines) plus a
real mod. Some conclusions that shape the implementation:

- **No macro expander is needed.** Only five directives exist (`#ifdef #ifndef #else #endif #define`)
  and `#define` is *always* a bare flag — zero function-like macros, zero replacement bodies.
- **No ternary `?:`**, no `do..while`, no `goto`, no character literals, no nested classes, no forward
  declarations, no multiple inheritance, no varargs, no `::` or `->`.
- **`>>` is never lexed.** `array<ref array<PIXEL>>` must close as two `>` tokens; shift-right is
  rebuilt in the parser from two adjacent ones.
- **48 `#ifdef` regions in vanilla open a brace their `#endif` doesn't close.** All of them sit inside
  function bodies — never splitting a class, enum or member list. The preprocessing lexer keeps both
  branches whenever both are individually brace-balanced, and only selects when it must.
- `class X : Y` and `class X extends Y` are both legal, mixed within one file.
- Modifier order is free (`override protected void`, `proto static float`, `static proto native bool`).
- Types are first-class values (`if (type == int)`), and primitives are declared as real classes.

## Building

Supports **IntelliJ 2025.2 and newer**. The build compiles against 2025.2, the oldest supported
release, and targets Java 21 bytecode because 2025.2 runs on JBR 21. No JDK needs installing:
`settings.gradle.kts` registers the foojay resolver, so Gradle provisions the toolchain itself.
See [AGENTS.md](AGENTS.md) for the development rules and the release process.

```bash
./gradlew build          # compile, validate plugin.xml, run all tests  <- the pre-commit gate
./gradlew buildPlugin    # produce the installable zip in build/distributions/
```

The lexer is generated from `src/main/grammars/EnfusionLexer.flex` into `src/gen/` (gitignored) by the
`generateEnfusionLexer` task, which every compile task depends on.

## Testing the plugin

### 1. Unit tests — seconds, no IDE

```bash
./gradlew test                          # everything
./gradlew test --tests '*LexerTest*'    # one class
```

### 2. Sandbox IDE — the real manual check

```bash
./gradlew runIde
```

This launches a **separate** IntelliJ instance with the plugin installed: the oldest supported
release (2025.2), the one the build compiles against. `./gradlew runIdeLatest` does the same on the
newest release (`latestPlatformVersion` in `gradle.properties`); run both before a release. It keeps its own config and
system directories under `build/idea-sandbox/`, so your day-to-day IDE settings, plugins and recent
projects are untouched. Open a DayZ mod checkout or the vanilla script root inside it.

**This is the acceptance gate for anything touching indexing, library roots or references** — none
of that can be exercised from `BasePlatformTestCase`, so a green test suite says nothing about it.
Every defect found so far in those areas was found here, not by the tests.

Editor basics:

- Open a vanilla file such as `P:\scripts\3_game\dayzgame.c`. Confirm the **status bar / file icon**
  says Enforce Script — not just that the colours look plausible.
- Keywords, strings, numbers, `//!` doc comments and `#ifdef` lines each render distinctly.
- Open a `.c` file in an unrelated C project: it must **not** be claimed. That is the
  `FileTypeOverrider` working (see *The `.c` extension collision* below).
- `Ctrl+/` toggles `//`, `Ctrl+Shift+/` wraps `/* */`, `Ctrl+]` jumps between matching braces.
- Click the `-` in the gutter next to a multi-line class or method body → it collapses to `{...}`;
  a one-line `{}` shows no fold gutter at all. Same check on a `.layout`, folding a widget's children
  block.
- Open a file under `Scripts/Server/` (they all open with `#ifdef SERVER`) → the whole region folds
  to `#ifdef SERVER ... #endif`, and a nested `#ifdef DIAG` inside it folds independently.

Navigation, opening a real DayZ mod:

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
- Find a `modded class` with an `override` method (e.g. `PartyHud`, or any state's
  `IsComplete`/`Activate`) → a green circular-arrow gutter icon appears on the method name; clicking
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

Code completion, opening a real DayZ mod:

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

Parameter info, opening a real DayZ mod:

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

Navigation from string literals, opening a real DayZ mod (the examples below use a
mod called BattleRoyale):

- Ctrl+Click a widget name such as `"RowHealthIcon"` in a `FindAnyWidget(...)` call → the matching
  `ImageWidgetClass` inside the `.layout` the enclosing class loads via `CreateWidgets(...)`.
- Ctrl+Click a layout path built by `+` concatenation (`BR_PARTY_PREFIX + "GUI/layouts/..."`) →
  the file. This is the one that proves `EnConstantEvaluator` folds through a `const string`.
- Ctrl+Click a bare `"STR_BR_..."` (server-side, no `#`) → its row in `LanguageCore/stringtable.csv`;
  and a `"#STR_PARTY_..."` inside a `.layout` → `Party/stringtable.csv`.
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
- Ctrl+Click each half of `"set:battleroyale_gui image:DayZBRLogo_White"` separately → the
  `ImageSetClass` and the `ImageSetDefClass` respectively.
- Ctrl+Click the RPC name in `AddRPC(RPC_DAYZBR_NAMESPACE, "SetPlayerCount", this)` → the handler
  method, with **no picker** — the `this` argument pins a single target.
- Ctrl+Click a method name in `AddTimer(duration, this, "OnPlayerCountChanged", ...)` → the method on
  that state, including one inherited from a superclass.
- Open a `.layout` file: the status bar must say **Enforce Layout**, not plain text; it folds, and
  `Ctrl+F12` shows a structure view of the widget tree.
- Trigger completion inside a `FindAnyWidget("")` call → only the names from layouts the class
  actually loads, not every widget in the project.

**config.cpp**

- `Scripts/Client/config.cpp` opens as **Enforce Config** (check the status bar), folds, and shows a
  structure view of the class tree.
- Ctrl+Click `"DayZExpansion_Scripts"` in `requiredAddons[]` → the `CfgPatches` class in Expansion's
  own `config.cpp`.
- Ctrl+Click `"BattleRoyale/Data/Inputs.xml"` → the file; `"…/Mod_Logo.paa"` → the texture.
- Ctrl+Click `"#STR_…"` and `"set:… image:…"` inside a config → the stringtable row and the imageset
  entry, resolved by the providers written for script and layouts.
- Ctrl+Click `displayName="$STR_CfgVehicles_…"` in a vanilla `P:\DZ` config → its row in the vanilla
  stringtable.
- Type `#ifdef ` in any `.c` file → the list offers `DAYZ_BATTLEROYALE` and `BR_PARTY` alongside
  the engine's own flags.
- Add `P:\DZ` to the roots in the settings → a warning about the first indexing appears; *Cancel*
  leaves the list unchanged. Add it again and confirm, wait for the scan, then Ctrl+Click
  `EffectArea` in `class BREffectArea: EffectArea` → vanilla's declaration. **Vanilla Configs**
  appears under *External Libraries*; confirm the IDE indexes configs only and does not sit on
  24 GB of `.paa`. Adding `P:\scripts` or a mod checkout shows no warning.
- A C or C++ file in an unrelated project is **not** claimed.

**Texture viewer**

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

**Texture previews on hover**

- Hover `"set:dayz_gui image:…"` in a `.layout` or a script → a popup with that one icon, its
  region (`… at x, y`) and the `.edds` path. The icon is sharp: the 2x atlas is the one used.
- Hover a mod's `"…/GUI/textures/….edds"` literal and a `picture="…/Mod_Logo.paa"` in a
  `config.cpp` → the whole texture, with size, format and mip count.
- Hover the name of an `ImageSetDefClass` inside an `.imageset` → the same cut-out icon.
- Hover a path to a BC7 font atlas → its preview. A broken path → a message, never an error.

**Texture icons in the gutter**

- Open a mod's `.imageset` → an icon beside each `ImageSetDefClass`, each showing its own image,
  and one beside the texture `path`. Hover an icon → its name and size.
- Open a busy `.layout` → an icon on each line with an `image0 "set:…"`; click one → the
  imageset entry. Typing in the layout stays smooth while the icons fill in.
- Open `P:\gui\imagesets\dayz_gui.imageset` (hundreds of entries) → icons appear after a moment,
  and scrolling and typing do not stall.
- Open a mod's `config.cpp` with `hiddenSelectionsTextures[]` → one icon per line. Untick "Texture
  preview in configs" → they disappear from configs only.

**Packing an imageset**

- Right-click a folder of PNG or TGA icons inside an addon → *Pack into Imageset…* → the texture path
  is the addon's prefix and the path to the `.edds`. *Pack* → the `.imageset` opens, with a gutter
  icon on each image showing the right picture; the `.edds` opens in the texture viewer, BGRA8 with
  its mips.
- Pack again with no change → Git shows no change to either file.
- Use one of the icons in a layout (`image0 "set:<name> image:<icon>"`) → the layout preview shows
  it. In game, the icon shows, with no neighbour bleeding into its edge.
- A folder with two `icon.png` in different subfolders → refused, naming `icon`.

**DayZ installation**

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

**Script modules**

- In a mod's `3_Game` file, declare a field of type `PlayerBase` → a warning that it is declared in
  `4_World`. In a `4_World` file, the same line → nothing.
- Call, from `3_Game`, a method that only a `4_World` `modded class` layer adds → a warning on the
  method name, none on the class name.
- In `3_Game`, write `modded class PlayerBase {}` → a warning on `PlayerBase`. A `PlayerBase` field in
  another `3_Game` file is still reported.
- A mod whose `config.cpp` lists non-standard folders in `defs` → its files follow the config, not
  their folder names.
- Open a few vanilla `P:\scripts\3_game` files → no warnings from this rule.

**Work drive**

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

**DayZ Tools**

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

**Running DayZ**

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

**New mods and addons**

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

**Game logs**

- Open *DayZ Logs* → *Script*, *RPT* and *Crash* tabs, each naming its file; start the game → the
  tabs switch to the new run and follow it.
- Select a DayZ run configuration with a server and two players → *Server*, *Alice* and *Bob* tabs
  of each kind, on their profile folders; select another configuration → the tabs follow it.
- In the *Script* tab, click an `@"scripts/3_Game/….c,17"` link → vanilla opens at
  that line; a mod's own `…(1):` compile error → the mod's file.
- *Errors and Warnings Only* → a stack trace stays under its error. *Open Older Run…* → the chosen
  file; *Follow Newest* → back.
- With no profile folder → a message pointing to the settings, no error.

**Building PBOs**

- Right-click a mod's addon folder → *Build PBO* → the options dialog, prefilled; *Build* → a *Run*
  tab with Addon Builder's log and *Build Successful*, the PBO in the output folder, and a
  notification. *Build PBO* again → no dialog.
- Break `config.cpp` (delete a `}`) and build without *Pack only* → *Build failed*, an error
  notification, and the `File …, line N` line is a link to the config.
- Build with a signing key → the `.bisign` appears; with no `.bikey` in `@Mod\Keys` → a warning.
- *Tools → DayZ → Build All PBOs* in a project with several addons → one tab, built one after the other.

**Layout preview**

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
- Open `P:\gui\layouts\day_z_hud_cars.layout` → the dials are grey arcs, not filled boxes; the RPM
  dial's last fifth is red, as are the low ends of the fuel and temperature dials.
- In `day_z_ingamemenu`, the modded-version warning wraps over three lines at the size Workbench
  shows. In `day_z_hud_cars`, the RPM needle points left.
- At 3840 × 2160 every outline is still visible. Scroll the wheel over a button → it zooms in around
  the pointer; drag → the view pans; a click without dragging still selects. *Fit* → back to the
  whole screen; *100%* → one layout pixel per screen pixel.

**Rename and inspections**

- `Shift+F6` on a method in a `modded class` that overrides vanilla → **refused**, with a message
  naming the script root. This is the guard, and it is the one that matters: without it the rename
  would rewrite `P:\scripts`, which is a library here but is an ordinary writable file on disk.
- `Shift+F6` on a class declared inside the mod → the declaration, every usage, its constructor and
  destructor, and any `modded class` layer of the same name all change together.
- `Shift+F6` on a widget name in a `.layout` → every `FindAnyWidget("…")` naming it updates too.
- `Shift+F6` to an illegal name (`class`, `2Fast`) → the dialog refuses it.
- Open a state file under `Scripts/Server/` and check the inspection warnings are **few and true**.
  Watch particularly for *"Variable named after a type"*: it is the one rule here with no corpus
  measurement behind it, so a wave of them on ordinary code means it is too eager and should be off
  by default.
- Break something on purpose — misspell the handler in `AddTimer(…, this, "OnTikc")`, or drop the
  `override` from a method in a `modded class` — and confirm each is flagged, then that `Alt+Enter`
  repairs it.

### 3. Compatibility verification

```bash
./gradlew verifyPlugin
```

Runs the JetBrains Plugin Verifier against `recommended()` IDEs. It downloads a full IDE per target,
so treat it as a pre-release gate rather than a per-change check.

The plugin is built against 2025.2 and declares `sinceBuild = 252` with `untilBuild` open; the
verifier is what proves each newer release. The verifier does **not** check bytecode versions, so
also run `./gradlew verifyPluginProjectConfiguration`, which catches a JVM target newer than the
oldest supported IDE can load.

Two **experimental API** usages are known and accepted, each load-bearing with no stable
equivalent:

- `AdditionalLibraryRootsListener.fireAdditionalLibraryChanged`, which targets a re-index at this
  one library instead of firing a full roots change. It is experimental in every supported release.
- `TargetPresentation` (with its builder) and `Pointer`, in the texture hover popup. They are the
  return types of `computePresentation()` and `createPointer()`, which every `DocumentationTarget`
  must implement, so there is no way to offer documentation through that API without them. They
  are flagged only on the older releases: `TargetPresentation` on 2025.2 and 2025.3, `Pointer` up
  to 2026.1. From 2026.2 neither is experimental, and a released IDE never changes them.

Either would break loudly rather than silently if the platform changed it, and the verifier is what
would catch it. **Internal** API usage is deliberately kept at zero. (`FileTypeOverrider` was a
further such usage until 2025.2, when it stopped being marked experimental.)

### Working from inside IntelliJ

Open this project in IDEA and `runIde` appears in the Gradle tool window under *Tasks → intellij
platform*. Running it in **debug** mode lets you hot-swap most Kotlin changes without restarting the
sandbox. Changes to `plugin.xml`, the `.flex` lexer or the `.bnf` grammar always need a restart, since
those are regenerated at build time.

Two plugins are worth having in your main IDE for this work:

- **Plugin DevKit** — bundled with IDEA Ultimate, must be enabled manually in Community.
- **PsiViewer** — adds `Tools → View PSI Structure`. This is the primary tool for
  debugging the grammar: it shows the live PSI tree for the file in the editor, including any
  `PsiErrorElement`.

### The corpus test

`EnfusionCorpusParseTest` parses the entire vanilla corpus under two preprocessor flag
configurations and asserts zero `PsiErrorElement`s, exact text round-tripping and no
`BAD_CHARACTER`. That both flag corners give identical results is what validates the preprocessing
lexer's branch policy.

A note on what it forced: a spec-faithful grammar cannot parse vanilla DayZ. The shipped sources
contain `break` and `return` without semicolons, two `proto` declarations with no terminator, a
missing comma between enum constants, a stray comma *before* an enum value (`GROUND, = 1`), a
trailing comma in an argument list, `for(;;;)` with a stray fourth clause, an `if` or `else` with no
body, statements before the first `case` of a `switch`, and a stray `;` between an attribute and its
declaration — all accepted by the engine compiler. Each corresponding leniency in `Enfusion.bnf` carries a comment
naming the file that forced it, so it does not get "tidied up" later. One file
(`3_game/global/ammotypes.c`, `map<string, int>>`) is genuinely malformed and is recorded as a known
defect rather than excused by loosening generic parsing everywhere.

`EnfusionCorpusLexTest` lexes the same corpus and asserts the lexer emits no `BAD_CHARACTER`,
leaves no gaps, and covers every file end to end. It also prints throughput — a
sudden drop is an early warning that something started backtracking:

```
Lexed 2811 files / 416578 lines / 2595003 tokens in 1154ms (360986 lines/s)
```

M1 adds a parsing counterpart asserting zero `PsiErrorElement`s and exact text round-tripping, run
under two different preprocessor define configurations.

`EnfusionTextureCorpusTest` decodes every `.edds` and `.paa` under `enfusionTextureRoots`
(sampled down to `enfusionCorpusLimit`, 2,000 per root by default) and fails on any file it calls
corrupt. A file in a format not decoded here is counted, not failed, and the run prints a breakdown
by format. Use `P:\gui`, `P:\DZ` (about 40,000 `.paa`, hence the sampling) and a few mods.

Both read their root from the `enfusionCorpusRoot` Gradle property and **skip** (rather than fail)
when it is unset or the path is absent, so CI works without it. The roots are machine-specific, so
set them in your user-level `~/.gradle/gradle.properties` rather than the committed one (the
committed `gradle.properties` lists every corpus property). Vanilla DayZ sources are proprietary and
are never committed to this repository.

## The `.c` extension collision

`.c` is also C. This plugin registers only `.es` by extension and claims `.c` **contextually** — a
`.c` file is treated as Enforce Script only when an ancestor directory contains a `config.cpp` or
`$PBOPREFIX$` marker. Ordinary C projects are untouched. This is not configurable yet.

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
