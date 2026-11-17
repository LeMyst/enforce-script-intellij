---
title: Textures
parent: Game files
nav_order: 2
---

# Textures

## Texture viewer

**`.edds` and `.paa` texture viewer** — opening a texture shows the image instead of binary content,
with zoom (toolbar or `Ctrl`+wheel), fit to window, actual size, a transparency checkerboard that can
be switched off (for textures that keep data in alpha), and a picker for every mip level. The status
line gives size, pixel format, mip count and compression.

### Formats

- For `.edds` it decodes uncompressed BGRA/BGRX/RGBA, DXT1, DXT3, DXT5 and BC7, raw or
  LZ4-compressed, which covers every `.edds` in the vanilla `gui` folder, the BC7 font atlases
  included.
- For `.paa` it decodes DXT1 to DXT5, raw or LZO-compressed: all but three of the 40,055 `.paa`
  files in the vanilla game data.

**Not supported:** BC4, BC5 and BC6H `.edds`, which the game data does not use; the uncompressed
`.paa` formats (ARGB4444, ARGB1555, ARGB8888, AI88), used by three vanilla detail and noise maps;
and cube maps. The viewer names an unsupported format instead of showing it.

It opens on the largest mip of at most 4096 × 4096; larger mips are in the picker. Both extensions
are a binary file type, so Find in Files and Replace in Files skip textures instead of matching
inside their bytes: there is no need to exclude texture folders to keep search results clean.

### Imageset outlines

Over an atlas, the viewer outlines every image that an imageset places on it, on the 1x and the 2x
texture alike: an icon with no outline has no `ImageSetDefClass` yet, and the free space in the
atlas is easy to see. Hovering an outline names the image and its region, and double-clicking opens
its entry. *Show Imageset Regions* in the toolbar turns the outlines off.

## Texture previews on hover

**Texture previews on hover** — hovering a texture path (`"…/loading_1.edds"`,
`picture="…/Mod_Logo.paa"`) or an imageset image (`"set:dayz_gui image:DayZLogo"`, in script,
layouts and configs) shows the image in the documentation popup, with its size, format and path.

An imageset image is cut out of its atlas, so the popup shows that one icon rather than the whole
sheet; when an imageset names a 1x and a 2x texture, the sharper one is used. Only the mip the
preview needs is decoded, and previews are cached until the texture changes.

**Not covered:** vanilla textures outside `gui/imagesets`, such as `P:\gui\textures`, are not in the
library roots, so a path into them does not resolve and shows nothing. Nor do imagesets and textures
in a folder marked **Excluded**, which the IDE does not index. After un-excluding one, if the preview
still does not appear, *File → Invalidate Caches…* makes the IDE index it.

## Texture icons in the gutter

**Texture icons in the gutter** — a small thumbnail beside each image of an `.imageset`, and
beside every line of script, layout or config naming a texture or an imageset image. Clicking
one goes to the texture or the imageset entry, and its tooltip gives the name and size. A line
naming several textures shows one icon.

Icons are computed in the background and share the hover previews' cache, so an imageset's atlas is
decoded once for all its icons. Each can be turned off under
*Settings → Editor → General → Gutter Icons*: "Texture preview", and "Texture preview in configs"
for `config.cpp` and `mod.cpp` on their own. Textures that cannot be previewed, such as an ARGB4444
`.paa`, get no icon.

## Making an atlas

A folder of pictures can be packed into an `.edds` atlas and its `.imageset`: see
[Imagesets](imagesets.md).
