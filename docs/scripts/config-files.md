---
title: Config files
parent: Writing scripts
nav_order: 7
---

# Config files

**`config.cpp` support** — a third language, covering `config.cpp`, `config.cpp.disabled` and
`mod.cpp` (claimed by exact file name, so it never competes with C++ over `.cpp`). Highlighting,
folding, brace matching, structure view, Find Usages and rename, plus the navigation that makes a
config worth opening:

- `class X : Y` resolves to Y's declaration in any indexed config;
- `requiredAddons[]` jumps to that addon's `CfgPatches` class — the mod load order, which exists
  nowhere else as a link;
- `inputs`, `picture` and `files[]` resolve to the file or folder they name.

`displayName="$STR_…"` (or `"#STR_…"`) and `ghostIcon="set:… image:…"` work through the
*existing* stringtable and imageset providers (see [String references](string-references.md)).

Vanilla's own configs are opt-in: add the unpacked game data (`P:\DZ`) to the roots and only its 207
configs are indexed, but they sit inside 24 GB of unpacked game that must be walked once, so the
settings page warns before adding it. See [Library roots](../setup/library-roots.md).

The `defines[]` of the project's configs feed `#ifdef` completion: see
[Completion](completion.md#ifdef-flags).

## Formatter

**Formatter** (`Ctrl+Alt+L`) for `config.cpp` and `mod.cpp`, **indentation only**, like the script
one: spacing (`scope=2;` or `scope = 2;`) and line breaks are left as written, and so is an array
written on one line (`units[]={};`). One level deeper:

- the contents of a class body;
- the elements of an array initializer written over several lines (`requiredAddons[]=` then
  `{ … };`), and of the arrays nested in it.

A closing `}` / `};`, and a `{` on a line of its own, line up with the line that opened them.
`#define` and other preprocessor lines stay at the first column. Enter follows the same rules:
after a class's or an array's `{`, the caret goes one level deeper.

Tabs by default, under *Settings → Editor → Code Style → Enforce Config*. The 207 vanilla configs
on `P:\DZ` are written exactly this way (tabs, an array's `{` on the line after its name, no spaces
around `=`), and a test reformats every one of them and checks that no line moves.
