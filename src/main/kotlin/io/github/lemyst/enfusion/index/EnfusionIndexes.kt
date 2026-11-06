package io.github.lemyst.enfusion.index

import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.indexing.ScalarIndexExtension
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor
import io.github.lemyst.enfusion.lang.EnfusionFileType
import io.github.lemyst.enfusion.psi.EnNamedElement
import io.github.lemyst.enfusion.psi.EnfusionTypes

/**
 * Name indexes backing Go to Class and Go to Symbol.
 *
 * These are populated from PSI rather than by a token scan, because deciding what a name *is*
 * genuinely needs structure: a method name is the identifier before the parameter list, a class
 * name may be a primitive keyword, and a `typedef`'s name is the identifier after a type that may
 * itself contain identifiers. A lexer-only scanner gets all three wrong.
 *
 * Parsing is affordable here — the corpus parses at over 100k lines/s, so the whole 417k-line
 * vanilla tree indexes in a few seconds.
 *
 * A stub tree would additionally let member *resolution* run without reparsing; that is what M4
 * needs and where stubs will be introduced. For name-keyed navigation this is sufficient.
 */
object EnfusionIndexes {

    /** Classes, enums and typedefs — everything that names a type. */
    val TYPES: ID<String, Void> = ID.create("enfusion.types")

    /** Methods, fields, and enum constants — everything else Go to Symbol should find. */
    val SYMBOLS: ID<String, Void> = ID.create("enfusion.symbols")

    /**
     * Keyed by the *superclass* name, so a lookup answers "which files declare a subclass of X".
     *
     * The inverse of [TYPES], and the only way to walk a hierarchy downwards: nothing about a class
     * says who extends it, so without this the subtypes view would have to parse every file.
     */
    val SUPERS: ID<String, Void> = ID.create("enfusion.supers")

    private val TYPE_ELEMENTS = setOf(
        EnfusionTypes.CLASS_DECL,
        EnfusionTypes.ENUM_DECL,
        EnfusionTypes.TYPEDEF_DECL,
    )

    fun isTypeDeclaration(element: PsiElement): Boolean =
        element.node?.elementType in TYPE_ELEMENTS

    /**
     * Members, not locals. A `varDeclarator` counts only when its declaration sits directly in a
     * class body or at file scope — the same rule reaches file-scope globals such as `g_Game`,
     * which is correct, since the language has no other place to put them.
     */
    fun isSymbolDeclaration(element: PsiElement): Boolean {
        return when (element.node?.elementType) {
            EnfusionTypes.METHOD_DECL, EnfusionTypes.ENUM_CONSTANT -> true
            EnfusionTypes.VAR_DECLARATOR -> {
                val ownerType = element.parent?.parent?.node?.elementType
                ownerType === EnfusionTypes.CLASS_BODY || element.parent?.parent is com.intellij.psi.PsiFile
            }
            else -> false
        }
    }

    /**
     * File scope only: a free function such as `GetGame()` or a global such as `g_Game`. A class
     * member belongs to its class, not to everyone.
     */
    fun isFileScopeDeclaration(element: PsiElement): Boolean =
        when (element.node?.elementType) {
            // A free function is a direct child of the file.
            EnfusionTypes.METHOD_DECL -> element.parent is com.intellij.psi.PsiFile
            // A declarator sits inside its varDecl, which is the file's child.
            EnfusionTypes.VAR_DECLARATOR -> element.parent?.parent is com.intellij.psi.PsiFile
            else -> false
        }

    fun collectNames(file: com.intellij.psi.PsiFile, accept: (PsiElement) -> Boolean): Map<String, Void?> {
        val names = HashMap<String, Void?>()
        PsiTreeUtil.processElements(file) { element ->
            if (element is EnNamedElement && accept(element)) {
                element.name?.takeIf { it.isNotEmpty() }?.let { names[it] = null }
            }
            true
        }
        return names
    }

    fun findFiles(id: ID<String, Void>, name: String, scope: com.intellij.psi.search.GlobalSearchScope) =
        FileBasedIndex.getInstance().getContainingFiles(id, name, scope)
}

abstract class EnfusionNameIndexBase : ScalarIndexExtension<String>() {

    protected abstract fun accepts(element: PsiElement): Boolean

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getInputFilter(): FileBasedIndex.InputFilter =
        com.intellij.util.indexing.DefaultFileTypeSpecificInputFilter(EnfusionFileType)

    override fun dependsOnFileContent(): Boolean = true

    /**
     * Bump on any change to what is indexed or how names are derived — **including a grammar
     * change**, which is the easy one to miss. These indexers run on PSI, so a rule that alters what
     * parses alters what they collect; without a bump the platform keeps the old data and the fix
     * appears not to work. Version 2 is the `Class`-as-type-argument fix, which made `map`, `set`
     * and every declaration after them in `enscript.c` indexable for the first time. Version 3
     * is the grammar catching up with a DayZ update (`sealed` members, `<<=`, `ref` foreach
     * variables, statements before a `case`), each of which used to cut a file short. Version 4 is
     * the lexer letting an unclosed string run to end of line instead of ending in a BAD_CHARACTER.
     * Version 5 is the lexer letting an unclosed block comment run to the end of the file.
     */
    override fun getVersion(): Int = 5

    override fun getIndexer(): DataIndexer<String, Void, FileContent> =
        DataIndexer { content ->
            val psi = content.psiFile
            EnfusionIndexes.collectNames(psi) { accepts(it) }
        }
}

class EnfusionTypeNameIndex : EnfusionNameIndexBase() {
    override fun getName(): ID<String, Void> = EnfusionIndexes.TYPES
    override fun accepts(element: PsiElement): Boolean = EnfusionIndexes.isTypeDeclaration(element)
}

class EnfusionSymbolNameIndex : EnfusionNameIndexBase() {
    override fun getName(): ID<String, Void> = EnfusionIndexes.SYMBOLS
    override fun accepts(element: PsiElement): Boolean = EnfusionIndexes.isSymbolDeclaration(element)
}

/**
 * Keyed by superclass name rather than by the declaring class's own name, which is why this cannot
 * reuse [EnfusionNameIndexBase] -- that indexes the name *of* each accepted declaration.
 */
class EnfusionSuperTypeIndex : ScalarIndexExtension<String>() {

    override fun getName(): ID<String, Void> = EnfusionIndexes.SUPERS

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun getInputFilter(): FileBasedIndex.InputFilter =
        com.intellij.util.indexing.DefaultFileTypeSpecificInputFilter(EnfusionFileType)

    override fun dependsOnFileContent(): Boolean = true

    /** Bumped with [EnfusionNameIndexBase.getVersion]; see there for why a grammar change counts. */
    override fun getVersion(): Int = 5

    override fun getIndexer(): DataIndexer<String, Void, FileContent> =
        DataIndexer { content ->
            val keys = HashMap<String, Void?>()
            PsiTreeUtil.findChildrenOfType(
                content.psiFile,
                io.github.lemyst.enfusion.psi.impl.EnClassDeclMixin::class.java,
            ).forEach { declaration ->
                val superName = declaration.getSuperName()
                    ?.let { io.github.lemyst.enfusion.resolve.EnTypeInference.baseTypeName(it) }
                if (superName != null) keys[superName] = null
            }
            keys
        }
}
