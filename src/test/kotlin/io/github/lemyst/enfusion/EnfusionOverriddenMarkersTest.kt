package io.github.lemyst.enfusion

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.psi.PsiElement
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.inspections.EnMissingOverrideInspection
import io.github.lemyst.enfusion.inspections.EnRedundantOverrideInspection
import io.github.lemyst.enfusion.nav.EnGotoSuperHandler
import io.github.lemyst.enfusion.nav.EnOverriddenLineMarkerProvider
import io.github.lemyst.enfusion.psi.EnNamedElement
import io.github.lemyst.enfusion.psi.impl.EnClassDeclMixin
import io.github.lemyst.enfusion.psi.impl.EnMethodDeclMixin

/**
 * The downward direction: who patches or extends a class and who redeclares its methods, as a
 * gutter icon, as Go to Implementation, and Go to Super Method for the way back up.
 */
class EnfusionOverriddenMarkersTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "vanilla/entity.es",
            """
            class EntityAI
            {
            	void EEInit() {}
            	void EEKilled(EntityAI killer) {}
            	static void Helper() {}
            }
            class ItemBase : EntityAI
            {
            	override void EEInit() {}
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "mods/a.es",
            "modded class ItemBase\n{\n\toverride void EEInit() {}\n}\nclass Apple : ItemBase\n{\n\toverride void EEKilled(EntityAI killer) {}\n}",
        )
        myFixture.addFileToProject("mods/b.es", "modded class ItemBase\n{\n\toverride void EEInit() {}\n}")
    }

    private fun file(path: String) = psiManager.findFile(myFixture.findFileInTempDir(path)!!)!!

    private inline fun <reified T : EnNamedElement> named(path: String, name: String, nth: Int = 0): T =
        PsiTreeUtil.findChildrenOfType(file(path), T::class.java).filter { it.name == name }[nth]

    private fun markers(element: EnNamedElement): List<LineMarkerInfo<*>> {
        val result = ArrayList<LineMarkerInfo<*>>()
        EnOverriddenLineMarkerProvider().collectSlowLineMarkers(mutableListOf(element.nameIdentifier!!), result)
        return result
    }

    fun testClassMarkerCountsLayersAndSubclasses() {
        val itemBase = named<EnClassDeclMixin>("vanilla/entity.es", "ItemBase")
        assertEquals(listOf("Modded by 2 layers, extended by 1 subclass"), markers(itemBase).map { it.lineMarkerTooltip })
        val entity = named<EnClassDeclMixin>("vanilla/entity.es", "EntityAI")
        // ItemBase with both its layers, and Apple below it.
        assertEquals(listOf("Extended by 4 subclasses"), markers(entity).map { it.lineMarkerTooltip })
    }

    fun testMethodMarkerOnTheOriginal() {
        val eeInit = named<EnMethodDeclMixin>("vanilla/entity.es", "EEInit", 1)
        assertEquals(listOf("Redeclared by 2 modded layers"), markers(eeInit).map { it.lineMarkerTooltip })
        val killed = named<EnMethodDeclMixin>("vanilla/entity.es", "EEKilled")
        assertEquals(listOf("Overridden in 1 subclass"), markers(killed).map { it.lineMarkerTooltip })
    }

    /** A layer's siblings are linked by the existing upward icon; only subclasses are below it. */
    fun testLayerHasNoLayersBelowIt() {
        val layer = named<EnMethodDeclMixin>("mods/b.es", "EEInit")
        assertTrue(markers(layer).isEmpty())
    }

    fun testStaticMethodsAreNotOverridden() {
        myFixture.addFileToProject("mods/c.es", "class Pear : EntityAI\n{\n\tstatic void Helper() {}\n}")
        assertTrue(markers(named<EnMethodDeclMixin>("vanilla/entity.es", "Helper")).isEmpty())
    }

    fun testGoToImplementation() {
        val eeInit = named<EnMethodDeclMixin>("vanilla/entity.es", "EEInit", 1)
        val found = DefinitionsScopedSearch.search(eeInit).findAll()
        assertEquals(2, found.size)
        assertTrue(found.all { it.containingFile.name in setOf("a.es", "b.es") })
        val itemBase = named<EnClassDeclMixin>("vanilla/entity.es", "ItemBase")
        assertEquals(3, DefinitionsScopedSearch.search(itemBase).findAll().size)
    }

    fun testGoToSuperFromAMethodAndFromAClass() {
        myFixture.configureByText("test.es", "class Banana : ItemBase\n{\n\toverride void EEKi<caret>lled(EntityAI killer) {}\n}")
        assertEquals(
            listOf("EntityAI"),
            EnGotoSuperHandler.targetsAt(myFixture.file, myFixture.caretOffset).map { owner(it) },
        )
        myFixture.configureByText("test2.es", "class Ban<caret>ana2 : ItemBase {}")
        val supers = EnGotoSuperHandler.targetsAt(myFixture.file, myFixture.caretOffset)
        assertTrue(supers.isNotEmpty())
        assertTrue(supers.all { (it as EnClassDeclMixin).name == "ItemBase" })
    }

    /** A layer that omits `extends` still overrides what the original's superclass declares. */
    fun testLayerOverridingASuperclassMethodIsNotRedundant() {
        myFixture.enableInspections(EnRedundantOverrideInspection())
        myFixture.configureByText("test.es", "modded class ItemBase\n{\n\toverride void EEKilled(EntityAI killer) {}\n}")
        myFixture.checkHighlighting(true, false, true)
        // A second layer: it overrides the first layer's EEKilled and, through the original, EntityAI's.
        myFixture.configureByText("test2.es", "modded class ItemBase\n{\n\toverride void EEKil<caret>led(EntityAI killer) {}\n}")
        assertEquals(
            listOf("ItemBase", "EntityAI"),
            EnGotoSuperHandler.targetsAt(myFixture.file, myFixture.caretOffset).map { owner(it) },
        )
    }

    /** An original comes before its layers: their `override` does not make it a redeclaration. */
    fun testOriginalIsNotARedeclarationOfItsLayers() {
        myFixture.enableInspections(EnMissingOverrideInspection())
        myFixture.addFileToProject("mods/mine_layer.es", "modded class MyThing\n{\n\toverride void Do() {}\n}")
        myFixture.configureByText("mine.es", "class MyThing\n{\n\tvoid Do() {}\n}")
        myFixture.checkHighlighting(true, false, true)
    }

    private fun owner(element: PsiElement): String? =
        PsiTreeUtil.getParentOfType(element, EnClassDeclMixin::class.java)?.name
}
