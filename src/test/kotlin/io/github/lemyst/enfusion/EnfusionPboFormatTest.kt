package io.github.lemyst.enfusion

import io.github.lemyst.enfusion.pbo.format.ByteArrayPboSource
import io.github.lemyst.enfusion.pbo.format.Lzss
import io.github.lemyst.enfusion.pbo.format.PboArchive
import io.github.lemyst.enfusion.pbo.format.PboFormatException
import io.github.lemyst.enfusion.pbo.format.PboPacking
import io.github.lemyst.enfusion.pbo.format.PboPaths
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EnfusionPboFormatTest {

    private fun open(bytes: ByteArray) = PboArchive.open(ByteArrayPboSource(bytes))

    @Test
    fun readsStoredEntriesAndPrefix() {
        val bytes = PboBytes.pbo(
            listOf(
                PboBytes.file("config.bin", "rap"),
                PboBytes.file("scripts\\3_Game\\a.c", "class A {}"),
            ),
            mapOf("prefix" to "MyMod\\Scripts\\", "product" to "dayz ugc"),
        )
        open(bytes).use { pbo ->
            assertEquals("MyMod/Scripts", pbo.prefix)
            assertEquals("dayz ugc", pbo.properties["product"])
            assertEquals(listOf("config.bin", "scripts/3_Game/a.c"), pbo.entries.map { it.name })
            assertEquals("class A {}", String(pbo.read(pbo.entries[1]), Charsets.ISO_8859_1))
            assertEquals(PboPacking.STORED, pbo.entries[1].packing)
        }
    }

    @Test
    fun readsWithoutProductBlock() {
        open(PboBytes.pbo(listOf(PboBytes.file("a.c", "x")))).use { pbo ->
            assertNull(pbo.prefix)
            assertEquals("x", String(pbo.read(pbo.entries.single())))
        }
    }

    @Test
    fun decompressesBackReferences() {
        // Repetitive enough that the encoder emits overlapping references, as real scripts do.
        val text = "class A { void F() { F(); F(); F(); } }\n".repeat(200)
        open(PboBytes.pbo(listOf(PboBytes.file("a.c", text, compress = true)))).use { pbo ->
            val entry = pbo.entries.single()
            assertEquals(PboPacking.COMPRESSED, entry.packing)
            assertTrue(entry.dataSize < text.length)
            assertEquals(text, String(pbo.read(entry), Charsets.ISO_8859_1))
        }
    }

    @Test
    fun referenceBeforeStartReadsSpaces() {
        // Flag byte 0: a back-reference first. Distance 5 from position 0 lands before the start.
        val stream = byteArrayOf(0, 5, 0) + checksumOf("   ")
        assertArrayEquals("   ".toByteArray(), Lzss.decompress(stream, 3))
    }

    @Test
    fun rejectsBadChecksum() {
        val good = PboBytes.compress("hello hello hello".toByteArray())
        good[good.size - 1] = (good[good.size - 1] + 1).toByte()
        assertThrows { Lzss.decompress(good, 17) }
    }

    @Test
    fun rejectsSizesPastTheEnd() {
        val bytes = PboBytes.pbo(listOf(PboBytes.Entry("a.c", "x".toByteArray(), claimedSize = 1L shl 31)))
        assertThrows { open(bytes) }
    }

    @Test
    fun rejectsTruncatedHeader() {
        val bytes = PboBytes.pbo(listOf(PboBytes.file("scripts/a.c", "x")))
        assertThrows { open(bytes.copyOf(8)) }
    }

    @Test
    fun encryptedEntriesAreFlaggedNotRead() {
        val bytes = PboBytes.pbo(listOf(PboBytes.Entry("a.c", "x".toByteArray(), method = "Encr")))
        open(bytes).use { pbo ->
            assertTrue(pbo.isEncrypted)
            assertThrows { pbo.read(pbo.entries.single()) }
        }
    }

    @Test
    fun refusesEntriesOverTheCap() {
        open(PboBytes.pbo(listOf(PboBytes.file("big.paa", "0123456789")))).use { pbo ->
            assertThrows { pbo.read(pbo.entries.single(), maxSize = 4) }
        }
    }

    @Test
    fun escapingNamesHaveNoPath() {
        assertEquals("scripts/3_Game/a.c", PboPaths.safeRelativePath("scripts\\3_Game\\a.c"))
        for (bad in listOf("..\\..\\evil.c", "/abs.c", "\\abs.c", "a\\\\b.c", "a\\.\\b.c", "a\\..\\..\\b.c", "///", "")) {
            assertNull(bad, PboPaths.safeRelativePath(bad))
        }
    }

    @Test
    fun obfuscatedNamesAreCleanedNotDropped() {
        // The shapes an obfuscated scripts PBO really uses: wildcards, control characters, and
        // bytes outside ASCII, which are fine on disk and kept.
        assertEquals("3_Game/__\u00e9_.c", PboPaths.safeRelativePath("3_Game\\*?\u00e9\u0001.c"))
        assertEquals("C_/x.c", PboPaths.safeRelativePath("C:\\x.c"))
        assertEquals("a_", PboPaths.safeRelativePath("a."))
        assertEquals("_con.c", PboPaths.safeRelativePath("con.c"))
        assertEquals("_NUL", PboPaths.safeRelativePath("NUL"))
        assertEquals("___._", PboPaths.safeRelativePath("\t\t*.*"))
    }

    private fun checksumOf(s: String): ByteArray {
        val sum = Lzss.checksum(s.toByteArray())
        return ByteArray(4) { ((sum shr (8 * it)) and 0xFF).toByte() }
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
            fail("expected PboFormatException")
        } catch (_: PboFormatException) {
        }
    }
}
