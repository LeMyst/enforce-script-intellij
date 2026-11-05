# Enforce Script for IntelliJ

IntelliJ IDEA language support for **Enforce Script**, the scripting language of DayZ mods
(`.c` files), focused on code navigation: go-to-declaration, find usages, `modded class` hierarchy,
structure view, and navigation from string literals into layouts, stringtables, imagesets and
inputs.

## What works today

- **Syntax highlighting** for the whole language, including doc comments and preprocessor directives.
- **Go to Declaration** (`Ctrl+Click` / `Ctrl+B`) on type names — in declarations, parameters, return
  types, `extends` clauses, generic arguments, `new X(...)`, and on static qualifiers such as
  `Math.Clamp(...)`. A name declared several times (vanilla `class X` plus one or more
  `modded class X`) offers every layer, because the layers are typically gated by mutually exclusive
  `#ifdef`s that no plugin can evaluate.
- **Go to Declaration** on locals, fields, `obj.method()` / `obj.field` member access and file-scope
  free functions, via lightweight type inference over declarations, parameters and `new X(...)`.
  Container element types are carried through, so `m_Zones.Get(0).Activate()` resolves — vanilla
  declares `array` and `map` accessors as returning a bare type parameter (`proto T Get(int n)`),
  so the element type exists only in the variable's own declaration. `auto` is inferred too, both
  from an initializer (`auto p = new Param1<int>(...)`) and from the collection in
  `foreach (auto x : ...)`.
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
  top of a class body, offers types only. Methods insert their parentheses. **Not offered:**
  keywords (`return`, `new`, `this`), a generic class's own type parameters (`T`), and named
  arguments or parameter hints.
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
- At the top of a class body type `Play` → type names (`PlayerBase`, `PlayerIdentity`); then after
  `PlayerBase ` type a field name → nothing is offered.
- Accept a method with parameters → the caret lands inside `(` `)`; one without → after `()`.
- During indexing → completion simply offers nothing, and **no `IndexNotReadyException`**.

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

One **experimental API** usage is known and accepted, load-bearing with no stable equivalent:
`AdditionalLibraryRootsListener.fireAdditionalLibraryChanged`, which targets a re-index at this one
library instead of firing a full roots change. It would break loudly rather than silently if the
platform changed it, and the verifier is what would catch it. **Internal** API usage is deliberately
kept at zero. (`FileTypeOverrider` was a second such usage until 2025.2, when it stopped being
marked experimental.)

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
