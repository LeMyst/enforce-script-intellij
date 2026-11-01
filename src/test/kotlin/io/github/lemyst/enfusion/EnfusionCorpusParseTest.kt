package io.github.lemyst.enfusion

import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.TokenType
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.ParsingTestCase
import io.github.lemyst.enfusion.lang.EnfusionParserDefinition
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.streams.toList

/**
 * The strongest signal in the project: parse every file of the vanilla corpus and assert three
 * things per file — no `PsiErrorElement`, exact text round-tripping, and no `BAD_CHARACTER`.
 *
 * Text round-tripping is what catches a token the preprocessing lexer dropped, which is the failure
 * mode a purely error-element check would miss entirely.
 *
 * The run reports throughput as it goes and names the slowest files at the end. That is not a
 * nicety: a collapse from hundreds of thousands of lines/s to a few thousand means the parser began
 * backtracking, and the slowest-file list points straight at the construct responsible.
 *
 * Skips when the corpus is absent, so CI without a `P:` drive stays green. Vanilla DayZ sources are
 * proprietary and are never committed here.
 *
 * Sample instead of walking all 2,811 files with `-PenfusionCorpusLimit=300`.
 */
abstract class EnfusionCorpusParseTestBase(
    defines: Set<String>,
    private val label: String,
) : ParsingTestCase("", "c", EnfusionParserDefinition(defines)) {

    override fun getTestDataPath(): String = ""

    override fun skipSpaces(): Boolean = false

    override fun includeRanges(): Boolean = false

    fun testCorpusParsesCleanly() {
        val root = System.getProperty("enfusion.corpus.root").orEmpty()
        if (root.isEmpty() || !File(root).isDirectory) {
            log("corpus not present, skipping")
            return
        }
        val limit = System.getProperty("enfusion.corpus.limit")?.toIntOrNull() ?: Int.MAX_VALUE
        val sampling = limit != Int.MAX_VALUE

        val paths = Files.walk(Paths.get(root)).use { stream ->
            stream.filter { it.toString().endsWith(".c", ignoreCase = true) }.toList()
        }.take(limit)

        val failures = mutableListOf<String>()
        val knownDefects = mutableListOf<String>()
        val timings = mutableListOf<Pair<Long, String>>()
        val badChars = TokenSet.create(TokenType.BAD_CHARACTER)
        var lines = 0
        val t0 = System.nanoTime()

        log("parsing ${paths.size} files from $root")

        paths.forEachIndexed { i, path ->
            val text = read(path)
            val fileLines = text.count { it == '\n' }
            lines += fileLines

            // When sampling, name the file BEFORE parsing it. Outlier reporting happens after a
            // file completes, which is useless if a file never completes — this is what identifies
            // a non-terminating parse.
            if (sampling) log("  -> [$i] ${fileLines}L $path")

            // Phases are timed separately: if `parse` dominates the grammar is backtracking, but if
            // the tree walks dominate the cost is the test harness, not the parser.
            val tStart = System.nanoTime()
            val psi = createPsiFile("corpus$i.c", text)
            psi.node // force AST construction inside the parse phase
            val tParsed = System.nanoTime()
            val err = PsiTreeUtil.findChildOfType(psi, PsiErrorElement::class.java)
            val hasBadChars = psi.node.getChildren(badChars).isNotEmpty()
            val tScanned = System.nanoTime()
            val roundTrips = psi.text == text
            val tEnd = System.nanoTime()

            val parseMs = (tParsed - tStart) / 1_000_000
            val scanMs = (tScanned - tParsed) / 1_000_000
            val textMs = (tEnd - tScanned) / 1_000_000
            val fileMs = (tEnd - tStart) / 1_000_000

            val defect = KNOWN_VANILLA_DEFECTS.entries.firstOrNull {
                path.toString().replace('\\', '/').endsWith(it.key)
            }
            if (defect != null) {
                knownDefects += "${defect.key} -- ${defect.value}"
            } else if (err != null) {
                val line = StringUtil.offsetToLineNumber(text, err.textOffset) + 1
                failures += "$path:$line  ${err.errorDescription}"
            }
            if (!roundTrips) failures += "$path  TEXT MISMATCH (${psi.textLength} vs ${text.length})"
            if (hasBadChars) failures += "$path  BAD_CHARACTER"

            timings += fileMs to path.toString()

            // Report outliers the moment they happen, so a run that is cut short still yields data.
            if (fileMs >= SLOW_FILE_MS) {
                log("  SLOW ${fileMs}ms (parse=$parseMs scan=$scanMs text=$textMs) ${fileLines}L  $path")
            }
            if ((i + 1) % 25 == 0) {
                val elapsed = maxOf((System.nanoTime() - t0) / 1_000_000, 1)
                log("  ${i + 1}/${paths.size} files, ${elapsed / 1000}s, ${lines * 1000L / elapsed} lines/s, ${failures.size} failures")
            }
        }

        val ms = (System.nanoTime() - t0) / 1_000_000
        log("parsed ${paths.size} files / $lines lines in ${ms}ms (${lines * 1000L / maxOf(ms, 1)} lines/s)")

        if (knownDefects.isNotEmpty()) {
            log("skipped ${knownDefects.size} file(s) with known defects in vanilla source:")
            knownDefects.forEach { log("  $it") }
        }

        timings.sortByDescending { it.first }
        log("slowest files:")
        timings.take(10).forEach { (fileMs, path) -> log("  ${fileMs}ms  $path") }

        if (failures.isNotEmpty()) {
            log("FAILURES (${failures.size}), first 40:")
            failures.take(40).forEach { log("  $it") }
            fail("[$label] ${failures.size} corpus failures - see log above")
        }
    }

    private fun log(message: String) {
        println("[$label] $message")
        System.out.flush()
    }

    private fun read(path: Path): String =
        try {
            Files.readString(path, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            String(Files.readAllBytes(path), charset("windows-1252"))
        }

    companion object {
        /** Anything slower than this is an outlier worth naming while the run is still going. */
        private const val SLOW_FILE_MS = 50L

        /**
         * Files that are genuinely malformed in Bohemia's own source, as opposed to constructs the
         * grammar has not learned yet.
         *
         * Everything else the corpus threw up was real: missing semicolons, a missing comma between
         * enum constants, a stray fourth clause in `for(;;;)`, an `if` with no body. Those the
         * engine compiler accepts, so the grammar accepts them too. The entries here it cannot --
         * accepting them would mean loosening generic parsing everywhere to excuse one typo.
         */
        private val KNOWN_VANILLA_DEFECTS = mapOf(
            "3_game/global/ammotypes.c" to
                "`map<string, int>> surfaceNameToEffectIDMap;` has one `>` too many",
        )
    }
}

/** Default flags: the shape a client build sees. */
class EnfusionCorpusParseTest :
    EnfusionCorpusParseTestBase(setOf("PLATFORM_WINDOWS"), "client")

/**
 * The opposite corner of the flag space. If both configurations parse clean, the keep-both-when-
 * balanced policy in EnfusionPreprocessingLexer is validated rather than merely plausible.
 */
class EnfusionCorpusParseServerTest :
    EnfusionCorpusParseTestBase(
        setOf("PLATFORM_WINDOWS", "SERVER", "DEVELOPER", "DIAG", "DIAG_DEVELOPER"),
        "server+diag",
    )
