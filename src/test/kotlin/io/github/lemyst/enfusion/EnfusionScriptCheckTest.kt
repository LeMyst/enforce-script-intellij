package io.github.lemyst.enfusion

import io.github.lemyst.enfusion.tools.logs.ScriptCheckLog
import junit.framework.TestCase

/** Reading a compile verdict from script-log lines shaped like the real ones. */
class EnfusionScriptCheckTest : TestCase() {

    fun testStillCompiling() {
        val lines = listOf(
            "10:55:04.856 SCRIPT       : Module: GameLib; loaded 21x files; 31x classes",
            "10:55:05.100 SCRIPT       : Module: Game; loaded 814x files; 2211x classes",
        )
        assertSame(ScriptCheckLog.Verdict.Pending, ScriptCheckLog.verdict(lines))
        assertSame(ScriptCheckLog.Verdict.Pending, ScriptCheckLog.verdict(emptyList()))
    }

    fun testEveryModuleCompiled() {
        val lines = listOf(
            "10:58:21.590 SCRIPT    (W): @\"scripts/3_Game/human.c,17\": Missing script declaration",
            "10:58:25.626 SCRIPT       : Module: World; loaded 2610x files",
            "10:58:43.045 SCRIPT       : Module: Mission; loaded 610x files",
        )
        assertSame(ScriptCheckLog.Verdict.Compiled, ScriptCheckLog.verdict(lines))
    }

    fun testCompileFailure() {
        val lines = listOf(
            "10:55:04.856 SCRIPT       : Module: GameLib; loaded 21x files",
            "10:55:04.979 SCRIPT    (E): @\"MyMod/Scripts/3_Game/settings.c,1\": Unknown type 'Settings'",
            "10:55:04.979 SCRIPT    (E): Can't compile \"Game\" script module!",
            "",
            "MyMod/Scripts/3_Game/settings.c(1): Unknown type 'Settings'",
        )
        val verdict = ScriptCheckLog.verdict(lines) as ScriptCheckLog.Verdict.Failed
        assertEquals("Game", verdict.module)
        assertEquals(
            listOf(
                "10:55:04.979 SCRIPT    (E): @\"MyMod/Scripts/3_Game/settings.c,1\": Unknown type 'Settings'",
                "MyMod/Scripts/3_Game/settings.c(1): Unknown type 'Settings'",
            ),
            verdict.errors,
        )
    }
}
