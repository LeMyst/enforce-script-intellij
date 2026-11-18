package io.github.lemyst.enfusion.doc

import com.intellij.codeInsight.documentation.DocumentationManagerUtil
import com.intellij.lang.ASTNode
import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.tree.IElementType
import io.github.lemyst.enfusion.psi.EnNamedElement
import io.github.lemyst.enfusion.psi.EnfusionTypes
import io.github.lemyst.enfusion.psi.impl.EnClassDeclMixin
import io.github.lemyst.enfusion.psi.impl.EnMethodDeclMixin
import io.github.lemyst.enfusion.psi.impl.EnNamedElementBase
import io.github.lemyst.enfusion.resolve.EnConstantEvaluator
import io.github.lemyst.enfusion.resolve.EnIntConstants
import io.github.lemyst.enfusion.resolve.EnOverrideResolution
import io.github.lemyst.enfusion.resolve.EnScriptModules
import io.github.lemyst.enfusion.resolve.EnTypeInference
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Quick Documentation (`Ctrl+Q`, and hover when the IDE shows documentation on hover) for every
 * Enforce Script declaration, and the one-line summary Ctrl+hover shows.
 *
 * The classic provider rather than the documentation target API: the platform wraps it in a
 * target of its own, so this adds no experimental API usage. The texture hover, registered
 * `order="first"` as a target provider, still answers first for strings naming a texture.
 *
 * Most of the engine API is `proto native` with no body, so the comment above it is all there is
 * to read, and until now that meant Ctrl+Click into vanilla and back.
 */
class EnfusionDocumentationProvider : AbstractDocumentationProvider() {

    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        if (element == null) return null
        val targets = EnDocTargets.of(element)
        val primary = targets.firstOrNull() ?: return null
        return EnDocRenderer.render(primary, targets)
    }

    override fun getQuickNavigateInfo(element: PsiElement?, originalElement: PsiElement?): String? {
        val declaration = element as? EnNamedElement ?: return null
        return EnDocRenderer.summary(declaration)
    }

    override fun getDocumentationElementForLink(psiManager: PsiManager, link: String, context: PsiElement?): PsiElement? =
        EnDocLinks.resolve(psiManager, link)
}

/** What a documentation request is about. */
internal object EnDocTargets {

    /**
     * The declarations [element] stands for, the one to document first.
     *
     * When a reference resolves to one declaration the platform passes that declaration; when it
     * resolves to several, as a name declared by several `modded class` layers does, it passes the
     * expression holding the reference, and the layers are resolved here.
     */
    fun of(element: PsiElement): List<EnNamedElement> {
        if (element is EnNamedElement) return listOf(element)
        if (DumbService.isDumb(element.project)) return emptyList()
        val resolved = element.references.asSequence()
            .filterIsInstance<PsiPolyVariantReference>()
            .flatMap { reference -> reference.multiResolve(false).asSequence().mapNotNull { it.element } }
            .filterIsInstance<EnNamedElement>()
            .distinct()
            .toList()
        // The original declaration first: it states the superclass and usually the comment, and
        // which layer a call really reaches is load order, which nothing in the source says.
        return resolved.sortedBy { if (isLayer(it)) 1 else 0 }
    }

    /** A declaration in a `modded class`, or an `override` of one: not the original. */
    fun isLayer(element: PsiElement): Boolean {
        if (element is EnClassDeclMixin) return element.isModded()
        if (element is EnMethodDeclMixin && element.isOverride()) return true
        return PsiTreeUtil.getParentOfType(element, EnClassDeclMixin::class.java)?.isModded() == true
    }
}

/**
 * Links inside the popup. A declaration is named by its file and offset, since a name alone does
 * not tell the layers of one class apart.
 */
internal object EnDocLinks {

    private const val DECLARATION = "enfusion-decl:"

    fun declaration(target: PsiElement): String? {
        val file = target.containingFile?.virtualFile ?: return null
        val url = URLEncoder.encode(file.url, StandardCharsets.UTF_8)
        return "$DECLARATION${target.textOffset}:$url"
    }

    fun resolve(psiManager: PsiManager, link: String): PsiElement? {
        if (!link.startsWith(DECLARATION)) return null
        val rest = link.removePrefix(DECLARATION)
        val offset = rest.substringBefore(':').toIntOrNull() ?: return null
        val url = URLDecoder.decode(rest.substringAfter(':'), StandardCharsets.UTF_8)
        val file = VirtualFileManager.getInstance().findFileByUrl(url) ?: return null
        val psiFile = psiManager.findFile(file) ?: return null
        var element = psiFile.findElementAt(offset)
        while (element != null && element !is com.intellij.psi.PsiFile) {
            if (element is EnNamedElement && element.textOffset == offset) return element
            element = element.parent
        }
        return null
    }
}

/** The popup's HTML, apart from the provider so tests can read it. */
internal object EnDocRenderer {

    /** Bound on the `extends` chain shown; vanilla's deepest is about a dozen classes. */
    private const val MAX_CHAIN = 16

    /** One line for Ctrl+hover: the signature, then where it is declared. */
    fun summary(declaration: EnNamedElement): String {
        val html = StringBuilder()
        owner(declaration)?.name?.let { html.append(escape(it)).append("<br>") }
        html.append("<code>").append(escape(EnDocSignatures.of(declaration))).append("</code>")
        (declaration as? EnNamedElementBase)?.fileLocation()?.let { html.append("<br>").append(escape(it)) }
        return html.toString()
    }

    fun render(primary: EnNamedElement, resolved: List<EnNamedElement>): String {
        val project = primary.project
        val html = StringBuilder()

        html.append(DocumentationMarkup.DEFINITION_START)
        EnDocSignatures.attributes(primary)?.let { html.append(escape(it)).append('\n') }
        html.append(escape(EnDocSignatures.of(primary)))
        html.append(DocumentationMarkup.DEFINITION_END)

        val (comment, source) = commentFor(primary, resolved)
        val sections = ArrayList<Pair<String, String>>()

        val parameterOf = primary.takeIf { it.node.elementType === EnfusionTypes.PARAM }
            ?.let { PsiTreeUtil.getParentOfType(it, EnMethodDeclMixin::class.java) }
        val description = if (parameterOf != null) {
            commentFor(parameterOf, listOf(parameterOf)).first.paramText(primary.name.orEmpty()).orEmpty()
        } else {
            comment.description
        }
        if (description.isNotEmpty() || (source != null && source !== primary)) {
            html.append(DocumentationMarkup.CONTENT_START)
            html.append(description)
            if (source != null && source !== primary) {
                if (description.isNotEmpty()) html.append("<p>")
                html.append("<i>From ").append(link(source, describe(source))).append("</i>")
            }
            html.append(DocumentationMarkup.CONTENT_END)
        }

        if (comment.params.isNotEmpty()) {
            sections += "Params:" to comment.params.joinToString("<br>") { (name, text) ->
                "<code>${escape(name)}</code>" + (if (text.isNotEmpty()) " &ndash; $text" else "")
            }
        }
        comment.returns?.let { sections += "Returns:" to it }
        for ((label, text) in comment.notes) sections += "$label:" to text

        valueOf(primary)?.let { sections += "Value:" to it }
        typeOf(primary)?.let { sections += "Type:" to it }

        when (primary) {
            is EnClassDeclMixin -> classSections(primary, sections)
            is EnMethodDeclMixin -> methodSections(primary, sections)
        }
        parameterOf?.let { sections += "Parameter of:" to link(it, describe(it)) }

        owner(primary)?.takeIf { primary !is EnClassDeclMixin && parameterOf == null && !isLocal(primary) }
            ?.let { sections += "Class:" to link(it, describe(it)) }
        EnScriptModules.moduleOf(primary)?.let { sections += "Module:" to escape(it.label) }
        (primary as? EnNamedElementBase)?.fileLocation()?.let { sections += "File:" to escape(it) }

        html.append(DocumentationMarkup.SECTIONS_START)
        for ((label, value) in sections) {
            html.append(DocumentationMarkup.SECTION_HEADER_START).append(escape(label))
                .append(DocumentationMarkup.SECTION_SEPARATOR).append(value).append(DocumentationMarkup.SECTION_END)
        }
        html.append(DocumentationMarkup.SECTIONS_END)
        if (project.isDisposed) return ""
        return html.toString()
    }

    /**
     * The comment to show and the declaration it was written on. A `modded class` layer or an
     * `override` rarely repeats the original's comment, so when [primary] has none the original
     * declaration's is used, then any other layer's, then what it overrides.
     */
    private fun commentFor(primary: EnNamedElement, resolved: List<EnNamedElement>): Pair<EnDocComment, EnNamedElement?> {
        val own = EnDocComment.parse(EnDocCommentFinder.commentsOf(primary))
        if (!own.isEmpty) return own to primary
        val fallbacks = LinkedHashSet<EnNamedElement>()
        fallbacks += resolved
        when (primary) {
            is EnClassDeclMixin -> fallbacks += EnTypeInference.classesNamed(primary.name, primary.project)
            is EnMethodDeclMixin -> {
                fallbacks += sameClassLayers(primary)
                fallbacks += EnOverrideResolution.superDeclarationsOf(primary)
            }
        }
        fallbacks -= primary
        for (candidate in fallbacks.sortedBy { if (EnDocTargets.isLayer(it)) 1 else 0 }) {
            val comment = EnDocComment.parse(EnDocCommentFinder.commentsOf(candidate))
            if (!comment.isEmpty) return comment to candidate
        }
        return EnDocComment.EMPTY to null
    }

    private fun classSections(cls: EnClassDeclMixin, sections: MutableList<Pair<String, String>>) {
        val project = cls.project
        val layers = EnTypeInference.classesNamed(cls.name, project)
        val chain = ArrayList<EnClassDeclMixin>()
        val seen = HashSet<String>()
        cls.name?.let { seen += it }
        var superName = (listOf(cls) + layers).firstNotNullOfOrNull { layer ->
            layer.getSuperName()?.let(EnTypeInference::baseTypeName)
        }
        while (superName != null && chain.size < MAX_CHAIN && seen.add(superName)) {
            val parentLayers = EnTypeInference.classesNamed(superName, project)
            val parent = parentLayers.firstOrNull { !it.isModded() } ?: parentLayers.firstOrNull() ?: break
            chain += parent
            superName = parentLayers.firstNotNullOfOrNull { layer -> layer.getSuperName()?.let(EnTypeInference::baseTypeName) }
        }
        if (chain.isNotEmpty()) {
            sections += "Extends:" to chain.joinToString(" &rarr; ") { link(it, it.name.orEmpty()) }
        }
        val others = layers.filter { it !== cls }
        val original = others.filter { !it.isModded() }
        if (cls.isModded() && original.isNotEmpty()) {
            sections += "Patches:" to original.joinToString("<br>") { link(it, describe(it)) }
        }
        val modded = others.filter { it.isModded() }
        if (modded.isNotEmpty()) {
            sections += (if (cls.isModded()) "Other layers:" else "Modded by:") to
                modded.joinToString("<br>") { link(it, describe(it)) }
        }
    }

    private fun methodSections(method: EnMethodDeclMixin, sections: MutableList<Pair<String, String>>) {
        val layers = sameClassLayers(method)
        if (layers.isNotEmpty()) {
            sections += "Other layers:" to layers.joinToString("<br>") { link(it, describe(it)) }
        }
        val ownerName = owner(method)?.name
        val overridden = EnOverrideResolution.superDeclarationsOf(method).filter { owner(it)?.name != ownerName }
        if (overridden.isNotEmpty()) {
            sections += "Overrides:" to overridden.joinToString("<br>") { link(it, describe(it)) }
        }
    }

    /** The same method declared in the other `modded class` layers of its class. */
    private fun sameClassLayers(method: EnMethodDeclMixin): List<EnMethodDeclMixin> {
        val name = method.name ?: return emptyList()
        val owner = owner(method) ?: return emptyList()
        return EnTypeInference.classesNamed(owner.name, method.project)
            .flatMap { EnOverrideResolution.directMethodsNamed(it, name) }
            .filter { it !== method }
            .sortedBy { if (EnDocTargets.isLayer(it)) 1 else 0 }
    }

    /** A constant's value: the folded integer, its hex form, and the flags that make it up. */
    private fun valueOf(declaration: EnNamedElement): String? {
        val type = declaration.node.elementType
        val isConstant = type === EnfusionTypes.ENUM_CONSTANT ||
            (type === EnfusionTypes.VAR_DECLARATOR && declaration.parent?.node?.findChildByType(EnfusionTypes.CONST) != null)
        if (!isConstant) return null
        EnIntConstants.valueOf(declaration)?.let { value -> return escape(EnDocValues.describeInt(value, flagsOf(declaration, value))) }
        val initializer = initializerOf(declaration) ?: return null
        if (initializer.node?.elementType === EnfusionTypes.LITERAL_EXPR &&
            initializer.node.findChildByType(EnfusionTypes.STRING) == null
        ) {
            return null
        }
        EnConstantEvaluator.evaluate(initializer)?.let { return "<code>${escape("\"" + it + "\"")}</code>" }
        return null
    }

    /**
     * The single-bit constants declared beside [declaration] that make up [value], when they
     * cover it exactly: the other constants of its enum, or the `const int`s of its file that share
     * its prefix (`ECE_`, `RF_`).
     */
    private fun flagsOf(declaration: EnNamedElement, value: Long): List<String> {
        if (value <= 0 || java.lang.Long.bitCount(value) < 2) return emptyList()
        val siblings: List<PsiElement> = if (declaration.node.elementType === EnfusionTypes.ENUM_CONSTANT) {
            declaration.parent?.children?.filter { it.node?.elementType === EnfusionTypes.ENUM_CONSTANT }.orEmpty()
        } else {
            val name = declaration.name ?: return emptyList()
            val prefix = name.substringBefore('_', "").takeIf { it.isNotEmpty() } ?: return emptyList()
            val file = declaration.containingFile ?: return emptyList()
            PsiTreeUtil.findChildrenOfType(file, EnNamedElement::class.java).filter {
                it.node.elementType === EnfusionTypes.VAR_DECLARATOR && it.name?.startsWith("${prefix}_") == true &&
                    it.parent?.node?.findChildByType(EnfusionTypes.CONST) != null
            }
        }
        val bits = LinkedHashMap<Long, String>()
        for (sibling in siblings) {
            if (sibling === declaration) continue
            val name = (sibling as? EnNamedElement)?.name ?: continue
            val bit = EnIntConstants.valueOf(sibling) ?: continue
            if (bit > 0 && java.lang.Long.bitCount(bit) == 1 && bit and value == bit) bits.putIfAbsent(bit, name)
        }
        if (bits.keys.fold(0L) { acc, bit -> acc or bit } != value) return emptyList()
        return bits.toSortedMap().values.toList()
    }

    /** The type a variable holds, linked; for `auto`, the type inferred. */
    private fun typeOf(declaration: EnNamedElement): String? {
        val type = declaration.node.elementType
        if (type !== EnfusionTypes.VAR_DECLARATOR && type !== EnfusionTypes.PARAM && type !== EnfusionTypes.FOREACH_VAR) {
            return null
        }
        val types = EnTypeInference.typesOfDeclaration(declaration, declaration.project)
            .distinctBy { EnTypeInference.presentableText(it) }
        if (types.isEmpty()) {
            return if (EnTypeInference.isAutoDeclaration(declaration)) "<i>not inferred</i>" else null
        }
        val text = types.joinToString(", ") { link(it.declaration, EnTypeInference.presentableText(it)) }
        return if (EnTypeInference.isAutoDeclaration(declaration)) "$text <i>(inferred)</i>" else text
    }

    private fun initializerOf(declaration: PsiElement): PsiElement? {
        if (declaration.node.findChildByType(EnfusionTypes.EQ) == null) return null
        return declaration.children.lastOrNull { it !is PsiWhiteSpace && it !is PsiComment }
    }

    private fun owner(element: PsiElement): EnClassDeclMixin? =
        if (element is EnClassDeclMixin) null else PsiTreeUtil.getParentOfType(element, EnClassDeclMixin::class.java)

    private fun isLocal(element: PsiElement): Boolean =
        PsiTreeUtil.getParentOfType(element, EnMethodDeclMixin::class.java) != null

    /** `modded PlayerBase · MyMod · 4_World/Entities/PlayerBase.c`, to tell layers apart. */
    private fun describe(element: EnNamedElement): String {
        val name = when (element) {
            is EnClassDeclMixin -> (if (element.isModded()) "modded " else "") + element.name
            else -> listOfNotNull(owner(element)?.let { (if (it.isModded()) "modded " else "") + it.name }, element.name)
                .joinToString(".")
        }
        val location = (element as? EnNamedElementBase)?.fileLocation()
        return if (location != null) "$name  ($location)" else name
    }

    private fun link(target: EnNamedElement, label: String): String {
        val ref = EnDocLinks.declaration(target) ?: return escape(label)
        val html = StringBuilder()
        DocumentationManagerUtil.createHyperlink(html, ref, escape(label), true)
        return html.toString()
    }

    private fun escape(text: String): String = StringUtil.escapeXmlEntities(text)
}

/** Value text, apart so that it is tested on numbers alone. */
internal object EnDocValues {

    /** `1060 (0x424) = ECE_TRACE | ECE_UPDATEPATHGRAPH | ECE_CREATEPHYSICS`. */
    fun describeInt(value: Long, flags: List<String>): String {
        // The language's int is 32 bits: `0xFFFFFFFF` is -1, and `~0` too.
        val int = value.toInt()
        val text = StringBuilder(int.toString())
        if (int < 0 || int > 9) text.append(" (0x").append(Integer.toHexString(int).uppercase()).append(')')
        if (flags.isNotEmpty()) text.append(" = ").append(flags.joinToString(" | "))
        return text.toString()
    }
}

/** The signature as declared, whitespace collapsed and comments left out. */
internal object EnDocSignatures {

    fun of(element: PsiElement): String {
        val node = element.node
        return when (node.elementType) {
            EnfusionTypes.METHOD_DECL -> text(node.children(), stopAfter = EnfusionTypes.PARAM_LIST)
            EnfusionTypes.CLASS_DECL -> text(node.children(), stopBefore = EnfusionTypes.CLASS_BODY)
            EnfusionTypes.ENUM_DECL -> text(node.children(), stopBefore = EnfusionTypes.LBRACE)
            EnfusionTypes.TYPEDEF_DECL -> text(node.children(), stopBefore = EnfusionTypes.SEMICOLON)
            EnfusionTypes.VAR_DECLARATOR -> declaratorText(element)
            EnfusionTypes.ENUM_CONSTANT -> {
                val enumName = (element.parent as? EnNamedElement)?.name
                val own = text(node.children())
                if (enumName != null) "$enumName.$own" else own
            }
            else -> text(node.children())
        }
    }

    /** `[Obsolete("Use X")]`, on its own line above the signature. */
    fun attributes(element: PsiElement): String? {
        val owner = if (element.node.elementType === EnfusionTypes.VAR_DECLARATOR) element.parent else element
        val list = owner?.node?.findChildByType(EnfusionTypes.ATTRIBUTE_LIST) ?: return null
        return text(sequenceOf(list))
    }

    /**
     * The type and modifiers come from the enclosing declaration, which may introduce several
     * names: `int a, b`. A constant keeps its initializer, which is its value.
     */
    private fun declaratorText(declarator: PsiElement): String {
        val declaration = declarator.parent?.node ?: return declarator.text
        val prefix = text(
            declaration.children().takeWhile { it.elementType !== EnfusionTypes.VAR_DECLARATOR },
        )
        val isConst = declaration.findChildByType(EnfusionTypes.CONST) != null
        val own = text(declarator.node.children(), stopBefore = if (isConst) null else EnfusionTypes.EQ)
        return "$prefix $own".trim()
    }

    private fun ASTNode.children(): Sequence<ASTNode> = generateSequence(firstChildNode) { it.treeNext }

    private val SKIPPED = setOf(EnfusionTypes.ATTRIBUTE_LIST)

    private fun text(nodes: Sequence<ASTNode>, stopBefore: IElementType? = null, stopAfter: IElementType? = null): String {
        val out = StringBuilder()
        for (node in nodes) {
            if (node.elementType === stopBefore) break
            if (node.elementType !in SKIPPED) appendLeaves(node, out)
            if (node.elementType === stopAfter) break
        }
        return TIGHT.replace(SPACES.replace(out.toString().trim(), " ")) { it.value.trim() }
    }

    private val SPACES = Regex("""\s+""")

    /** `( int a )` reads `(int a)`, `array< int >` reads `array<int>`. */
    private val TIGHT = Regex("""[(\[<] | [)\]>,]""")

    private fun appendLeaves(node: ASTNode, out: StringBuilder) {
        val psi = node.psi
        if (psi is PsiComment) return
        if (psi is PsiWhiteSpace) {
            out.append(' ')
            return
        }
        var child = node.firstChildNode
        if (child == null) {
            out.append(node.text)
            return
        }
        while (child != null) {
            appendLeaves(child, out)
            child = child.treeNext
        }
    }
}
