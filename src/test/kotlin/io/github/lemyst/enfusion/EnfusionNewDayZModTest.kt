package io.github.lemyst.enfusion

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.cfg.psi.impl.EnCfgClassMixin
import io.github.lemyst.enfusion.resolve.EnScriptModules
import io.github.lemyst.enfusion.tools.NewDayZContext
import io.github.lemyst.enfusion.tools.NewDayZFiles
import io.github.lemyst.enfusion.tools.detect.DayZModTemplates
import io.github.lemyst.enfusion.tools.detect.DayZModTemplates.Module

/**
 * *New DayZ Mod* and *New DayZ Addon*: the templates on their own, then the files written into a
 * project, where the plugin's own config parser and script-module lookup must agree with them.
 */
class EnfusionNewDayZModTest : BasePlatformTestCase() {

    private val scripts = DayZModTemplates.Addon(
        name = "MyMod_Scripts",
        prefix = "MyMod\\Scripts",
        requiredAddons = listOf("DZ_Data", "DZ_Scripts"),
        modules = setOf(Module.WORLD, Module.GAME),
        modName = "My \"Best\" Mod",
        defines = listOf("MYMOD"),
    )

    fun testConfigDeclaresEachModuleUnderThePrefix() {
        val config = DayZModTemplates.configCpp(scripts)
        assertTrue(config, config.contains("class MyMod_Scripts\n\t{\n\t\tunits[] = {};"))
        assertTrue(config, config.contains("\t\t\t\"DZ_Data\",\n\t\t\t\"DZ_Scripts\"\n"))
        assertTrue(config, config.contains("dir = \"MyMod\";"))
        assertTrue("quotes are doubled", config.contains("name = \"My \"\"Best\"\" Mod\";"))
        assertTrue("modules in load order", config.contains("dependencies[] = { \"Game\", \"World\" };"))
        assertTrue(config, config.contains("defines[] = { \"MYMOD\" };"))
        assertTrue(config, config.contains("class gameScriptModule\n\t\t\t{\n\t\t\t\tvalue = \"\";\n\t\t\t\tfiles[] = { \"MyMod/Scripts/3_Game\" };"))
        assertTrue(config, config.contains("files[] = { \"MyMod/Scripts/4_World\" };"))
        assertFalse(config, config.contains("missionScriptModule"))
        assertTrue("game before world", config.indexOf("gameScriptModule") < config.indexOf("worldScriptModule"))
    }

    fun testAddonWithoutModulesHasNoCfgMods() {
        val config = DayZModTemplates.configCpp(scripts.copy(modules = emptySet(), requiredAddons = emptyList()))
        assertFalse(config, config.contains("CfgMods"))
        assertTrue(config, config.contains("requiredAddons[] =\n\t\t{\n\t\t};"))
    }

    fun testDirIsThePrefixWithoutTheAddonFolder() {
        assertEquals("MyMod", scripts.dir)
        assertEquals("Author/MyMod", scripts.copy(prefix = "Author\\MyMod\\Scripts").dir)
        assertEquals("MyMod", scripts.copy(prefix = "MyMod").dir)
    }

    fun testModCppLeavesOutEmptyFields() {
        val text = DayZModTemplates.modCpp(DayZModTemplates.ModInfo(name = "My Mod", author = "Someone", overview = "Line one\nline two"))
        assertEquals("name = \"My Mod\";\noverview = \"Line one line two\";\nauthor = \"Someone\";\nversion = \"1.0\";\n", text)
    }

    fun testNamesAndPrefixes() {
        assertEquals("MyMod_Scripts", DayZModTemplates.addonName("MyMod", "Scripts"))
        assertEquals("My_Mod_Client_UI", DayZModTemplates.addonName("My-Mod", "Client UI"))
        assertEquals("_1Mod_Scripts", DayZModTemplates.addonName("1Mod", "Scripts"))
        assertNull(DayZModTemplates.classNameProblem("MyMod_Scripts"))
        assertNotNull(DayZModTemplates.classNameProblem("3Mod"))
        assertNull(DayZModTemplates.prefixProblem("MyMod\\Scripts"))
        assertNotNull(DayZModTemplates.prefixProblem("My Mod\\Scripts"))
        assertNotNull(DayZModTemplates.prefixProblem("MyMod\\\\Scripts"))
        assertEquals("MyMod\\Scripts\n", DayZModTemplates.pboPrefix(scripts.copy(prefix = "/MyMod/Scripts/")))
    }

    fun testModuleScriptSaysWhatItSees() {
        assertEquals("// 1_Core: code here sees only itself; not 2_GameLib, 3_Game, 4_World, 5_Mission.\n", DayZModTemplates.moduleScript(Module.CORE))
        assertEquals("// 5_Mission: code here sees itself and 1_Core, 2_GameLib, 3_Game, 4_World.\n", DayZModTemplates.moduleScript(Module.MISSION))
    }

    fun testContextPrefixJoinsTheParentAndTheFolder() {
        assertEquals("MyMod\\Scripts", NewDayZContext("MyMod", emptyList()).prefixOf(" Scripts "))
        assertEquals("Scripts", NewDayZContext("", emptyList()).prefixOf("Scripts"))
    }

    /** The files written parse, and each module's files[] names the folder created for it. */
    fun testCreatedAddonParsesAndPlacesItsModules() {
        val mod = myFixture.tempDirFixture.findOrCreateDir("MyMod")
        val config = NewDayZFiles.createAddon(project, mod, NewDayZFiles.AddonSpec("Scripts", scripts, pboPrefix = true))
        val dir = config.parent
        assertEquals("MyMod\\Scripts\n", String(dir.findChild("\$PBOPREFIX\$")!!.contentsToByteArray()))
        val world = dir.findChild("4_World")!!
        assertEquals(listOf("MyMod_Scripts.c"), world.children.map { it.name })
        assertNull("no folder for a module not ticked", dir.findChild("5_Mission"))

        val psi = PsiManager.getInstance(project).findFile(config)!!
        assertNull(PsiTreeUtil.findChildOfType(psi, PsiErrorElement::class.java)?.errorDescription, PsiTreeUtil.findChildOfType(psi, PsiErrorElement::class.java))
        for (module in listOf("gameScriptModule" to "3_Game", "worldScriptModule" to "4_World")) {
            val cls = PsiTreeUtil.findChildrenOfType(psi, EnCfgClassMixin::class.java).first { it.name == module.first }
            val path = cls.propertyValue("files")!!.stringValues().single()
            assertEquals(dir.findChild(module.second), EnScriptModules.locate(dir, path))
        }
    }

    fun testCreatedModHasItsFiles() {
        val parent = myFixture.tempDirFixture.findOrCreateDir("repo")
        val info = DayZModTemplates.ModInfo(name = "My Mod", author = "Someone", overview = "What it does.")
        val mod = NewDayZFiles.createMod(project, parent, NewDayZFiles.ModSpec("MyMod", info, DayZModTemplates.License.MIT, readme = true, gitignore = true))
        assertEquals("MyMod", mod.name)
        assertTrue(String(mod.findChild("mod.cpp")!!.contentsToByteArray()).startsWith("name = \"My Mod\";"))
        assertTrue(String(mod.findChild("LICENSE")!!.contentsToByteArray()).contains("Someone"))
        assertEquals("# My Mod\n\nWhat it does.\n", String(mod.findChild("README.md")!!.contentsToByteArray()))
        assertTrue(String(mod.findChild(".gitignore")!!.contentsToByteArray()).contains("*.biprivatekey"))
    }

    /** An empty folder name puts the mod in the chosen folder itself, as in a new, empty project. */
    fun testModInTheChosenFolderItself() {
        val parent = myFixture.tempDirFixture.findOrCreateDir("empty")
        val mod = NewDayZFiles.createMod(project, parent, NewDayZFiles.ModSpec("", DayZModTemplates.ModInfo("My Mod"), DayZModTemplates.License.NONE, readme = false, gitignore = false))
        assertEquals(parent, mod)
        assertEquals(listOf("mod.cpp"), parent.children.map { it.name })
    }
}
