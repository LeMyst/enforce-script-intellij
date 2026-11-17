package io.github.lemyst.enfusion.stringtable

import com.intellij.application.options.editor.CodeFoldingOptionsProvider
import com.intellij.ide.util.PropertiesComponent
import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.editor.Document
import com.intellij.openapi.options.BeanConfigurable
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.lemyst.enfusion.cfg.psi.EnfusionConfigFile
import io.github.lemyst.enfusion.cfg.psi.EnfusionConfigTypes
import io.github.lemyst.enfusion.objfmt.preview.LayoutTranslations
import io.github.lemyst.enfusion.objfmt.psi.EnfusionObjectTypes
import io.github.lemyst.enfusion.psi.EnfusionTypes
import io.github.lemyst.enfusion.refs.EnStringLiterals
import io.github.lemyst.enfusion.refs.StringTableKeyReferenceProvider

/**
 * A string naming a stringtable key folds to the key's text, collapsed by default:
 * `"#STR_MYMOD_PARTY_INVITE"` reads `"Invite to party"`, as Android Studio shows string resources.
 * The text is in the layout preview's chosen language, else `original`, else `english`; a key with
 * no row stays as it is. Moving the caret into the string unfolds it, and so does turning the
 * option off under *Settings → Editor → General → Code Folding*.
 *
 * The strings are those [StringTableKeyReferenceProvider] resolves: `#key` and server-side bare
 * `STR_…` in script, layout values, and `$STR_…` / `#STR_…` in configs. The texts come from
 * [LayoutTranslations], which reads every stringtable through the index and keeps each parsed once
 * per change, the vanilla one too large for the platform to index included.
 */
class EnStringTableFoldingBuilder : FoldingBuilderEx() {

    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        // Quick passes run in the editor's first paint, where an index lookup is not wanted.
        if (quick || !EnStringTableFoldingSettings.instance().state.foldKeys) return FoldingDescriptor.EMPTY_ARRAY
        val project = root.project
        if (DumbService.isDumb(project)) return FoldingDescriptor.EMPTY_ARRAY
        val dollar = root.containingFile is EnfusionConfigFile
        val keyed = ArrayList<Pair<PsiElement, String>>()
        PsiTreeUtil.processElements(root) { element ->
            if (isString(element)) {
                val content = EnStringLiterals.content(element)
                val start = content?.let { StringTableKeyReferenceProvider.keyStart(it, dollar) }
                if (content != null && start != null) keyed += element to content.substring(start)
            }
            true
        }
        if (keyed.isEmpty()) return FoldingDescriptor.EMPTY_ARRAY
        val table = LayoutTranslations.of(project, keyed.mapTo(HashSet()) { it.second })
        val language = EnStringTableFoldingSettings.language()
        val descriptors = ArrayList<FoldingDescriptor>()
        for ((element, key) in keyed) {
            val row = table.texts[key] ?: continue
            val text = listOfNotNull(language, "original", "english")
                .firstNotNullOfOrNull { row[it]?.takeIf(String::isNotBlank) } ?: continue
            descriptors += FoldingDescriptor(element.node, element.textRange, null, placeholder(text))
        }
        return descriptors.toTypedArray()
    }

    /** The string's whole literal, quotes included: script literals, layout and config values. */
    private fun isString(element: PsiElement): Boolean {
        val node = element.node ?: return false
        return when (node.elementType) {
            EnfusionTypes.LITERAL_EXPR -> node.findChildByType(EnfusionTypes.STRING) != null
            EnfusionObjectTypes.VALUE -> node.findChildByType(EnfusionObjectTypes.STRING) != null
            EnfusionConfigTypes.VALUE -> node.findChildByType(EnfusionConfigTypes.STRING) != null
            else -> false
        }
    }

    override fun getPlaceholderText(node: ASTNode): String = "\"…\""

    override fun isCollapsedByDefault(node: ASTNode): Boolean = true

    companion object {
        /** Long texts end in `…`: a placeholder is one line of code, not a paragraph. */
        private const val MAX_TEXT = 60

        fun placeholder(text: String): String {
            val line = text.replace(Regex("\\s+"), " ").trim()
            val shown = if (line.length > MAX_TEXT) line.take(MAX_TEXT - 1).trimEnd() + "…" else line
            return "\"$shown\""
        }
    }
}

/** The option, application-wide like the platform's own folding options. */
@Service(Service.Level.APP)
@State(name = "EnforceScriptStringTableFolding", storages = [Storage("editor.xml")])
class EnStringTableFoldingSettings : PersistentStateComponent<EnStringTableFoldingSettings.State> {

    class State {
        @JvmField
        var foldKeys: Boolean = true
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        fun instance(): EnStringTableFoldingSettings = ApplicationManager.getApplication().getService(EnStringTableFoldingSettings::class.java)

        /**
         * The layout preview's language (lower case), or null when it shows keys. The same
         * property the preview's toolbar writes, so a translator picks a language once.
         */
        fun language(): String? {
            val chosen = PropertiesComponent.getInstance().getValue("enfusion.layoutPreview.language", "english")
            return chosen.takeUnless { it == "Keys" }?.lowercase()
        }
    }
}

class EnStringTableFoldingOptionsProvider :
    BeanConfigurable<EnStringTableFoldingSettings.State>(EnStringTableFoldingSettings.instance().state, "Enforce Script"),
    CodeFoldingOptionsProvider {

    init {
        checkBox("Stringtable keys as their text", instance::foldKeys)
    }
}
