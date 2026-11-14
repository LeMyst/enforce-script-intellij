package io.github.lemyst.enfusion

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.inspections.EnScriptModuleVisibilityInspection
import java.io.File

/**
 * The script-module rule over the vanilla scripts, which compile, so every report is a false
 * positive by construction.
 *
 * The whole tree is loaded, since visibility depends on where every declaration lives, and the
 * files keep their folders (`3_game`, `4_world`, ...), which is how vanilla places them in modules.
 * They are added as `.es`, because a `.c` needs a `config.cpp` ancestor to be script. Then the rule
 * runs on every file of the first four modules (`5_Mission` sees everything), or on an even sample
 * of `enfusionCorpusLimit` of them.
 *
 * Skips when the corpus is absent, like the parse tests.
 */
class EnfusionScriptModuleCorpusTest : BasePlatformTestCase() {

    fun testVanillaHasNoModuleViolations() {
        val root = System.getProperty("enfusion.corpus.root").orEmpty()
        if (root.isEmpty() || !File(root).isDirectory) {
            println("[modules] corpus not present, skipping")
            return
        }
        val base = File(root)
        val sources = base.walkTopDown().filter { it.isFile && it.extension.equals("c", ignoreCase = true) }.toList()
        val t0 = System.nanoTime()
        val loaded = sources.map { file ->
            val relative = file.relativeTo(base).invariantSeparatorsPath.removeSuffix(".c") + ".es"
            myFixture.addFileToProject("scripts/$relative", file.readText().replace("\r\n", "\n"))
        }
        println("[modules] loaded ${loaded.size} vanilla files in ${(System.nanoTime() - t0) / 1_000_000} ms")

        val checked = loaded.filter { !it.virtualFile.path.contains("/5_mission/", ignoreCase = true) }
        val limit = System.getProperty("enfusion.corpus.limit")?.toIntOrNull()
        val sample = if (limit == null || limit >= checked.size) checked else checked.filterIndexed { i, _ -> i % (checked.size / limit) == 0 }

        val tool = EnScriptModuleVisibilityInspection()
        val manager = InspectionManager.getInstance(project)
        val noisy = ArrayList<String>()
        val t1 = System.nanoTime()
        for (file in sample) {
            for (report in inspect(tool, manager, file)) noisy += "${file.virtualFile.path}: $report"
        }
        println("[modules] checked ${sample.size} files in ${(System.nanoTime() - t1) / 1_000_000} ms, ${noisy.size} reports")
        noisy.take(20).forEach { println("[modules]   $it") }
        assertTrue("reported ${noisy.size} times on vanilla: ${noisy.take(5)}", noisy.isEmpty())
    }

    private fun inspect(tool: EnScriptModuleVisibilityInspection, manager: InspectionManager, file: PsiFile): List<String> {
        val holder = ProblemsHolder(manager, file, false)
        val visitor = tool.buildVisitor(holder, false)
        PsiTreeUtil.processElements(file) {
            visitor.visitElement(it)
            true
        }
        return holder.results.map { "${it.descriptionTemplate} (line ${it.lineNumber + 1})" }
    }
}
