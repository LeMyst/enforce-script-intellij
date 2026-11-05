package io.github.lemyst.enfusion.completion

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import io.github.lemyst.enfusion.psi.EnfusionTypes
import io.github.lemyst.enfusion.psi.impl.EnMethodDeclMixin

/**
 * Which keywords make sense at a completion position.
 *
 * Offering every keyword everywhere would put `override` in an `if` condition and `return` in a
 * parameter list, which is noise the moment there is more than one letter typed. So the position
 * is classified first, from the PSI around it, and each class gets its own list.
 *
 * Contextual keywords are included only where real code writes them: `modded`, `sealed`,
 * `extends` and `auto`. The engine-binding modifiers (`owned`, `external`, `local`, `reference`)
 * appear only on vanilla's `proto` declarations, which a mod never writes, and `thread` occurs only
 * in vanilla's comments.
 */
object EnfusionKeywords {

    /** A keyword, and whether accepting it should add a space (it is followed by something). */
    data class Keyword(val text: String, val addsSpace: Boolean)

    private fun spaced(vararg words: String) = words.map { Keyword(it, addsSpace = true) }
    private fun bare(vararg words: String) = words.map { Keyword(it, addsSpace = false) }

    /** `void` and `func` are left out of value positions: neither can type a value or a local. */
    private val VALUE_TYPES = bare("int", "float", "bool", "string", "vector", "typename")
    private val ALL_TYPES = VALUE_TYPES + bare("void", "func")

    private val STATEMENTS = spaced("if", "for", "foreach", "while", "switch", "delete") +
        bare("break", "continue")
    private val LOCAL_MODIFIERS = spaced("ref", "autoptr", "const", "auto")
    private val EXPRESSION_START = spaced("new") + bare("this", "super")
    private val LITERALS = bare("null", "true", "false")
    private val PARAMETER_MODIFIERS = spaced("out", "inout", "notnull", "ref", "const", "autoptr")
    private val MEMBER_MODIFIERS = spaced(
        "static", "const", "private", "protected", "override", "proto", "native", "ref", "autoptr",
    )
    private val FILE_SCOPE = spaced("class", "modded", "sealed", "enum", "typedef") +
        spaced("static", "const", "proto", "native", "ref")

    fun at(position: PsiElement): List<Keyword> {
        val parent = position.parent ?: return emptyList()
        val previous = PsiTreeUtil.prevVisibleLeaf(position)
        if (previous?.node?.elementType === EnfusionTypes.DOT) return emptyList()

        val method = PsiTreeUtil.getParentOfType(position, EnMethodDeclMixin::class.java)
        val body = method?.node?.findChildByType(EnfusionTypes.BLOCK_STMT)?.psi
        if (body != null && PsiTreeUtil.isAncestor(body, position, false)) {
            return inMethodBody(parent, method)
        }

        // `modded <caret>` can only go on to `class`, and so can `sealed <caret>` at file scope;
        // in a class body `sealed` is a member modifier. Checked before anything structural,
        // because `modded cl<caret>` parses as a variable of type `modded`.
        if (previous?.node?.elementType === EnfusionTypes.IDENT) {
            val inClassBody = enclosingOpener(position) == Opener.CLASS_BODY
            when {
                previous.text == "modded" -> return spaced("class")
                previous.text == "sealed" && !inClassBody -> return spaced("class")
                previous.text == "sealed" -> return memberStart()
            }
        }
        if (isAfterClassName(position)) return spaced("extends")

        return when (parent.node?.elementType) {
            EnfusionTypes.TYPE_REF -> typeSlot(parent)
            else -> if (EnfusionCompletionContributor.isUnparsedMemberStart(position)) {
                when (enclosingOpener(position)) {
                    Opener.FILE -> fileScope()
                    Opener.CLASS_BODY -> memberStart()
                    Opener.PARAMETERS -> PARAMETER_MODIFIERS + VALUE_TYPES
                    Opener.OTHER -> emptyList()
                }
            } else {
                emptyList()
            }
        }
    }

    private fun inMethodBody(parent: PsiElement, method: EnMethodDeclMixin): List<Keyword> {
        val parentType = parent.node?.elementType
        val statement = parent.parent
        val atStatementStart = statement != null &&
            parent.textRange.startOffset == statement.textRange.startOffset &&
            (
                (parentType === EnfusionTypes.REF_EXPR && statement.node?.elementType === EnfusionTypes.EXPR_STMT) ||
                    (parentType === EnfusionTypes.TYPE_REF && statement.node?.elementType === EnfusionTypes.LOCAL_VAR_DECL_STMT)
                )
        if (atStatementStart) {
            val out = ArrayList<Keyword>()
            out += STATEMENTS
            out += returnKeyword(method)
            out += LOCAL_MODIFIERS
            out += EXPRESSION_START
            out += VALUE_TYPES
            // Every statement sits inside a STATEMENT wrapper; siblings and the container are
            // the wrapper's.
            val wrapper = statement.parent?.takeIf { it.node?.elementType === EnfusionTypes.STATEMENT }
                ?: statement
            if (followsIfWithoutElse(wrapper)) out += spaced("else")
            if (isInSwitch(wrapper)) out += spaced("case") + Keyword("default", addsSpace = false)
            return out
        }
        return when (parentType) {
            EnfusionTypes.REF_EXPR -> EXPRESSION_START + LITERALS + VALUE_TYPES
            EnfusionTypes.TYPE_REF -> typeSlot(parent)
            else -> emptyList()
        }
    }

    /**
     * `return` with a space when the method returns a value, without one in a `void` method or a
     * constructor, where the next thing typed is the `;`.
     */
    private fun returnKeyword(method: EnMethodDeclMixin): Keyword {
        val returnType = method.getReturnTypeText()
        val returnsNothing = returnType == null || returnType == "void"
        return Keyword("return", addsSpace = !returnsNothing)
    }

    /** What may be written where a type is: depends on what the type belongs to. */
    private fun typeSlot(typeRef: PsiElement): List<Keyword> {
        val owner = typeRef.parent ?: return emptyList()
        return when (owner.node?.elementType) {
            EnfusionTypes.PARAM -> PARAMETER_MODIFIERS + VALUE_TYPES
            EnfusionTypes.LOCAL_VAR_DECL_STMT -> LOCAL_MODIFIERS + VALUE_TYPES
            EnfusionTypes.FOREACH_VAR -> spaced("ref", "auto") + VALUE_TYPES
            EnfusionTypes.TYPE_ARG -> spaced("ref") + VALUE_TYPES
            EnfusionTypes.TYPEDEF_DECL -> VALUE_TYPES
            EnfusionTypes.METHOD_DECL, EnfusionTypes.VAR_DECL ->
                if (owner.parent is PsiFile) fileScope() else memberStart()
            // `new X(...)` takes a class, and `class A : B` a class to extend: never a primitive.
            else -> emptyList()
        }
    }

    private fun memberStart(): List<Keyword> = MEMBER_MODIFIERS + ALL_TYPES

    private fun fileScope(): List<Keyword> = FILE_SCOPE + ALL_TYPES

    /** `class Foo <caret>` or `class Foo<Class T> <caret>`, before the body opens. */
    private fun isAfterClassName(position: PsiElement): Boolean {
        val previous = PsiTreeUtil.prevVisibleLeaf(position) ?: return false
        val classDecl = PsiTreeUtil.getParentOfType(previous, CLASS_DECL_CLASS, false) ?: return false
        if (classDecl.node.findChildByType(EnfusionTypes.SUPER_CLAUSE) != null) return false
        val nameOrParams = classDecl.node.findChildByType(EnfusionTypes.TYPE_PARAM_LIST)?.psi
            ?: (classDecl as? com.intellij.psi.PsiNameIdentifierOwner)?.nameIdentifier
            ?: return false
        return previous.textRange.endOffset == nameOrParams.textRange.endOffset &&
            position.textRange.startOffset > nameOrParams.textRange.endOffset
    }

    private enum class Opener { FILE, CLASS_BODY, PARAMETERS, OTHER }

    /**
     * What the nearest unclosed bracket before [position] opened, read from tokens.
     *
     * A lone identifier in a class body or a parameter list does not parse: the member or
     * parameter around it needs a name too, and the class's own parse gives up, leaving the
     * identifier as a direct child of the file. The PSI then says nothing about where it was
     * typed, but the tokens still do, and the language has no nested classes or local functions
     * to make the walk ambiguous.
     */
    private fun enclosingOpener(position: PsiElement): Opener {
        var depth = 0
        var leaf = PsiTreeUtil.prevVisibleLeaf(position)
        while (leaf != null) {
            when (leaf.node?.elementType) {
                EnfusionTypes.RBRACE, EnfusionTypes.RPAREN -> depth++
                EnfusionTypes.LPAREN -> if (depth == 0) return Opener.PARAMETERS else depth--
                EnfusionTypes.LBRACE -> if (depth == 0) return openerOfBrace(leaf) else depth--
            }
            leaf = PsiTreeUtil.prevVisibleLeaf(leaf)
        }
        return Opener.FILE
    }

    /** A class body, when `class` comes before this brace with no statement boundary between. */
    private fun openerOfBrace(brace: PsiElement): Opener {
        var leaf = PsiTreeUtil.prevVisibleLeaf(brace)
        while (leaf != null) {
            when (leaf.node?.elementType) {
                EnfusionTypes.CLASS_KW -> return Opener.CLASS_BODY
                EnfusionTypes.SEMICOLON, EnfusionTypes.LBRACE, EnfusionTypes.RBRACE,
                EnfusionTypes.RPAREN, EnfusionTypes.ENUM -> return Opener.OTHER
            }
            leaf = PsiTreeUtil.prevVisibleLeaf(leaf)
        }
        return Opener.OTHER
    }


    /** The statement before this one is an `if` that has no `else` yet. */
    private fun followsIfWithoutElse(statement: PsiElement): Boolean {
        var previous = statement.prevSibling
        while (previous is PsiWhiteSpace || previous is PsiComment) previous = previous.prevSibling
        val ifStmt = previous?.node?.let { it.findChildByType(EnfusionTypes.IF_STMT) ?: it }
            ?.takeIf { it.elementType === EnfusionTypes.IF_STMT } ?: return false
        return ifStmt.findChildByType(EnfusionTypes.ELSE) == null
    }

    /** Directly inside a `switch` body or one of its `case` arms. */
    private fun isInSwitch(statement: PsiElement): Boolean {
        val container: IElementType? = statement.parent?.node?.elementType
        return container === EnfusionTypes.SWITCH_STMT || container === EnfusionTypes.SWITCH_CASE
    }

    private val CLASS_DECL_CLASS = io.github.lemyst.enfusion.psi.impl.EnClassDeclMixin::class.java
}
