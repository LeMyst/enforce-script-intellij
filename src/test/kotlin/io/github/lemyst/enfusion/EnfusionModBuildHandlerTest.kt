package io.github.lemyst.enfusion

import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.util.Key
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.tools.detect.DayZModRelease
import io.github.lemyst.enfusion.tools.run.DayZModBuildHandler
import java.nio.file.Files
import java.nio.file.Path

/**
 * The release build's file work, run for real on temporary folders: third-party PBOs with their
 * signatures, keys, `mod.cpp` and extra files copied into place, and what no longer belongs removed.
 * With no project addons and no signing key, neither Addon Builder nor DSUtils runs, so this needs
 * no DayZ Tools.
 */
class EnfusionModBuildHandlerTest : BasePlatformTestCase() {

    private lateinit var temp: Path

    override fun setUp() {
        super.setUp()
        temp = Files.createTempDirectory("mod-release")
    }

    override fun tearDown() {
        try {
            temp.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    private fun file(relative: String, text: String = relative): Path {
        val path = temp.resolve(relative)
        Files.createDirectories(path.parent)
        return Files.writeString(path, text)
    }

    /** Runs the build to its end and returns its exit code and output. */
    private fun build(release: DayZModRelease): Pair<Int, String> {
        val output = StringBuilder()
        val handler = DayZModBuildHandler(release, temp.resolve("tools"), temp.resolve("work")) { null }
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                output.append(event.text)
            }
        })
        handler.startNotify()
        assertTrue("the build did not finish", handler.waitFor(30_000))
        return (handler.exitCode ?: -1) to output.toString()
    }

    fun testCopiesAndRemoves() {
        val pbo = file("third/Extra.pbo")
        file("third/Extra.pbo.someone.bisign")
        val key = file("third/someone.bikey")
        val modCpp = file("src/mod.cpp", "name = \"My Mod\";")
        val license = file("src/LICENSE")
        val out = temp.resolve("@MyMod")
        file("@MyMod/addons/Old.pbo")
        file("@MyMod/addons/notes.txt")
        file("@MyMod/keys/former.bikey")

        val release = DayZModRelease(
            output = out,
            addons = emptyList(),
            pbos = listOf(pbo),
            keys = listOf(key),
            modCpp = modCpp,
            extras = listOf(DayZModRelease.Extra(license, "LICENSE")),
        )
        val (exit, log) = build(release)
        assertEquals(log, 0, exit)
        assertTrue(Files.isRegularFile(out.resolve("addons").resolve("Extra.pbo")))
        assertTrue(Files.isRegularFile(out.resolve("addons").resolve("Extra.pbo.someone.bisign")))
        assertTrue(Files.isRegularFile(out.resolve("keys").resolve("someone.bikey")))
        assertEquals("name = \"My Mod\";", Files.readString(out.resolve("mod.cpp")))
        assertTrue(Files.isRegularFile(out.resolve("LICENSE")))
        assertFalse("a PBO no longer part of the mod goes", Files.exists(out.resolve("addons").resolve("Old.pbo")))
        assertFalse("so does a key", Files.exists(out.resolve("keys").resolve("former.bikey")))
        assertTrue("a file the build does not manage stays", Files.exists(out.resolve("addons").resolve("notes.txt")))

        // A second build copies nothing again.
        val (again, secondLog) = build(release)
        assertEquals(0, again)
        assertFalse(secondLog, secondLog.contains("Copied"))
    }

    /** A file that is not there fails the build, rather than shipping a mod without it. */
    fun testMissingFileFails() {
        val release = DayZModRelease(
            output = temp.resolve("@MyMod"),
            addons = emptyList(),
            modCpp = temp.resolve("src").resolve("mod.cpp"),
        )
        val (exit, log) = build(release)
        assertEquals(log, 1, exit)
        assertTrue(log, log.contains("does not exist"))
    }
}
