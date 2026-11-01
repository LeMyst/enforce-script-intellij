package io.github.lemyst.enfusion

import com.intellij.psi.PsiPolyVariantReference
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.psi.impl.EnMethodDeclMixin

/**
 * Two gaps in member resolution, both found from the same real file
 * (`Scripts/Client/3_Game/Loading/LoadingScreen.c` in BattleRoyale): a `modded class` field
 * declared only on the vanilla layer, and an overloaded vanilla method (`game.c`'s `GetWorldName`).
 */
class EnfusionModdedMemberResolutionTest : BasePlatformTestCase() {

    /**
     * `findMembers` used to walk only `owner`'s own body plus its `extends` chain. A `modded class`
     * does not `extends` the layer it patches, so a field declared solely on the vanilla layer
     * (`DayZGame m_DayZGame;` in vanilla `class LoadingScreen`) was invisible from a modded layer of
     * the very same class -- exactly `Scripts/Client/3_Game/Loading/LoadingScreen.c`'s
     * `if (!m_DayZGame) return;`.
     */
    fun testFieldDeclaredOnlyOnTheVanillaLayerResolvesFromAModdedLayer() {
        myFixture.addFileToProject(
            "vanilla/dayzgame.es",
            """
            class LoadingScreen
            {
            	DayZGame m_DayZGame;
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "mod.es",
            """
            modded class LoadingScreen
            {
            	void Foo()
            	{
            		if (!m_DayZ<caret>Game)
            			return;
            	}
            }
            """.trimIndent(),
        )
        val reference = myFixture.getReferenceAtCaretPosition() as PsiPolyVariantReference
        val targets = reference.multiResolve(false)
        assertEquals(1, targets.size)
        assertEquals("m_DayZGame", (targets[0].element as? io.github.lemyst.enfusion.psi.EnNamedElement)?.name)
    }

    /**
     * `game.c` declares both `proto void GetWorldName( out string world_name )` and the zero-arg
     * `string GetWorldName()` wrapper on the same class. A zero-arg call site used to resolve to
     * both -- name-only matching has no idea which overload a caller meant.
     */
    fun testZeroArgCallResolvesOnlyToTheZeroArgOverload() {
        myFixture.configureByText(
            "game.es",
            """
            class CGame
            {
            	proto void GetWorldName( out string world_name );
            	string GetWorldName()
            	{
            		string world_name;
            		GetWorldName(world_name);
            		return world_name;
            	}
            }

            CGame GetGame();

            class Foo
            {
            	void Bar()
            	{
            		string s = GetGame().GetWorld<caret>Name();
            	}
            }
            """.trimIndent(),
        )
        val reference = myFixture.getReferenceAtCaretPosition() as PsiPolyVariantReference
        val targets = reference.multiResolve(false).mapNotNull { it.element as? EnMethodDeclMixin }
        assertEquals(1, targets.size)
        assertEquals("()", targets[0].getParameterListText())
    }

    /** The one-arg call site resolves to the other overload -- the filter looks both ways. */
    fun testOneArgCallResolvesOnlyToTheOutParamOverload() {
        myFixture.configureByText(
            "game.es",
            """
            class CGame
            {
            	proto void GetWorldName( out string world_name );
            	string GetWorldName()
            	{
            		return "";
            	}
            }

            CGame GetGame();

            class Foo
            {
            	void Bar()
            	{
            		string s;
            		GetGame().GetWorld<caret>Name(s);
            	}
            }
            """.trimIndent(),
        )
        val reference = myFixture.getReferenceAtCaretPosition() as PsiPolyVariantReference
        val targets = reference.multiResolve(false).mapNotNull { it.element as? EnMethodDeclMixin }
        assertEquals(1, targets.size)
        assertEquals("( out string world_name )", targets[0].getParameterListText())
    }

    private fun targetsAtCaret(): List<com.intellij.psi.PsiElement> =
        (myFixture.getReferenceAtCaretPosition() as PsiPolyVariantReference)
            .multiResolve(false).mapNotNull { it.element }

    private fun ownerOf(element: com.intellij.psi.PsiElement): io.github.lemyst.enfusion.psi.impl.EnClassDeclMixin? =
        com.intellij.psi.util.PsiTreeUtil.getParentOfType(element, io.github.lemyst.enfusion.psi.impl.EnClassDeclMixin::class.java)

    /**
     * `super.EEKilled(killer)` inside a modded layer's own `override void EEKilled`, the most common
     * line in any DayZ mod. `super` has no type to infer, so this resolved to nothing at all; it must
     * reach the patched layer and never the override it is written in.
     */
    fun testSuperInAModdedLayerReachesThePatchedLayerNotItself() {
        myFixture.addFileToProject(
            "vanilla/playerbase.es",
            """
            class PlayerBase
            {
            	void EEKilled(Object killer)
            	{
            	}
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "mod.es",
            """
            modded class PlayerBase
            {
            	override void EEKilled(Object killer)
            	{
            		super.EEKi<caret>lled(killer);
            	}
            }
            """.trimIndent(),
        )
        val targets = targetsAtCaret()
        assertEquals(1, targets.size)
        assertEquals("playerbase.es", targets[0].containingFile.name)
        assertFalse("must not be the modded layer itself", ownerOf(targets[0])!!.isModded())
    }

    /**
     * In an ordinary subclass `super` is the superclass chain only. A later `modded` layer of the
     * subclass itself comes *after* it, so it is not a candidate, and neither is the method the
     * call is written in.
     */
    fun testSuperInAnOrdinaryClassReachesOnlyTheSuperclass() {
        myFixture.addFileToProject(
            "mod/playerbase_mod.es",
            """
            modded class PlayerBase
            {
            	override void Init()
            	{
            		super.Init();
            	}
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "vanilla.es",
            """
            class ManBase
            {
            	void Init()
            	{
            	}
            }

            class PlayerBase extends ManBase
            {
            	override void Init()
            	{
            		super.In<caret>it();
            	}
            }
            """.trimIndent(),
        )
        val owners = targetsAtCaret().map { ownerOf(it)?.name }
        assertEquals(listOf("ManBase"), owners)
    }

    /**
     * `PlayerBase.Cast(man)` inside the class itself. A bare identifier checks the enclosing class's
     * members first, and a constructor and a destructor are members named after the class, so this
     * used to land on `PlayerBase()` and `~PlayerBase()` instead of the type.
     */
    fun testOwnClassNameAsAQualifierResolvesToTheClassNotItsConstructor() {
        myFixture.configureByText(
            "playerbase.es",
            """
            class PlayerBase
            {
            	void PlayerBase()
            	{
            	}

            	void ~PlayerBase()
            	{
            	}

            	static PlayerBase FromObject(Class obj)
            	{
            		return Player<caret>Base.Cast(obj);
            	}
            }
            """.trimIndent(),
        )
        val targets = targetsAtCaret()
        assertFalse("resolved to nothing", targets.isEmpty())
        assertTrue(
            "expected only the class, got $targets",
            targets.all { it is io.github.lemyst.enfusion.psi.impl.EnClassDeclMixin },
        )
    }
}
