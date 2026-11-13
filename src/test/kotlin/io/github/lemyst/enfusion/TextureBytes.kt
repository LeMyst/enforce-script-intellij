package io.github.lemyst.enfusion

import java.io.ByteArrayOutputStream

/**
 * Builds small `.edds` and `.paa` files in memory, laid out as the real ones are. Tests never copy
 * textures out of the game data, which is proprietary.
 */
object TextureBytes {

    /**
     * A BGRA8 `.edds`. [pixel] gives the ARGB colour of each top-mip pixel; smaller mips are filled
     * with the colour of their top-left pixel, which is all the tests need from them.
     * With [lz4], each mip is stored as one literal-only LZ4 chunk instead of `COPY`.
     */
    fun eddsBgra(width: Int, height: Int, mips: Int = 1, lz4: Boolean = false, pixel: (Int, Int) -> Int): ByteArray {
        val levels = (0 until mips).map { level ->
            val w = maxOf(1, width shr level)
            val h = maxOf(1, height shr level)
            val out = ByteArrayOutputStream()
            for (y in 0 until h) for (x in 0 until w) {
                val argb = if (level == 0) pixel(x, y) else pixel(0, 0)
                out.write(argb and 0xFF)
                out.write((argb shr 8) and 0xFF)
                out.write((argb shr 16) and 0xFF)
                out.write((argb ushr 24) and 0xFF)
            }
            out.toByteArray()
        }
        val header = ddsHeader(width, height, mips, fourCC = 0, pfFlags = 0x41, bitCount = 32,
            masks = intArrayOf(0xFF0000, 0xFF00, 0xFF, 0xFF000000.toInt()))
        return eddsBody(header, levels.reversed().map { if (lz4) "LZ4 " to lz4Literals(it) else "COPY" to it })
    }

    /** A BC5 `.edds` with one empty mip: readable, not decodable, as no game texture uses BC5. */
    fun eddsBc5(width: Int, height: Int): ByteArray =
        eddsDx10(width, height, dxgi = 83, ByteArray(maxOf(1, (width + 3) / 4) * maxOf(1, (height + 3) / 4) * 16))

    /** A BC7 `.edds` with one mip, every 4x4 block equal to [block]. */
    fun eddsBc7(width: Int, height: Int, block: ByteArray): ByteArray =
        eddsDx10(width, height, dxgi = 98, (0 until maxOf(1, (width + 3) / 4) * maxOf(1, (height + 3) / 4)).flatMap { block.toList() }.toByteArray())

    private fun eddsDx10(width: Int, height: Int, dxgi: Int, blocks: ByteArray): ByteArray {
        val header = ddsHeader(width, height, 1, fourCC = fourCC("DX10"), pfFlags = 0x4, bitCount = 0, masks = IntArray(4))
        val dx10 = ByteArrayOutputStream().apply { int(dxgi); int(3); int(0); int(1); int(0) }.toByteArray()
        return eddsBody(header + dx10, listOf("COPY" to blocks))
    }

    /** A DXT1 `.edds` with one mip, every 4x4 block equal to [block]. */
    fun eddsDxt1(width: Int, height: Int, block: ByteArray): ByteArray {
        val blocks = (0 until maxOf(1, (width + 3) / 4) * maxOf(1, (height + 3) / 4)).flatMap { block.toList() }.toByteArray()
        val header = ddsHeader(width, height, 1, fourCC = fourCC("DXT1"), pfFlags = 0x4, bitCount = 0, masks = IntArray(4))
        return eddsBody(header, listOf("COPY" to blocks))
    }

    /** A DXT1 `.paa` with one mip, raw or as a literal-run LZO stream. */
    fun paaDxt1(width: Int, height: Int, block: ByteArray, lzo: Boolean): ByteArray {
        val blocks = (0 until maxOf(1, width / 4) * maxOf(1, height / 4)).flatMap { block.toList() }.toByteArray()
        val data = if (lzo) lzoLiterals(blocks) else blocks
        val out = ByteArrayOutputStream()
        out.short(0xFF01)
        out.tag("CGVA", ByteArray(4))
        out.tag("CXAM", ByteArray(4))
        out.tag("SFFO", ByteArray(64))
        out.short(0) // palette
        out.short(if (lzo) width or 0x8000 else width)
        out.short(height)
        out.write(data.size and 0xFF); out.write((data.size shr 8) and 0xFF); out.write((data.size shr 16) and 0xFF)
        out.write(data)
        out.short(0); out.short(0) // end of the mip list
        out.short(0)
        return out.toByteArray()
    }

    /** A `.paa` header with an ARGB4444 code: named, not decoded. */
    fun paaArgb4444(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.short(0x4444)
        out.short(0)
        out.short(width); out.short(height)
        val size = width * height * 2
        out.write(size and 0xFF); out.write((size shr 8) and 0xFF); out.write((size shr 16) and 0xFF)
        out.write(ByteArray(size))
        out.short(0); out.short(0)
        return out.toByteArray()
    }

    /** LZO1X: an initial literal run (first byte 17 + n, which needs n >= 4), then end-of-stream. */
    fun lzoLiterals(data: ByteArray): ByteArray {
        require(data.size in 4..238)
        return byteArrayOf((17 + data.size).toByte()) + data + byteArrayOf(0x11, 0, 0)
    }

    /** One BC1 block whose four texels per row read colour 0, 1, 2, 3 from left to right. */
    fun bc1Block(c0: Int, c1: Int): ByteArray {
        val indices = (0 until 4).fold(0) { acc, row -> acc or (0b11_10_01_00 shl (8 * row)) }
        return byteArrayOf(
            c0.toByte(), (c0 shr 8).toByte(), c1.toByte(), (c1 shr 8).toByte(),
            indices.toByte(), (indices shr 8).toByte(), (indices shr 16).toByte(), (indices shr 24).toByte(),
        )
    }

    /** A literal-only LZ4 mip as `.edds` stores it: size, one chunk header flagged last, one block. */
    fun lz4Literals(data: ByteArray): ByteArray {
        val block = ByteArrayOutputStream()
        val n = data.size
        if (n >= 15) {
            block.write(0xF0)
            var rest = n - 15
            while (rest >= 255) { block.write(255); rest -= 255 }
            block.write(rest)
        } else {
            block.write(n shl 4)
        }
        block.write(data)
        val bytes = block.toByteArray()
        return ByteArrayOutputStream().apply {
            int(n)
            int(bytes.size or (0x80 shl 24))
            write(bytes)
        }.toByteArray()
    }

    private fun ddsHeader(width: Int, height: Int, mips: Int, fourCC: Int, pfFlags: Int, bitCount: Int, masks: IntArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("DDS ".toByteArray(Charsets.US_ASCII))
        out.int(124); out.int(0x1007); out.int(height); out.int(width); out.int(0); out.int(0); out.int(mips)
        repeat(11) { out.int(0) }
        out.int(32); out.int(pfFlags); out.int(fourCC); out.int(bitCount)
        masks.forEach { out.int(it) }
        out.int(0x1000); out.int(0); out.int(0); out.int(0); out.int(0)
        return out.toByteArray()
    }

    private fun eddsBody(header: ByteArray, blocks: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(header)
        for ((tag, data) in blocks) {
            out.write(tag.toByteArray(Charsets.US_ASCII))
            out.int(data.size)
        }
        blocks.forEach { out.write(it.second) }
        return out.toByteArray()
    }

    private fun fourCC(s: String): Int = s.toByteArray(Charsets.US_ASCII).let {
        (it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8) or ((it[2].toInt() and 0xFF) shl 16) or ((it[3].toInt() and 0xFF) shl 24)
    }

    private fun ByteArrayOutputStream.int(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF); write((v shr 16) and 0xFF); write((v ushr 24) and 0xFF)
    }

    private fun ByteArrayOutputStream.short(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.tag(name: String, data: ByteArray) {
        write("GGAT".toByteArray(Charsets.US_ASCII))
        write(name.toByteArray(Charsets.US_ASCII))
        int(data.size)
        write(data)
    }
}
