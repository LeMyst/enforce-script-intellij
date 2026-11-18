package io.github.lemyst.enfusion.index

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.DefaultFileTypeSpecificInputFilter
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import io.github.lemyst.enfusion.cfg.EnfusionConfigFileType
import io.github.lemyst.enfusion.cfg.psi.EnfusionConfigFile
import io.github.lemyst.enfusion.cfg.psi.impl.EnCfgClassMixin

/**
 * The classes each top-level config section declares, for completion of item class names:
 * `CfgVehicles` → `Apple`, `Inventory_Base`, ... in every config.
 *
 * Name lookups already have [EnfusionConfigClassIndex]; what it cannot answer is *which classes
 * are in `CfgVehicles`*, which completion needs without parsing every config. The value per file
 * is one line per class, `name` then a tab then the class's own `scope` (empty when it inherits
 * one), so completion in spawn calls can leave out the base classes that state `scope = 0` or `1`.
 */
class EnfusionConfigSectionIndex : FileBasedIndexExtension<String, String>() {

    override fun getName(): ID<String, String> = NAME

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer(): DataExternalizer<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getInputFilter(): FileBasedIndex.InputFilter = DefaultFileTypeSpecificInputFilter(EnfusionConfigFileType)

    override fun dependsOnFileContent(): Boolean = true

    override fun getVersion(): Int = 1

    override fun getIndexer(): DataIndexer<String, String, FileContent> = DataIndexer { content ->
        val file = content.psiFile as? EnfusionConfigFile ?: return@DataIndexer emptyMap()
        val sections = HashMap<String, StringBuilder>()
        for (section in PsiTreeUtil.getChildrenOfTypeAsList(file, EnCfgClassMixin::class.java)) {
            val sectionName = section.name ?: continue
            if (section.isForwardDeclaration()) continue
            val lines = sections.getOrPut(sectionName) { StringBuilder() }
            for (cls in PsiTreeUtil.getChildrenOfTypeAsList(section, EnCfgClassMixin::class.java)) {
                if (cls.isForwardDeclaration()) continue
                val name = cls.name?.takeIf { it.isNotEmpty() } ?: continue
                val scope = cls.propertyValue("scope")?.value()?.text?.trim().orEmpty()
                if (lines.isNotEmpty()) lines.append('\n')
                lines.append(name).append('\t').append(scope)
            }
        }
        sections.filterValues { it.isNotEmpty() }.mapValues { it.value.toString() }
    }

    companion object {
        val NAME: ID<String, String> = ID.create("enfusion.cfg.section")
    }
}
