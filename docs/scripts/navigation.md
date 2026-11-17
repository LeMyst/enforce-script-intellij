---
title: Navigation
parent: Writing scripts
nav_order: 1
---

# Navigation

## Go to Declaration on types

**Go to Declaration** (`Ctrl+Click` / `Ctrl+B`) on type names — in declarations, parameters, return
types, `extends` clauses, generic arguments, `new X(...)`, and on static qualifiers such as
`Math.Clamp(...)`. A name declared several times (vanilla `class X` plus one or more
`modded class X`) offers every layer, because the layers are typically gated by mutually exclusive
`#ifdef`s that no plugin can evaluate.

## Go to Declaration on members, locals and functions

**Go to Declaration** on locals, fields, `obj.method()` / `obj.field` member access and file-scope
free functions, via lightweight type inference over declarations, parameters and `new X(...)`.

Container element types are carried through, so `m_Zones.Get(0).Activate()` resolves — vanilla
declares `array` and `map` accessors as returning a bare type parameter (`proto T Get(int n)`),
so the element type exists only in the variable's own declaration.

A class that names no superclass still inherits from `Class`, vanilla's root of every class, so
`Widget.Cast(w)` and `w.ClassName()` reach it even though `Widget` extends `Managed` and `Managed`
extends nothing.

`auto` is inferred too, both from an initializer (`auto p = new Param1<int>(...)`) and from the
collection in `foreach (auto x : ...)`.

## Overrides

**Go to Declaration and a gutter icon on `override` methods**, linking back to what they override —
another `modded class` layer of the same class, or a superclass. A method's own name is a
declaration rather than a reference, so without this Ctrl+Click on it did nothing at all.

## Go to Class and Go to Symbol

**Go to Class** (`Ctrl+N`) over classes, enums and typedefs; **Go to Symbol**
(`Ctrl+Shift+Alt+N`) over methods, fields, globals and enum constants.

## Find Usages

**Find Usages** (`Alt+F7`) on any declaration — needs no resolver of its own, since every usage is
already a real `PsiReference` and the platform's default search runs on top of that.

## Class hierarchy

**Class hierarchy** (`Ctrl+H`) modelling both relationships the language has: `extends`, and
`modded class` layering. Every layer of a class hangs off the base, which is the view that matters
here — a `modded class X` names nothing at all, so the link to what it patches exists only because
the names match. `Ctrl+H` on a type *reference* opens that type's hierarchy, not the enclosing
class's.

## Structure view

**Structure view** (`Ctrl+F12`) with icons for static, const and override members.

## From string literals

Widget names, layout and asset paths, stringtable keys, imageset images, RPC and timer names and
keybind actions navigate too: see [String references](string-references.md).
