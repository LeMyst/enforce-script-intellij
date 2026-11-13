package io.github.lemyst.enfusion.pbo.format

/**
 * Bohemia's LZSS, used for `Cprs` entries in a PBO.
 *
 * A flag byte governs the next eight items, low bit first: a set bit is one literal byte, a clear
 * bit is a two-byte back-reference, 12 bits of distance and 4 bits of length minus 3, into a
 * 4 KB window. A reference reaching before the start of the output reads spaces, because the
 * encoder's window starts filled with them. The stream ends with a 4-byte additive checksum of
 * the output.
 *
 * Output stops at [expectedSize] whatever the stream says, so a hostile entry cannot make it grow.
 */
object Lzss {

    fun decompress(input: ByteArray, expectedSize: Int): ByteArray {
        val out = ByteArray(expectedSize)
        var o = 0
        var i = 0
        fun next(): Int {
            if (i >= input.size) throw PboFormatException("compressed entry ends early")
            return input[i++].toInt() and 0xFF
        }
        while (o < expectedSize) {
            val flags = next()
            var bit = 0
            while (bit < 8 && o < expectedSize) {
                if (flags and (1 shl bit) != 0) {
                    out[o++] = next().toByte()
                } else {
                    val b1 = next()
                    val b2 = next()
                    var from = o - (b1 or ((b2 and 0xF0) shl 4))
                    var length = (b2 and 0x0F) + 3
                    while (length > 0 && o < expectedSize) {
                        out[o++] = if (from < 0) SPACE else out[from]
                        from++
                        length--
                    }
                }
                bit++
            }
        }
        if (i + 4 <= input.size) {
            var stored = 0
            for (k in 0 until 4) stored = stored or ((input[i + k].toInt() and 0xFF) shl (8 * k))
            if (stored != checksum(out)) throw PboFormatException("compressed entry fails its checksum")
        }
        return out
    }

    fun checksum(data: ByteArray): Int {
        var sum = 0
        for (b in data) sum += b.toInt() and 0xFF
        return sum
    }

    private const val SPACE: Byte = 0x20
}
