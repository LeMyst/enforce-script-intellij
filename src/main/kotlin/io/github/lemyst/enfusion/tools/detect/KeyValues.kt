package io.github.lemyst.enfusion.tools.detect

/**
 * Valve's KeyValues text format, as Steam writes `libraryfolders.vdf` and `appmanifest_*.acf`:
 * `"key" "value"` pairs and `"key" { ... }` blocks, with `//` comments.
 *
 * Steam has changed the case of some keys over the years (`LibraryFolders`, `libraryfolders`), so
 * lookups ignore case. A malformed file yields whatever was read before the fault rather than an
 * exception: detection is best effort, and a half-read file is still better than none.
 */
class KeyValues private constructor(private val entries: List<Pair<String, Any>>) {

    /** The string value of [key], or null when it is absent or a block. */
    fun string(key: String): String? =
        entries.firstOrNull { it.first.equals(key, ignoreCase = true) && it.second is String }?.second as String?

    /** The block under [key], or null when it is absent or a plain value. */
    fun block(key: String): KeyValues? =
        entries.firstOrNull { it.first.equals(key, ignoreCase = true) && it.second is KeyValues }?.second as KeyValues?

    /** Every entry, in file order: values are `String` or [KeyValues]. */
    val all: List<Pair<String, Any>> get() = entries

    companion object {
        fun parse(text: String): KeyValues = Parser(text).block(topLevel = true)
    }

    private class Parser(private val text: String) {
        private var at = 0

        fun block(topLevel: Boolean): KeyValues {
            val entries = ArrayList<Pair<String, Any>>()
            while (true) {
                val key = token() ?: break
                if (key == CLOSE) {
                    if (topLevel) continue
                    break
                }
                if (key == OPEN) continue
                val value = token() ?: break
                when (value) {
                    OPEN -> entries += key to block(topLevel = false)
                    CLOSE -> break
                    else -> entries += key to value
                }
            }
            return KeyValues(entries)
        }

        /** The next string, or the [OPEN] / [CLOSE] markers; null at the end of the text. */
        private fun token(): String? {
            while (at < text.length) {
                val c = text[at]
                when {
                    c.isWhitespace() -> at++
                    c == '/' && text.startsWith("//", at) -> {
                        val eol = text.indexOf('\n', at)
                        at = if (eol < 0) text.length else eol + 1
                    }

                    c == '{' -> { at++; return OPEN }
                    c == '}' -> { at++; return CLOSE }
                    c == '"' -> return quoted()
                    else -> return bare()
                }
            }
            return null
        }

        private fun quoted(): String {
            at++
            val out = StringBuilder()
            while (at < text.length) {
                val c = text[at++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' && at < text.length -> {
                        when (val e = text[at++]) {
                            'n' -> out.append('\n')
                            't' -> out.append('\t')
                            else -> out.append(e)
                        }
                    }

                    else -> out.append(c)
                }
            }
            return out.toString()
        }

        private fun bare(): String {
            val start = at
            while (at < text.length && !text[at].isWhitespace() && text[at] != '{' && text[at] != '}' && text[at] != '"') at++
            return text.substring(start, at)
        }

        companion object {
            // Not valid as KeyValues strings, so they cannot clash with a real token.
            const val OPEN = "\u0000{"
            const val CLOSE = "\u0000}"
        }
    }
}
