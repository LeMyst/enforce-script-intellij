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

    /** One arm of a region: the tokens strictly between its directive and the next one. */
    private class Branch(val region: Region, directive: Int, val active: Boolean) {
        val from = directive + 1
        var to = -1

        /** Net `{` minus `}`, with nested regions counted under the same policy. */
        var balance = 0
    }

    private class Region(val firstActive: Boolean) {
        val branches = ArrayList<Branch>(2)

        /** Whether every branch is emitted; decided when output first reaches the region. */
        var keepAll: Boolean? = null

        fun addBranch(directive: Int, active: Boolean): Branch =
            Branch(this, directive, active).also { branches.add(it) }
    }

    private var buf: CharSequence = ""
    private var bufStart = 0
    private var bufEnd = 0
    private var out: List<Tok> = emptyList()
    private var pos = 0

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        buf = buffer
        bufStart = startOffset
        bufEnd = endOffset

        out = resolve(tokenize(buffer, startOffset, endOffset))
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
    //
    // Everything below is linear in the token count and uses no recursion. The nesting depth of
    // `#ifdef` is chosen by whoever wrote the file, and this runs in every PSI build, including
    // indexing of files the user has never opened. An earlier recursive version rescanned each
    // nested region once per enclosing level and evaluated the selected branch of an unbalanced
    // region twice. Its cost doubled with every two levels of nested unbalanced `#ifndef`
    // (240 bytes took 0.2 s, so 480 bytes would take days), and 2,000 nested `#ifdef` lines took
    // 34 s.

    /** Builds every region in one pass, then emits the token stream with each decision applied. */
    private fun resolve(raw: List<Tok>): List<Tok> {
        val n = raw.size
        // The branch an `#ifdef`, `#ifndef` or `#else` token opens; null for every other token,
        // including `#endif` and a stray `#else` outside any region, which are emitted as they are.
        val branchAt = arrayOfNulls<Branch>(n)
        val open = ArrayList<Region>()

        for (i in 0 until n) {
            val t = raw[i]
            when (t.type) {
                EnfusionTypes.LBRACE -> open.lastOrNull()?.let { it.branches.last().balance++ }
                EnfusionTypes.RBRACE -> open.lastOrNull()?.let { it.branches.last().balance-- }
                EnfusionTypes.PP_DIRECTIVE -> {
                    val s = text(t).trimStart()
                    val top = open.lastOrNull()
                    when {
                        isConditionalOpen(s) -> {
                            val flag = flagOf(s)
                            val negated = s.startsWith("#ifndef")
                            val region = Region(if (negated) flag !in defines else flag in defines)
                            branchAt[i] = region.addBranch(i, region.firstActive)
                            open.add(region)
                        }
                        top == null -> Unit
                        s.startsWith("#endif") -> {
                            top.branches.last().to = i
                            open.removeAt(open.size - 1)
                            close(top, open.lastOrNull())
                        }
                        // Only `#ifdef`/`#else` exist, so a later branch is always the complement
                        // of the first.
                        s.startsWith("#else") -> {
                            top.branches.last().to = i
                            branchAt[i] = top.addBranch(i, !top.firstActive)
                        }
                    }
                }
            }
        }
        // Unterminated regions: each takes the rest of the file as its last branch.
        while (open.isNotEmpty()) {
            val region = open.removeAt(open.size - 1)
            region.branches.last().to = n
            close(region, open.lastOrNull())
        }

        val significant = SignificantIndex(raw)
        val out = ArrayList<Tok>(n)
        var i = 0
        while (i < n) {
            out.add(raw[i])
            val branch = branchAt[i]
            if (branch == null) {
                i++
                continue
            }
            val region = branch.region
            val keepAll = region.keepAll ?: region.branches.all {
                it.balance == 0 && selfContained(raw, significant, it.from, it.to)
            }.also { region.keepAll = it }
            if (keepAll || branch.active) {
                i++ // emit the branch's tokens, nested regions included, as they come
            } else {
                if (branch.to > branch.from) {
                    out.add(Tok(EnfusionTypes.INACTIVE_CODE, raw[branch.from].start, raw[branch.to - 1].end))
                }
                i = branch.to // the next #else, the #endif, or the end of the file
            }
        }
        return out
    }

    /**
     * Adds a finished region to the brace balance of the branch enclosing it: a region whose
     * branches are all balanced contributes zero; otherwise its selected branch counts.
     */
    private fun close(region: Region, enclosing: Region?) {
        if (enclosing == null) return
        val contribution =
            if (region.branches.all { it.balance == 0 }) 0
            else region.branches.firstOrNull { it.active }?.balance ?: 0
        enclosing.branches.last().balance += contribution
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
    private fun selfContained(raw: List<Tok>, significant: SignificantIndex, from: Int, to: Int): Boolean {
        val first = significant.next[from]
        if (first >= to) return true // empty branch
        // A branch opening with `else` or `}` continues a statement that began outside the region,
        // so it can never stand alongside its sibling. dayzgame.c guards a bare `else { ... }` arm
        // with `#ifdef PLATFORM_PS4`; emitting it next to the other branch leaves a dangling else.
        val firstType = raw[first].type
        if (firstType === EnfusionTypes.ELSE || firstType === EnfusionTypes.RBRACE) return false
        val lastType = raw[significant.previous[to - 1]].type
        return lastType === EnfusionTypes.SEMICOLON || lastType === EnfusionTypes.RBRACE
    }

    /**
     * For every token index, the nearest significant token at or after it ([next], `size` if none)
     * and at or before it ([previous], -1 if none). Without it, a branch made only of nested
     * directives would be rescanned once per nesting level.
     */
    private class SignificantIndex(raw: List<Tok>) {
        val next = IntArray(raw.size + 1)
        val previous = IntArray(raw.size)

        init {
            next[raw.size] = raw.size
            for (i in raw.indices.reversed()) next[i] = if (isIgnorable(raw[i].type)) next[i + 1] else i
            var last = -1
            for (i in raw.indices) {
                if (!isIgnorable(raw[i].type)) last = i
                previous[i] = last
            }
        }
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

    private companion object {
        fun isIgnorable(t: IElementType): Boolean =
            t === com.intellij.psi.TokenType.WHITE_SPACE ||
                t === EnfusionTypes.LINE_COMMENT || t === EnfusionTypes.BLOCK_COMMENT ||
                t === EnfusionTypes.LINE_DOC_COMMENT || t === EnfusionTypes.BLOCK_DOC_COMMENT ||
                t === EnfusionTypes.PP_DIRECTIVE
    }
}
