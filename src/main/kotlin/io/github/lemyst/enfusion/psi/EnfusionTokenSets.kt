package io.github.lemyst.enfusion.psi

import com.intellij.psi.tree.TokenSet

object EnfusionTokenSets {

    @JvmField
    val LINE_COMMENTS: TokenSet =
        TokenSet.create(EnfusionTypes.LINE_COMMENT, EnfusionTypes.LINE_DOC_COMMENT)

    @JvmField
    val BLOCK_COMMENTS: TokenSet =
        TokenSet.create(EnfusionTypes.BLOCK_COMMENT, EnfusionTypes.BLOCK_DOC_COMMENT)

    @JvmField
    val DOC_COMMENTS: TokenSet =
        TokenSet.create(EnfusionTypes.LINE_DOC_COMMENT, EnfusionTypes.BLOCK_DOC_COMMENT)

    /**
     * Preprocessor tokens are comment-class on purpose: the parser then skips
     * them everywhere — file scope, class body, statement list, mid-argument-list — so a directive
     * in an unanticipated position can never break a parse.
     */
    @JvmField
    val COMMENTS: TokenSet = TokenSet.create(
        EnfusionTypes.LINE_COMMENT,
        EnfusionTypes.BLOCK_COMMENT,
        EnfusionTypes.LINE_DOC_COMMENT,
        EnfusionTypes.BLOCK_DOC_COMMENT,
        EnfusionTypes.PP_DIRECTIVE,
        EnfusionTypes.INACTIVE_CODE,
    )

    @JvmField
    val STRINGS: TokenSet = TokenSet.create(EnfusionTypes.STRING)

    @JvmField
    val NUMBERS: TokenSet =
        TokenSet.create(EnfusionTypes.INT_LITERAL, EnfusionTypes.FLOAT_LITERAL)

    @JvmField
    val KEYWORDS: TokenSet = TokenSet.create(
        EnfusionTypes.CLASS_KW, EnfusionTypes.ENUM, EnfusionTypes.TYPEDEF, EnfusionTypes.NEW,
        EnfusionTypes.DELETE, EnfusionTypes.RETURN, EnfusionTypes.IF, EnfusionTypes.ELSE,
        EnfusionTypes.FOR, EnfusionTypes.FOREACH, EnfusionTypes.WHILE, EnfusionTypes.SWITCH,
        EnfusionTypes.CASE, EnfusionTypes.DEFAULT, EnfusionTypes.BREAK, EnfusionTypes.CONTINUE,
        EnfusionTypes.NULL, EnfusionTypes.THIS, EnfusionTypes.SUPER, EnfusionTypes.TRUE,
        EnfusionTypes.FALSE, EnfusionTypes.REF, EnfusionTypes.AUTOPTR, EnfusionTypes.STATIC,
        EnfusionTypes.CONST, EnfusionTypes.PRIVATE, EnfusionTypes.PROTECTED,
        EnfusionTypes.OVERRIDE, EnfusionTypes.PROTO, EnfusionTypes.NATIVE, EnfusionTypes.NOTNULL,
        EnfusionTypes.OUT, EnfusionTypes.INOUT, EnfusionTypes.VOLATILE,
    )

    @JvmField
    val PRIMITIVE_TYPES: TokenSet = TokenSet.create(
        EnfusionTypes.VOID, EnfusionTypes.INT, EnfusionTypes.FLOAT, EnfusionTypes.BOOL,
        EnfusionTypes.STRING_TYPE, EnfusionTypes.VECTOR, EnfusionTypes.TYPENAME, EnfusionTypes.FUNC,
    )

    @JvmField
    val BRACES: TokenSet = TokenSet.create(EnfusionTypes.LBRACE, EnfusionTypes.RBRACE)

    @JvmField
    val PARENS: TokenSet = TokenSet.create(EnfusionTypes.LPAREN, EnfusionTypes.RPAREN)

    @JvmField
    val BRACKETS: TokenSet = TokenSet.create(EnfusionTypes.LBRACKET, EnfusionTypes.RBRACKET)

    @JvmField
    val OPERATORS: TokenSet = TokenSet.create(
        EnfusionTypes.EQ, EnfusionTypes.PLUS, EnfusionTypes.MINUS, EnfusionTypes.STAR,
        EnfusionTypes.SLASH, EnfusionTypes.PERCENT, EnfusionTypes.BANG, EnfusionTypes.TILDE,
        EnfusionTypes.AMP, EnfusionTypes.PIPE, EnfusionTypes.CARET, EnfusionTypes.LT,
        EnfusionTypes.GT, EnfusionTypes.LTLT, EnfusionTypes.ANDAND, EnfusionTypes.OROR,
        EnfusionTypes.EQEQ, EnfusionTypes.NEQ, EnfusionTypes.LE, EnfusionTypes.GE,
        EnfusionTypes.PLUS_EQ, EnfusionTypes.MINUS_EQ, EnfusionTypes.STAR_EQ,
        EnfusionTypes.SLASH_EQ, EnfusionTypes.AMP_EQ, EnfusionTypes.PIPE_EQ,
        EnfusionTypes.PLUSPLUS, EnfusionTypes.MINUSMINUS,
    )
}
