---
title: Generating code
parent: Writing scripts
nav_order: 8
---

# Generating code

## Override Methods

**Override Methods** (*Code → Override Methods…*, `Ctrl+O`) inside a class or a `modded class`
lists the methods it can override, grouped by the class that declares them, and writes the picked
ones with `override` and a `super` call:

```c
override void EEInit()
{
	super.EEInit();
}

override bool CanPutInCargo(EntityAI parent)
{
	return super.CanPutInCargo(parent);
}
```

- **What is listed:** in a `modded class`, the methods of the class's other layers, each layer a
  group of its own with its file, then those of the superclass chain; in any other class, the
  chain. A method the class already overrides is left out, matched by name and parameter types so
  that the other overloads stay. So are static methods, `proto` and `sealed` ones, constructors and
  destructors, and a superclass's private methods. A `modded class` is the class it patches, so
  the private methods of its other layers are listed.
- **What is kept:** `protected` and `private`, and every parameter as declared: `out`, `inout`,
  `notnull`, generic types and default values. Where a base method gives a default, vanilla's
  overrides repeat it more often than they drop it, and the compiler accepts both. `event` is the
  base declaration's business and is not repeated, as vanilla writes `override void EOnFrame(…)`.
- **Call super**, a checkbox in the chooser, is on by default and remembered. Without it, a
  value-returning method returns `false`, `0`, `""`, `vector.Zero` or `null`, so the code compiles.
- The methods go after the member at the caret, indented by the formatter, and the caret ends up
  at the end of the first one's `super` call.
