package io.github.lemyst.enfusion

import io.github.lemyst.enfusion.pbo.PboExtractor
import io.github.lemyst.enfusion.pbo.PboFolders
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class EnfusionPboFoldersTest {

    private val temp: Path = Files.createTempDirectory("enfusion-pbo-folders")

    @After
    fun cleanUp() = PboExtractor.deleteTree(temp)

    private fun file(relative: String, size: Int = 1): Path {
        val path = temp.resolve(relative)
        Files.createDirectories(path.parent)
        Files.write(path, ByteArray(size))
        return path
    }

    private fun names(found: PboFolders.Found) = found.pbos.map { temp.relativize(it).toString().replace('\\', '/') }

    @Test
    fun findsEveryLayoutAWorkshopFolderUses() {
        file("!Workshop/@Beta/addons/Scripts.pbo", 10)
        file("!Workshop/@Alpha/addons/Data.PBO", 20)
        file("!Workshop/@Alpha/addons/Scripts.pbo", 30)
        file("addons/vanilla.pbo", 40)
        file("!Workshop/@Alpha/addons/Scripts.pbo.bikey")
        file("!Workshop/@Alpha/mod.cpp")
        val found = PboFolders.scan(temp)
        assertEquals(
            listOf(
                "!Workshop/@Alpha/addons/Data.PBO",
                "!Workshop/@Alpha/addons/Scripts.pbo",
                "!Workshop/@Beta/addons/Scripts.pbo",
                "addons/vanilla.pbo",
            ),
            names(found),
        )
        assertEquals(100L, found.totalBytes)
    }

    @Test
    fun staysWithinTheDepthLimit() {
        file("a/b/c/d.pbo")
        file("a/b/c/d/e.pbo")
        assertEquals(listOf("a/b/c/d.pbo"), names(PboFolders.scan(temp)))
    }

    @Test
    fun skipsHiddenFolders() {
        file(".git/objects/x.pbo")
        file("@Mod/addons/x.pbo")
        assertEquals(listOf("@Mod/addons/x.pbo"), names(PboFolders.scan(temp)))
    }

    @Test
    fun aMissingFolderFindsNothing() {
        val found = PboFolders.scan(temp.resolve("absent"))
        assertTrue(found.pbos.isEmpty())
    }
}
