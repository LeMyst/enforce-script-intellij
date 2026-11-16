package io.github.lemyst.enfusion

import io.github.lemyst.enfusion.pbo.format.PboArchive
import io.github.lemyst.enfusion.pbo.format.PboWriter
import io.github.lemyst.enfusion.tools.detect.DayZAddonPacking
import junit.framework.TestCase
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest

/** Packing the project's addons at launch: the PBO written, and only the addons that changed. */
class EnfusionAddonPackingTest : TestCase() {

    private lateinit var temp: Path

    override fun setUp() {
        super.setUp()
        temp = Files.createTempDirectory("addon-packing")
    }

    override fun tearDown() {
        try {
            temp.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    /** What the writer stores, the plugin's own reader reads back, with the prefix and the SHA-1 trailer. */
    fun testWrittenPboReadsBack() {
        val config = Files.writeString(temp.resolve("config.cpp"), "class CfgPatches {};")
        val script = Files.writeString(Files.createDirectories(temp.resolve("scripts")).resolve("a.c"), "void A();")
        val pbo = temp.resolve("out.pbo")
        PboWriter.write(pbo, "MyMod\\Scripts", listOf(PboWriter.File("config.cpp", config), PboWriter.File("scripts\\a.c", script)))
        PboArchive.open(pbo).use { archive ->
            assertEquals("MyMod/Scripts", archive.prefix)
            assertEquals(listOf("config.cpp", "scripts/a.c"), archive.entries.map { it.name })
            assertEquals("void A();", String(archive.read(archive.find("scripts/a.c")!!)))
        }
        val bytes = Files.readAllBytes(pbo)
        val body = bytes.copyOfRange(0, bytes.size - 21)
        assertEquals("a zero byte before the checksum", 0.toByte(), bytes[bytes.size - 21])
        assertTrue(MessageDigest.getInstance("SHA-1").digest(body).contentEquals(bytes.copyOfRange(bytes.size - 20, bytes.size)))
    }

    fun testNamesPrefixesAndExclusions() {
        assertEquals("Scripts_Client.pbo", DayZAddonPacking.Addon(temp, "MyMod\\Scripts\\Client").pboName)
        assertEquals("Data.pbo", DayZAddonPacking.Addon(temp, "Data").pboName)
        assertTrue(DayZAddonPacking.excluded("logo.psd"))
        assertTrue(DayZAddonPacking.excluded("model.blend1"))
        assertFalse(DayZAddonPacking.excluded("logo.paa"))

        val addon = Files.createDirectories(temp.resolve("Scripts"))
        assertEquals("not on the work drive: the folder's name", "Scripts", DayZAddonPacking.prefixOf(addon, 'P'))
        Files.writeString(addon.resolve("\$PBOPREFIX\$"), "MyMod\\Scripts\n")
        assertEquals("declared", "MyMod\\Scripts", DayZAddonPacking.prefixOf(addon, 'P'))
    }

    /** A second launch packs nothing; an edit, an added or a removed file repacks that addon only. */
    fun testOnlyChangedAddonsArePacked() {
        val one = addon("One", "config.cpp" to "class CfgPatches {};", "a.c" to "1", "notes.bak" to "x")
        val two = addon("Two", "config.cpp" to "class CfgPatches {};")
        val mod = temp.resolve("out").resolve("@MyMod")
        val manifests = temp.resolve("out").resolve("manifests")
        val addons = listOf(DayZAddonPacking.Addon(one, "MyMod\\One"), DayZAddonPacking.Addon(two, "MyMod\\Two"))

        val first = DayZAddonPacking.pack(addons, mod, manifests)
        assertEquals(listOf("One.pbo", "Two.pbo"), first.packed)
        PboArchive.open(mod.resolve("Addons").resolve("One.pbo")).use { archive ->
            assertEquals("excluded files stay out", listOf("a.c", "config.cpp"), archive.entries.map { it.name })
        }

        assertEquals(listOf("One.pbo", "Two.pbo"), DayZAddonPacking.pack(addons, mod, manifests).unchanged)

        val edited = Files.writeString(one.resolve("a.c"), "12")
        Files.setLastModifiedTime(edited, FileTime.fromMillis(Files.getLastModifiedTime(edited).toMillis() + 2000))
        assertEquals(listOf("One.pbo"), DayZAddonPacking.pack(addons, mod, manifests).packed)

        Files.writeString(two.resolve("b.c"), "new")
        assertEquals(listOf("Two.pbo"), DayZAddonPacking.pack(addons, mod, manifests).packed)

        val removed = DayZAddonPacking.pack(addons.take(1), mod, manifests)
        assertEquals(listOf("Two.pbo"), removed.removed)
        assertFalse(Files.exists(mod.resolve("Addons").resolve("Two.pbo")))
    }

    private fun addon(name: String, vararg files: Pair<String, String>): Path {
        val folder = Files.createDirectories(temp.resolve("src").resolve(name))
        for ((file, text) in files) Files.writeString(folder.resolve(file), text)
        return folder
    }
}
