package io.github.lemyst.enfusion.refs

import com.intellij.codeInsight.completion.CompletionUtilCore
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementResolveResult
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPolyVariantReferenceBase
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.ResolveResult
import com.intellij.psi.impl.source.resolve.ResolveCache
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import com.intellij.util.indexing.FileBasedIndex
import io.github.lemyst.enfusion.cfg.psi.impl.EnCfgClassMixin
import io.github.lemyst.enfusion.cfg.psi.impl.EnCfgPropertyMixin
import io.github.lemyst.enfusion.index.EnfusionConfigIndexes
import io.github.lemyst.enfusion.index.EnfusionConfigSectionIndex
import io.github.lemyst.enfusion.lang.EnfusionIcons
import io.github.lemyst.enfusion.psi.EnfusionTypes
import io.github.lemyst.enfusion.resolve.EnConstantEvaluator

/**
 * Item class names in script strings, navigating to their config class: the main link between
 * scripts and configs, and until now only a string. Vanilla passes a literal class name 365
 * times to `CreateInInventory`, 87 to `IsKindOf`, 31 to `CreateObjectEx`, 29 to
 * `CreateAttachment` and 15 to `CreateObject`.
 *
 * - The class-name argument of the spawn calls and of `IsKindOf` resolves to the top-level class
 *   of that name in `CfgVehicles`, `CfgWeapons` or `CfgMagazines`, every declaration of it, since
 *   mods override an item by declaring it again. Completion offers those classes; in a spawn call,
 *   only those that do not state `scope` 0 or 1, which cannot be spawned.
 * - A config path, `ConfigGetFloat("CfgAmmo Bullet_9x19 hit")`, resolves word by word: the section,
 *   the class in it, then a property or subclass of that class or of one of its bases. A path built
 *   with `+` gets the words of its first literal, usually only the section.
 *
 * Soft: vanilla configs are opt-in (`P:\DZ`, or the game's `Addons` as a PBO root), so without them
 * every vanilla item is unresolved, which is no mistake.
 */
class EnConfigClassReferenceProvider : PsiReferenceProvider() {

    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val content = EnStringLiterals.content(element) ?: return PsiReference.EMPTY_ARRAY
        if (content.isEmpty()) return PsiReference.EMPTY_ARRAY
        val site = EnCallArg.siteOf(element) ?: return PsiReference.EMPTY_ARRAY
        val callee = site.calleeName ?: return PsiReference.EMPTY_ARRAY
        val isWholeArgument = site.arguments.getOrNull(site.index) === element
        return when {
            callee in SPAWN_CALLS && site.index == 0 && isWholeArgument && isClassName(content) ->
                single(element, content, spawnable = true)
            callee == "IsKindOf" && site.index <= 1 && isWholeArgument && isClassName(content) ->
                single(element, content, spawnable = false)
            callee in PATH_CALLS && site.index == 0 && startsThePath(element, site) ->
                pathReferences(element, content)
            else -> PsiReference.EMPTY_ARRAY
        }
    }

    /**
     * A class name, or one being typed: completion runs on a copy of the file where the caret holds
     * the platform's dummy identifier, followed by a space.
     */
    private fun isClassName(content: String): Boolean =
        CLASS_NAME.matches(content.replace(CompletionUtilCore.DUMMY_IDENTIFIER_TRIMMED, "x").trimEnd())

    private fun single(element: PsiElement, content: String, spawnable: Boolean): Array<PsiReference> {
        val range = EnStringLiterals.subRange(element, 0, content.length) ?: return PsiReference.EMPTY_ARRAY
        return arrayOf(EnConfigClassReference(element, range, EnConfigPath.Part.Item(spawnable)))
    }

    /**
     * Whether [element] is the path's first literal: the whole argument, or the leftmost operand of
     * the concatenation that is.
     */
    private fun startsThePath(element: PsiElement, site: EnCallArg.Site): Boolean {
        val argument = site.arguments.getOrNull(site.index) ?: return false
        if (argument === element) return true
        val whole = EnConstantEvaluator.concatenationAround(element)
        if (whole !== argument) return false
        var leftmost: PsiElement = whole
        while (leftmost.node.elementType === EnfusionTypes.ADD_EXPR || leftmost.node.elementType === EnfusionTypes.PAREN_EXPR) {
            leftmost = leftmost.children.firstOrNull() ?: return false
        }
        return leftmost === element
    }

    private fun pathReferences(element: PsiElement, content: String): Array<PsiReference> {
        val words = WORD.findAll(content).toList()
        if (words.isEmpty() || words.first().range.first != 0 && content.substring(0, words.first().range.first).isNotBlank()) {
            return PsiReference.EMPTY_ARRAY
        }
        val out = ArrayList<PsiReference>()
        val path = ArrayList<String>()
        for ((index, word) in words.withIndex()) {
            if (index > 2) break
            val range = EnStringLiterals.subRange(element, word.range.first, word.range.last + 1) ?: break
            val part = when (index) {
                0 -> EnConfigPath.Part.Section
                1 -> EnConfigPath.Part.InSection(path[0])
                else -> EnConfigPath.Part.Member(path[0], path[1])
            }
            // A section is a `Cfg…` class; anything else is not a config path at all.
            if (index == 0 && !word.value.startsWith("cfg", ignoreCase = true)) return PsiReference.EMPTY_ARRAY
            out += EnConfigClassReference(element, range, part)
            path += word.value
        }
        return out.toTypedArray()
    }

    companion object {
        /** Calls whose first argument is the class to create. */
        val SPAWN_CALLS = setOf(
            "CreateObject", "CreateObjectEx", "CreateInInventory", "CreateInHands", "CreateEntityInCargo",
            "CreateEntityInCargoEx", "CreateAttachment", "CreateAttachmentEx", "SpawnEntity", "SpawnEntityAI",
            "SpawnEntityOnGround", "SpawnEntityOnGroundPos", "SpawnEntityOnGroundOnCursorDir",
            "SpawnEntityOnGroundRaycastDispersed",
        )

        /** Calls whose first argument is a config path, `"Section Class property"`. */
        val PATH_CALLS = setOf(
            "ConfigGetFloat", "ConfigGetInt", "ConfigGetText", "ConfigGetString", "ConfigGetTextRaw",
            "ConfigGetVector", "ConfigGetFloatArray", "ConfigGetIntArray", "ConfigGetTextArray",
            "ConfigGetTextArrayRaw", "ConfigIsExisting", "ConfigGetChildrenCount", "ConfigGetChildName",
            "ConfigGetType", "ConfigGetBaseName",
        )

        private val CLASS_NAME = Regex("[A-Za-z0-9_]+")
        private val WORD = Regex("[A-Za-z0-9_]+")
    }
}

/** What one word of a config path, or an item name, stands for. */
internal object EnConfigPath {

    sealed class Part {
        /** An item class, in any of the item sections. */
        class Item(val spawnable: Boolean) : Part()

        /** A top-level `Cfg…` class. */
        object Section : Part()

        /** A class directly in [section]. */
        class InSection(val section: String) : Part()

        /** A property or subclass of [cls] in [section], or of one of its bases. */
        class Member(val section: String, val cls: String) : Part()
    }

    val ITEM_SECTIONS = listOf("CfgVehicles", "CfgWeapons", "CfgMagazines")

    private const val MAX_BASES = 16

    fun resolve(project: Project, name: String, part: Part): List<PsiElement> = when (part) {
        is Part.Item -> ITEM_SECTIONS.flatMap { classesIn(project, it, name) }
        Part.Section -> classesNamed(project, name).filter { it.enclosingClass() == null && !it.isForwardDeclaration() }
        is Part.InSection -> classesIn(project, part.section, name)
        is Part.Member -> classesIn(project, part.section, part.cls).firstNotNullOfOrNull { member(project, it, name, 0) }
            ?.let(::listOf).orEmpty()
    }

    /**
     * Every declaration of [name] directly in a top-level [section]. A forward declaration
     * (`class Apple;`) only when nothing else is found: it names the class but says nothing of it.
     */
    fun classesIn(project: Project, section: String, name: String): List<EnCfgClassMixin> {
        val all = classesNamed(project, name).filter { cls ->
            val parent = cls.enclosingClass()
            parent != null && parent.enclosingClass() == null && parent.name.equals(section, ignoreCase = true)
        }
        return all.filter { !it.isForwardDeclaration() }.ifEmpty { all }
    }

    /**
     * Config class names ignore case, as the engine does: vanilla writes `"cfgAmmo "` as often as
     * `"CfgAmmo "`. The index is keyed by the exact name, so a miss is retried over its keys.
     */
    private fun classesNamed(project: Project, name: String): List<EnCfgClassMixin> {
        val scope = GlobalSearchScope.allScope(project)
        val index = FileBasedIndex.getInstance()
        var files = EnfusionConfigIndexes.findFiles(EnfusionConfigIndexes.CLASSES, name, scope)
        var matching: (String?) -> Boolean = { it == name }
        if (files.isEmpty()) {
            val keys = ArrayList<String>()
            index.processAllKeys(EnfusionConfigIndexes.CLASSES, { key -> if (key.equals(name, ignoreCase = true)) keys += key; true }, scope, null)
            files = keys.flatMap { EnfusionConfigIndexes.findFiles(EnfusionConfigIndexes.CLASSES, it, scope) }.distinct()
            matching = { it.equals(name, ignoreCase = true) }
        }
        val psiManager = PsiManager.getInstance(project)
        return files.flatMap { file ->
            val psiFile = psiManager.findFile(file) ?: return@flatMap emptyList()
            PsiTreeUtil.findChildrenOfType(psiFile, EnCfgClassMixin::class.java).filter { matching(it.name) }
        }
    }

    /** A property or subclass named [name] in [cls], else in its base, which sits in the same section. */
    private fun member(project: Project, cls: EnCfgClassMixin, name: String, depth: Int): PsiElement? {
        if (depth > MAX_BASES) return null
        PsiTreeUtil.getChildrenOfTypeAsList(cls, EnCfgPropertyMixin::class.java)
            .firstOrNull { name.equals(it.getKey(), ignoreCase = true) }?.let { return it }
        PsiTreeUtil.getChildrenOfTypeAsList(cls, EnCfgClassMixin::class.java)
            .firstOrNull { name.equals(it.name, ignoreCase = true) }?.let { return it }
        val base = cls.getSuperName() ?: return null
        val section = cls.enclosingClass()?.name ?: return null
        return classesIn(project, section, base).firstNotNullOfOrNull { member(project, it, name, depth + 1) }
    }

    /** Class names for completion, from [EnfusionConfigSectionIndex], without parsing a config. */
    fun variants(project: Project, part: Part): List<String> {
        val sections = when (part) {
            is Part.Item -> ITEM_SECTIONS
            is Part.InSection -> listOf(part.section)
            else -> return emptyList()
        }
        val spawnable = (part as? Part.Item)?.spawnable == true
        val scope = GlobalSearchScope.allScope(project)
        val names = LinkedHashSet<String>()
        for (section in sections) {
            for (value in FileBasedIndex.getInstance().getValues(EnfusionConfigSectionIndex.NAME, section, scope)) {
                for (line in value.lineSequence()) {
                    val name = line.substringBefore('\t')
                    val classScope = line.substringAfter('\t', "")
                    if (spawnable && (classScope == "0" || classScope == "1")) continue
                    names += name
                }
            }
        }
        return names.toList()
    }
}

class EnConfigClassReference internal constructor(
    element: PsiElement,
    range: TextRange,
    private val part: EnConfigPath.Part,
) : PsiPolyVariantReferenceBase<PsiElement>(element, range, /* soft = */ true) {

    override fun multiResolve(incompleteCode: Boolean): Array<ResolveResult> =
        ResolveCache.getInstance(element.project).resolveWithCaching(this, RESOLVER, false, incompleteCode)

    override fun getVariants(): Array<Any> {
        if (DumbService.isDumb(element.project)) return emptyArray()
        return EnConfigPath.variants(element.project, part)
            .map { LookupElementBuilder.create(it).withIcon(EnfusionIcons.FILE).withTypeText("config class") }
            .toTypedArray()
    }

    private companion object {
        val RESOLVER = ResolveCache.PolyVariantResolver<EnConfigClassReference> { ref, _ ->
            val project = ref.element.project
            if (DumbService.isDumb(project)) return@PolyVariantResolver ResolveResult.EMPTY_ARRAY
            val name = ref.value
            if (name.isEmpty()) return@PolyVariantResolver ResolveResult.EMPTY_ARRAY
            PsiElementResolveResult.createResults(EnConfigPath.resolve(project, name, ref.part))
        }
    }
}
