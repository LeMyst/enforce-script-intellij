package io.github.lemyst.enfusion

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.pbo.PboCache
import io.github.lemyst.enfusion.pbo.PboExtractor
import io.github.lemyst.enfusion.pbo.PboFolders
import io.github.lemyst.enfusion.roots.EnfusionRoots
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * What a `.pbo` root and a folder of PBOs put into the library, as the provider resolves it.
 * The unpacking itself is [EnfusionPboExtractorTest]'s; this is the root list's side.
 */
class EnfusionPboRootsTest : BasePlatformTestCase() {

    private lateinit var dir: File
    private lateinit var pbo: Path
    private var cache: Path? = null

    override fun setUp() {
        super.setUp()
        dir = FileUtil.createTempDirectory("enfusion-pbo-roots", null, true)
        VfsRootAccess.allowRootAccess(testRootDisposable, dir.path, PboCache.root.toString())
        pbo = dir.toPath().resolve("@Mod/addons/Scripts.pbo")
        Files.createDirectories(pbo.parent)
        Files.write(pbo, PboBytes.pbo(listOf(PboBytes.file("3_Game\\a.c", "class A {}")), mapOf("prefix" to "Mod\\Scripts")))
        cache = PboCache.ensure(pbo.toString())!!.dir
    }

    override fun tearDown() {
        try {
            cache?.let { PboExtractor.deleteTree(it) }
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun vf(path: Path): VirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!

    fun testAPboRootShowsItsCacheAndItself() {
        val cacheDir = vf(cache!!)
        val pboFile = vf(pbo)
        assertEquals(listOf(cacheDir, pboFile), EnfusionRoots.resolveForRead(listOf(pbo.toString())))
    }

    fun testAFolderRootBringsItsPbosCacheButNotTheFileAgain() {
        val folder = dir.toPath().resolve("@Mod")
        PboFolders.scanAndRecord(folder.toString())
        val folderDir = vf(folder)
        val cacheDir = vf(cache!!)
        assertEquals(listOf(folderDir, cacheDir), EnfusionRoots.resolveForRead(listOf(folder.toString())))
    }
}
