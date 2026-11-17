package io.github.lemyst.enfusion.texture.format

/**
 * Packs pictures into an imageset: the atlas [AtlasPacker] lays out, its pixels, and the `.imageset`
 * descriptor naming each picture's `Pos` and `Size`, as vanilla's are written. No IntelliJ API.
 *
 * The same pictures and options always give the same atlas and the same text: pictures are placed
 * in a fixed order and listed by name, so packing again after no change is no diff.
 */
object ImageSetPacking {

    /** A picture: its image name, its group (null for none) and its pixels. */
    class Source(val name: String, val group: String?, val width: Int, val height: Int, val argb: IntArray)

    class Options(
        val padding: Int = 2,
        val maxSide: Int = 4096,
        /** Repeat each picture's edge pixels into its padding, so filtering at the edge samples the picture, not the transparent gap. */
        val extrude: Boolean = true,
    )

    class Packed(val width: Int, val height: Int, val argb: IntArray, val placements: List<Placed>)

    class Placed(val source: Source, val x: Int, val y: Int)

    class Problem(message: String) : Exception(message)

    /**
     * An image name from a file name: what `"set:… image:…"` can name, so letters, digits and `_`
     * (the space ends the image half of the string).
     */
    fun imageName(fileName: String): String {
        val base = fileName.substringBeforeLast('.')
        val clean = base.replace(Regex("[^A-Za-z0-9_]"), "_")
        return if (clean.firstOrNull()?.isDigit() == true) "_$clean" else clean.ifEmpty { "_" }
    }

    fun pack(sources: List<Source>, options: Options = Options()): Packed {
        val clashes = sources.groupBy { it.name.lowercase() }.filterValues { it.size > 1 }.keys
        if (clashes.isNotEmpty()) {
            throw Problem("Two pictures would be named ${clashes.sorted().joinToString(", ")}: an image name is one per imageset, groups or not.")
        }
        val byName = sources.associateBy { it.name }
        val atlas = try {
            AtlasPacker.pack(sources.map { AtlasPacker.Sprite(it.name, it.width, it.height) }, options.padding, options.maxSide)
        } catch (e: AtlasPacker.DoesNotFit) {
            throw Problem(e.message ?: "the pictures do not fit")
        }
        val pixels = IntArray(atlas.width * atlas.height)
        val placed = atlas.placements.map { p -> Placed(byName.getValue(p.sprite.name), p.x, p.y) }
        for (p in placed) {
            val s = p.source
            for (y in 0 until s.height) System.arraycopy(s.argb, y * s.width, pixels, (p.y + y) * atlas.width + p.x, s.width)
            if (options.extrude) extrude(pixels, atlas.width, p, options.padding)
        }
        return Packed(atlas.width, atlas.height, pixels, placed.sortedBy { it.source.name.lowercase() })
    }

    /** Fills the [padding] round a picture with its nearest edge pixel; the padding is the picture's own. */
    private fun extrude(pixels: IntArray, stride: Int, p: Placed, padding: Int) {
        val s = p.source
        for (dy in -padding until s.height + padding) {
            val sy = dy.coerceIn(0, s.height - 1)
            for (dx in -padding until s.width + padding) {
                if (dx in 0 until s.width && dy in 0 until s.height) continue
                val sx = dx.coerceIn(0, s.width - 1)
                pixels[(p.y + dy) * stride + p.x + dx] = s.argb[sy * s.width + sx]
            }
        }
    }

    /**
     * The `.imageset` for [packed], naming the atlas [texturePath] (the game's path to it, without
     * a `{GUID}`, as vanilla's `dayz_inventory.imageset` writes it). Pictures without a group go
     * under `Images`, the others under their group in `Groups`, each list by name.
     */
    fun imageSet(name: String, texturePath: String, packed: Packed): String = buildString {
        append("ImageSetClass {\n")
        append(" Name ").append(quote(name)).append('\n')
        append(" RefSize ").append(packed.width).append(' ').append(packed.height).append('\n')
        append(" Textures {\n")
        append("  ImageSetTextureClass {\n")
        append("   mpix 1\n")
        append("   path ").append(quote(texturePath)).append('\n')
        append("  }\n")
        append(" }\n")
        val (loose, grouped) = packed.placements.partition { it.source.group == null }
        append(" Images {\n")
        loose.forEach { image(it, "  ") }
        append(" }\n")
        if (grouped.isNotEmpty()) {
            append(" Groups {\n")
            for ((group, members) in grouped.groupBy { it.source.group!! }.toSortedMap(String.CASE_INSENSITIVE_ORDER)) {
                append("  ImageSetGroupClass ").append(imageName(group)).append(" {\n")
                append("   Name ").append(quote(group)).append('\n')
                append("   Images {\n")
                members.forEach { image(it, "    ") }
                append("   }\n")
                append("  }\n")
            }
            append(" }\n")
        }
        append("}\n")
    }

    private fun StringBuilder.image(p: Placed, indent: String) {
        val s = p.source
        append(indent).append("ImageSetDefClass ").append(s.name).append(" {\n")
        append(indent).append(" Name ").append(quote(s.name)).append('\n')
        append(indent).append(" Pos ").append(p.x).append(' ').append(p.y).append('\n')
        append(indent).append(" Size ").append(s.width).append(' ').append(s.height).append('\n')
        append(indent).append(" Flags 0\n")
        append(indent).append("}\n")
    }

    private fun quote(value: String): String = "\"" + value.replace("\"", "'") + "\""
}
