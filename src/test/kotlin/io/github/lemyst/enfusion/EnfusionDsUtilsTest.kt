package io.github.lemyst.enfusion

import io.github.lemyst.enfusion.tools.detect.DsUtilsCommand
import junit.framework.TestCase
import java.nio.file.Path

/** DayZ Tools' signing and signature-checking command lines, and reading the check's verdicts. */
class EnfusionDsUtilsTest : TestCase() {

    private val root: Path = Path.of("").toAbsolutePath().root
    private val bin = root.resolve("Tools").resolve("Bin")

    fun testCommandLines() {
        val key = root.resolve("keys").resolve("MyMod.biprivatekey")
        val pbo = root.resolve("out").resolve("Data.pbo")
        assertEquals(
            listOf(bin.resolve("DsUtils").resolve("DSSignFile.exe").toString(), key.toString(), pbo.toString()),
            DsUtilsCommand.sign(bin, key, pbo),
        )
        assertEquals(
            listOf(bin.resolve("DsUtils").resolve("DSCheckSignatures.exe").toString(), "a", "b"),
            DsUtilsCommand.check(bin, Path.of("a"), Path.of("b")).let { listOf(it[0], Path.of(it[1]).toString(), Path.of(it[2]).toString()) },
        )
    }

    /** Only what is not OK is a problem. */
    fun testProblems() {
        val output = """
            Signature Data.pbo.MyMod.bisign is OK
            Signature Scripts_Client.pbo.MyMod.bisign is wrong
            Key not found for signature Extra.pbo.someone.bisign
            No signature found for Sounds.pbo
        """.trimIndent()
        assertEquals(
            listOf(
                "Signature Scripts_Client.pbo.MyMod.bisign is wrong",
                "Key not found for signature Extra.pbo.someone.bisign",
                "No signature found for Sounds.pbo",
            ),
            DsUtilsCommand.problems(output),
        )
        assertTrue(DsUtilsCommand.problems("Signature Data.pbo.MyMod.bisign is OK\n").isEmpty())
    }
}
