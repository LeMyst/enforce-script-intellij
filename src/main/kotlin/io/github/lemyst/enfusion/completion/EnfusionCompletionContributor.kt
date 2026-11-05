package io.github.lemyst.enfusion.completion

import com.intellij.codeInsight.completion.AddSpaceInsertHandler
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionUtil
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.completion.util.ParenthesesInsertHandler
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.indexing.FileBasedIndex
import io.github.lemyst.enfusion.index.EnfusionGlobalIndex
import io.github.lemyst.enfusion.index.EnfusionIndexes
import io.github.lemyst.enfusion.psi.EnNamedElement
import io.github.lemyst.enfusion.psi.EnfusionTokenSets
import io.github.lemyst.enfusion.psi.EnfusionTypes
import io.github.lemyst.enfusion.psi.impl.EnClassDeclMixin
import io.github.lemyst.enfusion.psi.impl.EnMethodDeclMixin
import io.github.lemyst.enfusion.resolve.EnTypeInference

/**
 * Code completion for identifiers: members after a dot, names in scope for a bare identifier, and
 * type names where a type is written.
 *
 * It answers from the same machinery Go to Declaration uses, so the two cannot disagree: a member
 * is offered after `player.` exactly when Ctrl+Click on it would resolve. In particular the walk
 * covers every `modded class` layer of the receiver's class, because a layer does not `extends`
 * the one it patches and a member declared on any of them is reachable from all.
 *
 * A contributor rather than `getVariants()` on the references, because the bare-name and type
 * cases draw on project-wide indexes: here the prefix is known before anything is built, so a
 * one-letter prefix does not turn every type and global in the corpus into a lookup element.
 */
class EnfusionCompletionContributor : CompletionContributor() {

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val position = parameters.position
        if (position.node?.elementType !== EnfusionTypes.IDENT) return
        val parent = position.parent ?: return

        // Keywords first, and recorded as seen: vanilla declares the primitives as real classes
        // (`class int`), so `int` would otherwise be offered twice, once from the type index.
        val keywords = EnfusionKeywords.at(position)
        val seen = HashSet<String>()
        for (keyword in keywords) {
            if (!seen.add(keyword.text)) continue
            result.addElement(PrioritizedLookupElement.withPriority(keywordLookup(keyword), KEYWORD_PRIORITY))
        }

        when (parent.node?.elementType) {
            EnfusionTypes.MEMBER_EXPR -> if (isAfterDot(parent, position)) completeMembers(parent, result)
            EnfusionTypes.REF_EXPR -> completeScope(position, result, seen)
            EnfusionTypes.TYPE_REF -> completeTypes(position.project, result, seen)
            else -> if (isUnparsedMemberStart(position)) completeTypes(position.project, result, seen)
        }
    }

    private fun keywordLookup(keyword: EnfusionKeywords.Keyword): LookupElement {
        val element = LookupElementBuilder.create(keyword.text).bold()
        return if (keyword.addsSpace) element.withInsertHandler(AddSpaceInsertHandler.INSTANCE) else element
    }

    private fun isAfterDot(memberExpr: PsiElement, position: PsiElement): Boolean {
        val dot = memberExpr.node.findChildByType(EnfusionTypes.DOT) ?: return false
        return position.textRange.startOffset >= dot.textRange.endOffset
    }

    // ---------------------------------------------------------------------------------------------
    // `qualifier.<caret>`
    // ---------------------------------------------------------------------------------------------

    private fun completeMembers(memberExpr: PsiElement, result: CompletionResultSet) {
        val project = memberExpr.project
        val qualifier = memberExpr.children.firstOrNull { it !is PsiWhiteSpace } ?: return

        if (qualifier.node?.elementType === EnfusionTypes.SUPER_EXPR) {
            // The original, not the completion copy: the layers come back from the index as PSI of
            // the real file, and the copy's own body would never be an ancestor of any of them.
            val owner = PsiTreeUtil.getParentOfType(memberExpr, EnClassDeclMixin::class.java)
                ?.let { CompletionUtil.getOriginalOrSelf(it) } ?: return
            addMembers(EnTypeInference.allSuperMembers(owner, project), result, staticOnly = false)
            return
        }

        // A name that resolves to a type is a static access: `Math.` offers `Clamp`, not every
        // instance method `Math` happens to inherit. An enum offers its constants and nothing else.
        val named = (qualifier.reference as? PsiPolyVariantReference)
            ?.multiResolve(false)?.mapNotNull { it.element }.orEmpty()
        val enums = named.filter { it.node?.elementType === EnfusionTypes.ENUM_DECL }
        if (enums.isNotEmpty()) {
            for (enum in enums) {
                PsiTreeUtil.findChildrenOfType(enum, EnNamedElement::class.java)
                    .filter { it.node?.elementType === EnfusionTypes.ENUM_CONSTANT }
                    .forEach { constant -> constant.name?.let { result.addElement(lookupFor(constant, it)) } }
            }
            return
        }
        val staticOnly = named.isNotEmpty() && named.all { EnfusionIndexes.isTypeDeclaration(it) }

        // One visited set for all owners: classesOf returns every layer of a `modded class`, and
        // each walk already covers all of them, so a fresh set would repeat it once per layer.
        val members = ArrayList<EnNamedElement>()
        val visited = HashSet<String>()
        for (owner in EnTypeInference.classesOf(qualifier, project)) {
            EnTypeInference.allMembers(owner, project, members, visited = visited)
        }
        addMembers(members, result, staticOnly)
    }

    /**
     * One entry per signature. Every `modded class` layer that overrides a method redeclares it, so
     * without this `OnScheduledTick` would be offered once per layer; overloads, whose parameter
     * lists differ, each stay.
     */
    private fun addMembers(
        members: List<EnNamedElement>,
        result: CompletionResultSet,
        staticOnly: Boolean,
        seenNames: MutableSet<String>? = null,
        priority: Double = 0.0,
    ) {
        val seen = HashSet<String>()
        for (member in members) {
            ProgressManager.checkCanceled()
            val name = member.name ?: continue
            // Nothing can name a constructor or destructor in an expression: construction is
            // always `new X(...)`.
            if (member is EnMethodDeclMixin && (member.isConstructor() || member.isDestructor())) continue
            if (staticOnly && !isStaticAccessible(member)) continue
            val key = if (member is EnMethodDeclMixin) name + member.getParameterListText() else name
            if (!seen.add(key)) continue
            seenNames?.add(name)
            result.addElement(PrioritizedLookupElement.withPriority(lookupFor(member, name), priority))
        }
    }

    /** Reachable through the class name: a static member, or a constant (`const` is per class). */
    private fun isStaticAccessible(member: EnNamedElement): Boolean {
        if (member is EnMethodDeclMixin) return member.isStatic()
        val declaration = member.node.treeParent ?: return false
        return declaration.findChildByType(STATIC_OR_CONST) != null
    }

    // ---------------------------------------------------------------------------------------------
    // A bare `<caret>`
    // ---------------------------------------------------------------------------------------------

    /**
     * Names in scope, innermost first, so that a local hides a field of the same name and a field
     * hides a global — the order [io.github.lemyst.enfusion.resolve.EnIdentifierReference] resolves
     * in. Types come last: a bare name at the start of a statement may equally begin a declaration
     * (`PlayerBase p`) or a static call (`Math.Clamp`).
     */
    private fun completeScope(position: PsiElement, result: CompletionResultSet, seen: MutableSet<String>) {
        val project = position.project

        val method = PsiTreeUtil.getParentOfType(position, EnMethodDeclMixin::class.java)
        if (method != null) {
            for (local in localsInScope(method, position)) {
                val name = local.name ?: continue
                if (!seen.add(name)) continue
                result.addElement(PrioritizedLookupElement.withPriority(lookupFor(local, name), LOCAL_PRIORITY))
            }
        }

        PsiTreeUtil.getParentOfType(position, EnClassDeclMixin::class.java)?.let { owner ->
            val members = EnTypeInference.allMembers(owner, project).filter { it.name !in seen }
            addMembers(members, result, staticOnly = false, seenNames = seen, priority = MEMBER_PRIORITY)
        }

        completeGlobals(project, result, seen)
        completeTypes(project, result, seen)
    }

    /**
     * Parameters, plus locals and `foreach` variables declared before the caret in a block that
     * encloses it. Resolution ignores both conditions, which is harmless there; offering a name
     * from a sibling block, or one declared further down, would not be.
     */
    private fun localsInScope(method: EnMethodDeclMixin, position: PsiElement): List<EnNamedElement> {
        val caret = position.textRange.startOffset
        val found = ArrayList<EnNamedElement>()
        for (candidate in PsiTreeUtil.findChildrenOfType(method, EnNamedElement::class.java)) {
            when (candidate.node?.elementType) {
                EnfusionTypes.PARAM -> found += candidate
                EnfusionTypes.FOREACH_VAR, EnfusionTypes.VAR_DECLARATOR -> {
                    if (EnfusionIndexes.isSymbolDeclaration(candidate)) continue
                    // `int count = <caret>` must not offer `count` itself.
                    if (candidate.textRange.endOffset > caret) continue
                    val scope = PsiTreeUtil.findFirstParent(candidate, true) {
                        it.node?.elementType in LOCAL_SCOPES
                    } ?: continue
                    if (PsiTreeUtil.isAncestor(scope, position, false)) found += candidate
                }
            }
        }
        // Nearest declaration first, so that it is the one kept when an inner block shadows a name.
        return found.sortedByDescending { it.textRange.startOffset }
    }

    /**
     * Free functions and globals, straight from [EnfusionGlobalIndex]: the signature is stored with
     * the name, so no declaring file is parsed to show it.
     */
    private fun completeGlobals(project: Project, result: CompletionResultSet, seen: MutableSet<String>) {
        val index = FileBasedIndex.getInstance()
        val scope = GlobalSearchScope.allScope(project)
        for (name in matchingKeys(index, EnfusionGlobalIndex.NAME, project, result)) {
            if (name in seen) continue
            val signature = index.getValues(EnfusionGlobalIndex.NAME, name, scope).firstOrNull() ?: continue
            seen += name
            var element = LookupElementBuilder.create(name)
                .withIcon(if (signature.isFunction) AllIcons.Nodes.Function else AllIcons.Nodes.Variable)
                .withTypeText(signature.type)
            if (signature.isFunction) {
                element = element.withTailText(signature.parameters, true)
                    .withInsertHandler(ParenthesesInsertHandler.getInstance(signature.hasParameters))
            }
            result.addElement(PrioritizedLookupElement.withPriority(element, GLOBAL_PRIORITY))
        }
    }

    /** Classes, enums and typedefs — the primitives too, which vanilla declares as classes. */
    private fun completeTypes(project: Project, result: CompletionResultSet, seen: Set<String>) {
        val index = FileBasedIndex.getInstance()
        val scope = GlobalSearchScope.allScope(project)
        for (name in matchingKeys(index, EnfusionIndexes.TYPES, project, result)) {
            if (name in seen) continue
            // A key can outlive the last declaration of its name; it is only offered while one exists.
            if (index.getContainingFiles(EnfusionIndexes.TYPES, name, scope).isEmpty()) continue
            result.addElement(LookupElementBuilder.create(name).withIcon(AllIcons.Nodes.Class))
        }
    }

    /** Index keys the typed prefix matches, filtered before any of them becomes a lookup element. */
    private fun matchingKeys(
        index: FileBasedIndex,
        id: com.intellij.util.indexing.ID<String, *>,
        project: Project,
        result: CompletionResultSet,
    ): List<String> {
        val matcher = result.prefixMatcher
        val keys = ArrayList<String>()
        index.processAllKeys(id, { key ->
            ProgressManager.checkCanceled()
            if (matcher.prefixMatches(key)) keys += key
            true
        }, GlobalSearchScope.allScope(project), null)
        return keys
    }

    // ---------------------------------------------------------------------------------------------

    private fun lookupFor(declaration: EnNamedElement, name: String): LookupElement {
        var element = LookupElementBuilder.create(declaration, name)
            .withIcon((declaration as? NavigationItem)?.presentation?.getIcon(false))
        if (declaration is EnMethodDeclMixin) {
            val hasParameters = declaration.node.findChildByType(EnfusionTypes.PARAM_LIST)
                ?.findChildByType(EnfusionTypes.PARAM) != null
            element = element
                .withTailText(declaration.getParameterListText(), true)
                .withTypeText(declaration.getReturnTypeText())
                .withInsertHandler(ParenthesesInsertHandler.getInstance(hasParameters))
        } else {
            element = element.withTypeText(declaredTypeText(declaration))
        }
        return element
    }

    /** A declarator carries no type of its own; it is on the enclosing declaration. */
    private fun declaredTypeText(declaration: EnNamedElement): String? {
        val node = declaration.node
        return (node.findChildByType(EnfusionTypes.TYPE_REF) ?: node.treeParent?.findChildByType(EnfusionTypes.TYPE_REF))
            ?.text?.trim()
    }

    internal companion object {
        /**
         * A half-written field or method in a class body or at file scope: `Play<caret>` followed by
         * the next member.
         *
         * That is the most common place a type gets typed, and it never reaches a `typeRef`: a member
         * declaration needs a type *and* a name before it parses, so the lone identifier lands in an
         * error element, or directly in the class body. Whatever an identifier there can start, it is
         * a type. Inside a method body the statement parser already yields a `refExpr` or `typeRef`,
         * so this does not apply there.
         */
        internal fun isUnparsedMemberStart(position: PsiElement): Boolean {
            val parent = position.parent
            val loose = parent is PsiErrorElement || parent is PsiFile ||
                parent.node?.elementType === EnfusionTypes.CLASS_BODY
            if (!loose) return false
            if (PsiTreeUtil.getParentOfType(position, EnMethodDeclMixin::class.java) != null) return false
            // After a dot it is a member name, however the expression around it failed to parse; after
            // a type (`PlayerBase m_<caret>`, `array<int> m_<caret>`) it is the name being declared.
            val previous = PsiTreeUtil.prevVisibleLeaf(position)?.node?.elementType ?: return true
            return previous !== EnfusionTypes.DOT && previous !== EnfusionTypes.IDENT &&
                previous !== EnfusionTypes.GT && previous !== EnfusionTypes.RBRACKET &&
                !EnfusionTokenSets.PRIMITIVE_TYPES.contains(previous)
        }

        private val STATIC_OR_CONST = TokenSet.create(EnfusionTypes.STATIC, EnfusionTypes.CONST)

        /** What a local's visibility ends with: its block, or the loop that declares it. */
        private val LOCAL_SCOPES = setOf(
            EnfusionTypes.BLOCK_STMT,
            EnfusionTypes.FOR_STMT,
            EnfusionTypes.FOREACH_STMT,
            EnfusionTypes.SWITCH_STMT,
            EnfusionTypes.METHOD_DECL,
        )

        private const val LOCAL_PRIORITY = 30.0

        /** Just under locals: `re` should offer `return` ahead of a field named `m_Result`. */
        private const val KEYWORD_PRIORITY = 25.0
        private const val MEMBER_PRIORITY = 20.0
        private const val GLOBAL_PRIORITY = 10.0
    }
}
