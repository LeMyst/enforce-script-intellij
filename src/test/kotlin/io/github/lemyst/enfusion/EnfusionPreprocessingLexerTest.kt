package io.github.lemyst.enfusion

import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import io.github.lemyst.enfusion.lexer.EnfusionDefines
import io.github.lemyst.enfusion.lexer.EnfusionPreprocessingLexer
import io.github.lemyst.enfusion.psi.EnfusionTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * The keep-both-when-balanced `#ifdef` policy, and the cost bounds that make it safe to run on files
 * nobody has reviewed: every PSI build of an Enforce Script file goes through this lexer, including
 * indexing of a freshly cloned mod.
 */
class EnfusionPreprocessingLexerTest {

    private fun lex(text: String, defines: Set<String> = EnfusionDefines.DEFAULT): List<Pair<IElementType, String>> {
        val lexer = EnfusionPreprocessingLexer(defines)
        lexer.start(text, 0, text.length, 0)
        val out = mutableListOf<Pair<IElementType, String>>()
        while (true) {
            val type = lexer.tokenType ?: break
            if (type != TokenType.WHITE_SPACE) out += type to text.substring(lexer.tokenStart, lexer.tokenEnd)
            lexer.advance()
        }
        return out
    }

    private fun inactive(text: String, defines: Set<String> = EnfusionDefines.DEFAULT) =
        lex(text, defines).filter { it.first == EnfusionTypes.INACTIVE_CODE }.map { it.second.trim() }

    /** The token stream must cover the buffer exactly, or PsiBuilder rejects it. */
    private fun assertContiguous(text: String) {
        val lexer = EnfusionPreprocessingLexer(EnfusionDefines.DEFAULT)
        lexer.start(text, 0, text.length, 0)
        var expected = 0
        while (lexer.tokenType != null) {
            assertEquals(expected, lexer.tokenStart)
            expected = lexer.tokenEnd
            lexer.advance()
        }
        assertEquals(text.length, expected)
    }

    @Test
    fun balancedBranchesAreBothKept() {
        val text = "#ifdef SERVER\nint a;\n#else\nint b;\n#endif\n"
        assertTrue(inactive(text).isEmpty())
        assertEquals(2, lex(text).count { it.first == EnfusionTypes.IDENT && it.second in setOf("a", "b") })
    }

    @Test
    fun unbalancedRegionKeepsOnlyTheSelectedBranch() {
        val text = "void F()\n{\n#ifdef SERVER\nif (x) {\n#else\nif (y) {\n#endif\n}\n}\n"
        assertEquals(listOf("if (x) {"), inactive(text))
        assertEquals(listOf("if (y) {"), inactive(text, setOf("SERVER")))
        assertContiguous(text)
    }

    @Test
    fun negatedRegionSelectsTheOtherBranch() {
        val text = "#ifndef SERVER\nif (x) {\n#else\nif (y) {\n#endif\n}\n"
        assertEquals(listOf("if (y) {"), inactive(text))
    }

    /** `3_game/entities/man.c`: balanced, but each branch is only a class header. */
    @Test
    fun headerOnlyBranchesAreNotBothKept() {
        val text = "#ifdef FEATURE_NETWORK_RECONCILIATION\nclass Man extends Person\n#else\n" +
            "class Man extends DayZPlayer\n#endif\n{\n}\n"
        assertEquals(listOf("class Man extends Person"), inactive(text))
    }

    /** dayzgame.c: a branch opening with `else` continues a statement from outside the region. */
    @Test
    fun branchOpeningWithElseIsNotKeptAlongsideItsSibling() {
        val text = "if (a) { }\n#ifdef PLATFORM_PS4\nelse { b(); }\n#endif\n"
        assertEquals(listOf("else { b(); }"), inactive(text))
    }

    /** An unbalanced nested region counts through its selected branch towards the outer balance. */
    @Test
    fun nestedUnbalancedRegionCountsTowardsTheEnclosingBranch() {
        // The outer #ifdef SERVER branch is balanced only through its nested selected branch.
        val text = "#ifdef SERVER\n#ifdef PLATFORM_WINDOWS\nif (x) {\n#else\nif (y) {\n#endif\n}\n#endif\n"
        assertEquals(listOf("if (y) {"), inactive(text))
    }

    @Test
    fun unterminatedRegionRunsToTheEndOfTheFile() {
        val text = "#ifdef SERVER\nif (x) {\n#else\nif (y) {\n"
        assertEquals(listOf("if (x) {"), inactive(text))
        assertContiguous(text)
    }

    @Test
    fun strayDirectivesAreLeftAlone() {
        val text = "#endif\nint a;\n#else\nint b;\n"
        assertTrue(inactive(text).isEmpty())
        assertEquals(2, lex(text).count { it.first == EnfusionTypes.PP_DIRECTIVE })
    }

    /**
     * Each level holds an unmatched `{`, so every region is unbalanced. The recursive resolver
     * evaluated each selected branch twice per level, doubling the work with every 12 bytes.
     */
    @Test(timeout = 10_000)
    fun nestedUnbalancedRegionsCostLinearTime() {
        val text = "#ifndef X\n{\n".repeat(20_000)
        assertContiguous(text)
        assertTrue(inactive(text).isEmpty())
    }

    /**
     * Nesting depth is chosen by the file's author and bounded only by its size: 300,000 levels is
     * 2.1 MB, inside the platform's default 2.5 MB limit for files it parses. Run on a 1 MiB stack,
     * the JVM default, so that any recursion proportional to depth overflows here.
     */
    @Test
    fun deepNestingNeitherOverflowsTheStackNorTakesSuperlinearTime() {
        val depth = 300_000
        val cases = listOf(
            "#ifdef\n".repeat(depth),
            "#ifdef A\n".repeat(depth) + "{\n" + "#endif\n".repeat(depth),
            "#ifdef A\n#else\n".repeat(depth) + "}\n" + "#endif\n".repeat(depth),
        )
        for (text in cases) {
            val failure = AtomicReference<Throwable>()
            val worker = Thread(null, {
                try {
                    assertContiguous(text)
                } catch (t: Throwable) {
                    failure.set(t)
                }
            }, "deep-ifdef", 1L shl 20)
            worker.isDaemon = true
            worker.start()
            worker.join(10_000)
            assertFalse("resolution did not finish in 10 s for ${text.take(20)}...", worker.isAlive)
            failure.get()?.let { fail("resolution failed for ${text.take(20)}...: $it") }
        }
    }
}
