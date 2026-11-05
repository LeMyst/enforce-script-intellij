package io.github.lemyst.enfusion.index

import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.DefaultFileTypeSpecificInputFilter
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileBasedIndexExtension
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.IOUtil
import com.intellij.util.io.KeyDescriptor
import io.github.lemyst.enfusion.lang.EnfusionFileType
import io.github.lemyst.enfusion.psi.EnNamedElement
import io.github.lemyst.enfusion.psi.EnfusionTypes
import io.github.lemyst.enfusion.psi.impl.EnMethodDeclMixin
import java.io.DataInput
import java.io.DataOutput

/**
 * What completion shows for a file-scope declaration, stored so it can be shown without a parse.
 *
 * [parameters] is the written parameter list, or null for a variable; [type] is the return or
 * declared type.
 */
data class EnGlobalSignature(val parameters: String?, val type: String?) {
    val isFunction: Boolean get() = parameters != null
    val hasParameters: Boolean get() = parameters != null && parameters != "()"
}

/**
 * File-scope declarations: free functions (`GetGame()`, `Print()`, `Vector()`) and globals
 * (`g_Game`), keyed by name, with their signature as the value.
 *
 * [EnfusionIndexes.SYMBOLS] already holds these names, mixed in with every method and field of
 * every class, and telling them apart means parsing the declaring file. That is acceptable for
 * resolving one name; it is not for completion, which with a one-letter prefix wants hundreds of
 * globals at once. Storing the signature here is what lets a bare-name popup open without
 * touching a single PSI tree.
 */
class EnfusionGlobalIndex : FileBasedIndexExtension<String, EnGlobalSignature>() {

    override fun getName(): ID<String, EnGlobalSignature> = NAME

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getValueExternalizer(): DataExternalizer<EnGlobalSignature> = Externalizer

    override fun getInputFilter(): FileBasedIndex.InputFilter =
        DefaultFileTypeSpecificInputFilter(EnfusionFileType)

    override fun dependsOnFileContent(): Boolean = true

    /** Bump with the grammar, as for [EnfusionNameIndexBase.getVersion]. */
    override fun getVersion(): Int = 2

    override fun getIndexer(): DataIndexer<String, EnGlobalSignature, FileContent> =
        DataIndexer { content ->
            val out = HashMap<String, EnGlobalSignature>()
            // Direct children only: everything file-scope is a child of the file or of a varDecl
            // that is, so there is no need to walk into a single class or method body.
            for (child in content.psiFile.children) {
                val declarations = when (child.node?.elementType) {
                    EnfusionTypes.METHOD_DECL -> listOf(child)
                    EnfusionTypes.VAR_DECL -> child.children.filter {
                        it.node?.elementType === EnfusionTypes.VAR_DECLARATOR
                    }
                    else -> emptyList()
                }
                for (declaration in declarations) {
                    if (declaration !is EnNamedElement) continue
                    val name = declaration.name?.takeIf { it.isNotEmpty() } ?: continue
                    // Overloads in one file collapse to the first: the popup offers the name once.
                    out.putIfAbsent(name, signatureOf(declaration))
                }
            }
            out
        }

    private fun signatureOf(declaration: EnNamedElement): EnGlobalSignature =
        if (declaration is EnMethodDeclMixin) {
            EnGlobalSignature(declaration.getParameterListText(), declaration.getReturnTypeText())
        } else {
            val type = declaration.node.treeParent?.findChildByType(EnfusionTypes.TYPE_REF)?.text?.trim()
            EnGlobalSignature(null, type)
        }

    private object Externalizer : DataExternalizer<EnGlobalSignature> {
        override fun save(out: DataOutput, value: EnGlobalSignature) {
            IOUtil.writeUTF(out, value.parameters ?: NONE)
            IOUtil.writeUTF(out, value.type ?: NONE)
        }

        override fun read(input: DataInput): EnGlobalSignature {
            val parameters = IOUtil.readUTF(input)
            val type = IOUtil.readUTF(input)
            return EnGlobalSignature(parameters.takeUnless { it == NONE }, type.takeUnless { it == NONE })
        }

        /** No parameter list starts with a NUL, so it can stand for "absent". */
        private const val NONE = "\u0000"
    }

    companion object {
        val NAME: ID<String, EnGlobalSignature> = ID.create("enfusion.globals")
    }
}
