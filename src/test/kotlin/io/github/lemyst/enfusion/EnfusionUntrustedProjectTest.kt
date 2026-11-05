package io.github.lemyst.enfusion

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.roots.EnfusionLibraryRootsProvider
import io.github.lemyst.enfusion.roots.EnfusionRoots
import io.github.lemyst.enfusion.settings.EnfusionProjectSettings
import io.github.lemyst.enfusion.startup.missingRootsMessage

/**
 * The root list lives in `.idea/enfusion.xml`, which a cloned repository can ship. Until the user
 * trusts the project, nothing in it may be resolved: a UNC root would make Windows contact the
 * repository author's host over SMB on project open.
 *
 * The platform disables its trust check in tests, so these pass the trust state explicitly to the
 * same functions production calls with the real one. The root is a real directory because the
 * provider resolves through `LocalFileSystem`, which the light fixture's temp file system is not.
 */
class EnfusionUntrustedProjectTest : BasePlatformTestCase() {

    private lateinit var root: VirtualFile
    private var savedRoots: List<String> = emptyList()

    override fun setUp() {
        super.setUp()
        val dir = FileUtil.createTempDirectory("enfusion-root", null, true)
        VfsRootAccess.allowRootAccess(testRootDisposable, dir.path)
        root = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir)!!
        // The light project outlives this test, so its settings are put back afterwards. The path
        // keeps its Windows separators, as a hand-written enfusion.xml would.
        val settings = EnfusionProjectSettings.getInstance(project)
        savedRoots = settings.roots
        settings.roots = listOf(dir.path)
    }

    override fun tearDown() {
        try {
            EnfusionProjectSettings.getInstance(project).roots = savedRoots
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun indexedRoots(trusted: Boolean): List<VirtualFile> =
        EnfusionLibraryRootsProvider().librariesFor(project, trusted).flatMap { it.sourceRoots }

    fun testATrustedProjectIndexesItsConfiguredRoot() {
        assertContainsElements(indexedRoots(trusted = true), root)
        assertContainsElements(EnfusionLibraryRootsProvider().rootsToWatchFor(project, trusted = true), root)
    }

    fun testAnUntrustedProjectIndexesNoConfiguredRoot() {
        assertDoesntContain(indexedRoots(trusted = false), root)
        assertDoesntContain(EnfusionLibraryRootsProvider().rootsToWatchFor(project, trusted = false), root)
    }

    fun testAnUntrustedProjectExposesNoConfiguredRoot() {
        assertEquals(EnfusionProjectSettings.getInstance(project).roots, EnfusionRoots.configuredRoots(project, trusted = true))
        assertEmpty(EnfusionRoots.configuredRoots(project, trusted = false))
    }

    fun testMissingRootsAreEscapedInTheNotification() {
        val message = missingRootsMessage(listOf("<img src=\"http://example.invalid/x\">", "P:\\Mods\\Tom & Jerry"))
        assertFalse(message, message.contains("<img"))
        assertTrue(message, message.contains("&lt;img src=&quot;http://example.invalid/x&quot;&gt;"))
        assertTrue(message, message.contains("Tom &amp; Jerry"))
    }
}
