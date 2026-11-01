package io.github.lemyst.enfusion.resolve

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import io.github.lemyst.enfusion.index.EnfusionIndexes
import io.github.lemyst.enfusion.psi.EnNamedElement
import io.github.lemyst.enfusion.psi.EnfusionTypes
import io.github.lemyst.enfusion.psi.impl.EnClassDeclMixin
import io.github.lemyst.enfusion.psi.impl.EnMethodDeclMixin

/**
 * Works out which class an expression evaluates to, so that `player.GetIdentity()` can be resolved
 * rather than only `PlayerBase.Cast(...)`.
 *
 * This is deliberately shallow. It reads *declared* types — the language annotates every local,
 * parameter, field and return value — so nothing here needs flow analysis or full generic
 * unification. What it does not cover (`auto`) simply resolves to nothing rather than guessing.
 *
 * Type *arguments* are the one thing carried rather than discarded, because the containers are
 * everywhere: `array<ref X>` and `map<K, V>` declare their accessors as returning a bare type
 * parameter (`proto T Get(int n)`), so without substitution every `.Get(0).Member` in the codebase
 * dead-ends. See [EnType] and [substitutionsOf].
 */
object EnTypeInference {

    /** Depth cap for chained calls such as `a.b().c().d()` and for superclass walks. */
    private const val MAX_DEPTH = 12

    /**
     * A class together with the type arguments it was written with, so `array<ref Foo>` stays
     * distinguishable from `array<int>` long enough to answer what `Get(0)` returns.
     *
     * [arguments] are the `typeRef` PSI elements from the `typeArgList`, not their text: keeping the
     * PSI is what makes a nested `array<ref array<Foo>>` work without re-parsing a string.
     */
    data class EnType(val declaration: EnClassDeclMixin, val arguments: List<PsiElement> = emptyList())

    /**
     * The class declarations an expression's value belongs to.
     *
     * Several are possible, and that is not an error: with `modded class` a type name legitimately
     * has one declaration per layer, and a member may live in any of them.
     */
    fun classesOf(expr: PsiElement?, project: Project, depth: Int = 0): List<EnClassDeclMixin> =
        typesOf(expr, project, depth).map { it.declaration }.distinct()

    /**
     * As [classesOf], but carrying type arguments through the expression.
     *
     * This is what makes `m_PlayAreas.Get(0).X` resolve: the element type is not written anywhere at
     * the call site, only in the *declaration* of `m_PlayAreas`, and the method being called returns
     * a bare type parameter (`proto T Get(int n)`). Both halves have to be in hand at once to turn
     * `T` back into a real class.
     */
    fun typesOf(expr: PsiElement?, project: Project, depth: Int = 0): List<EnType> {
        if (expr == null || depth > MAX_DEPTH) return emptyList()
        return when (expr.node?.elementType) {
            EnfusionTypes.PAREN_EXPR ->
                typesOf(firstExprChild(expr), project, depth + 1)

            EnfusionTypes.THIS_EXPR ->
                listOfNotNull(PsiTreeUtil.getParentOfType(expr, EnClassDeclMixin::class.java))
                    .map { EnType(it) }

            EnfusionTypes.NEW_EXPR ->
                typesFromTypeRef(typeRefOf(expr), project, emptyMap(), depth)

            // `GetZone().GetArea()` — the call's type is its callee's return type. The callee's own
            // qualifier is what supplies the substitution when that return type is a parameter.
            EnfusionTypes.CALL_EXPR -> {
                val callee = firstExprChild(expr)
                typesOfDeclarations(resolveTargets(callee), receiverOf(callee), project, depth)
            }

            // `player` / `m_Queue` — the variable's declared type.
            EnfusionTypes.REF_EXPR, EnfusionTypes.MEMBER_EXPR ->
                typesOfDeclarations(resolveTargets(expr), receiverOf(expr), project, depth)

            else -> emptyList()
        }
    }

    /** The expression a member access reads through, i.e. `a` in `a.b` — null for a bare name. */
    private fun receiverOf(expr: PsiElement?): PsiElement? {
        if (expr?.node?.elementType !== EnfusionTypes.MEMBER_EXPR) return null
        return firstExprChild(expr)
    }

    /** The declared type of each resolved declaration, with the receiver's arguments substituted. */
    private fun typesOfDeclarations(
        declarations: List<PsiElement>,
        receiver: PsiElement?,
        project: Project,
        depth: Int,
    ): List<EnType> {
        if (declarations.isEmpty()) return emptyList()
        // Computed once for the whole declaration list, and only when something was resolved.
        val substitutions = substitutionsOf(receiver, project, depth)

        val result = LinkedHashSet<EnType>()
        for (declaration in declarations) {
            // A qualifier that resolves straight to a class is a static access: `Math.PI2`.
            if (declaration is EnClassDeclMixin) {
                result += EnType(declaration)
                continue
            }
            val typeRef = declaredTypeRef(declaration)
            if (isAuto(typeRef)) {
                result += inferredTypesOf(declaration, project, depth)
                continue
            }
            result += typesFromTypeRef(typeRef, project, substitutions, depth)
        }
        return result.toList()
    }

    /**
     * `auto` is not a type -- it is an instruction to look somewhere else for one.
     *
     * The lexer deliberately leaves it an ordinary identifier occupying the type slot, so it arrives
     * here as a `typeRef` reading `auto` that resolves to no class at all. Vanilla writes it in two
     * places: `auto p = new Param1<int>(...)`, where the initializer says the type, and
     * `foreach (auto x : collection)`, where the collection's element type does.
     */
    private fun isAuto(typeRef: PsiElement?): Boolean =
        typeRef != null && baseTypeName(typeRef.text) == "auto"

    private fun inferredTypesOf(declaration: PsiElement, project: Project, depth: Int): List<EnType> =
        when (declaration.node?.elementType) {
            EnfusionTypes.VAR_DECLARATOR ->
                typesOf(initializerOf(declaration), project, depth + 1)

            EnfusionTypes.FOREACH_VAR ->
                foreachVarTypes(declaration, project, depth)

            else -> emptyList()
        }

    /**
     * The expression after `=` in `auto x = ...`.
     *
     * `varDeclarator ::= IDENT arrayExtent* (EQ expr)?` and `children` yields composite nodes only,
     * so with an `=` present the initializer is simply the last of them.
     */
    private fun initializerOf(declarator: PsiElement): PsiElement? {
        if (declarator.node?.findChildByType(EnfusionTypes.EQ) == null) return null
        return declarator.children.lastOrNull()
    }

    /**
     * The element type behind `foreach (auto x : collection)`.
     *
     * Which type argument to take depends on how many variables the loop declares, because the
     * two-variable form means different things per container: over an `array<T>` the first variable
     * is the *index* and the second the element, while over a `map<K, V>` they are the key and the
     * value. Taking the last argument for the value position covers both, and the single-variable
     * form over a map yielding `V` matches how the engine iterates one.
     */
    private fun foreachVarTypes(variable: PsiElement, project: Project, depth: Int): List<EnType> {
        val loop = variable.parent?.takeIf { it.node?.elementType === EnfusionTypes.FOREACH_STMT }
            ?: return emptyList()
        // `foreachStmt ::= FOREACH LPAREN foreachVar (COMMA foreachVar)? COLON expr RPAREN statement`,
        // and `children` yields composite nodes only -- so the iterated expression sits immediately
        // after the variables, and the loop body immediately after that.
        val children = loop.children
        val variables = children.filter { it.node?.elementType === EnfusionTypes.FOREACH_VAR }
        val position = variables.indexOf(variable)
        if (position < 0) return emptyList()
        val collection = children.getOrNull(variables.size) ?: return emptyList()
        val arguments = typesOf(collection, project, depth + 1).firstOrNull()?.arguments.orEmpty()
        if (arguments.isEmpty()) return emptyList()

        // Index position over a single-argument container: `foreach (auto i, auto e : array<T>)`.
        if (variables.size == 2 && position == 0 && arguments.size < 2) {
            return classesNamed("int", project).map { EnType(it) }
        }
        val argument = if (variables.size == 2 && position == 0) arguments.first() else arguments.last()
        return typesFromTypeRef(argument, project, emptyMap(), depth)
    }

    /**
     * Type parameter name to the `typeRef` standing in for it, taken from the receiver's own type.
     *
     * `map<string, ref Foo>` binds `TKey` and `TValue` positionally, so this cannot special-case a
     * single `T`: the parameter names come from the class declaration and are zipped with the
     * arguments actually written.
     */
    private fun substitutionsOf(
        receiver: PsiElement?,
        project: Project,
        depth: Int,
    ): Map<String, PsiElement> {
        if (receiver == null) return emptyMap()
        val receiverType = typesOf(receiver, project, depth + 1).firstOrNull() ?: return emptyMap()
        val parameters = typeParametersOf(receiverType.declaration)
        if (parameters.isEmpty()) return emptyMap()
        return parameters.zip(receiverType.arguments).toMap()
    }

    /** Resolves a written type to real classes, replacing a type parameter with what it stands for. */
    private fun typesFromTypeRef(
        typeRef: PsiElement?,
        project: Project,
        substitutions: Map<String, PsiElement>,
        depth: Int,
    ): List<EnType> {
        if (typeRef == null || depth > MAX_DEPTH) return emptyList()
        val name = baseTypeName(typeRef.text) ?: return emptyList()

        // `T` in `proto T Get(int n)`. The substituted node was written in the receiver's context,
        // so its own arguments are concrete and it resolves with no substitutions of its own.
        substitutions[name]?.let { return typesFromTypeRef(it, project, emptyMap(), depth + 1) }

        val arguments = typeArgumentsOf(typeRef)
        return classesNamed(name, project).map { EnType(it, arguments) }
    }

    /** The `typeRef` elements inside a `typeArgList`, one per written argument. */
    private fun typeArgumentsOf(typeRef: PsiElement): List<PsiElement> {
        val list = typeRef.node?.findChildByType(EnfusionTypes.TYPE_ARG_LIST) ?: return emptyList()
        return list.psi.children
            .filter { it.node?.elementType === EnfusionTypes.TYPE_ARG }
            // `typeArg ::= Class? REF? typeRef` -- the modifiers are tokens, the type is the child.
            .mapNotNull { it.node?.findChildByType(EnfusionTypes.TYPE_REF)?.psi }
    }

    /**
     * The declared parameter names of a generic class: `array<Class T>` yields `[T]`.
     *
     * The **last** identifier, not the first. `typeParam ::= <<contextual "Class">> IDENT` and that
     * leading `Class` is itself lexed as an IDENT, so taking the first one names every parameter of
     * every generic class "Class" -- substitution then silently matches nothing.
     */
    private fun typeParametersOf(cls: EnClassDeclMixin): List<String> {
        val list = cls.node?.findChildByType(EnfusionTypes.TYPE_PARAM_LIST) ?: return emptyList()
        return list.psi.children
            .filter { it.node?.elementType === EnfusionTypes.TYPE_PARAM }
            .mapNotNull { parameter ->
                parameter.node?.getChildren(TokenSet.create(EnfusionTypes.IDENT))?.lastOrNull()?.text
            }
    }

    private fun resolveTargets(expr: PsiElement?): List<PsiElement> {
        val reference = expr?.reference as? PsiPolyVariantReference ?: return emptyList()
        return reference.multiResolve(false).mapNotNull { it.element }
    }

    /**
     * The type written on a declaration.
     *
     * A `varDeclarator` carries none of its own — one declaration can introduce several names — so
     * the type comes from the enclosing `varDecl`. A method contributes its return type, which is
     * what makes chained calls work.
     */
    private fun declaredTypeRef(declaration: PsiElement): PsiElement? {
        val node = declaration.node ?: return null
        return (
            node.findChildByType(EnfusionTypes.TYPE_REF)
                ?: node.treeParent?.findChildByType(EnfusionTypes.TYPE_REF)
            )?.psi
    }

    private fun typeRefOf(expr: PsiElement): PsiElement? =
        expr.node?.findChildByType(EnfusionTypes.TYPE_REF)?.psi

    /**
     * Strips modifiers and type arguments: `ref array<ref Foo>` becomes `array`.
     *
     * The element type of a container is deliberately not followed — that would need generic
     * substitution, and getting it wrong is worse than resolving nothing.
     */
    fun baseTypeName(text: String): String? {
        var name = text.trim().substringBefore('<').substringBefore('[').trim()
        for (modifier in listOf("ref ", "autoptr ", "notnull ", "out ", "inout ", "const ")) {
            while (name.startsWith(modifier)) name = name.removePrefix(modifier).trim()
        }
        name = name.substringAfterLast(' ').trim()
        return name.ifEmpty { null }
    }

    /** Every declaration of a class name, across all `modded class` layers. */
    fun classesNamed(name: String?, project: Project): List<EnClassDeclMixin> {
        if (name.isNullOrEmpty()) return emptyList()
        val psiManager = PsiManager.getInstance(project)
        val found = ArrayList<EnClassDeclMixin>()
        for (file in EnfusionIndexes.findFiles(EnfusionIndexes.TYPES, name, GlobalSearchScope.allScope(project))) {
            val psiFile: PsiFile = psiManager.findFile(file) ?: continue
            PsiTreeUtil.findChildrenOfType(psiFile, EnClassDeclMixin::class.java)
                .filterTo(found) { it.name == name }
        }
        return found
    }

    /**
     * Every declaration of a *type* name: class, enum or typedef.
     *
     * Broader than [classesNamed] on purpose. An enum is not an `EnClassDeclMixin`, so a name used
     * as a static qualifier -- `CallType.Client`, the shape every RPC handler opens with -- resolved
     * to nothing when only classes were considered, even though the same name one line up in
     * `void Handler(CallType type, ...)` resolved fine through the `typeRef` path.
     */
    fun typesNamed(name: String?, project: Project): List<PsiElement> {
        if (name.isNullOrEmpty()) return emptyList()
        val psiManager = PsiManager.getInstance(project)
        val found = ArrayList<PsiElement>()
        for (file in EnfusionIndexes.findFiles(EnfusionIndexes.TYPES, name, GlobalSearchScope.allScope(project))) {
            val psiFile: PsiFile = psiManager.findFile(file) ?: continue
            PsiTreeUtil.findChildrenOfType(psiFile, EnNamedElement::class.java)
                .filterTo(found) { it.name == name && EnfusionIndexes.isTypeDeclaration(it) }
        }
        return found
    }

    /**
     * Members of [owner] and its superclasses matching [name].
     *
     * Searches every `modded class` layer of [owner]'s name, not just [owner] itself: a modded layer
     * does not `extends` the layer it patches, so a field or method declared on the vanilla layer
     * (or a different modded layer) would otherwise be invisible from code written against another
     * layer of the very same class -- `modded class LoadingScreen { void Foo() { m_DayZGame... } }`
     * referencing a field the vanilla `class LoadingScreen` declares is exactly this shape.
     */
    fun findMembers(
        owner: EnClassDeclMixin,
        name: String,
        project: Project,
        out: MutableSet<PsiElement> = LinkedHashSet(),
        depth: Int = 0,
        visited: MutableSet<String> = HashSet(),
    ): MutableSet<PsiElement> {
        if (depth > MAX_DEPTH) return out
        val className = owner.name
        if (className != null && !visited.add(className)) return out
        val layers = if (className != null) classesNamed(className, project) else listOf(owner)

        // The real superclass is usually stated only on the base layer -- a modded layer typically
        // omits `extends` entirely -- so the first layer that states one wins.
        var superName: String? = null
        for (layer in layers) {
            layer.node.findChildByType(EnfusionTypes.CLASS_BODY)?.psi?.let { body ->
                for (member in PsiTreeUtil.findChildrenOfType(body, EnNamedElement::class.java)) {
                    if (member.name == name && EnfusionIndexes.isSymbolDeclaration(member)) out += member
                }
            }
            if (superName == null) superName = layer.getSuperName()?.let { baseTypeName(it) }
        }

        val parent = superName?.let { classesNamed(it, project).firstOrNull() } ?: return out
        return findMembers(parent, name, project, out, depth + 1, visited)
    }

    /**
     * Members that `super.name` reaches from inside [owner] — never [owner]'s own body, or
     * `super.EEKilled(killer)` inside `override void EEKilled` would land on itself.
     *
     * For an ordinary class that is the superclass chain only: its own `modded` layers come *after*
     * it, so `super` cannot reach them. For a `modded class` it is every other layer of the same
     * class plus the chain above. `super` there means the layer being patched, and load order is not
     * something the plugin can know, so every candidate is offered, as with any other multi-layer
     * name.
     */
    fun findSuperMembers(owner: EnClassDeclMixin, name: String, project: Project): List<PsiElement> {
        if (owner.isModded()) {
            val ownBody = owner.node.findChildByType(EnfusionTypes.CLASS_BODY)?.psi
            return findMembers(owner, name, project)
                .filter { ownBody == null || !PsiTreeUtil.isAncestor(ownBody, it, true) }
        }
        val superName = owner.getSuperName()?.let { baseTypeName(it) } ?: return emptyList()
        val parent = classesNamed(superName, project).firstOrNull() ?: return emptyList()
        return findMembers(parent, name, project).toList()
    }

    private fun firstExprChild(element: PsiElement?): PsiElement? =
        element?.children?.firstOrNull { it !is com.intellij.psi.PsiWhiteSpace }

    /** The return type of a method, used when a call appears as a qualifier. */
    @Suppress("unused")
    fun returnTypeOf(method: EnMethodDeclMixin, project: Project): List<EnClassDeclMixin> =
        classesNamed(method.getReturnTypeText()?.let { baseTypeName(it) }, project)
}
