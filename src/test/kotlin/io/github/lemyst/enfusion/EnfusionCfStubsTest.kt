package io.github.lemyst.enfusion

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.psi.EnNamedElement
import io.github.lemyst.enfusion.psi.impl.EnMethodDeclMixin
import io.github.lemyst.enfusion.roots.CfStubs

/**
 * The bundled Community Framework stub (`resources/stubs/cf/RPCManager.c`), exposed by
 * [io.github.lemyst.enfusion.roots.EnfusionLibraryRootsProvider] as its own [io.github.lemyst.enfusion.roots.CfStubsLibrary].
 * Without it `GetRPCManager()` and `CallType` never resolve on a machine with no real `@CF` checkout
 * -- the common case, since CF is a Steam Workshop dependency this project has never had locally.
 */
class EnfusionCfStubsTest : BasePlatformTestCase() {

    fun testGetRpcManagerResolvesToTheStub() {
        myFixture.configureByText(
            "a.es",
            """
            class Foo
            {
            	void Bar() { GetRPC<caret>Manager(); }
            }
            """.trimIndent(),
        )
        val reference = myFixture.getReferenceAtCaretPosition() as PsiPolyVariantReference
        val target = reference.multiResolve(false).singleOrNull()?.element as? EnMethodDeclMixin
        assertNotNull("GetRPCManager() should resolve to the bundled stub", target)
        assertEquals("GetRPCManager", target!!.name)
    }

    fun testCallTypeResolvesToTheStub() {
        myFixture.configureByText(
            "a.es",
            """
            class Foo
            {
            	void Handler(Call<caret>Type type) {}
            }
            """.trimIndent(),
        )
        val reference = myFixture.getReferenceAtCaretPosition() as PsiPolyVariantReference
        val target = reference.multiResolve(false).singleOrNull()?.element as? EnNamedElement
        assertNotNull("CallType should resolve to the bundled stub", target)
        assertEquals("CallType", target!!.name)
    }

    fun testAddRpcOnTheStubResolvesAsAMethodOfRpcManager() {
        myFixture.configureByText(
            "a.es",
            """
            class Foo
            {
            	void Bar() { GetRPCManager().Add<caret>RPC("NS", "Handler", this); }
            }
            """.trimIndent(),
        )
        val reference = myFixture.getReferenceAtCaretPosition() as PsiPolyVariantReference
        val target = reference.multiResolve(false).singleOrNull()?.element as? EnMethodDeclMixin
        assertNotNull("AddRPC should resolve on the stub's RPCManager", target)
        assertEquals("AddRPC", target!!.name)
    }

    // The stub steps aside by itself once CF's real sources are a root, so that the two never
    // offer competing declarations of the same names.

    private val realRpcManager = "JM/CF/Scripts/3_Game/CommunityFramework/RPC/RPCManager.c"

    private fun dir(path: String): VirtualFile = myFixture.tempDirFixture.findOrCreateDir(path)

    private fun cfCheckout(at: String): VirtualFile {
        myFixture.tempDirFixture.createFile("$at/$realRpcManager", "class RPCManager {}")
        return dir(at)
    }

    fun testTheStubIsUsedWithoutRealSources() {
        myFixture.tempDirFixture.createFile("P/scripts/3_game/dummy.c", "")
        val roots = CfStubs.rootsFor(listOf(dir("P/scripts")))
        assertEquals(listOfNotNull(CfStubs.root), roots)
        assertFalse("the stub must exist to be offered", roots.isEmpty())
    }

    fun testTheStubStepsAsideForACheckoutAddedAtAnyLevel() {
        cfCheckout("DayZ-CommunityFramework")
        for (level in listOf("", "/JM", "/JM/CF", "/JM/CF/Scripts")) {
            val root = dir("DayZ-CommunityFramework$level")
            assertEmpty("root at '$level'", CfStubs.rootsFor(listOf(dir("P/scripts"), root)))
        }
    }

    fun testTheStubStepsAsideForAFolderHoldingACheckout() {
        cfCheckout("Mods/DayZ-CommunityFramework")
        assertEmpty(CfStubs.rootsFor(listOf(dir("Mods"))))
    }

    fun testAModWithItsOwnRpcManagerKeepsTheStub() {
        myFixture.tempDirFixture.createFile("SomeMod/Scripts/3_Game/SomeMod/RPC/RPCManager.c", "")
        assertEquals(listOfNotNull(CfStubs.root), CfStubs.rootsFor(listOf(dir("SomeMod"))))
    }
}
