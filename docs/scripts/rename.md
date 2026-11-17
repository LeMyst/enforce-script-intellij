---
title: Rename
parent: Writing scripts
nav_order: 5
---

# Rename

**Rename** (`Shift+F6`) on any declaration, including the three bindings that carry no reference
and that a rename following only references would leave broken: a `modded class` and the class it
patches, an `override` and what it overrides, and a constructor named after its class.

It also crosses into strings — renaming a widget in a `.layout` updates every `FindAnyWidget("…")`
that names it.

A rename whose family reaches vanilla is **refused with an explanation** rather than half-applied,
since the game's extracted source on `P:` is a library here but is an ordinary writable file on
disk.
