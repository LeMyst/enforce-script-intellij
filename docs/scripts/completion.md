---
title: Completion and parameter info
parent: Writing scripts
nav_order: 2
---

# Completion and parameter info

## Code completion

**Code completion** for identifiers, answered by the same type inference as
[Go to Declaration](navigation.md), so a name is offered exactly where Ctrl+Click on it would
resolve.

After a dot it lists the receiver's members from every `modded class` layer and the superclass
chain, once per signature, so an override redeclared by four layers appears once while overloads
each stay. The popup opens on `.` without Ctrl+Space. Through a class name (`Math.`) only statics and
constants appear, through an enum only its constants, and `super.` in a `modded class` reaches the
other layers but never its own.

A bare name offers, innermost first: parameters and the locals declared above the caret in an
enclosing block, then the class's members, then file-scope functions and globals (`GetGame`,
`g_Game`), then type names. A type position, including a half-written field at the top of a class
body, offers types only. Methods insert their parentheses.

## Keywords

**Keywords** are offered by position rather than all at once: statements and local modifiers at
the start of a statement (`else` only after an `if` without one, `case` and `default` only in
a `switch`), `null` / `true` / `this` / `new` inside an expression, `out` / `inout` / `notnull`
in a parameter list, member modifiers in a class body, `class` / `modded` / `enum` / `typedef`
at file scope, `class` after `modded`, and `extends` after a class name. A keyword that is
followed by something inserts its space; `return` does so only when the method returns a
value. The engine-binding modifiers (`owned`, `external`) are left out, since only vanilla's
`proto` declarations use them. **Not offered:** a generic class's own type parameters (`T`).

## Parameter info

**Parameter info** (`Ctrl+P`, and on its own when `(` is typed or a method is accepted from
completion) inside a call's parentheses: the parameters of every overload the call may mean,
with the one the caret is in highlighted and default values shown (`bool create_local =
false`). Most of the engine API is `proto native` with no documentation beyond its signature, so
this is usually the only place to read it without leaving the call.

It covers member calls, `super.` calls, methods of the enclosing class and its layers, free
functions, and `new X(...)` constructors. All overloads are listed even where Ctrl+Click narrows to
one, since a call being typed has not chosen yet; one that cannot take the arguments typed so far is
greyed out, and an override redeclared by several `modded class` layers is shown once.

## Inlay hints

**Parameter names** before the literal arguments of a call, so that
`CallLater(Refresh, 1000, false)` reads `CallLater(Refresh, delay: 1000, repeat: false)`:

- Only before a number (a negated one too), `true` / `false`, `null` or a string, as Java does.
  A name or a constant usually says what it is already.
- Only when every overload the call may mean has the same parameter name at that position, so a
  hint is never a guess; and not for a one-parameter method named `Set…`.
- An exclude list under *Settings → Editor → Inlay Hints → Parameter names → Enforce Script*,
  prefilled with the calls whose arguments are obvious: `Print`, `Error`, `Math.*`, `Vector`,
  container access (`*.Get`, `*.Insert`, `*.Find`, ...) and `string.Format`. *Do not show hints
  for current method* on a hint adds its method.

**Types of `auto` variables**, after the name: `auto p: Param1<int> = new Param1<int>(5)` and
`foreach (auto zone: Zone : m_Zones)`, from the same inference as Go to Declaration. Nothing is
shown when inference gives up, or finds several types that disagree. It is under *Settings →
Editor → Inlay Hints → Types*.

## `#ifdef` flags

**`#ifdef` completion** over the engine's flags *plus* every flag declared in `CfgMods` →
`defines[]` of any indexed `config.cpp`, the project's and its dependency mods' — `MYMOD`, `MYMOD_PARTY`, `MYMOD_DEBUG`. Those exist nowhere but a
config array, and a mistyped one silently disables the code it guards with no error at build time
or after.

## In string literals

Completion inside string literals is scoped like their navigation: see
[String references](string-references.md).
