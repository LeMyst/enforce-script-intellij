---
title: Imagesets
parent: Game files
nav_order: 3
---

# Imagesets

**Packing an imageset** — *Pack into Imageset…* on a folder in the *Project* view packs its
pictures (PNG, TGA, BMP, GIF, JPEG and TIFF, at any depth) into one `.edds` atlas and the
`.imageset` that names them, both written next to the folder by default.

## Names and groups

Each picture is an image named after its file (`arrow_left.png` → `arrow_left`; characters a
`"set:… image:…"` string cannot hold become `_`). Two pictures with one name are refused.
*Subfolders become groups* puts each first-level subfolder's pictures in its own
`ImageSetGroupClass`.

## Placement

Pictures are placed largest first with MaxRects, on the smallest power-of-two atlas that holds
them (at most *Largest atlas side*). *Gap between pictures* is 0 by default, as DayZ's atlases
are packed: vanilla's images are mostly 0 or 1 pixel apart and against the atlas edge, mods'
almost all 0. The gap is only ever between pictures, never along the edge, so sixteen 64² icons
still fill a 256² atlas. A gap of 2 keeps an icon drawn smaller, from a smaller mip, from
blending into its neighbour; *Repeat each picture's edge* fills half the gap on each side with
the picture's own edge pixels.

## Output

The atlas is BGRA8, uncompressed, with a full mip chain: the format of most vanilla UI atlases
(13 of the 23 under `P:\gui\imagesets`), with every mip stored raw as in `dayz_gui.edds`, whose
header it copies (most others compress their larger mips). The `.imageset` lists the images by name
and names the atlas by its game path (the addon's prefix and the path inside it, prefilled), without
a `{GUID}`, as vanilla's `dayz_inventory.imageset` does.

**Repeatable:** the same pictures and options give byte-identical files, so packing again with
no change is no diff.

## Extracting the images

*Extract Images…* on an `.imageset` in the *Project* view cuts every image out of its atlas into a
PNG picture, in a folder named after the imageset inside the folder chosen. An image listed in an
`ImageSetGroupClass` goes into a subfolder named after the group, so packing the folder again
with *Subfolders become groups* gives back the same names and groups. An old atlas whose sources
were lost gets a source folder this way.

The images are cut from the sharpest texture the set names (the 2x one when there is one), at that
texture's own resolution, alpha kept. Images with the same name in one file get `_2`, `_3`, ...;
existing pictures are replaced after a confirmation.

## Not supported yet

Block compression (DXT1, DXT5, BC7) and LZ4-compressed mips, which make smaller files (a 1024² atlas
is 5.6 MB uncompressed); updating an existing imageset while keeping its images in place;
remembering the options per folder.
