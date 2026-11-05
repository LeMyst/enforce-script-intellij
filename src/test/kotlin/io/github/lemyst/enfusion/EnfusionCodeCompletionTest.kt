package io.github.lemyst.enfusion

import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Identifier completion: members after a dot, names in scope, and type names. */
class EnfusionCodeCompletionTest : BasePlatformTestCase() {

    private fun complete(text: String): List<String> {
        myFixture.configureByText("test.es", text.trimIndent())
        myFixture.completeBasic()
        return myFixture.lookupElementStrings.orEmpty()
    }

    /** Members of every layer and of the superclass; never the constructor or a local. */
    fun testMembersAfterDotSpanLayersAndSuperclasses() {
        myFixture.addFileToProject(
            "classes.es",
            """
            class EntityAI
            {
            	void EEKilled(Object killer) {}
            	int m_Health;
            }
            class PlayerBase : EntityAI
            {
            	void PlayerBase() {}
            	void ~PlayerBase() {}
            	void OnTick() {}
            }
            modded class PlayerBase
            {
            	override void OnTick() {}
            	void PartyJoin() {}
            }
            """.trimIndent(),
        )
        val offered = complete(
            """
            class Hud
            {
            	void Update(PlayerBase player)
            	{
            		int unrelated;
            		player.<caret>
            	}
            }
            """,
        )
        assertContainsElements(offered, "EEKilled", "m_Health", "OnTick", "PartyJoin")
        assertDoesntContain(offered, "PlayerBase", "unrelated", "Update")
        // The override in the modded layer is the same signature, so it is offered once.
        assertEquals(1, offered.count { it == "OnTick" })
    }

    /** Overloads differ in their parameters, so both stay. */
    fun testOverloadsAreEachOffered() {
        myFixture.addFileToProject(
            "rpc.es",
            """
            class Sender
            {
            	void Send(int a) {}
            	void Send(int a, int b) {}
            	void Flush() {}
            }
            """.trimIndent(),
        )
        val offered = complete("class A { void F(Sender s) { s.<caret> } }")
        assertEquals(2, offered.count { it == "Send" })
    }

    /** Through a class name only statics and constants are reachable. */
    fun testStaticAccessOffersStaticsAndConstants() {
        myFixture.addFileToProject(
            "math.es",
            """
            class Math
            {
            	static const float PI = 3.14;
            	const int LIMIT = 4;
            	static float Clamp(float v, float lo, float hi) {}
            	float Instance() {}
            	int m_Field;
            }
            """.trimIndent(),
        )
        val offered = complete("class A { void F() { Math.<caret> } }")
        assertContainsElements(offered, "PI", "LIMIT", "Clamp")
        assertDoesntContain(offered, "Instance", "m_Field")
    }

    /**
     * `Class` is vanilla's implicit root, so a class naming no superclass still reaches its members:
     * `Cast` through a class name, `ClassName` through an instance.
     */
    fun testImplicitRootMembers() {
        myFixture.addFileToProject(
            "root.es",
            """
            class Class
            {
            	proto static Class Cast(Class from);
            	proto native owned string ClassName();
            }
            class Managed {}
            class Widget : Managed { void Show(bool show) {} }
            """.trimIndent(),
        )
        assertContainsElements(complete("class A { void F() { Widget.<caret> } }"), "Cast")
        assertContainsElements(complete("class A { void F(Widget w) { w.<caret> } }"), "Cast", "ClassName", "Show")
    }

    fun testEnumQualifierOffersItsConstants() {
        myFixture.addFileToProject("calltype.es", "enum CallType { Server, Client }")
        val offered = complete("class A { void F() { CallType.<caret> } }")
        assertSameElements(offered, "Server", "Client")
    }

    /** `super.` inside a modded class reaches the other layers, never its own body. */
    fun testSuperInModdedClassOffersOtherLayers() {
        myFixture.addFileToProject(
            "base.es",
            "class MissionServer { void OnInit() {} void OnUpdate(float dt) {} }",
        )
        val offered = complete(
            """
            modded class MissionServer
            {
            	void OwnHelper() {}
            	override void OnInit() { super.<caret> }
            }
            """,
        )
        assertContainsElements(offered, "OnInit", "OnUpdate")
        assertDoesntContain(offered, "OwnHelper")
    }

    /** The element type of a container comes from the variable's declaration. */
    fun testMembersThroughContainerAccessor() {
        myFixture.addFileToProject(
            "containers.es",
            """
            class array<Class T> { proto T Get(int n); proto int Count(); }
            class Zone { void Activate() {} void Shrink() {} }
            """.trimIndent(),
        )
        val offered = complete(
            """
            class A
            {
            	ref array<ref Zone> m_Zones;
            	void F() { m_Zones.Get(0).<caret> }
            }
            """,
        )
        assertContainsElements(offered, "Activate", "Shrink")
        assertDoesntContain(offered, "Count")
    }

    /** Locals before the caret in an enclosing block, parameters, fields, globals and types. */
    fun testBareNameOffersScopeGlobalsAndTypes() {
        myFixture.addFileToProject(
            "globals.es",
            """
            class DayZGame {}
            DayZGame g_Game;
            proto native DayZGame GetGame();
            class Gadget {}
            """.trimIndent(),
        )
        val offered = complete(
            """
            class Base { int m_Inherited; }
            class A : Base
            {
            	int m_Count;
            	void F(int gParam)
            	{
            		{ int gSibling; }
            		int gLocal;
            		g<caret>
            		int gLater;
            	}
            }
            """,
        )
        assertContainsElements(offered, "gParam", "gLocal", "g_Game")
        assertDoesntContain(offered, "gSibling", "gLater")

        // Upper case, because the IDE's default matching is case-sensitive on the first letter.
        val upper = complete("class A { void F() { G<caret> } }")
        assertContainsElements(upper, "GetGame", "Gadget")

        val members = complete("class Base { int m_Inherited; } class A : Base { int m_Count; void F() { m_<caret> } }")
        assertContainsElements(members, "m_Count", "m_Inherited")
    }

    /**
     * `m_Map<caret>` on the line above `m_Menu = ...;` parses as a declaration, a local named
     * `m_Menu` of type `m_Map`. The name is just as likely a variable, so fields are still offered.
     */
    fun testNameAboveAnAssignmentOffersFields() {
        val offered = complete(
            """
            class Hud
            {
            	Widget m_MapWidget;
            	Widget m_MapLegend;
            	Widget m_Menu;
            	void Init()
            	{
            		m_MapWidget = null;
            		m_Map<caret>
            		m_Menu = null;
            	}
            }
            """,
        )
        assertContainsElements(offered, "m_MapWidget", "m_MapLegend")
    }

    /** A local hides a field of the same name: it is offered once, as the local. */
    fun testLocalShadowsField() {
        complete(
            """
            class A
            {
            	string m_Name;
            	void F() { int m_Name; m_N<caret> }
            }
            """,
        )
        // A single candidate is inserted directly, so the popup never opens.
        myFixture.checkResult(
            """
            class A
            {
            	string m_Name;
            	void F() { int m_Name; m_Name }
            }
            """.trimIndent(),
        )
    }

    /** Where a type is written, only types are offered. */
    fun testTypePositionOffersTypesOnly() {
        myFixture.addFileToProject("types.es", "class PlayerBase {} class PlayerIdentity {} enum PlayerStat { A }")
        val offered = complete("class A { void F(int playerCount) { Player<caret> p = null; } }")
        assertContainsElements(offered, "PlayerBase", "PlayerIdentity", "PlayerStat")
        assertDoesntContain(offered, "playerCount")
    }

    /**
     * A half-written member in a class body or at file scope never parses as a `typeRef`, since a
     * declaration needs a name too, but it is still where most types are typed.
     */
    fun testUnparsedMemberStartOffersTypes() {
        myFixture.addFileToProject("types.es", "class PlayerBase {} class PlayerIdentity {}")
        for (text in listOf(
            "class A\n{\n\tPlay<caret>\n\tint m_X;\n\tvoid F() {}\n}",
            "class A\n{\n\tint m_X;\n\tPlay<caret>\n}",
            "class A\n{\n\tref Play<caret>\n\tint m_X;\n}",
            "Play<caret>\nclass B {}",
        )) {
            assertContainsElements(complete(text), "PlayerBase", "PlayerIdentity")
        }
    }

    /** The name being declared after a type is the user's to invent: nothing is offered. */
    fun testDeclaredNameOffersNothing() {
        myFixture.addFileToProject("types.es", "class PlayerBase {} class PlayerIdentity {}")
        for (text in listOf(
            "class A\n{\n\tPlayerBase Pl<caret>\n\tint m_X;\n}",
            "class A\n{\n\tarray<int> Pl<caret>\n}",
        )) {
            assertEmpty(complete(text))
        }
    }

    /** A method with parameters leaves the caret between the parentheses; one without, after them. */
    fun testMethodInsertsParentheses() {
        myFixture.addFileToProject(
            "rpc.es",
            "class Sender { void SendNow(int a) {} void SendLater(int a) {} void Stop() {} void Start() {} }",
        )
        complete("class A { void F(Sender s) { s.SendN<caret> } }")
        myFixture.checkResult("class A { void F(Sender s) { s.SendNow(<caret>) } }")

        complete("class A { void F(Sender s) { s.Sto<caret> } }")
        myFixture.checkResult("class A { void F(Sender s) { s.Stop()<caret> } }")
    }

    /** The popup shows the parameter list and the return type, as the declaration reads. */
    fun testMethodPresentationShowsSignature() {
        myFixture.addFileToProject("rpc.es", "class Sender { bool Send(int channel, string data) {} void Stop() {} }")
        complete("class A { void F(Sender s) { s.<caret> } }")
        val send = myFixture.lookupElements!!.first { it.lookupString == "Send" }
        val presentation = LookupElementPresentation.renderElement(send)
        assertEquals("(int channel, string data)", presentation.tailText)
        assertEquals("bool", presentation.typeText)
    }

    /** Free functions come from the globals index and insert their parentheses too. */
    fun testGlobalFunctionInsertsParentheses() {
        myFixture.addFileToProject(
            "proto.es",
            "proto native void PrintFormatted(string s);\nproto native void PrintPlain(string s);",
        )
        complete("class A { void F() { PrintF<caret> } }")
        myFixture.checkResult("class A { void F() { PrintFormatted(<caret>) } }")
    }
}
