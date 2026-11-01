package io.github.lemyst.enfusion

import com.intellij.psi.TokenType
import io.github.lemyst.enfusion.lexer.EnfusionLexerAdapter
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.streams.asSequence

/**
 * The M0 acceptance criterion: lex every file of the vanilla corpus and assert the lexer never
 * produces a BAD_CHARACTER and never loses a character.
 *
 * Skips (rather than fails) when the corpus is absent, so CI without a `P:` drive stays green.
 * Vanilla DayZ sources are proprietary and are never committed here.
 */
class EnfusionCorpusLexTest {

    @Test
    fun wholeCorpusLexesCleanly() {
        val root = System.getProperty("enfusion.corpus.root").orEmpty()
        assumeTrue("corpus not present on this machine", root.isNotEmpty() && File(root).isDirectory)

        val failures = mutableListOf<String>()
        var files = 0
        var lines = 0
        var tokens = 0L
        val t0 = System.nanoTime()

        Files.walk(Paths.get(root)).use { stream ->
            stream.asSequence()
                .filter { it.toString().endsWith(".c", ignoreCase = true) }
                .forEach { path ->
                    val text = read(path)
                    files++
                    lines += text.count { it == '\n' }

                    val lexer = EnfusionLexerAdapter()
                    lexer.start(text)
                    var covered = 0
                    while (lexer.tokenType != null) {
                        tokens++
                        if (lexer.tokenStart != covered) {
                            failures += "$path: gap at offset $covered"
                            break
                        }
                        if (lexer.tokenType == TokenType.BAD_CHARACTER) {
                            val line = text.take(lexer.tokenStart).count { it == '\n' } + 1
                            val ch = text.substring(lexer.tokenStart, lexer.tokenEnd)
                            failures += "$path:$line BAD_CHARACTER ${escape(ch)}"
                        }
                        covered = lexer.tokenEnd
                        lexer.advance()
                    }
                    if (covered != text.length) {
                        failures += "$path: lexer stopped at $covered of ${text.length}"
                    }
                }
        }

        val ms = (System.nanoTime() - t0) / 1_000_000
        println(
            "Lexed $files files / $lines lines / $tokens tokens in ${ms}ms " +
                "(${lines * 1000L / maxOf(ms, 1)} lines/s)"
        )

        if (failures.isNotEmpty()) {
            fail("${failures.size} corpus lexing failures:\n" + failures.take(50).joinToString("\n"))
        }
    }

    private fun read(path: Path): String =
        try {
            Files.readString(path, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            // Some vanilla files are not valid UTF-8.
            String(Files.readAllBytes(path), charset("windows-1252"))
        }

    private fun escape(s: String) = s.map { c ->
        if (c.code in 32..126) c.toString() else "U+%04X".format(c.code)
    }.joinToString("")
}
