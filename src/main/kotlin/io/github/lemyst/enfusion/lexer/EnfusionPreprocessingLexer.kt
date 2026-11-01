package io.github.lemyst.enfusion.lexer

import com.intellij.lexer.LexerBase
import com.intellij.psi.tree.IElementType
import io.github.lemyst.enfusion.psi.EnfusionTypes

/**
 * Wraps the raw lexer and resolves `#ifdef` regions before the parser sees them.
 *
 * The corpus contains 48 regions where an `#ifdef` opens a brace its `#endif` does not close — an
 * `if (x) {` in one branch with the matching `} else {` in the other. Every one of them sits inside
 * a function body; none splits a class, enum or member list.
 *
 * The policy that follows from that:
 *
 *  - **Both branches individually brace-balanced → keep both.** The parser simply sees two
 *    consecutive statement or member lists, and declarations in *both* branches stay indexed. This
 *    is the overwhelmingly common case, and it is why a whole file wrapped in `#ifdef SERVER` is
 *    always fully navigable no matter how the flags are set.
 *  - **Either branch unbalanced → keep the branch the flags select** and collapse the other into one
 *    [EnfusionTypes.INACTIVE_CODE] token. Selection only ever happens where it is unavoidable.
 *
 * Because each branch of every real hazard is internally balanced, even a completely wrong define
 * set still yields a parseable file — the only cost is lost resolution inside the skipped branch.
 *
 * Not incremental, and it does not need to be: `PsiBuilderImpl` tokenizes the whole buffer eagerly.
 * Highlighting uses [EnfusionLexerAdapter] directly, which is fully restartable.
 */
class EnfusionPreprocessingLexer(
    private val defines: Set<String>,
) : LexerBase() {

    private class Tok(val type: IElementType, val start: Int, val end: Int)

    private class Branch(val directive: Int, val from: Int, val to: Int, val active: Boolean)

    private class Region(val branches: List<Branch>, val endifIndex: Int, val endExclusive: Int)

    private var buf: CharSequence = ""
    private var bufStart = 0
    private var bufEnd = 0
    private var out: List<Tok> = emptyList()
    private var pos = 0

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        buf = buffer
        bufStart = startOffset
        bufEnd = endOffset

        val raw = tokenize(buffer, startOffset, endOffset)
        val result = ArrayList<Tok>(raw.size)
        emitRange(raw, 0, raw.size, result)
        out = result
        pos = 0
    }

    override fun getState(): Int = 0

    override fun getTokenType(): IElementType? = if (pos < out.size) out[pos].type else null

    override fun getTokenStart(): Int = out[pos].start

    override fun getTokenEnd(): Int = out[pos].end

    override fun advance() {
        if (pos < out.size) pos++
    }

    override fun getBufferSequence(): CharSequence = buf

    override fun getBufferEnd(): Int = bufEnd

    // ------------------------------------------------------------------ raw pass

    private fun tokenize(buffer: CharSequence, start: Int, end: Int): List<Tok> {
        val lexer = EnfusionLexerAdapter()
        lexer.start(buffer, start, end, 0)
        val list = ArrayList<Tok>(((end - start) / 4).coerceAtLeast(16))
        while (true) {
            val t = lexer.tokenType ?: break
            list.add(Tok(t, lexer.tokenStart, lexer.tokenEnd))
            lexer.advance()
        }
        return list
    }

    // ------------------------------------------------------------------ region resolution

    private fun emitRange(raw: List<Tok>, from: Int, to: Int, out: MutableList<Tok>) {
        var i = from
        while (i < to) {
            val t = raw[i]
            if (t.type === EnfusionTypes.PP_DIRECTIVE && isConditionalOpen(text(t))) {
                val region = parseRegion(raw, i, to)
                emitRegion(raw, region, out)
                i = region.endExclusive
            } else {
                out.add(t)
                i++
            }
        }
    }

    private fun emitRegion(raw: List<Tok>, region: Region, out: MutableList<Tok>) {
        val keepAll = region.branches.all {
            balanced(raw, it.from, it.to) && selfContained(raw, it.from, it.to)
        }
        for (branch in region.branches) {
            out.add(raw[branch.directive])
            if (keepAll || branch.active) {
                emitRange(raw, branch.from, branch.to, out)
            } else if (branch.to > branch.from) {
                out.add(
                    Tok(
                        EnfusionTypes.INACTIVE_CODE,
                        raw[branch.from].start,
                        raw[branch.to - 1].end,
                    )
                )
            }
        }
        if (region.endifIndex >= 0) out.add(raw[region.endifIndex])
    }

    /**
     * Whether a branch is a complete syntactic unit, i.e. safe to emit alongside its siblings.
     *
     * Brace balance alone is not enough. `3_game/entities/man.c` guards only a class *header*:
     *
     * ```
     * #ifdef FEATURE_NETWORK_RECONCILIATION
     * class Man extends Person
     * #else
     * class Man extends DayZPlayer
     * #endif
     * {   ...shared body...   }
     * ```
     *
     * Both branches contain zero braces, so both are "balanced", yet keeping both emits two class
     * headers followed by a single body. Requiring a branch to end at a declaration or statement
     * boundary — `;`, `}`, or empty — rejects that case while still keeping both branches of the
     * whole-file `#ifdef SERVER ... #endif` wrappers and of ordinary statement-level guards.
     */
    private fun selfContained(raw: List<Tok>, from: Int, to: Int): Boolean {
        val first = firstSignificant(raw, from, to) ?: return true // empty branch
        // A branch opening with `else` or `}` continues a statement that began outside the region,
        // so it can never stand alongside its sibling. dayzgame.c guards a bare `else { ... }` arm
        // with `#ifdef PLATFORM_PS4`; emitting it next to the other branch leaves a dangling else.
        if (first === EnfusionTypes.ELSE || first === EnfusionTypes.RBRACE) return false
        val last = lastSignificant(raw, from, to) ?: return true
        return last === EnfusionTypes.SEMICOLON || last === EnfusionTypes.RBRACE
    }

    private fun isIgnorable(t: IElementType): Boolean =
        t === com.intellij.psi.TokenType.WHITE_SPACE ||
            t === EnfusionTypes.LINE_COMMENT || t === EnfusionTypes.BLOCK_COMMENT ||
            t === EnfusionTypes.LINE_DOC_COMMENT || t === EnfusionTypes.BLOCK_DOC_COMMENT ||
            t === EnfusionTypes.PP_DIRECTIVE

    private fun firstSignificant(raw: List<Tok>, from: Int, to: Int): IElementType? {
        for (i in from until to) if (!isIgnorable(raw[i].type)) return raw[i].type
        return null
    }

    private fun lastSignificant(raw: List<Tok>, from: Int, to: Int): IElementType? {
        for (i in to - 1 downTo from) if (!isIgnorable(raw[i].type)) return raw[i].type
        return null
    }

    /** Parses one `#ifdef .. [#else ..] #endif`, ignoring nested regions at greater depth. */
    private fun parseRegion(raw: List<Tok>, openIndex: Int, to: Int): Region {
        val openText = text(raw[openIndex])
        val negated = openText.trimStart().startsWith("#ifndef")
        val flag = flagOf(openText)
        val firstActive = if (negated) flag !in defines else flag in defines

        val separators = ArrayList<Int>()
        var endifIndex = -1
        var depth = 0
        var i = openIndex

        while (i < to) {
            val t = raw[i]
            if (t.type === EnfusionTypes.PP_DIRECTIVE) {
                val s = text(t).trimStart()
                when {
                    isConditionalOpen(s) -> depth++
                    s.startsWith("#endif") -> {
                        depth--
                        if (depth == 0) {
                            endifIndex = i
                            i++
                            break
                        }
                    }
                    s.startsWith("#else") && depth == 1 -> separators.add(i)
                }
            }
            i++
        }
        // Unterminated region: treat the rest of the range as its body.
        val endExclusive = i
        val bodyEnd = if (endifIndex >= 0) endifIndex else to

        val branches = ArrayList<Branch>(separators.size + 1)
        val marks = ArrayList<Int>(separators.size + 1)
        marks.add(openIndex)
        marks.addAll(separators)
        for (n in marks.indices) {
            val contentFrom = marks[n] + 1
            val contentTo = if (n + 1 < marks.size) marks[n + 1] else bodyEnd
            // Only `#ifdef`/`#else` exist, so a second branch is always the complement of the first.
            val active = if (n == 0) firstActive else !firstActive
            branches.add(Branch(marks[n], contentFrom, contentTo, active))
        }
        return Region(branches, endifIndex, endExclusive)
    }

    /**
     * Brace balance of a token range, with nested regions resolved under the same policy: a nested
     * region whose branches are all balanced contributes zero; otherwise its selected branch counts.
     */
    private fun balanced(raw: List<Tok>, from: Int, to: Int): Boolean = balanceOf(raw, from, to) == 0

    private fun balanceOf(raw: List<Tok>, from: Int, to: Int): Int {
        var balance = 0
        var i = from
        while (i < to) {
            val t = raw[i]
            if (t.type === EnfusionTypes.PP_DIRECTIVE && isConditionalOpen(text(t))) {
                val region = parseRegion(raw, i, to)
                val allBalanced = region.branches.all { balanceOf(raw, it.from, it.to) == 0 }
                if (!allBalanced) {
                    val active = region.branches.firstOrNull { it.active }
                    if (active != null) balance += balanceOf(raw, active.from, active.to)
                }
                i = region.endExclusive
                continue
            }
            if (t.type === EnfusionTypes.LBRACE) balance++
            else if (t.type === EnfusionTypes.RBRACE) balance--
            i++
        }
        return balance
    }

    // ------------------------------------------------------------------ helpers

    private fun text(t: Tok): String = buf.subSequence(t.start, t.end).toString()

    private fun isConditionalOpen(s: String): Boolean {
        val trimmed = s.trimStart()
        return trimmed.startsWith("#ifdef") || trimmed.startsWith("#ifndef")
    }

    /** `#ifdef` and `#ifndef` take exactly one bare identifier — no `defined()`, no `&&`. */
    private fun flagOf(directive: String): String {
        var i = 0
        val s = directive
        // skip leading whitespace and the directive word
        while (i < s.length && s[i].isWhitespace()) i++
        while (i < s.length && !s[i].isWhitespace()) i++
        while (i < s.length && s[i].isWhitespace()) i++
        val start = i
        while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
        return s.substring(start, i)
    }
}
