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
On a `modded class`, an upward icon leads to the class it patches, listed first, and to the other
layers of that class. A constructor leads only to constructors and a destructor only to
destructors, although `~PlayerBase()` carries the class's name too.

**Go to Super Method** (`Ctrl+U`) goes the same way from anywhere in the method, and from
elsewhere in a class to the class it extends or, for a `modded class`, the class it patches.

An original declaration overrides only its superclass: its own `modded class` layers come after
it. A layer that does not repeat `extends` still overrides what the original's superclass
declares, so `modded class PlayerBase { override void EEInit() }` reaches `EntityAI`'s `EEInit`
when vanilla's `PlayerBase` declares none.

## Layers and overrides below

The other direction, *who else redeclares this?*, which is the first question when two mods
conflict over `OnScheduledTick` or `SetActions`:

- **On a class**, a gutter icon when it has `modded class` layers or subclasses. Its tooltip
  counts them (*Modded by 3 layers, extended by 2 subclasses*), and clicking it lists each one with
  its file. Subclasses are counted transitively, each with its own layers.
- **On a method**, an icon when `modded class` layers redeclare it or subclasses (and their
  layers) override it, with the same list.
- **Go to Implementation** (`Ctrl+Alt+B`, or *Navigate → Implementation(s)*) on a class or
  method, at its declaration or at a reference, gives the same list. Put the caret on
  `SetActions` in vanilla's `ItemBase`, or on a call such as `item.SetActions()`, and press it.

A layer gets no icon for the other layers of its class: there is no load order the plugin can
know, and the upward icon already links them. It works in read-only library files too, so
opening vanilla's `PlayerBase.c` shows which layers patch it. The icons are computed in the
background once the file is highlighted, each class once for all its methods; past 5,000 classes
below one class the list stops growing.

## Quick Documentation

**Quick Documentation** (`Ctrl+Q`, and on hover when *Show quick documentation on mouse move*
is on) on classes, enums, typedefs, methods, fields, globals, enum constants, locals and
parameters, at a reference or at the declaration itself. Most of the engine API is `proto native`
with no body, so its comment is all there is to read.

- **The signature** as declared, with attributes such as `[Obsolete("...")]` above it.
- **The comment:** a `/** */` or `/*! */` block or consecutive `//!` lines directly above the
  declaration, with no blank line in between, or a `//!<` (or `//!`) after it on the same line.
  A field, constant or enum constant also takes a plain `//` after it on the same line, which is
  how vanilla documents most of its constants (`const int ECE_TRACE = 4; // trace under entity…`).
  `\brief`, `\param`, `\return`, `\note`, `\warning`, `\see`, `\p` and `@code … @endcode` are
  rendered; `\fn`, `\defgroup`, `\addtogroup`, `@{` and `@}` only group declarations for
  Doxygen's own pages and are left out. Every comment is shown as text, never as markup.
- **A layer without a comment** takes the original declaration's, then another layer's, then the
  comment of what it overrides, and says where it came from. A reference that resolves to several
  `modded class` layers documents the original declaration.
- **Where it comes from:** the declaring class, the script module (`3_Game`, ...) and the file.
  A class shows its `extends` chain and every `modded class` layer; a method, the other layers
  that redeclare it and what it overrides. Each is a link.
- **Values:** an enum constant's value, implicit ones included (the constant before it plus one),
  and a `const int`'s, in hex too, and as the flags declared beside it when they cover it
  exactly (`1060 (0x424) = ECE_TRACE | ECE_UPDATEPATHGRAPH | ECE_CREATEPHYSICS`). A
  `const string` shows its folded text.
- **Types:** a local, field or parameter shows its type, linked; for `auto`, the type inferred.
  A parameter shows its method's `\param` text.

`Ctrl` + hover on a name shows the signature, its class and its file on one line.

Rendered over every one of the 8,731 documented declarations in the vanilla scripts in about 20
seconds altogether, none taking more than 60 ms. **Not supported:** stringtable texts and
texture previews stay on their strings; see [String references](string-references.md).

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
