package io.github.lemyst.enfusion

import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.psi.impl.EnClassDeclMixin
import io.github.lemyst.enfusion.resolve.EnClassHierarchy

/**
 * The two relationships the hierarchy view models: `extends`, and `modded class` layering.
 *
 * Layering is the one worth testing hard. A `modded class X` names nothing -- no `extends`, no
 * reference to the layer it patches -- so the link exists only because the names match, and it is
 * attached in one direction only so that base -> layer -> base cannot become an endlessly expandable
 * cycle in the tree.
 */
class EnfusionClassHierarchyTest : BasePlatformTestCase() {

    private fun classNamed(name: String): EnClassDeclMixin =
        PsiTreeUtil.findChildrenOfType(myFixture.file, EnClassDeclMixin::class.java)
            .first { it.name == name && !it.isModded() }

    private fun moddedClassNamed(name: String): EnClassDeclMixin =
        PsiTreeUtil.findChildrenOfType(myFixture.file, EnClassDeclMixin::class.java)
            .first { it.name == name && it.isModded() }

    private fun namesOf(classes: List<EnClassDeclMixin>) = classes.mapNotNull { it.name }.sorted()

    fun testSubtypesFindsClassesThatExtendIt() {
        myFixture.configureByText(
            "a.es",
            """
            class Base {}
            class Middle extends Base {}
            class Other extends Middle {}
            class Unrelated {}
            """.trimIndent(),
        )
        assertEquals(listOf("Middle"), namesOf(EnClassHierarchy.subtypesOf(classNamed("Base"))))
        assertEquals(listOf("Other"), namesOf(EnClassHierarchy.subtypesOf(classNamed("Middle"))))
        assertTrue(EnClassHierarchy.subtypesOf(classNamed("Unrelated")).isEmpty())
    }

    fun testSupertypesFollowsExtends() {
        myFixture.configureByText(
            "a.es",
            """
            class Base {}
            class Middle extends Base {}
            """.trimIndent(),
        )
        assertEquals(listOf("Base"), namesOf(EnClassHierarchy.supertypesOf(classNamed("Middle"))))
        assertTrue(EnClassHierarchy.supertypesOf(classNamed("Base")).isEmpty())
    }

    /** The layering relationship, which no text in the modded declaration expresses. */
    fun testModdedLayerIsASubtypeOfTheBase() {
        myFixture.configureByText(
            "a.es",
            """
            class PlayerBase {}
            modded class PlayerBase {}
            """.trimIndent(),
        )
        assertEquals(
            listOf("PlayerBase"),
            namesOf(EnClassHierarchy.subtypesOf(classNamed("PlayerBase"))),
        )
        assertTrue(EnClassHierarchy.subtypesOf(classNamed("PlayerBase"))[0].isModded())
    }

    fun testBaseIsTheSupertypeOfAModdedLayer() {
        myFixture.configureByText(
            "a.es",
            """
            class PlayerBase {}
            modded class PlayerBase {}
            """.trimIndent(),
        )
        val supers = EnClassHierarchy.supertypesOf(moddedClassNamed("PlayerBase"))
        assertEquals(listOf("PlayerBase"), namesOf(supers))
        assertFalse("the supertype must be the base layer, not the modded one", supers[0].isModded())
    }

    /**
     * The cycle guard. Attaching layers in both directions would make base -> layer -> base a branch
     * the tree expands forever, so a layer never lists other layers as *its* subtypes.
     */
    fun testModdedLayerDoesNotListLayersAsSubtypes() {
        myFixture.configureByText(
            "a.es",
            """
            class PlayerBase {}
            modded class PlayerBase {}
            """.trimIndent(),
        )
        assertTrue(EnClassHierarchy.subtypesOf(moddedClassNamed("PlayerBase")).isEmpty())
    }

    /** Several layers of one class all hang off the base, which is the view a mod author wants. */
    fun testEveryLayerHangsOffTheBase() {
        myFixture.addFileToProject("b.es", "modded class PlayerBase {}")
        myFixture.configureByText(
            "a.es",
            """
            class PlayerBase {}
            modded class PlayerBase {}
            """.trimIndent(),
        )
        assertEquals(2, EnClassHierarchy.subtypesOf(classNamed("PlayerBase")).size)
    }

    /** A class never appears among its own relatives, whatever the shape of the declarations. */
    fun testAClassIsNeverItsOwnRelative() {
        myFixture.configureByText(
            "a.es",
            """
            class PlayerBase {}
            modded class PlayerBase {}
            class Child extends PlayerBase {}
            """.trimIndent(),
        )
        val base = classNamed("PlayerBase")
        assertFalse(EnClassHierarchy.subtypesOf(base).any { it === base })
        assertFalse(EnClassHierarchy.supertypesOf(base).any { it === base })
    }
}
