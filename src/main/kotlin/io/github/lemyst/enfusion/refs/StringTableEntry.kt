package io.github.lemyst.enfusion.refs

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.FakePsiElement
import javax.swing.Icon

/**
 * A row in a `stringtable.csv`, presented as something the platform can navigate to.
 *
 * The CSV has no PSI of its own by design, so there is no real element to return. A
 * [FakePsiElement] that knows its file and line is enough for Go to Declaration, for the
 * multi-target picker and for Quick Definition, and it costs nothing to build.
 */
class StringTableEntry(
    private val project: Project,
    private val file: VirtualFile,
    private val key: String,
    private val line: Int,
) : FakePsiElement() {

    /**
     * The containing file, when the platform has one for it.
     *
     * A `.csv` in a library root is an ordinary plain-text file, so this normally succeeds — but
     * [getManager] and [isValid] are answered without it, so a null parent degrades to a target
     * that still navigates rather than to an exception.
     */
    override fun getParent(): PsiElement? = containingFile

    override fun getContainingFile(): PsiFile? = PsiManager.getInstance(project).findFile(file)

    override fun getManager(): PsiManager = PsiManager.getInstance(project)

    override fun getProject(): Project = project

    override fun isValid(): Boolean = file.isValid

    override fun getName(): String = key

    override fun getPresentableText(): String = key

    /** Names the addon rather than the file: every addon's stringtable has the same file name. */
    override fun getLocationString(): String = file.parent?.name ?: file.name

    override fun getIcon(open: Boolean): Icon? = file.fileType.icon

    override fun canNavigate(): Boolean = file.isValid

    override fun canNavigateToSource(): Boolean = canNavigate()

    override fun navigate(requestFocus: Boolean) {
        OpenFileDescriptor(project, file, line, 0).navigate(requestFocus)
    }

    override fun equals(other: Any?): Boolean =
        other is StringTableEntry && other.file == file && other.line == line

    override fun hashCode(): Int = 31 * file.hashCode() + line
}
