---
title: Language notes
parent: Contributing
nav_order: 3
---

# Design notes worth knowing

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

The leniencies the grammar needs to parse vanilla at all are listed under
[Corpus tests](testing.md#corpus-tests).
