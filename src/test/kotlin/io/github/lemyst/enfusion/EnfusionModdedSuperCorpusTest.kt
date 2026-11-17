package io.github.lemyst.enfusion

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.inspections.EnModdedOverrideWithoutSuperInspection
import io.github.lemyst.enfusion.psi.impl.EnClassDeclMixin
import io.github.lemyst.enfusion.psi.impl.EnMethodDeclMixin
import java.io.File

/**
 * *Override in a modded class does not call super* over real mods, with vanilla loaded beside them
 * so that what each layer overrides resolves.
 *
 * A measurement more than a gate: mods do skip `super` on purpose now and then, so the reports are
 * printed per method name for a person to judge, as `AGENTS.md` asks before a rule is enabled by
 * default. The mods are the script trees among `enfusionConfigRoots`; vanilla is
 * `enfusionCorpusRoot`, which has no `modded class` at all and must stay silent.
 *
 * Skips when the corpus is absent, like the parse tests.
 */
class EnfusionModdedSuperCorpusTest : BasePlatformTestCase() {

    fun testMeasureOnModCheckouts() {
        val corpus = System.getProperty("enfusion.corpus.root").orEmpty()
        if (corpus.isEmpty() || !File(corpus).isDirectory) {
            println("[super] corpus not present, skipping")
            return
        }
        load(File(corpus), "scripts")
        val mods = System.getProperty("enfusion.config.roots").orEmpty().split(';')
            .map(String::trim).filter(String::isNotEmpty).map(::File)
            .filter { it.isDirectory && it.canonicalFile != File(corpus).canonicalFile }
        val loaded = mods.mapIndexed { index, root -> root to load(root, "mods/m$index") }
            .filter { it.second.isNotEmpty() }

        val tool = EnModdedOverrideWithoutSuperInspection()
        val manager = InspectionManager.getInstance(project)
        for ((root, files) in loaded) {
            var overrides = 0
            val reported = HashMap<String, Int>()
            for (file in files) {
                overrides += PsiTreeUtil.findChildrenOfType(file, EnMethodDeclMixin::class.java).count {
                    it.isOverride() && PsiTreeUtil.getParentOfType(it, EnClassDeclMixin::class.java)?.isModded() == true
                }
                for (name in inspect(tool, manager, file)) reported.merge(name, 1, Int::plus)
            }
            println("[super] ${root.name}: ${files.size} files, $overrides overrides in modded classes, ${reported.values.sum()} reports")
            reported.entries.sortedByDescending { it.value }.take(25).forEach { println("[super]   ${it.value} x ${it.key}") }
        }
    }

    private fun load(root: File, prefix: String): List<PsiFile> =
        root.walkTopDown()
            .onEnter { it.name != ".git" && it.name != ".claude" }
            .filter { it.isFile && it.extension.equals("c", ignoreCase = true) }
            .map { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath.removeSuffix(".c") + ".es"
                myFixture.addFileToProject("$prefix/$relative", file.readText().replace("\r\n", "\n"))
            }
            .toList()

    /** The reported method names. */
    private fun inspect(tool: EnModdedOverrideWithoutSuperInspection, manager: InspectionManager, file: PsiFile): List<String> {
        val holder = ProblemsHolder(manager, file, false)
        val visitor = tool.buildVisitor(holder, false)
        PsiTreeUtil.processElements(file) {
            visitor.visitElement(it)
            true
        }
        return holder.results.map { it.psiElement.text }
    }
}
