package io.github.lemyst.enfusion

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.NavigationItem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.indexing.FindSymbolParameters
import io.github.lemyst.enfusion.nav.EnfusionGotoClassContributor
import io.github.lemyst.enfusion.nav.EnfusionGotoSymbolContributor
import io.github.lemyst.enfusion.nav.EnfusionStructureViewElement
import io.github.lemyst.enfusion.psi.EnNamedElement
import io.github.lemyst.enfusion.psi.impl.EnClassDeclMixin
import io.github.lemyst.enfusion.psi.impl.EnMethodDeclMixin

/**
 * The M2 acceptance gate: the behaviour a user actually sees from Go to Class, Go to Symbol and the
 * structure view. The corpus test proves files *parse*; nothing there exercises the indexes or the
 * contributors, so a broken input filter or a wrong declaration-scope rule would sail past it.
 */
class EnfusionNavigationTest : BasePlatformTestCase() {

    // `.es` rather than `.c`: the `.c` extension is claimed contextually by the FileTypeOverrider,
    // which needs a config.cpp marker in an ancestor directory. `.es` is claimed outright.
    private val source = """
        class BattleRoyaleBase
        {
        	protected int m_PlayerCount;
        }

        modded class BattleRoyaleClient extends BattleRoyaleBase
        {
        	static const int MAX_PLAYERS = 64;
        	protected ref array<ref Param> m_Queue;

        	void BattleRoyaleClient() {}
        	void ~BattleRoyaleClient() {}

        	override void StartMatchMaking(string region, int timeout = 30)
        	{
        		int localOnly = 0;
        		foreach (int i, Param entry : m_Queue)
        		{
        			localOnly += i;
        		}
        	}
        }

        enum EMatchState
        {
        	LOBBY = 0,
        	RUNNING
        }

        typedef map<string, int> TScoreMap;

        DayZGame g_Game;
    """.trimIndent()

    private fun addSource() {
        myFixture.addFileToProject("br.es", source)
    }

    /**
     * Whether the contributor reports a declaration of [name] **coming from `br.es`**.
     *
     * Scoping to the fixture's own file is not fussiness. BasePlatformTestCase's light fixture
     * reuses one project across test methods, and the corpus ParsingTestCase runs in the same JVM,
     * so the global index legitimately holds tens of thousands of unrelated names. Asserting over
     * every name in the index would be testing the whole JVM rather than this fixture.
     */
    private fun declaredInFixture(contributor: ChooseByNameContributorEx, name: String): Boolean =
        items(contributor, name).any { (it as PsiElement).containingFile?.name == "br.es" }

    private fun fixtureItems(contributor: ChooseByNameContributorEx, name: String): List<NavigationItem> =
        items(contributor, name).filter { (it as PsiElement).containingFile?.name == "br.es" }

    private fun items(contributor: ChooseByNameContributorEx, name: String): List<NavigationItem> {
        val found = mutableListOf<NavigationItem>()
        contributor.processElementsWithName(
            name,
            { found.add(it); true },
            FindSymbolParameters.simple(project, true),
        )
        return found
    }

    // ---------------------------------------------------------------- Go to Class

    fun testGotoClassFindsClassesEnumsAndTypedefs() {
        addSource()
        val types = EnfusionGotoClassContributor()
        assertTrue("class missing", declaredInFixture(types, "BattleRoyaleClient"))
        assertTrue("base class missing", declaredInFixture(types, "BattleRoyaleBase"))
        assertTrue("enum missing", declaredInFixture(types, "EMatchState"))
        assertTrue("typedef missing", declaredInFixture(types, "TScoreMap"))
    }

    /** Methods and fields must not pollute Go to Class. */
    fun testGotoClassExcludesMembers() {
        addSource()
        val types = EnfusionGotoClassContributor()
        assertFalse("method leaked into type index", declaredInFixture(types, "StartMatchMaking"))
        assertFalse("field leaked into type index", declaredInFixture(types, "m_PlayerCount"))
    }

    fun testGotoClassResolvesToTheDeclaration() {
        addSource()
        val found = fixtureItems(EnfusionGotoClassContributor(), "BattleRoyaleClient")
        assertEquals(1, found.size)
        val element = found.single() as PsiElement
        assertTrue("expected a class declaration, got ${element.javaClass}", element is EnClassDeclMixin)
        assertTrue("should be recognised as modded", (element as EnClassDeclMixin).isModded())
        assertEquals("BattleRoyaleBase", element.getSuperName())
    }

    // ---------------------------------------------------------------- Go to Symbol

    fun testGotoSymbolFindsMethodsFieldsAndEnumConstants() {
        addSource()
        val symbols = EnfusionGotoSymbolContributor()
        assertTrue("method missing", declaredInFixture(symbols, "StartMatchMaking"))
        assertTrue("field missing", declaredInFixture(symbols, "m_PlayerCount"))
        assertTrue("const missing", declaredInFixture(symbols, "MAX_PLAYERS"))
        assertTrue("enum constant missing", declaredInFixture(symbols, "LOBBY"))
        assertTrue("file-scope global missing", declaredInFixture(symbols, "g_Game"))
    }

    /** Locals and parameters are not symbols — they would drown the popup. */
    fun testGotoSymbolExcludesLocalsAndParameters() {
        addSource()
        val symbols = EnfusionGotoSymbolContributor()
        assertFalse("local leaked", declaredInFixture(symbols, "localOnly"))
        assertFalse("parameter leaked", declaredInFixture(symbols, "region"))
        assertFalse("foreach variable leaked", declaredInFixture(symbols, "entry"))
    }

    fun testGotoSymbolResolvesToTheMethod() {
        addSource()
        val found = fixtureItems(EnfusionGotoSymbolContributor(), "StartMatchMaking")
        assertEquals(1, found.size)
        val method = found.single() as PsiElement
        assertTrue("expected a method, got ${method.javaClass}", method is EnMethodDeclMixin)
        assertTrue("override flag not detected", (method as EnMethodDeclMixin).isOverride())
    }

    // ---------------------------------------------------------------- structure view

    fun testStructureViewListsTopLevelAndMembers() {
        val file = myFixture.configureByText("br.es", source)
        val root = EnfusionStructureViewElement(file)

        val topLevel = root.children.mapNotNull { (it.value as? EnNamedElement)?.name }
        assertContainsElements(
            topLevel,
            "BattleRoyaleBase", "BattleRoyaleClient", "EMatchState", "TScoreMap", "g_Game",
        )

        val clientNode = root.children.single {
            (it.value as? EnNamedElement)?.name == "BattleRoyaleClient"
        }
        assertContainsElements(childNames(clientNode), "MAX_PLAYERS", "m_Queue", "StartMatchMaking")
    }

    fun testStructureViewExpandsEnumConstants() {
        val file = myFixture.configureByText("br.es", source)
        val root = EnfusionStructureViewElement(file)
        val enumNode = root.children.single { (it.value as? EnNamedElement)?.name == "EMatchState" }
        assertContainsElements(childNames(enumNode), "LOBBY", "RUNNING")
    }

    /** `TreeElement.getChildren()` is untyped, so unwrap before reaching for the PSI value. */
    private fun childNames(node: StructureViewTreeElement): List<String> =
        node.children.mapNotNull {
            ((it as? StructureViewTreeElement)?.value as? EnNamedElement)?.name
        }

    // ---------------------------------------------------------------- naming rules

    /** `class bool` and friends are real declarations in 1_core/proto. */
    fun testPrimitiveKeywordIsALegalClassName() {
        val file = myFixture.configureByText("prim.es", "class bool\n{\n\tproto native string ToString();\n}")
        val cls = PsiTreeUtil.findChildOfType(file, EnClassDeclMixin::class.java)
        assertNotNull(cls)
        assertEquals("bool", cls!!.name)
    }

    /** The method name is the identifier before the parameter list, not the return type. */
    fun testMethodNameIsNotTheReturnTypeOrAttribute() {
        val file = myFixture.configureByText(
            "m.es",
            "class C\n{\n\t[Obsolete(\"x\")]\n\tstatic proto native EntityAI SpawnItem(string type);\n}",
        )
        val method = PsiTreeUtil.findChildOfType(file, EnMethodDeclMixin::class.java)
        assertNotNull(method)
        assertEquals("SpawnItem", method!!.name)
        assertTrue(method.isStatic())
    }

    /** Constructors are `void ClassName(...)`, identified only by matching the enclosing class. */
    fun testConstructorAndDestructorAreRecognised() {
        val file = myFixture.configureByText(
            "c.es",
            "class Widget\n{\n\tvoid Widget() {}\n\tvoid ~Widget() {}\n\tvoid Update() {}\n}",
        )
        val methods = PsiTreeUtil.findChildrenOfType(file, EnMethodDeclMixin::class.java).toList()
        assertEquals(3, methods.size)
        assertTrue("constructor not detected", methods[0].isConstructor())
        assertTrue("destructor not detected", methods[1].isDestructor())
        assertFalse("Update must not be a constructor", methods[2].isConstructor())
    }

    // ---------------------------------------------------------------- go to declaration

    private fun resolveAtCaret(): List<PsiElement> {
        val reference = myFixture.file.findReferenceAt(myFixture.caretOffset) ?: return emptyList()
        val poly = reference as? com.intellij.psi.PsiPolyVariantReference
            ?: return listOfNotNull(reference.resolve())
        return poly.multiResolve(false).mapNotNull { it.element }
    }

    /** The original report: Ctrl+Click a type in a local declaration. */
    fun testTypeReferenceResolvesToItsClass() {
        myFixture.addFileToProject("area.es", "class BattleRoyalePlayArea\n{\n\tfloat m_Radius;\n}")
        myFixture.configureByText(
            "use.es",
            "class Round\n{\n\tvoid Tick()\n\t{\n\t\tBattleRoyalePlay<caret>Area future = GetZone();\n\t}\n}",
        )
        val targets = resolveAtCaret()
        assertEquals("expected exactly one target, got $targets", 1, targets.size)
        assertEquals("BattleRoyalePlayArea", (targets.single() as EnNamedElement).name)
        assertEquals("area.es", targets.single().containingFile.name)
    }

    /**
     * `modded class` means a name legitimately has several declarations, and the plugin cannot tell
     * which is live because the layers are often gated by mutually exclusive `#ifdef`s. Both must
     * be offered.
     */
    fun testModdedClassResolvesToEveryLayer() {
        myFixture.addFileToProject("vanilla.es", "class PlayerBase\n{\n\tvoid EEKilled(Class killer) {}\n}")
        myFixture.addFileToProject("mod.es", "modded class PlayerBase\n{\n\toverride void EEKilled(Class killer) {}\n}")
        myFixture.configureByText(
            "use.es",
            "class C\n{\n\tvoid f()\n\t{\n\t\tPlayer<caret>Base p;\n\t}\n}",
        )
        val files = resolveAtCaret().map { it.containingFile.name }.sorted()
        assertEquals("both layers should be offered, got $files", listOf("mod.es", "vanilla.es"), files)
    }

    /** A static qualifier is a refExpr, not a typeRef — the largest bucket of type references. */
    fun testStaticQualifierResolvesToItsClass() {
        myFixture.addFileToProject("utils.es", "class BattleRoyaleUtils\n{\n\tstatic void Debug(string m) {}\n}")
        myFixture.configureByText(
            "use.es",
            "class C\n{\n\tvoid f()\n\t{\n\t\tBattleRoyale<caret>Utils.Debug(\"hi\");\n\t}\n}",
        )
        val targets = resolveAtCaret()
        assertEquals("expected the class, got $targets", 1, targets.size)
        assertEquals("BattleRoyaleUtils", (targets.single() as EnNamedElement).name)
    }

    /**
     * Primitives are navigable, because here they are real classes: vanilla declares `class int`,
     * `class float`, `class string` and `class vector` in `1_core/proto/enconvert.c`, with methods
     * such as `ToString()`.
     */
    fun testPrimitiveTypeResolvesToItsClassDeclaration() {
        myFixture.addFileToProject("prims.es", "class int\n{\n\tproto owned string ToString();\n}")
        myFixture.configureByText("p.es", "class C\n{\n\tvoid f()\n\t{\n\t\tin<caret>t count = 0;\n\t}\n}")
        val targets = resolveAtCaret()
        assertEquals("expected class int, got $targets", 1, targets.size)
        assertEquals("int", (targets.single() as EnNamedElement).name)
    }

    /** A static member reached through its class: `Math.PI2`, `BattleRoyaleUtils.Debug(...)`. */
    fun testStaticMemberResolvesThroughItsClass() {
        myFixture.addFileToProject(
            "math.es",
            "class Math\n{\n\tstatic const float PI2 = 6.28;\n\tstatic float Clamp(float v) {}\n}",
        )
        myFixture.configureByText(
            "use.es",
            "class C\n{\n\tvoid f()\n\t{\n\t\tfloat x = Math.PI<caret>2;\n\t}\n}",
        )
        val targets = resolveAtCaret()
        assertEquals("expected the constant, got $targets", 1, targets.size)
        assertEquals("PI2", (targets.single() as EnNamedElement).name)
        assertEquals("math.es", targets.single().containingFile.name)
    }

    /** The original report: an instance member reached through a local's declared type. */
    fun testInstanceMemberResolvesThroughLocalType() {
        myFixture.addFileToProject(
            "player.es",
            """
            class PlayerBase
            {
                PlayerIdentity GetIdentity() {}
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "use3.es",
            """
            class C
            {
                void f()
                {
                    PlayerBase player;
                    player.GetIden<caret>tity();
                }
            }
            """.trimIndent(),
        )
        val targets = resolveAtCaret()
        assertEquals("expected GetIdentity, got $targets", 1, targets.size)
        assertEquals("player.es", targets.single().containingFile.name)
    }

    /** A parameter's declared type works exactly like a local's. */
    fun testInstanceMemberResolvesThroughParameterType() {
        myFixture.addFileToProject(
            "area.es",
            """
            class PlayArea
            {
                float GetRadius() {}
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "use4.es",
            """
            class C
            {
                void f(PlayArea area)
                {
                    area.GetRad<caret>ius();
                }
            }
            """.trimIndent(),
        )
        assertEquals(1, resolveAtCaret().size)
    }

    /** `GetZone().GetArea()` — the chain runs through the first call's return type. */
    fun testChainedCallResolvesThroughReturnType() {
        myFixture.addFileToProject(
            "zone.es",
            """
            class Zone
            {
                PlayArea GetArea() {}
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "area2.es",
            """
            class PlayArea
            {
                float radius;
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "use5.es",
            """
            class C
            {
                Zone GetZone() {}
                void f()
                {
                    GetZone().GetAr<caret>ea();
                }
            }
            """.trimIndent(),
        )
        val targets = resolveAtCaret()
        assertEquals("expected GetArea, got $targets", 1, targets.size)
        assertEquals("zone.es", targets.single().containingFile.name)
    }

    /**
     * An unqualified call to a file-scope free function.
     *
     * The language declares `GetGame()`, `Print()`, `Error()`, `Vector()` and
     * `GetHourMinuteSecond()` at file scope in `1_core/proto` rather than on a class, and calls to
     * them carry no qualifier at all.
     */
    fun testFreeFunctionResolvesFromUnqualifiedCall() {
        myFixture.addFileToProject(
            "ensystem.es",
            """
            proto void GetHourMinuteSecond(out int hour, out int minute, out int second);
            """.trimIndent(),
        )
        myFixture.configureByText(
            "log.es",
            """
            class Log
            {
                void Stamp()
                {
                    int hour;
                    int minute;
                    int second;
                    GetHourMinuteSec<caret>ond(hour, minute, second);
                }
            }
            """.trimIndent(),
        )
        val targets = resolveAtCaret()
        assertEquals("expected the free function, got $targets", 1, targets.size)
        assertEquals("ensystem.es", targets.single().containingFile.name)
    }

    /** A chain that starts at a free function: `GetGame().GetPlayer()`. */
    fun testChainThroughFreeFunctionReturnType() {
        myFixture.addFileToProject(
            "game.es",
            """
            class CGame
            {
                PlayerBase GetPlayer() {}
            }
            proto native CGame GetGame();
            """.trimIndent(),
        )
        myFixture.addFileToProject("pb.es", "class PlayerBase\n{\n}")
        myFixture.configureByText(
            "usegame.es",
            """
            class C
            {
                void f()
                {
                    GetGame().GetPla<caret>yer();
                }
            }
            """.trimIndent(),
        )
        val targets = resolveAtCaret()
        assertEquals("expected GetPlayer, got $targets", 1, targets.size)
        assertEquals("game.es", targets.single().containingFile.name)
    }

    /** Ctrl+Click on a bare identifier reaches its own declaration. */
    fun testIdentifierResolvesToItsLocalDeclaration() {
        myFixture.configureByText(
            "use6.es",
            """
            class C
            {
                void f()
                {
                    int counter = 0;
                    coun<caret>ter = 1;
                }
            }
            """.trimIndent(),
        )
        val targets = resolveAtCaret()
        assertEquals("expected the local, got $targets", 1, targets.size)
        assertEquals("counter", (targets.single() as EnNamedElement).name)
    }

    /** A local shadows a class of the same name — the innermost scope wins. */
    fun testLocalShadowsClassOfSameName() {
        myFixture.addFileToProject(
            "mathcls.es",
            """
            class Math
            {
                static float PI2 = 6.28;
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "use7.es",
            """
            class C
            {
                void f()
                {
                    int Math = 0;
                    Mat<caret>h = 1;
                }
            }
            """.trimIndent(),
        )
        val targets = resolveAtCaret()
        assertEquals(1, targets.size)
        assertEquals("the local must win over the class", "use7.es", targets.single().containingFile.name)
    }

    /** Members are inherited, so a declaration on a base class must still be reachable. */
    fun testStaticMemberResolvesThroughSuperclass() {
        myFixture.addFileToProject("base.es", "class BaseUtils\n{\n\tstatic void Log(string m) {}\n}")
        myFixture.addFileToProject("derived.es", "class Utils extends BaseUtils\n{\n}")
        myFixture.configureByText(
            "use2.es",
            "class C\n{\n\tvoid f()\n\t{\n\t\tUtils.Lo<caret>g(\"x\");\n\t}\n}",
        )
        val targets = resolveAtCaret()
        assertEquals("expected the inherited method, got $targets", 1, targets.size)
        assertEquals("base.es", targets.single().containingFile.name)
    }

    /** An unknown name resolves to nothing, and must not throw or report an error. */
    fun testUnresolvedTypeIsSoft() {
        myFixture.configureByText(
            "u.es",
            "class C\n{\n\tvoid f()\n\t{\n\t\tCF_Str<caret>ingStream s;\n\t}\n}",
        )
        val reference = myFixture.file.findReferenceAt(myFixture.caretOffset)
        assertNotNull("a reference should exist even when unresolved", reference)
        assertTrue("must be soft so it is not painted as an error", (reference as PsiReferenceBase<*>).isSoft)
        assertEmpty(resolveAtCaret())
    }

    /** In `typedef map<Item, vector> TItemsMap` the name must not come from inside the type. */
    fun testTypedefNameIsNotTakenFromItsType() {
        val file = myFixture.configureByText("t.es", "typedef map<InventoryItem, vector> TItemsMap;")
        val named = PsiTreeUtil.findChildrenOfType(file, EnNamedElement::class.java)
            .map { it.name }
        assertTrue("expected TItemsMap in $named", named.contains("TItemsMap"))
        assertFalse("type name leaked as the declaration name", named.contains("map"))
    }
}
