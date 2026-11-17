---
title: Highlighting, folding and formatting
parent: Writing scripts
nav_order: 4
---

# Highlighting, folding and formatting

## Syntax highlighting

**Syntax highlighting** for the whole language, including doc comments and preprocessor directives.
Typing `"` inserts its closing quote, and a string left without one is reported on the string
itself (*Missing closing quote*) rather than as a parse error on whatever follows it.

## Code folding

**Code folding** (`Ctrl+.` / the gutter `-`/`+`) on every brace-delimited block that spans more than
one line: class bodies, method/statement bodies, enum and switch bodies, array literals in
Enforce Script; widget declarations and their children blocks in `.layout`/`.imageset`.

Also on `#ifdef`/`#ifndef` regions, which fold whole (not per `#else` arm) and keep the flag visible
while collapsed — `#ifdef SERVER ... #endif`. A branch the current define set *excludes* folds too,
and starts collapsed.

## Formatter

**Formatter** (`Ctrl+Alt+L`) — **indentation only**, deliberately. It adds no spacing or wrapping
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
