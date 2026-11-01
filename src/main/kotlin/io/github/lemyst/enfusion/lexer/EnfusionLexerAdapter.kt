package io.github.lemyst.enfusion.lexer

import com.intellij.lexer.FlexAdapter

/**
 * The raw, fully restartable lexer. Used for syntax highlighting and the words scanner, where
 * incrementality matters and preprocessor branch resolution must not happen.
 *
 * M1 adds `EnfusionPreprocessingLexer`, a non-incremental wrapper used only by the
 * `ParserDefinition`, which resolves `#ifdef` regions (keeping both branches when both are
 * brace-balanced) before handing tokens to the parser.
 */
class EnfusionLexerAdapter : FlexAdapter(_EnfusionLexer(null))
