package io.github.lemyst.enfusion

import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.TokenType
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.ParsingTestCase
import io.github.lemyst.enfusion.objfmt.EnfusionObjectParserDefinition
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.streams.toList

/**
 * The object-format counterpart of [EnfusionCorpusParseTestBase]: parse every `.layout` and
 * `.imageset` that can be found and assert no `PsiErrorElement`, exact text round-trip and no
 * `BAD_CHARACTER`.
 *
 * Both vanilla and a mod checkout are walked, and the mod is not optional. Vanilla layouts are
 * machine-written and contain no comments whatsoever; hand-written ones do, and the comment rules
 * would otherwise be dead code that had never met a real input.
 *
 * Skips when no root is present, so a machine without a `P:` drive stays green.
 */
class EnfusionObjectCorpusParseTest :
    ParsingTestCase("", "layout", EnfusionObjectParserDefinition()) {

    override fun getTestDataPath(): String = ""

    override fun skipSpaces(): Boolean = false

    override fun includeRanges(): Boolean = false

    fun testLayoutCorpusParsesCleanly() {
        val roots = System.getProperty("enfusion.layout.roots").orEmpty()
            .split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() && File(it).isDirectory }
        if (roots.isEmpty()) {
            log("no layout roots present, skipping")
            return
        }

        val paths = roots.flatMap { root ->
            Files.walk(Paths.get(root)).use { stream ->
                stream.filter { path ->
                    val name = path.toString()
                    // `.claude/worktrees` holds duplicate checkouts of the same layouts; parsing
                    // them adds runtime and no coverage.
                    !name.replace('\\', '/').contains("/.claude/") &&
                        (name.endsWith(".layout", ignoreCase = true) ||
                            name.endsWith(".imageset", ignoreCase = true))
                }.toList()
            }
        }

        val failures = mutableListOf<String>()
        val badChars = TokenSet.create(TokenType.BAD_CHARACTER)
        var lines = 0
        var comments = 0
        val t0 = System.nanoTime()

        log("parsing ${paths.size} files from ${roots.joinToString(", ")}")

        paths.forEachIndexed { i, path ->
            val text = read(path)
            lines += text.count { it == '\n' }
            if (text.contains("//") || text.contains("/*")) comments++

            val extension = if (path.toString().endsWith(".imageset", ignoreCase = true)) "imageset" else "layout"
            val psi = createPsiFile("corpus$i.$extension", text)
            psi.node

            val error = PsiTreeUtil.findChildOfType(psi, PsiErrorElement::class.java)
            if (error != null) {
                val line = StringUtil.offsetToLineNumber(text, error.textOffset) + 1
                failures += "$path:$line  ${error.errorDescription}"
            }
            if (psi.text != text) {
                failures += "$path  TEXT MISMATCH (${psi.textLength} vs ${text.length})"
            }
            if (psi.node.getChildren(badChars).isNotEmpty()) failures += "$path  BAD_CHARACTER"
        }

        val ms = (System.nanoTime() - t0) / 1_000_000
        log("parsed ${paths.size} files / $lines lines in ${ms}ms (${lines * 1000L / maxOf(ms, 1)} lines/s)")
        log("$comments file(s) contained a comment")

        // Guards the reason the mod checkout is in the root list at all. If it drops out of the
        // configuration this test would still pass while silently no longer exercising comments.
        assertTrue("no commented layout was parsed -- is a mod checkout in enfusionLayoutRoots?", comments > 0)

        if (failures.isNotEmpty()) {
            log("FAILURES (${failures.size}), first 40:")
            failures.take(40).forEach { log("  $it") }
            fail("${failures.size} layout corpus failures - see log above")
        }
    }

    private fun log(message: String) {
        println("[layout] $message")
        System.out.flush()
    }

    private fun read(path: Path): String =
        try {
            Files.readString(path, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            String(Files.readAllBytes(path), charset("windows-1252"))
        }
}
