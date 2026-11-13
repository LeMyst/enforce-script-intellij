package io.github.lemyst.enfusion

import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.cfg.EnfusionConfigFileType
import io.github.lemyst.enfusion.lang.EnfusionFileType
import io.github.lemyst.enfusion.objfmt.EnfusionObjectFileType
import io.github.lemyst.enfusion.pbo.EnPboFileType
import io.github.lemyst.enfusion.pbo.PboEntryView
import io.github.lemyst.enfusion.pbo.PboEntryViews
import io.github.lemyst.enfusion.pbo.PboExtractor
import io.github.lemyst.enfusion.pbo.format.ByteArrayPboSource
import io.github.lemyst.enfusion.pbo.format.PboArchive
import io.github.lemyst.enfusion.texture.EnTextureFileType

/** What the PBO browser opens for each kind of entry. Needs the platform for file types. */
class EnfusionPboEntryViewsTest : BasePlatformTestCase() {

    private val config = RapBytes.write(
        RapBytes.Cls("", "", listOf(RapBytes.Cls("CfgPatches", "", listOf(RapBytes.Value("units", "x"))))),
    )

    private fun view(entry: PboBytes.Entry): PboEntryView =
        PboArchive.open(ByteArrayPboSource(PboBytes.pbo(listOf(entry)))).use { archive ->
            PboEntryViews.view(archive, archive.entries.single(), "Scripts.pbo")
        }

    private fun text(entry: PboBytes.Entry): PboEntryView.Text {
        val v = view(entry)
        assertTrue("expected text, got $v", v is PboEntryView.Text)
        return v as PboEntryView.Text
    }

    fun testConfigBinIsRebuiltAsConfigCpp() {
        val v = text(PboBytes.Entry("Scripts\\config.bin", config))
        assertEquals("config.cpp", v.name)
        assertSame(EnfusionConfigFileType, v.fileType)
        assertTrue(v.text, v.text.startsWith("// Rebuilt by Enforce Script from Scripts/config.bin in Scripts.pbo"))
        assertTrue(v.text, v.text.contains("class CfgPatches"))
    }

    fun testOtherRapifiedEntriesKeepTheirName() {
        val v = text(PboBytes.Entry("data\\metal.rvmat", config))
        assertEquals("metal.rvmat", v.name)
        assertSame(EnfusionConfigFileType, v.fileType)
    }

    fun testScriptsGetTheScriptLanguageWithUnixLineEnds() {
        val v = text(PboBytes.file("3_Game\\a.c", "class A\r\n{\r\n}\r\n", compress = true))
        assertSame(EnfusionFileType, v.fileType)
        assertEquals("class A\n{\n}\n", v.text)
    }

    fun testLayoutsGetTheirLanguage() {
        assertSame(EnfusionObjectFileType, text(PboBytes.file("gui\\x.layout", "FrameWidgetClass x {}")).fileType)
    }

    fun testTexturesGoToTheTextureViewer() {
        val bytes = byteArrayOf(0x44, 0x44, 0x53, 0x20, 0, 0, 0, 1)
        val v = view(PboBytes.Entry("data\\t_co.paa", bytes))
        assertTrue("$v", v is PboEntryView.Binary)
        assertSame(EnTextureFileType, (v as PboEntryView.Binary).fileType)
        assertEquals(bytes.toList(), v.bytes.toList())
    }

    fun testUnknownTextOpensAsPlainText() {
        val v = text(PboBytes.file("mission.sqm", "version=54;\n"))
        assertSame(PlainTextFileType.INSTANCE, v.fileType)
    }

    fun testUnknownBinaryIsRefusedWithAReason() {
        val v = view(PboBytes.Entry("data\\model.p3d", byteArrayOf(0x4D, 0x4C, 0x4F, 0x44, 0, 0, 1, 2)))
        assertTrue("$v", v is PboEntryView.Unavailable)
        assertTrue((v as PboEntryView.Unavailable).message, v.message.contains(".p3d"))
    }

    fun testEncryptedEntriesAreRefused() {
        val v = view(PboBytes.Entry("a.c", "x".toByteArray(), method = "Encr"))
        assertTrue("$v", v is PboEntryView.Unavailable)
        assertTrue((v as PboEntryView.Unavailable).message, v.message.contains("encrypted"))
    }

    fun testTextThatIsNotUtf8IsReadAsLatin1() {
        assertEquals("café", PboEntryViews.decode(byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte())))
        assertEquals("café", PboEntryViews.decode("﻿café".toByteArray(Charsets.UTF_8)))
    }

    fun testPboFilesAreClaimedByExtension() {
        assertSame(EnPboFileType, com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getFileTypeByFileName("Scripts.pbo"))
    }

    fun testOutputPathMatchesWhereTheCacheWrites() {
        assertEquals("MyMod/Scripts/3_Game/a.c", PboExtractor.outputPath("MyMod/Scripts", "3_Game/a.c"))
        assertEquals("MyMod/Scripts/config.cpp", PboExtractor.outputPath("MyMod/Scripts", "config.bin"))
        assertEquals("sub/config.cpp", PboExtractor.outputPath(null, "sub/config.bin"))
        assertNull(PboExtractor.outputPath("MyMod", "data/t_co.paa"))
        assertNull(PboExtractor.outputPath("MyMod", "../evil.c"))
    }
}
