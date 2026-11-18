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

    /**
     * A mod of several addons repeats the same paths in each, so the addon is named too: the
     * outermost folder with a `config.cpp`, the PBO, and only when its name is not the mod's.
     */
    fun testLocationNamesTheAddonInsideAMod() {
        myFixture.addFileToProject("mymod/SafeZone/config.cpp", "class CfgPatches {};")
        myFixture.addFileToProject("mymod/SafeZone/scripts/config.cpp", "class CfgPatches {};")
        val inAddon = myFixture.addFileToProject("mymod/SafeZone/scripts/4_World/zone.es", "class Zone {}")
        myFixture.addFileToProject("single/config.cpp", "class CfgPatches {};")
        val modIsAddon = myFixture.addFileToProject("single/scripts/4_World/one.es", "class One {}")
        myFixture.addFileToProject("named/named/config.cpp", "class CfgPatches {};")
        val sameName = myFixture.addFileToProject("named/named/scripts/two.es", "class Two {}")

        val settings = io.github.lemyst.enfusion.settings.EnfusionProjectSettings.getInstance(project)
        val saved = settings.roots
        try {
            settings.roots = listOf("/src/mymod", "/src/single", "/src/named")
            fun location(file: com.intellij.psi.PsiFile) =
                PsiTreeUtil.findChildOfType(file, io.github.lemyst.enfusion.psi.impl.EnClassDeclMixin::class.java)!!.fileLocation()
            assertEquals("mymod/SafeZone · scripts/4_World/zone.es", location(inAddon))
            assertEquals("single · scripts/4_World/one.es", location(modIsAddon))
            assertEquals("named · named/scripts/two.es", location(sameName))
        } finally {
            settings.roots = saved
        }
    }

    /** The Structure view lists one file's members, so the class and file would only repeat. */
    fun testStructureViewShowsTheSignatureOnly() {
        val file = myFixture.configureByText("a.es", "class A { int Get(string key) { return 0; } }")
        assertEquals("(string key): int", methodIn(file).signaturePresentation().locationString)
    }
}
