package io.github.lemyst.enfusion.psi.impl

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiReference
import io.github.lemyst.enfusion.psi.EnfusionTokenSets
import io.github.lemyst.enfusion.psi.EnfusionTypes
import io.github.lemyst.enfusion.resolve.EnIdentifierReference
import io.github.lemyst.enfusion.resolve.EnTypeReference

/**
 * Base for the two PSI shapes whose identifier names a type.
 *
 * `getReference()` is overridden rather than a `PsiReferenceContributor` being registered, and that
 * is not a style choice: for Grammar-Kit PSI the platform never consults the provider registry.
 * `PsiElementBase.getReference()` returns null and `SharedPsiElementImplUtil.getReferences()` only
 * calls `getReference()` — the registry is reached solely for `ContributedReferenceHost` and
 * `PsiLanguageInjectionHost`. A contributor would silently do nothing here.
 */
abstract class EnTypeReferencingMixin(node: ASTNode) : ASTWrapperPsiElement(node) {

    override fun getReference(): PsiReference? {
        // Primitives are clickable too. `int`, `float`, `bool`, `string`, `vector`, `typename` and
        // `func` are declared as real classes in vanilla 1_core/proto/enconvert.c, complete with
        // methods such as `ToString()` — so they are ordinary navigation targets, not keywords with
        // nowhere to go. EnClassDeclMixin already indexes them, since a class name may itself be a
        // primitive keyword; only the reference was missing.
        val identifier = node.findChildByType(EnfusionTypes.IDENT)
            ?: node.findChildByType(EnfusionTokenSets.PRIMITIVE_TYPES)
            ?: return null
        // The range must be element-relative. It must also be supplied explicitly: the no-range
        // constructor asks ElementManipulators for one and throws when none is registered.
        return EnTypeReference(this, identifier.textRange.shiftLeft(node.startOffset))
    }
}

/**
 * A type in a declaration: local, field, parameter, return type, `extends` clause, generic argument
 * and `new X(...)` are all the same `typeRef` rule, so this single mixin covers roughly two thirds
 * of all type-reference sites in real code.
 */
abstract class EnTypeRefMixin(node: ASTNode) : EnTypeReferencingMixin(node)

/**
 * A bare identifier expression: a local, parameter, `foreach` variable, field, file-scope global,
 * or a class name used as a static qualifier (`Math.Clamp(...)`, `BattleRoyaleUtils.Debug(...)`).
 */
abstract class EnRefExprMixin(node: ASTNode) : ASTWrapperPsiElement(node) {

    /**
     * A bare identifier is resolved by scope, not by the type index alone: it may be a local, a
     * parameter, a `foreach` variable, a field, a file-scope global, or a class name used as a
     * static qualifier. [EnIdentifierReference] tries them in that order, so a local shadows a
     * class of the same name.
     */
    override fun getReference(): PsiReference? {
        val identifier = node.findChildByType(EnfusionTypes.IDENT) ?: return null
        return EnIdentifierReference(this, identifier.textRange.shiftLeft(node.startOffset))
    }
}
