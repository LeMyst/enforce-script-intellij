---
title: Highlighting, folding and formatting
parent: Writing scripts
nav_order: 4
---

# Highlighting, folding and formatting

## Syntax highlighting

**Syntax highlighting** for the whole language, including doc comments and preprocessor directives.
Typing `"` inserts its closing quote, and a string left without one is reported on the string
itself (*Missing closing quote*) rather than as a parse error on whatever follows it. An unclosed
`/*` is reported as *Unclosed comment*, in scripts, layouts and configs. The colours are under
*Settings → Editor → Color Scheme → Enforce Script*. *Comment with Line Comment* and *Block
Comment* work, and braces, brackets and parentheses are matched.

## Colour swatches

**Colour swatches** in the gutter beside each colour, and the IDE's colour picker when one is
clicked, writing the picked colour back in the notation it was read from:

| Where | Notation | Written back |
|---|---|---|
| Layouts | `color 0.141 0.141 0.141 1`, `"text color"`, `"disabled text color"`, `"shadow color"`, `"outline color"` and any other `"… color"` | the channels, three decimals as vanilla writes them; three channels stay three |
| `.styles` | `Color="4294967295"`, an ARGB integer | the integer |
| Script | `ARGB(255, 242, 38, 19)` and `ARGBF(1, 0.95, 0.15, 0.07)` with literal colour channels; a computed alpha (`ARGB(m_Alpha, 242, 199, 68)`) is shown opaque | the same call, bytes or two decimals; a computed alpha is kept as written |
| Script | `const int COLOR_RED = 0xFFF22613;`, a constant whose name says colour | `0xAARRGGBB` |
| Configs and script strings | procedural textures, `"#(argb,8,8,3)color(0.5,0.5,0.5,1,co)"` | the four channels |

Layout channels and script `ARGB` bytes are the same colour space: `0.141` is `36` of 255, the near
black `#242424`. Measured in game, an `ImageWidget` with `color 0.141 0.141 0.141 1` and one given
`ARGB(255, 36, 36, 36)` came out the same `#242424`, so both swatches show the value as written.
Workbench's editor shows `0.141` as `#696969`; the swatch and the layout preview follow the game.
**Not covered:** a hex literal that is not a `const int` named for a colour, since a
flag or a mask would get a swatch too; and `color[]` arrays in configs, whose light and particle
values can exceed 1. The plugin opens `*.styles` files as XML, so they get the IDE's XML editing as
well.

## Code folding

**Code folding** (`Ctrl+.` / the gutter `-`/`+`) on every brace-delimited block that spans more than
one line: class bodies, method/statement bodies, enum and switch bodies, array literals in
Enforce Script; widget declarations and their children blocks in `.layout`/`.imageset`.

Also on `#ifdef`/`#ifndef` regions, which fold whole (not per `#else` arm) and keep the flag visible
while collapsed — `#ifdef SERVER ... #endif`. A branch the current define set *excludes* folds too,
and starts collapsed.

Stringtable keys fold to their text: see
[Stringtables](../game-files/stringtables.md#texts-in-place-of-keys).

## Formatter

**Formatter** (`Ctrl+Alt+L`) — **indentation only**, deliberately. It adds no spacing or wrapping
rules, so Allman braces and however you spaced a line survive untouched, including the tabs
vanilla uses to align names (`protected Camera<tabs>m_Camera;`); it defaults to tabs because
vanilla is tab-indented without exception. One level deeper, each as vanilla writes it
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
