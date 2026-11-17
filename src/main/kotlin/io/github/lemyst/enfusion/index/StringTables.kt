package io.github.lemyst.enfusion.index

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.util.indexing.FileBasedIndex
import io.github.lemyst.enfusion.roots.EnfusionLibraryRootsProvider
import java.util.concurrent.ConcurrentHashMap

/**
 * Where a localization key is declared: every row of every `stringtable.csv` in the project and its
 * roots that declares it, the case of the key ignored as the engine ignores it.
 *
 * Most stringtables are answered by [StringTableIndex]. The ones the index never saw are read here:
 * the platform indexes no file over its IntelliSense size limit (2.5 MB by default), and the
 * vanilla `languagecore\stringtable.csv` is 5 MB, so without this no vanilla key would be found.
 * Each such file is read once per change and only its keys and lines are kept, about 7,000 entries
 * for the vanilla one, not its translations.
 *
 * Everything here needs a read action and smart mode, as the index does.
 */
object StringTables {

    /** The row declaring a key: its file, and its line there (0-based). */
    data class Row(val file: VirtualFile, val line: Int)

    /** The rows declaring [key], from every stringtable the project sees. */
    fun rowsOf(project: Project, key: String): List<Row> {
        if (key.isEmpty()) return emptyList()
        val normal = StringTableIndex.normalise(key)
        val rows = ArrayList<Row>()
        FileBasedIndex.getInstance().processValues(StringTableIndex.NAME, normal, null, { file, line ->
            rows += Row(file, line)
            true
        }, scope(project))
        for (file in unindexed(project)) {
            linesOf(file)?.get(normal)?.let { rows += Row(file, it) }
        }
        // A small stringtable can be in both while the index catches up with a new file.
        return rows.distinct()
    }

    /** The stringtables declaring [key]. */
    fun filesOf(project: Project, key: String): List<VirtualFile> = rowsOf(project, key).map { it.file }.distinct()

    /** Every stringtable the project sees, the ones too large to index included. */
    fun all(project: Project): List<VirtualFile> =
        (FilenameIndex.getVirtualFilesByName(StringTableIndex.FILE_NAME, false, scope(project)) + unindexed(project)).distinct()

    /** Whether the project sees any stringtable with a key in it. */
    fun hasAny(project: Project): Boolean {
        if (unindexed(project).any { !linesOf(it).isNullOrEmpty() }) return true
        val index = FileBasedIndex.getInstance()
        val scope = scope(project)
        var found = false
        index.processAllKeys(StringTableIndex.NAME, { key ->
            found = index.getContainingFiles(StringTableIndex.NAME, key, scope).isNotEmpty()
            !found
        }, project)
        return found
    }

    /**
     * allScope, not a module's resolve scope: a SyntheticLibrary has no OrderEntry, so a module
     * scope would leave out the vanilla `languagecore` root entirely.
     */
    private fun scope(project: Project): GlobalSearchScope = GlobalSearchScope.allScope(project)

    /**
     * The stringtables the index has nothing for. A stringtable with only a header is among them
     * too; reading it costs nothing. Found again when roots change, files are added or removed, or
     * indexing ends, which is when the answer changes.
     *
     * They are looked for at the top of each content and library root and one folder below, not
     * through `FilenameIndex`, which leaves out files over the size limit too (a test shows it).
     * That is where the large ones are: vanilla's at the top of its `languagecore` root, a mod's in
     * its `LanguageCore` addon.
     */
    private fun unindexed(project: Project): List<VirtualFile> =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            val index = FileBasedIndex.getInstance()
            val scope = scope(project)
            val roots = ProjectRootManager.getInstance(project).contentRoots.toList() +
                EnfusionLibraryRootsProvider().getAdditionalProjectLibraries(project).flatMap { it.sourceRoots }
            val tables = roots.asSequence()
                .flatMap { root -> sequenceOf(root) + root.children.asSequence().filter { it.isDirectory } }
                .mapNotNull { dir -> dir.findChild(StringTableIndex.FILE_NAME) }
                .filter { !it.isDirectory && it.length > 0 && scope.contains(it) }
                .distinct()
                .filter { index.getFileData(StringTableIndex.NAME, it, project).isEmpty() }
                .toList()
            CachedValueProvider.Result.create(
                tables,
                ProjectRootModificationTracker.getInstance(project),
                VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
                DumbService.getInstance(project).modificationTracker,
            )
        }

    private class Lines(val stamp: Long, val lines: Map<String, Int>)

    private val read = ConcurrentHashMap<String, Lines>()

    /** The keys of [file] and their lines, as [StringTableIndex] would have them, read again only when it changes. */
    private fun linesOf(file: VirtualFile): Map<String, Int>? {
        if (!file.isValid) return null
        val document = FileDocumentManager.getInstance().getCachedDocument(file)
        val stamp = document?.modificationStamp ?: file.modificationStamp
        read[file.url]?.takeIf { it.stamp == stamp }?.let { return it.lines }
        val text = document?.immutableCharSequence ?: runCatching { VfsUtilCore.loadText(file) }.getOrNull() ?: return null
        return StringTableIndex.index(text).also { read[file.url] = Lines(stamp, it) }
    }
}
