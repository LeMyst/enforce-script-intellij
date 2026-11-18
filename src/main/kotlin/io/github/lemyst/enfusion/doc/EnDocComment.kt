package io.github.lemyst.enfusion.doc

/**
 * A Doxygen comment the way vanilla writes them, reduced to what the documentation popup shows.
 *
 * Measured on `P:\scripts`: 2,206 `/** */` and 619 `/*! */` blocks, 4,898 `//!` lines above a
 * declaration, and 355 trailing `//!<`. The commands that matter are `\param` (2,292 uses),
 * `\brief` (1,855), `\p` (1,468), `\return` (906), `@code` (389) and `\note` (388). `\fn` (403)
 * only repeats the signature, and `\defgroup`, `\addtogroup`, `@{` and `@}` group declarations
 * for Doxygen's own pages; neither says anything a popup should show.
 *
 * Every piece of text is HTML-escaped before the inline commands become markup, so a comment in a
 * library file can never inject markup of its own.
 *
 * No IntelliJ API, so it is tested on plain strings.
 */
class EnDocComment private constructor(
    /** The description, as HTML paragraphs. Empty when the comment has none. */
    val description: String,
    /** `\param` entries in order: the parameter name, then its text as HTML. */
    val params: List<Pair<String, String>>,
    /** The `\return` text as HTML. */
    val returns: String?,
    /** `\note`, `\warning`, `\see` and the like: a label, then the text as HTML. */
    val notes: List<Pair<String, String>>,
) {

    val isEmpty: Boolean
        get() = description.isEmpty() && params.isEmpty() && returns == null && notes.isEmpty()

    /** The text `\param name` gives, or null. */
    fun paramText(name: String): String? = params.firstOrNull { it.first == name }?.second

    companion object {

        val EMPTY = EnDocComment("", emptyList(), null, emptyList())

        /** Commands that only group or restate declarations: the whole line goes. */
        private val DROPPED = setOf(
            "fn", "class", "struct", "enum", "name", "defgroup", "addtogroup", "ingroup", "file",
            "var", "def", "typedef", "memberof", "page", "section", "subsection", "subsubsection",
            "anchor", "mainpage", "private", "public", "protected", "static", "overload", "property",
        )

        /** Commands that open a labelled section, with the label the popup gives them. */
        private val NOTE_LABELS = mapOf(
            "note" to "Note", "remark" to "Note", "remarks" to "Note", "attention" to "Attention",
            "warning" to "Warning", "bug" to "Bug", "todo" to "To do", "deprecated" to "Deprecated",
            "see" to "See also", "sa" to "See also", "since" to "Since", "pre" to "Precondition",
            "post" to "Postcondition",
        )

        private val DESCRIPTION_COMMANDS = setOf("brief", "short", "details", "desc")
        private val RETURN_COMMANDS = setOf("return", "returns", "retval", "result")
        private val PARAM_COMMANDS = setOf("param", "tparam")

        /** A line opening with a command: `\param[in] name text`, `@return text`. */
        private val COMMAND = Regex("""^[\\@]([A-Za-z]+)(\[[^\]]*])?(?:\s+(.*))?$""")
        private val CODE_START = Regex("""^[\\@](code|verbatim)\b.*""")
        private val CODE_END = Regex("""[\\@](endcode|endverbatim)\b""")
        private val GROUP_MARKER = Regex("""^[\\@][{}]""")

        private val INLINE_CODE = Regex("""[\\@](?:p|c|a|ref)\s+([^\s,.;:()]+)""")
        private val INLINE_BOLD = Regex("""[\\@]b\s+([^\s,.;:()]+)""")
        private val INLINE_ITALIC = Regex("""[\\@](?:e|em)\s+([^\s,.;:()]+)""")
        private val INLINE_BREAK = Regex("""[\\@]n\b""")

        /** Parses the comment texts, raw from the file, that document one declaration. */
        fun parse(comments: List<String>): EnDocComment {
            if (comments.isEmpty()) return EMPTY
            val builder = Builder()
            for (comment in comments) {
                for (line in contentLines(comment)) builder.line(line)
                // Two `//!` lines one after the other are one paragraph; two comments of
                // different kinds are not continued into each other.
                if (comment.startsWith("/*")) builder.blank()
            }
            return builder.build()
        }

        /** The text lines of one comment, its markers stripped. */
        internal fun contentLines(comment: String): List<String> {
            if (comment.startsWith("/*")) {
                var body = comment.removePrefix("/*").removeSuffix("*/")
                // `/**`, `/*!`, and the trailing forms `/**<` and `/*!<`.
                body = body.removePrefix("*").removePrefix("!").removePrefix("<")
                return body.split('\n').map { raw ->
                    val line = raw.trimEnd('\r')
                    val trimmed = line.trimStart()
                    when {
                        // ` * text` and ` *text`: the decorative column.
                        trimmed.startsWith("*") && !trimmed.startsWith("*/") ->
                            trimmed.removePrefix("*").let { if (it.startsWith(" ")) it.substring(1) else it }
                        else -> line
                    }
                }
            }
            // `//!`, `///`, `//!<`, and separators such as `////////` that are nothing but slashes.
            var text = comment.trimEnd('\r').trimStart('/')
            text = text.removePrefix("!").removePrefix("<")
            return listOf(text)
        }

        private fun escape(text: String): String = buildString(text.length) {
            for (c in text) {
                when (c) {
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '&' -> append("&amp;")
                    '"' -> append("&quot;")
                    else -> append(c)
                }
            }
        }

        /** Escapes [text], then turns the inline commands into markup. */
        internal fun inline(text: String): String {
            var html = escape(text)
            html = INLINE_CODE.replace(html) { "<code>${it.groupValues[1]}</code>" }
            html = INLINE_BOLD.replace(html) { "<b>${it.groupValues[1]}</b>" }
            html = INLINE_ITALIC.replace(html) { "<i>${it.groupValues[1]}</i>" }
            html = INLINE_BREAK.replace(html, "<br>")
            return html
        }
    }

    /** One part of the comment that collects text: the description, a parameter, a note. */
    private class Section {
        val paragraphs = ArrayList<StringBuilder>()
        private var open = false

        fun text(html: String) {
            if (html.isEmpty()) return
            if (!open) {
                paragraphs += StringBuilder()
                open = true
            }
            val current = paragraphs.last()
            if (current.isNotEmpty()) current.append(' ')
            current.append(html)
        }

        /** A block that stands on its own, such as code. */
        fun block(html: String) {
            paragraphs += StringBuilder(html)
            open = false
        }

        fun breakParagraph() {
            open = false
        }

        fun html(): String = paragraphs.filter { it.isNotEmpty() }.joinToString("<p>")
    }

    private class Builder {
        val description = Section()
        val params = ArrayList<Pair<String, Section>>()
        var returns: Section? = null
        val notes = ArrayList<Pair<String, Section>>()
        var current: Section = description
        var code: StringBuilder? = null

        fun blank() {
            if (code != null) {
                code!!.append('\n')
                return
            }
            // A blank line ends a `\param` or `\note` paragraph, as in Doxygen: what follows is
            // the detailed description again.
            current.breakParagraph()
            current = description
        }

        fun line(raw: String) {
            code?.let { buffer ->
                val end = CODE_END.find(raw)
                if (end == null) {
                    buffer.append(raw).append('\n')
                } else {
                    buffer.append(raw.substring(0, end.range.first)).append('\n')
                    closeCode()
                }
                return
            }
            val line = raw.trim()
            if (line.isEmpty()) {
                blank()
                return
            }
            if (GROUP_MARKER.containsMatchIn(line)) return
            if (CODE_START.matches(line)) {
                code = StringBuilder()
                return
            }
            val command = COMMAND.find(line)
            if (command != null) {
                val name = command.groupValues[1].lowercase()
                val rest = command.groupValues[3]
                when (name) {
                    in DROPPED -> return
                    in DESCRIPTION_COMMANDS -> {
                        current = description
                        current.text(inline(rest))
                        return
                    }
                    in PARAM_COMMANDS -> {
                        val parameter = rest.substringBefore(' ').trim().trimEnd(',', ':')
                        val text = rest.substringAfter(' ', "").trim()
                        val section = Section()
                        params += parameter to section
                        current = section
                        section.text(inline(text))
                        return
                    }
                    in RETURN_COMMANDS -> {
                        val section = returns ?: Section().also { returns = it }
                        current = section
                        section.text(inline(rest))
                        return
                    }
                    in NOTE_LABELS -> {
                        val section = Section()
                        notes += NOTE_LABELS.getValue(name) to section
                        current = section
                        section.text(inline(rest))
                        return
                    }
                }
                // Anything else is text: `\Entities\` in a path, or a command this does not know.
            }
            current.text(inline(line))
        }

        fun closeCode() {
            val text = code?.toString()?.trimIndent()?.trim('\n') ?: return
            code = null
            // An example goes with the description, even when it follows `\return` as it does in
            // vanilla's `Math.RandomInt`: under *Returns* it would read as part of the value.
            if (text.isNotBlank()) description.block("<pre><code>${escape(text)}</code></pre>")
            current = description
        }

        fun build(): EnDocComment {
            closeCode()
            return EnDocComment(
                description = description.html(),
                params = params.map { it.first to it.second.html() }.filter { it.first.isNotEmpty() },
                returns = returns?.html()?.ifEmpty { null },
                notes = notes.map { it.first to it.second.html() }.filter { it.second.isNotEmpty() },
            )
        }
    }
}
