package io.github.lemyst.enfusion

import com.intellij.find.FindModel
import com.intellij.find.impl.FindInProjectUtil
import com.intellij.openapi.application.runWriteAction
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.usageView.UsageInfo
import com.intellij.usages.FindUsagesProcessPresentation
import com.intellij.usages.UsageViewPresentation
import com.intellij.util.CommonProcessors

/**
 * Find in Files and Replace in Files skip textures.
 *
 * Before the plugin claimed `.edds` and `.paa`, the platform guessed their type from content, and a
 * texture whose bytes looked like text came back as a page of garbage matches. Modders excluded
 * whole `GUI/textures` folders to stop that, which also hid imagesets from navigation.
 */
class EnfusionTextureFindInFilesTest : BasePlatformTestCase() {

    fun testTexturesAreNotSearched() {
        // Uncompressed BGRA whose every pixel spells "Needle": exactly the bytes that read as text.
        val needle = "Needle".toByteArray()
        val bytes = TextureBytes.eddsBgra(16, 16) { x, y ->
            val i = (y * 16 + x) * 4
            (0 until 4).fold(0) { acc, k -> acc or ((needle[(i + k) % needle.size].toInt() and 0xFF) shl (8 * k)) }
        }
        for (path in listOf("MyMod/GUI/textures/a.edds", "MyMod/GUI/textures/b.paa")) {
            val file = myFixture.tempDirFixture.createFile(path)
            runWriteAction { file.setBinaryContent(if (path.endsWith(".paa")) "Needle Needle Needle".repeat(20).toByteArray() else bytes) }
        }
        myFixture.addFileToProject("MyMod/Scripts/needle.es", "class Needle {}")
        // The control: the same text bytes under an extension nobody claims are guessed to be text
        // and searched, which is what textures used to get.
        val control = myFixture.tempDirFixture.createFile("MyMod/GUI/textures/c.unclaimed")
        runWriteAction { control.setBinaryContent("Needle Needle Needle".repeat(20).toByteArray()) }

        val model = FindModel().apply {
            stringToFind = "Needle"
            isProjectScope = true
            isCaseSensitive = true
        }
        val found = CommonProcessors.CollectProcessor<UsageInfo>()
        FindInProjectUtil.findUsages(model, project, found, FindUsagesProcessPresentation(UsageViewPresentation()))
        val files = found.results.mapNotNull { it.virtualFile?.name }.toSet()
        assertEquals(setOf("needle.es", "c.unclaimed"), files)
    }
}
