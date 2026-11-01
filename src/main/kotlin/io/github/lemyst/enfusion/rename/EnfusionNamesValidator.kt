package io.github.lemyst.enfusion.rename

import com.intellij.lang.refactoring.NamesValidator
import com.intellij.openapi.project.Project

/**
 * What the rename dialog will accept.
 *
 * Without this the platform falls back to a validator that accepts any non-blank string, so
 * `Rename` would happily write `void 2Foo()` or `int class;` into the file and leave the user to
 * discover it at build time.
 *
 * The keyword list is the lexer's, transcribed rather than derived: the tokens live in a JFlex
 * file with no runtime table to query. Contextual words (`modded`, `sealed`, `auto`, `owned`) are
 * deliberately **absent** — they lex as plain identifiers, so a variable may legally be called
 * `auto`, however unwise, and rejecting the name would be inventing a rule the compiler does not
 * have.
 */
class EnfusionNamesValidator : NamesValidator {

    override fun isKeyword(name: String, project: Project?): Boolean = name in KEYWORDS

    override fun isIdentifier(name: String, project: Project?): Boolean =
        IDENTIFIER.matches(name) && !isKeyword(name, project)

    private companion object {
        val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")

        val KEYWORDS = setOf(
            "class", "enum", "typedef", "new", "delete", "return",
            "if", "else", "for", "foreach", "while", "switch", "case", "default",
            "break", "continue", "null", "this", "super", "true", "false",
            "void", "int", "float", "bool", "string", "vector", "typename", "func",
            "ref", "autoptr", "static", "const", "private", "protected",
            "override", "proto", "native", "notnull", "out", "inout", "volatile",
        )
    }
}
