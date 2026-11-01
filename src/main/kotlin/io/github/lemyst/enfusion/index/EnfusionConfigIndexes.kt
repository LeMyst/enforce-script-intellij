package io.github.lemyst.enfusion.index

import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.DefaultFileTypeSpecificInputFilter
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.indexing.ScalarIndexExtension
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import io.github.lemyst.enfusion.cfg.EnfusionConfigFileType
import io.github.lemyst.enfusion.cfg.psi.impl.EnCfgClassMixin

/**
 * Name indexes over `config.cpp` and `mod.cpp`.
 *
 * Two rather than one, for the same reason [EnfusionObjectIndexes] has three: an addon name and an
 * ordinary config class name are different kinds of thing, and keying them together would let
 * `requiredAddons[] = {"DZ_Data"}` resolve to some unrelated nested `class DZ_Data` — of which the
 * corpus holds 78,000 candidates.
 */
object EnfusionConfigIndexes {

    /** Every class name declared anywhere in a config, forward declarations included. */
    val CLASSES: ID<String, Void> = ID.create("enfusion.cfg.class")

    /** Only classes declared directly under `CfgPatches` — that is, addon names. */
    val ADDONS: ID<String, Void> = ID.create("enfusion.cfg.addon")

    const val CFG_PATCHES = "CfgPatches"

    fun declarations(file: PsiFile): Collection<EnCfgClassMixin> =
        PsiTreeUtil.findChildrenOfType(file, EnCfgClassMixin::class.java)

    /**
     * An addon declaration is a class whose only ancestor is `CfgPatches`.
     *
     * Checking the immediate parent rather than searching the subtree matters: `CfgPatches` classes
     * carry `requiredAddons[]` and nothing else, but a mod that nests further would otherwise have
     * its inner classes indexed as addons too.
     */
    fun isAddonDeclaration(cls: EnCfgClassMixin): Boolean {
        val parent = cls.enclosingClass() ?: return false
        return parent.name == CFG_PATCHES && parent.enclosingClass() == null
    }

    fun findFiles(id: ID<String, Void>, name: String, scope: GlobalSearchScope) =
        FileBasedIndex.getInstance().getContainingFiles(id, name, scope)
}

abstract class EnfusionConfigIndexBase : ScalarIndexExtension<String>() {

    protected abstract fun accepts(cls: EnCfgClassMixin): Boolean

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getInputFilter(): FileBasedIndex.InputFilter =
        DefaultFileTypeSpecificInputFilter(EnfusionConfigFileType)

    override fun dependsOnFileContent(): Boolean = true

    /** Bump on any change to what is indexed, the config grammar included. */
    override fun getVersion(): Int = 1

    override fun getIndexer(): DataIndexer<String, Void, FileContent> =
        DataIndexer { content ->
            val names = HashMap<String, Void?>()
            for (cls in EnfusionConfigIndexes.declarations(content.psiFile)) {
                if (!accepts(cls)) continue
                cls.name?.takeIf { it.isNotEmpty() }?.let { names[it] = null }
            }
            names
        }
}

class EnfusionConfigClassIndex : EnfusionConfigIndexBase() {
    override fun getName(): ID<String, Void> = EnfusionConfigIndexes.CLASSES
    override fun accepts(cls: EnCfgClassMixin): Boolean = true
}

class EnfusionConfigAddonIndex : EnfusionConfigIndexBase() {
    override fun getName(): ID<String, Void> = EnfusionConfigIndexes.ADDONS
    override fun accepts(cls: EnCfgClassMixin): Boolean = EnfusionConfigIndexes.isAddonDeclaration(cls)
}
