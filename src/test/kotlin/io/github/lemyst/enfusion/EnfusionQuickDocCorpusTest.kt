package io.github.lemyst.enfusion

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.doc.EnDocCommentFinder
import io.github.lemyst.enfusion.doc.EnfusionDocumentationProvider
import io.github.lemyst.enfusion.index.EnfusionIndexes
import io.github.lemyst.enfusion.psi.EnNamedElement
import java.io.File

/**
 * Quick Documentation over the vanilla scripts: every documented declaration renders without
 * throwing, and none takes noticeable time. Undocumented ones are sampled too, since they take
 * the fallback through the other layers and what they override.
 *
 * Skips when the corpus is absent, like the parse tests.
 */
class EnfusionQuickDocCorpusTest : BasePlatformTestCase() {

    fun testEveryDocumentedDeclarationRenders() {
        val root = System.getProperty("enfusion.corpus.root").orEmpty()
        if (root.isEmpty() || !File(root).isDirectory) {
            println("[quickdoc] corpus not present, skipping")
            return
        }
        val base = File(root)
        val loaded = base.walkTopDown().filter { it.isFile && it.extension.equals("c", ignoreCase = true) }.map { file ->
            val relative = file.relativeTo(base).invariantSeparatorsPath.removeSuffix(".c") + ".es"
            myFixture.addFileToProject("scripts/$relative", file.readText().replace("\r\n", "\n"))
        }.toList()

        val provider = EnfusionDocumentationProvider()
        var documented = 0
        var rendered = 0
        var undocumented = 0
        var slowest = 0L
        var slowestName = ""
        val t0 = System.nanoTime()
        for (file in loaded) {
            for (declaration in PsiTreeUtil.findChildrenOfType(file, EnNamedElement::class.java)) {
                if (!EnfusionIndexes.isSymbolDeclaration(declaration) && !EnfusionIndexes.isTypeDeclaration(declaration)) continue
                val hasComment = EnDocCommentFinder.commentsOf(declaration).isNotEmpty()
                if (hasComment) documented++ else if (undocumented++ % 25 != 0) continue
                val start = System.nanoTime()
                val html = provider.generateDoc(declaration, null)
                val took = System.nanoTime() - start
                if (took > slowest) {
                    slowest = took
                    slowestName = "${declaration.name} in ${file.virtualFile.path}"
                }
                assertNotNull("no documentation for ${declaration.name} in ${file.virtualFile.path}", html)
                rendered++
            }
        }
        val total = (System.nanoTime() - t0) / 1_000_000
        println("[quickdoc] $documented documented declarations, $rendered rendered in $total ms; slowest ${slowest / 1_000_000} ms ($slowestName)")
        assertTrue("no documented declaration found", documented > 0)
        assertTrue("slowest took ${slowest / 1_000_000} ms: $slowestName", slowest < 2_000_000_000L)
    }
}
