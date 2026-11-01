package io.github.lemyst.enfusion.lang

import com.intellij.lang.BracePair
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import io.github.lemyst.enfusion.psi.EnfusionTypes

/**
 * Angle brackets are deliberately absent: `<` and `>` are also comparison operators, and the lexer
 * cannot tell a type-argument list from a comparison. Matching them would mis-fire constantly.
 */
class EnfusionBraceMatcher : PairedBraceMatcher {

    override fun getPairs(): Array<BracePair> = PAIRS

    override fun isPairedBracesAllowedBeforeType(
        lbraceType: IElementType,
        contextType: IElementType?,
    ): Boolean = true

    override fun getCodeConstructStart(file: PsiFile?, openingBraceOffset: Int): Int =
        openingBraceOffset

    companion object {
        private val PAIRS = arrayOf(
            BracePair(EnfusionTypes.LBRACE, EnfusionTypes.RBRACE, true),
            BracePair(EnfusionTypes.LPAREN, EnfusionTypes.RPAREN, false),
            BracePair(EnfusionTypes.LBRACKET, EnfusionTypes.RBRACKET, false),
        )
    }
}
