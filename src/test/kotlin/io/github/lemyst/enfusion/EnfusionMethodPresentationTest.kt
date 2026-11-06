package io.github.lemyst.enfusion

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.psi.impl.EnMethodDeclMixin
import io.github.lemyst.enfusion.resolve.EnOverrideResolution

/**
 * How a method reads in the popups that offer several of them. Overrides share a signature by
 * definition, so the class and file are what tell the gutter icon's choices apart.
 */
class EnfusionMethodPresentationTest : BasePlatformTestCase() {

    private fun methodIn(file: com.intellij.psi.PsiFile): EnMethodDeclMixin =
        PsiTreeUtil.findChildOfType(file, EnMethodDeclMixin::class.java)!!

    fun testOverrideTargetsNameTheirClassAndFile() {
        myFixture.addFileToProject("vanilla/dayzgame.es", "class DayZGame { void OnEvent(int id) {} }")
        myFixture.addFileToProject("expansion/dayzgame.es", "modded class DayZGame { override void OnEvent(int id) {} }")
        val mine = myFixture.configureByText("mine.es", "modded class DayZGame { override void OnEvent(int id) {} }")

        val locations = EnOverrideResolution.superDeclarationsOf(methodIn(mine))
            .map { it.presentation!!.locationString!! }
        assertEquals(locations.toString(), 2, locations.size)
        assertEquals(locations.toString(), 2, locations.toSet().size)
        assertTrue(locations.toString(), locations.any { it.startsWith("(int id): void  —  DayZGame · ") && it.endsWith("vanilla/dayzgame.es") })
        assertTrue(locations.toString(), locations.any { it.startsWith("(int id): void  —  modded DayZGame · ") && it.endsWith("expansion/dayzgame.es") })
    }

    /** The Structure view lists one file's members, so the class and file would only repeat. */
    fun testStructureViewShowsTheSignatureOnly() {
        val file = myFixture.configureByText("a.es", "class A { int Get(string key) { return 0; } }")
        assertEquals("(string key): int", methodIn(file).signaturePresentation().locationString)
    }
}
