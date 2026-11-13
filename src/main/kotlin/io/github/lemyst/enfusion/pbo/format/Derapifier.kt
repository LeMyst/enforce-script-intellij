package io.github.lemyst.enfusion.pbo.format

/**
 * Turns a rapified (binarized) config, the `config.bin` a packed PBO carries instead of
 * `config.cpp`, back into config text the plugin's config language parses.
 *
 * Layout: `\0raP`, two constant u32s, a u32 offset to an enum table, then the root class body. A
 * class body is the parent's name, an entry count, and entries:
 *
 * | type | entry                                                   |
 * |------|---------------------------------------------------------|
 * | 0    | `class Name` + u32 offset of its body                   |
 * | 1    | value: subtype byte, name, value                        |
 * | 2    | `name[] = {…}`                                          |
 * | 3    | `class Name;` (forward declaration)                     |
 * | 4    | `delete Name;`                                          |
 * | 5    | u32 flag, then `name[] += {…}` (flag 2 is `-=`)         |
 *
 * Value and array element subtypes: 0 string, 1 float, 2 int32, 3 nested array (arrays only),
 * 4 a bare word, 6 int64. Counts are 7-bit varints.
 *
 * Every offset is checked and nesting is bounded. A hostile file can make two classes share one
 * body, so the output size is bounded too, or the text could grow exponentially.
 */
object Derapifier {

    private const val MAX_DEPTH = 64
    private const val MAX_OUTPUT = 64 * 1024 * 1024

    fun isRapified(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0.toByte() && bytes[1] == 'r'.code.toByte() &&
            bytes[2] == 'a'.code.toByte() && bytes[3] == 'P'.code.toByte()

    fun derapify(bytes: ByteArray): String {
        if (!isRapified(bytes) || bytes.size < 16) throw PboFormatException("not a rapified config")
        val out = StringBuilder(bytes.size * 3)
        Reader(bytes, out).classBody(16, depth = 0, indent = 0)
        return out.toString()
    }

    private class Reader(private val b: ByteArray, private val out: StringBuilder) {
        private var pos = 0

        fun classBody(offset: Long, depth: Int, indent: Int) {
            if (depth > MAX_DEPTH) throw PboFormatException("classes nested deeper than $MAX_DEPTH")
            if (offset < 0 || offset >= b.size) throw PboFormatException("class body offset $offset is outside the file")
            val saved = pos
            pos = offset.toInt()
            asciiz() // the parent's name; the entry that pointed here already printed it
            val count = varint()
            repeat(count) { entry(depth, indent) }
            pos = saved
        }

        private fun entry(depth: Int, indent: Int) {
            when (val type = u8()) {
                0 -> {
                    val name = asciiz()
                    val bodyOffset = u32()
                    val parent = parentOf(bodyOffset)
                    line(indent, "class $name" + (if (parent.isNotEmpty()) " : $parent" else ""))
                    line(indent, "{")
                    classBody(bodyOffset, depth + 1, indent + 1)
                    line(indent, "};")
                }
                1 -> {
                    val subtype = u8()
                    val name = asciiz()
                    line(indent, "$name = ${scalar(subtype)};")
                }
                2 -> {
                    val name = asciiz()
                    line(indent, "$name[] = ${array(depth)};")
                }
                3 -> line(indent, "class ${asciiz()};")
                // The config grammar has no `delete`, and vanilla never uses it, so it is kept as a
                // comment: visible to the reader, harmless to the parser.
                4 -> line(indent, "// delete ${asciiz()};")
                5 -> {
                    val flag = u32()
                    val name = asciiz()
                    val op = if (flag == 2L) "-=" else "+="
                    line(indent, "$name[] $op ${array(depth)};")
                }
                else -> throw PboFormatException("unknown config entry type $type at ${pos - 1}")
            }
        }

        private fun parentOf(offset: Long): String {
            if (offset < 0 || offset >= b.size) throw PboFormatException("class body offset $offset is outside the file")
            val saved = pos
            pos = offset.toInt()
            val name = asciiz()
            pos = saved
            return name
        }

        private fun array(depth: Int): String {
            if (depth > MAX_DEPTH) throw PboFormatException("arrays nested deeper than $MAX_DEPTH")
            val count = varint()
            val items = ArrayList<String>(minOf(count, 1024))
            repeat(count) {
                val type = u8()
                items += if (type == 3) array(depth + 1) else scalar(type)
            }
            return items.joinToString(", ", "{", "}")
        }

        private fun scalar(type: Int): String = when (type) {
            0 -> quote(asciiz())
            1 -> float(Float.fromBits(u32().toInt()))
            2 -> u32().toInt().toString()
            4 -> asciiz().let { if (IDENT.matches(it)) it else quote(it) }
            6 -> i64().toString()
            else -> throw PboFormatException("unknown config value type $type at ${pos - 1}")
        }

        private fun line(indent: Int, text: String) {
            if (out.length > MAX_OUTPUT) throw PboFormatException("config expands past ${MAX_OUTPUT / (1024 * 1024)} MB")
            repeat(indent) { out.append('\t') }
            out.append(text).append('\n')
        }

        private fun u8(): Int {
            if (pos >= b.size) throw PboFormatException("config ends early")
            return b[pos++].toInt() and 0xFF
        }

        private fun u32(): Long {
            var v = 0L
            for (shift in 0 until 32 step 8) v = v or (u8().toLong() shl shift)
            return v
        }

        private fun i64(): Long {
            var v = 0L
            for (shift in 0 until 64 step 8) v = v or (u8().toLong() shl shift)
            return v
        }

        private fun varint(): Int {
            var v = 0L
            var shift = 0
            while (true) {
                val byte = u8()
                v = v or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) break
                shift += 7
                if (shift > 28) throw PboFormatException("malformed count at ${pos - 1}")
            }
            // Each entry takes at least two bytes, so a count beyond that is a lie.
            if (v > b.size) throw PboFormatException("count $v is larger than the file")
            return v.toInt()
        }

        private fun asciiz(): String {
            val start = pos
            while (true) {
                if (pos >= b.size) throw PboFormatException("config string runs past the end")
                if (b[pos] == 0.toByte()) break
                pos++
            }
            // Configs are 8-bit; UTF-8 is what DayZ's text configs use for non-ASCII display names.
            val s = String(b, start, pos - start, Charsets.UTF_8)
            pos++
            return s
        }
    }

    private val IDENT = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** Quotes the way the grammar reads it: `""` for a quote, and no raw line breaks. */
    private fun quote(s: String): String =
        "\"" + s.replace("\"", "\"\"").replace("\r", "").replace("\n", "\\n") + "\""

    /**
     * Shortest text that reads back as the same float, which is what `Float.toString` gives.
     * Whole numbers lose their `.0` (`1`, not `1.0`), the form hand-written configs use.
     */
    private fun float(f: Float): String {
        if (f.isNaN() || f.isInfinite()) return quote(f.toString())
        val s = f.toString()
        return when {
            s.contains('E') -> s.replace('E', 'e')
            s.endsWith(".0") -> s.dropLast(2)
            else -> s
        }
    }
}
