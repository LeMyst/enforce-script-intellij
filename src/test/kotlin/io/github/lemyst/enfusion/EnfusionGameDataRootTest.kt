package io.github.lemyst.enfusion

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.roots.EnfusionRoots
import io.github.lemyst.enfusion.roots.EnfusionVanillaConfigLibrary

/**
 * Which root-list entries count as the unpacked game data (`P:\DZ`). Those are indexed for their
 * configs only and trigger a warning when added, so a false positive is the costly mistake: a mod
 * root taken for game data would silently lose its scripts.
 */
class EnfusionGameDataRootTest : BasePlatformTestCase() {

    private fun dir(path: String, vararg children: String): VirtualFile {
        children.forEach { myFixture.tempDirFixture.findOrCreateDir("$path/$it") }
        return myFixture.tempDirFixture.findOrCreateDir(path)
    }

    fun testTheUnpackedGameIsGameData() {
        assertTrue(EnfusionVanillaConfigLibrary.isGameDataTree(dir("P/DZ", "gear", "characters", "weapons")))
    }

    fun testTheNameMatchesInAnyCase() {
        assertTrue(EnfusionVanillaConfigLibrary.isGameDataTree(dir("P/dz", "gear", "characters")))
    }

    fun testAModWithTheSameFoldersIsNotGameData() {
        assertFalse(EnfusionVanillaConfigLibrary.isGameDataTree(dir("P/ClothingMod", "gear", "characters", "scripts")))
    }

    fun testAnUnrelatedFolderNamedDzIsNotGameData() {
        assertFalse(EnfusionVanillaConfigLibrary.isGameDataTree(dir("Other/DZ", "scripts")))
    }

    fun testSplitKeepsTheOrderOfScriptRoots() {
        val scripts = dir("P/scripts", "3_game")
        val game = dir("P/DZ", "gear", "characters")
        val mod = dir("Mods/SomeMod", "scripts")

        val (gameData, scriptRoots) = EnfusionRoots.splitGameData(listOf(scripts, game, mod))

        assertEquals(listOf(game), gameData)
        assertEquals(listOf(scripts, mod), scriptRoots)
    }
}
