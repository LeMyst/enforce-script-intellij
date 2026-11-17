---
title: Layouts
parent: Game files
nav_order: 1
---

# Layouts

## The layout language

**`.layout` / `.imageset` support** — a dedicated PSI language (not just syntax highlighting) with
structure view and folding, which is what makes widget and imageset navigation resolve to real
elements instead of a plain-text search. See [String references](../scripts/string-references.md).

## Layout preview

**Layout preview** — a `.layout` opens as text with a read-only preview beside it (*Layout* in the
editor's top-right switches between text, both, and preview only). The preview updates a moment
after the text changes.

### Placement and size

The widget tree is laid out on a screen of the chosen size (1920 × 1080 by default) the way the
vanilla layouts use the properties:

- `size` / `position` in pixels or as fractions of the parent (`hexactsize`, `vexactsize`,
  `hexactpos`, `vexactpos`);
- `halign` / `valign` with offsets from the matching edge;
- grid spacers in `Rows` × `Columns` cells (more rows when the children need them, sized to their
  content on an axis with *Size To Content*, `Margin` inside the edges and `Padding` between the
  cells, proportional children measured against the room left inside);
- text sized to its text with `"size to text h"` / `"size to text v"` (lines as tall as the font's
  `.fnt` says, the width estimated);
- wrap spacers flowing their children (lines placed by `content_halign` / `content_valign`,
  `bottom` stacking them upwards), a `visible 0` child taking no slot, a wrap spacer's
  *Size To Content* fitting it to its children;
- and children cut off at their parent's edges unless it says `clipchildren 0`.

Siblings are drawn by `priority`, lowest first. With `fixaspect`, a proportional `size` gives only a
ratio: `outside` covers the parent with a box of that ratio, `inside` fits one in it
(`size 1.6 0.9` is a full 16:9 screen), and `fixwidth` (`fixheight`) derives the width (height) from
the other side.

### Styles and colours

Panels, buttons, windows and spacers are drawn in their **`style`**, as the game draws them: the
style of that name for the widget's class in the `*.styles` files the project sees (vanilla's
`gui/looknfeel/dayzwidgets.styles`, now a library root, and a mod's own), its `Normal` state's nine
imageset pictures framing the box (corners at their size, edges and centre stretched), tinted by the
widget's `color`. A widget with no `style` wears its class's `Default`.

Where the style or its pictures are not found, or the style draws nothing (`blank`,
`ColorablePanel`), a panel is filled with its plain `color`, and a button is left empty (its colour
tints its style, so it would be a block of colour).

Every `color` is linear, as the engine's, and is shown in sRGB, so `0.141 0.141 0.141` is the
mid-grey Workbench shows, not near black.

### Images

Images show their `image0` (an imageset entry or a texture) or `imageTexture`, through the same
cache as the [texture hover](textures.md#texture-previews-on-hover), stretched to their box, tinted
by their `color`, cut by their `Mask` at their `Progress` and mirrored by `flip u` / `flip v`, and
turned by their `rotation` about their `pivot`, the centre by default (the outline stays upright, as
in Workbench).

A texture path no root holds, such as vanilla's `Gui/textures/…`, is looked up on the work drive
when the project indexes it; an image that is not found is only outlined.

### Text

Text widgets show their `text` at `"exact text size"` pixels (with `"exact text" 1` and no size,
their `font`'s own size: 24 for `sdf_MetronBook24`), or `text_proportion` of their height, wrapped
to the box with `wrap 1` and placed by `"text halign"` / `"text valign"` (top left by default) and
`text_offset`.

A `#key` text shows in the language chosen in the toolbar, from the stringtables that declare the
key (the project's and the vanilla one), falling back to `original`, then to the key; *Keys* shows
the keys themselves. The choice is kept for every layout.

### Other widgets

Check boxes get their box, combo boxes their arrows, and scroll widgets and the server browser
their scroll bars, drawn plainly. Every widget is outlined; *Hidden widgets* shows the ones with
`visible 0`, dashed.

### Selecting and zooming

Clicking a widget puts the caret on its declaration: the smallest widget under the pointer, then,
clicking the same spot again, the next larger one, and the caret's widget is outlined in blue,
its whole box even where its parent cuts it off.

The wheel zooms around the pointer, dragging pans, *Fit* fits the screen to the pane again and
*100%* shows it at one layout pixel per screen pixel; outlines stay one pixel wide at any zoom.

### What the preview does not model

**Approximate, not pixel-exact:**

- the typeface is the IDE's, not the layout's `font`;
- styles are drawn in their `Normal` state only (no focus or hover), and their fonts and text
  colours are not used;
- `Padding` / `Margin` on wrap spacers, *Size To Content* outside spacers, scaling, the rotation of
  children and blend modes (`mode additive`) are not modelled;
- and script classes do not run.

Editing in the preview (moving, resizing, a properties panel) is not supported.
