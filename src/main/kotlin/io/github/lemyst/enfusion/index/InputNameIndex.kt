package io.github.lemyst.enfusion.index

import com.intellij.util.indexing.DataIndexer
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FileContent
import com.intellij.util.indexing.ID
import com.intellij.util.indexing.ScalarIndexExtension
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.KeyDescriptor

/**
 * Keybind action names declared in an `Inputs.xml`.
 *
 * The index is only a file-narrowing device, so it is built with a regex rather than by walking XML
 * PSI. A name occurs three times in one file — under `<actions>`, `<sorting>` and `<preset>` — and
 * indexing all three costs nothing, because picking the right one happens at resolve time where the
 * document structure is available anyway.
 */
object InputNameIndex {

    val NAME: ID<String, Void> = ID.create("enfusion.inputs")

    const val FILE_NAME = "inputs.xml"

    /** `<input name="UAPartyMenu" …>`. */
    private val INPUT_NAME = Regex("""<input\b[^>]*\bname\s*=\s*"([^"]+)"""")

    fun index(text: CharSequence): Map<String, Void?> {
        val result = HashMap<String, Void?>()
        for (match in INPUT_NAME.findAll(text)) {
            val name = match.groupValues[1]
            if (name.isNotEmpty()) result[name] = null
        }
        return result
    }
}

class EnfusionInputNameIndex : ScalarIndexExtension<String>() {

    override fun getName(): ID<String, Void> = InputNameIndex.NAME

    override fun getKeyDescriptor(): KeyDescriptor<String> = EnumeratorStringDescriptor.INSTANCE

    override fun dependsOnFileContent(): Boolean = true

    /** Bump on any change to what is indexed. */
    override fun getVersion(): Int = 1

    /**
     * Matched by name, case-insensitively: the engine loads whatever `inputs=` in a `config.cpp`
     * points at, and mods spell it both `Inputs.xml` and `inputs.xml`.
     */
    override fun getInputFilter(): FileBasedIndex.InputFilter =
        FileBasedIndex.InputFilter { file -> file.name.equals(InputNameIndex.FILE_NAME, ignoreCase = true) }

    override fun getIndexer(): DataIndexer<String, Void, FileContent> =
        DataIndexer { content -> InputNameIndex.index(content.contentAsText) }
}
