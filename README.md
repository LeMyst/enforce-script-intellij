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
  (`"#STR_BR_..."` or the bare server-side form) to its row in `stringtable.csv`; each half of
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
  BGRA/BGRX/RGBA, DXT1, DXT3 and DXT5, raw or LZ4-compressed, which covers every `.edds` in the
  vanilla `gui` folder except the font atlases. For `.paa` it decodes DXT1 to DXT5, raw or
  LZO-compressed: all but three of the 40,055 `.paa` files in the vanilla game data. **Not
  supported:** BC7 `.edds`, used only by those 17 font atlases; the uncompressed `.paa` formats
  (ARGB4444, ARGB1555, ARGB8888, AI88), used by three vanilla detail and noise maps; and cube maps.
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
  BC7 font atlases, get no icon.
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
  because vanilla is tab-indented without exception. A test formats 150 vanilla files and asserts no
  token ever moves between lines. `#ifdef` / `#else` / `#endif` are pinned to the first column, which
  is the one formatting choice the corpus does *not* settle — vanilla writes it both ways — so it is
  a checkbox under *Code Style | Enforce Script | Wrapping and Braces*, not a rule.
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
  name. `displayName="#STR_…"` and `ghostIcon="set:… image:…"` work through the *existing* stringtable
  and imageset providers, reused unchanged. Vanilla's own configs are opt-in: add the unpacked game
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

## Configuration

**Settings → Languages & Frameworks → Enforce Script.**

The vanilla script root is auto-detected on first open of a DayZ addon (a project containing a
`config.cpp`); dependency mod checkouts found next to the project are offered rather than added
unasked. The list is ordered, and the order is the tiebreak when the same class name is declared in
more than one root. The unpacked game data (`P:\DZ`) is never added automatically: add it by hand to
navigate into vanilla configs. Only its `config.cpp` files are indexed, and the settings page warns
first, because finding them means walking the whole folder once.

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

- **Detected, not typed.** A field left empty uses the detected location, shown in grey. The DayZ,
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
- When a configured root is on the work drive and the drive is not mounted, the missing-roots
  notification at project open says so and offers *Mount P:*.
- A mount belongs to the Windows session that made it: a drive mounted from an elevated process is
  invisible to a normal one, and the reverse. If the IDE runs elevated and DayZ Tools does not (or
  the other way round), each sees its own.
- **Not supported:** extracting the game data onto the drive (tracked separately), and choosing
  the letter for WorkDrive.exe, which reads it from DayZ Tools' settings.

## Error reports

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

- A balloon reports the detected vanilla root on first open, and **Enforce Script Sources** appears
  under *External Libraries*. A root that cannot be resolved is named in a warning rather than
  silently ignored.
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
  entry, resolved by the providers written for script and layouts with no config-specific code.
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
- Open one of the `P:\gui\fonts` BC7 atlases → a message naming BC7, not an error dialog.
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
- A BC7 font atlas or a broken path → a message, never an error.

**Texture icons in the gutter**

- Open a mod's `.imageset` → an icon beside each `ImageSetDefClass`, each showing its own image,
  and one beside the texture `path`. Hover an icon → its name and size.
- Open a busy `.layout` → an icon on each line with an `image0 "set:…"`; click one → the
  imageset entry. Typing in the layout stays smooth while the icons fill in.
- Open `P:\gui\imagesets\dayz_gui.imageset` (hundreds of entries) → icons appear after a moment,
  and scrolling and typing do not stall.
- Open a mod's `config.cpp` with `hiddenSelectionsTextures[]` → one icon per line. Untick "Texture
  preview in configs" → they disappear from configs only.

**DayZ installation**

- Open *Settings → … → Enforce Script → DayZ Installation* → after a moment every field shows the
  detected folder in grey and the line at the bottom names what was found. Settings opens without a
  freeze while detection runs.
- Type a wrong folder into *DayZ* → a warning that `DayZ_x64.exe` is not there; *OK* still saves.
- Clear it again → back to the detected path. Restart the IDE → the typed paths are kept, in
  `options/enfusion-dayz.xml` of the IDE's config folder, not in `.idea`.
- On a machine without DayZ Tools → *Not detected* in its field and no error.

**Work drive**

- With `P:` mounted → the status bar shows `P: → <folder>`. *Tools → DayZ → Unmount Work Drive*
  → a notification, the status bar shows `P: not mounted`, and `P:\scripts` navigation stops.
- *Mount Work Drive* → `P:` returns, the status bar updates, and Ctrl+Click into vanilla works again
  without a restart.
- Restart the IDE with `P:` unmounted → the missing-roots notification offers *Mount P:*.
- *Mount* while `P:` is already mounted → a warning, nothing run.
- Rename DayZ Tools' `WorkDrive.exe` temporarily → mounting falls back to `subst`.

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
