package io.github.lemyst.enfusion

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.inspections.EnScriptModuleVisibilityInspection
import io.github.lemyst.enfusion.resolve.EnScriptModules
import io.github.lemyst.enfusion.resolve.ScriptModule

/** Script module visibility: which module a file is in, and what it may name. */
class EnfusionScriptModuleTest : BasePlatformTestCase() {

    private fun reports(path: String): List<String> {
        myFixture.enableInspections(EnScriptModuleVisibilityInspection())
        myFixture.configureFromTempProjectFile(path)
        return myFixture.doHighlighting()
            .filter { it.severity >= HighlightSeverity.WEAK_WARNING }
            .mapNotNull(HighlightInfo::getDescription)
    }

    fun testModuleFromFolderNames() {
        val file = myFixture.addFileToProject("MyMod/Scripts/4_World/Entities/player.es", "class P {}").virtualFile
        assertEquals(ScriptModule.WORLD, EnScriptModules.moduleOf(file, project))
        val lower = myFixture.addFileToProject("scripts/3_game/x.es", "class X {}").virtualFile
        assertEquals(ScriptModule.GAME, EnScriptModules.moduleOf(lower, project))
        val none = myFixture.addFileToProject("MyMod/Scripts/Common/y.es", "class Y {}").virtualFile
        assertNull(EnScriptModules.moduleOf(none, project))
    }

    fun testModuleFromConfigWinsOverFolderNames() {
        myFixture.addFileToProject(
            "MyMod/Scripts/config.cpp",
            """
            class CfgMods
            {
                class MyMod
                {
                    class defs
                    {
                        class gameScriptModule { files[] = { "MyMod/Scripts/Client" }; };
                        class worldScriptModule { files[] = { "MyMod/Scripts/Shared/3_Game" }; };
                    };
                };
            };
            """.trimIndent(),
        )
        val client = myFixture.addFileToProject("MyMod/Scripts/Client/ui.es", "class Ui {}").virtualFile
        assertEquals(ScriptModule.GAME, EnScriptModules.moduleOf(client, project))
        // Named 3_Game, but the config compiles it into the world module.
        val shared = myFixture.addFileToProject("MyMod/Scripts/Shared/3_Game/s.es", "class S {}").virtualFile
        assertEquals(ScriptModule.WORLD, EnScriptModules.moduleOf(shared, project))
    }

    fun testTypeFromALaterModuleIsReported() {
        myFixture.addFileToProject("MyMod/Scripts/4_World/player.es", "class PlayerBase {}")
        myFixture.addFileToProject("MyMod/Scripts/3_Game/hud.es", "class Hud { PlayerBase m_Player; }")
        val found = reports("MyMod/Scripts/3_Game/hud.es")
        assertEquals(found.toString(), listOf("'PlayerBase' is declared in 4_World and is not visible from 3_Game"), found)
    }

    fun testTypeFromAnEarlierModuleIsFine() {
        myFixture.addFileToProject("MyMod/Scripts/3_Game/man.es", "class Man {}")
        myFixture.addFileToProject("MyMod/Scripts/4_World/use.es", "class Use { Man m_Man; }")
        assertEmpty(reports("MyMod/Scripts/4_World/use.es"))
    }

    fun testModdedLayerKeepsTheClassVisibleButNotItsNewMembers() {
        myFixture.addFileToProject("MyMod/Scripts/3_Game/man.es", "class Man { void Walk() {} }")
        myFixture.addFileToProject("MyMod/Scripts/4_World/man_mod.es", "modded class Man { void Fly() {} }")
        myFixture.addFileToProject(
            "MyMod/Scripts/3_Game/use.es",
            "class Use { void Go(Man man) { man.Walk(); man.Fly(); } }",
        )
        val found = reports("MyMod/Scripts/3_Game/use.es")
        assertEquals(found.toString(), listOf("'Fly' is declared in 4_World and is not visible from 3_Game"), found)
    }

    fun testModdedClassOfALaterModuleIsReported() {
        myFixture.addFileToProject("MyMod/Scripts/4_World/player.es", "class PlayerBase {}")
        myFixture.addFileToProject("MyMod/Scripts/3_Game/player_mod.es", "modded class PlayerBase { void Test() {} }")
        val found = reports("MyMod/Scripts/3_Game/player_mod.es")
        assertEquals(found.toString(), listOf("'PlayerBase' is declared in 4_World and is not visible from 3_Game"), found)
    }

    fun testModdedClassOfAnEarlierModuleIsFine() {
        myFixture.addFileToProject("MyMod/Scripts/3_Game/man.es", "class Man {}")
        myFixture.addFileToProject("MyMod/Scripts/4_World/man_mod.es", "modded class Man {}")
        assertEmpty(reports("MyMod/Scripts/4_World/man_mod.es"))
    }

    /** The misplaced layer does not compile, so it must not make the class visible elsewhere. */
    fun testMisplacedModdedLayerDoesNotHideTheClass() {
        myFixture.addFileToProject("MyMod/Scripts/4_World/player.es", "class PlayerBase { void Walk() {} }")
        myFixture.addFileToProject("MyMod/Scripts/3_Game/player_mod.es", "modded class PlayerBase { void Test() {} }")
        myFixture.addFileToProject(
            "MyMod/Scripts/3_Game/use.es",
            "class Use { PlayerBase m_Player; void Go() { m_Player.Test(); } }",
        )
        val found = reports("MyMod/Scripts/3_Game/use.es")
        assertEquals(
            found.toString(),
            listOf(
                "'PlayerBase' is declared in 4_World and is not visible from 3_Game",
                "'Test' is declared in 4_World and is not visible from 3_Game",
            ),
            found,
        )
    }

    /** Nothing is claimed about a class declared only outside the known modules. */
    fun testModdedClassOfAnUnplacedClassIsNotReported() {
        myFixture.addFileToProject("Stubs/player.es", "class PlayerBase {}")
        myFixture.addFileToProject("MyMod/Scripts/3_Game/player_mod.es", "modded class PlayerBase {}")
        assertEmpty(reports("MyMod/Scripts/3_Game/player_mod.es"))
    }

    fun testFieldAddedByALaterLayerIsReported() {
        myFixture.addFileToProject("MyMod/Scripts/3_Game/man.es", "class Man { void Walk() { m_Wings = 2; } }")
        myFixture.addFileToProject("MyMod/Scripts/4_World/man_mod.es", "modded class Man { int m_Wings; }")
        val found = reports("MyMod/Scripts/3_Game/man.es")
        assertEquals(found.toString(), listOf("'m_Wings' is declared in 4_World and is not visible from 3_Game"), found)
    }

    fun testGlobalFunctionFromALaterModuleIsReported() {
        myFixture.addFileToProject("MyMod/Scripts/5_Mission/util.es", "void MissionHelper() {}")
        myFixture.addFileToProject("MyMod/Scripts/4_World/use.es", "class Use { void Go() { MissionHelper(); } }")
        val found = reports("MyMod/Scripts/4_World/use.es")
        assertEquals(found.toString(), listOf("'MissionHelper' is declared in 5_Mission and is not visible from 4_World"), found)
    }

    /** One declaration in an earlier module is enough, wherever the others are. */
    fun testDeclaredInSeveralModules() {
        myFixture.addFileToProject("ModA/Scripts/3_Game/thing.es", "class Thing {}")
        myFixture.addFileToProject("ModB/Scripts/4_World/thing.es", "class Thing {}")
        myFixture.addFileToProject("MyMod/Scripts/3_Game/use.es", "class Use { Thing m_Thing; }")
        assertEmpty(reports("MyMod/Scripts/3_Game/use.es"))
    }

    /** Vanilla `1_core/proto/proto.c` returns `Object`, a `3_Game` class, from a `proto native` method. */
    fun testProtoDeclarationsAreNotChecked() {
        myFixture.addFileToProject("scripts/3_game/object.es", "class Object {}")
        myFixture.addFileToProject("scripts/1_core/proto.es", "class Link { proto private native Object Get(); }")
        assertEmpty(reports("scripts/1_core/proto.es"))
    }

    fun testFileInNoKnownModuleIsNotChecked() {
        myFixture.addFileToProject("MyMod/Scripts/4_World/player.es", "class PlayerBase {}")
        myFixture.addFileToProject("MyMod/Scripts/Common/use.es", "class Use { PlayerBase m_Player; }")
        assertEmpty(reports("MyMod/Scripts/Common/use.es"))
    }

    fun testDeclarationInNoKnownModuleCountsAsVisible() {
        myFixture.addFileToProject("Stubs/player.es", "class PlayerBase {}")
        myFixture.addFileToProject("MyMod/Scripts/3_Game/use.es", "class Use { PlayerBase m_Player; }")
        assertEmpty(reports("MyMod/Scripts/3_Game/use.es"))
    }
}
