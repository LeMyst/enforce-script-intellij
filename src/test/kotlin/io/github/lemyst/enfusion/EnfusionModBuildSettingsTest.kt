package io.github.lemyst.enfusion

import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.tools.AddonBuildState
import io.github.lemyst.enfusion.tools.AddonBuilds
import io.github.lemyst.enfusion.tools.run.DayZExtraState
import io.github.lemyst.enfusion.tools.run.DayZModBuildConfiguration
import io.github.lemyst.enfusion.tools.run.DayZModBuildConfigurationType
import io.github.lemyst.enfusion.tools.run.DayZModBuildEditor
import org.jdom.Element
import java.nio.file.Files

/**
 * A DayZ Mod Build configuration keeps everything it is given: through its editor, through the
 * copy the Run Configurations dialog works on, and through saving to and reading from the
 * project's XML. A user's extra files were lost on the way once.
 */
class EnfusionModBuildSettingsTest : BasePlatformTestCase() {

    private fun newConfiguration(): DayZModBuildConfiguration {
        val factory = ConfigurationTypeUtil.findConfigurationType(DayZModBuildConfigurationType::class.java).configurationFactories.single()
        return factory.createTemplateConfiguration(project) as DayZModBuildConfiguration
    }

    private fun filled(): DayZModBuildConfiguration = newConfiguration().also { c ->
        c.options.output = "C:\\out\\@MyMod"
        c.options.keys.add("C:\\keys\\someone.bikey")
        c.options.extras.add(DayZExtraState().also { it.source = "C:\\src\\LICENSE"; it.target = "LICENSE" })
        c.options.extras.add(DayZExtraState().also { it.source = "C:\\src\\notes.md"; it.target = "README.md" })
        c.options.lowerCaseNames = true
    }

    private fun assertKept(c: DayZModBuildConfiguration, how: String) {
        assertEquals(how, "C:\\out\\@MyMod", c.options.output)
        assertEquals(how, listOf("C:\\keys\\someone.bikey"), c.options.keys)
        assertEquals(how, listOf("C:\\src\\LICENSE" to "LICENSE", "C:\\src\\notes.md" to "README.md"), c.options.extras.map { it.source to it.target })
        assertTrue(how, c.options.lowerCaseNames)
    }

    fun testSavedAndReadBack() {
        val element = Element("configuration")
        filled().writeExternal(element)
        val xml = JDOMUtil.write(element)
        assertTrue(xml, xml.contains("LICENSE") && xml.contains("README.md"))
        val back = newConfiguration()
        back.readExternal(element)
        assertKept(back, "read back from $xml")
    }

    fun testCopied() {
        assertKept(filled().clone() as DayZModBuildConfiguration, "a copy")
    }

    fun testThroughTheEditor() {
        val editor = DayZModBuildEditor(project)
        Disposer.register(testRootDisposable, editor)
        editor.component
        editor.resetFrom(filled())
        val applied = newConfiguration()
        editor.applyTo(applied)
        assertKept(applied, "applied by the editor")
        val element = Element("configuration")
        applied.writeExternal(element)
        val back = newConfiguration()
        back.readExternal(element)
        assertKept(back, "applied, saved and read back: ${JDOMUtil.write(element)}")
    }

    /**
     * The release build lists an addon through the work drive, and *Build PBO* may have saved it
     * under the project's path: two spellings of one folder, standing in here for `P:\MyMod\Scripts`
     * and the folder `P:\MyMod` links to. Both find the same lists, and saving keeps one entry.
     */
    fun testAddonListsFoundThroughAnotherPathToTheFolder() {
        val dir = Files.createTempDirectory("addon-lists")
        try {
            val scripts = Files.createDirectories(dir.resolve("MyMod").resolve("Scripts"))
            // Linux resolves `..` through the folder itself, so it must exist.
            val other = Files.createDirectories(dir.resolve("MyMod").resolve("Other"))
            val roundabout = other.resolve("..").resolve("Scripts")
            val builds = AddonBuilds()
            builds.save(AddonBuildState().also { it.source = scripts.toString(); it.include = "C:\\lists\\mine.lst" })

            assertEquals("C:\\lists\\mine.lst", builds.get(roundabout)?.include)

            builds.save(AddonBuildState().also { it.source = roundabout.toString(); it.include = "C:\\lists\\new.lst" })
            assertEquals(listOf(roundabout.toString()), builds.all().map { it.source })
            assertEquals("C:\\lists\\new.lst", builds.get(scripts)?.include)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
