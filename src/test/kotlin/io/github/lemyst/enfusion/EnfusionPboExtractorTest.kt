package io.github.lemyst.enfusion

import io.github.lemyst.enfusion.pbo.PboExtractor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class EnfusionPboExtractorTest {

    private val temp: Path = Files.createTempDirectory("enfusion-pbo-test")

    @After
    fun cleanUp() = PboExtractor.deleteTree(temp)

    private fun extract(bytes: ByteArray): Pair<Path, PboExtractor.Result> {
        val pbo = temp.resolve("Scripts.pbo")
        Files.write(pbo, bytes)
        val target = temp.resolve("out")
        return target to PboExtractor.extract(pbo, target, "@MyMod/addons/Scripts.pbo")
    }

    @Test
    fun keepsOnlyNavigableEntriesUnderThePrefix() {
        val config = RapBytes.write(RapBytes.Cls("", "", listOf(RapBytes.Cls("CfgPatches", "", listOf()))))
        val (out, result) = extract(
            PboBytes.pbo(
                listOf(
                    PboBytes.Entry("config.bin", config),
                    PboBytes.file("config.cpp", "stale text config"),
                    PboBytes.file("3_Game\\a.c", "class A {}", compress = true),
                    PboBytes.file("gui\\layouts\\x.layout", "FrameWidgetClass x {}"),
                    PboBytes.file("data\\big.paa", "not wanted"),
                    PboBytes.file("data\\model.p3d", "not wanted"),
                    PboBytes.file("..\\..\\evil.c", "escape"),
                ),
                mapOf("prefix" to "MyMod\\Scripts"),
            ),
        )
        val base = out.resolve("MyMod/Scripts")
        assertEquals("class A {}", Files.readString(base.resolve("3_Game/a.c")))
        assertTrue(Files.isRegularFile(base.resolve("gui/layouts/x.layout")))
        assertEquals("MyMod\\Scripts\n", Files.readString(base.resolve("\$PBOPREFIX\$")))

        val cpp = Files.readString(base.resolve("config.cpp"))
        assertTrue(cpp, cpp.startsWith("// Rebuilt by Enforce Script from config.bin in @MyMod/addons/Scripts.pbo"))
        assertTrue(cpp, cpp.contains("class CfgPatches"))

        assertFalse(Files.exists(base.resolve("data")))
        assertFalse(Files.exists(base.resolve("config.bin")))
        assertFalse(Files.exists(temp.resolve("evil.c")))
        assertEquals(3, result.filesWritten)
        assertTrue(result.warnings.toString(), result.warnings.any { "unsafe name" in it })
    }

    @Test
    fun noPrefixWritesAtTheRoot() {
        val (out, _) = extract(PboBytes.pbo(listOf(PboBytes.file("a.c", "x"))))
        assertEquals("x", Files.readString(out.resolve("a.c")))
        assertTrue(Files.isRegularFile(out.resolve("\$PBOPREFIX\$")))
    }

    @Test
    fun readOnlyTreesCanStillBeDeleted() {
        val (out, _) = extract(PboBytes.pbo(listOf(PboBytes.file("a.c", "x"))))
        PboExtractor.makeReadOnly(out)
        assertFalse(Files.isWritable(out.resolve("a.c")))
        PboExtractor.deleteTree(out)
        assertFalse(Files.exists(out))
    }
}
