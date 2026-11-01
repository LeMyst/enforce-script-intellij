package io.github.lemyst.enfusion

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.DebugUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.ParsingTestCase
import io.github.lemyst.enfusion.index.EnfusionIndexes
import io.github.lemyst.enfusion.lang.EnfusionParserDefinition
import io.github.lemyst.enfusion.psi.EnAssignExpr
import io.github.lemyst.enfusion.psi.EnEnumConstant
import io.github.lemyst.enfusion.psi.EnForeachVar
import io.github.lemyst.enfusion.psi.EnNamedElement
import io.github.lemyst.enfusion.psi.impl.EnMethodDeclMixin

/**
 * Focused snippets reduced from corpus failures. Each dumps its PSI tree on failure, which is the
 * only reliable way to see *where* a rule over-consumed — Grammar-Kit's expected-token lists
 * describe what could have followed, not what actually went wrong.
 */
class EnfusionSnippetParseTest :
    ParsingTestCase("", "c", EnfusionParserDefinition(setOf("PLATFORM_WINDOWS"))) {

    override fun getTestDataPath(): String = ""
    override fun skipSpaces(): Boolean = false
    override fun includeRanges(): Boolean = false

    private fun check(name: String, text: String) {
        parseClean(name, text)
    }

    private fun parseClean(name: String, text: String): PsiFile {
        val psi = createPsiFile(name, text)
        val err = PsiTreeUtil.findChildOfType(psi, PsiErrorElement::class.java)
        if (err != null) {
            println("=== $name FAILED at offset ${err.textOffset}: ${err.errorDescription}")
            println(DebugUtil.psiToString(psi, true))
            System.out.flush()
            fail("$name: ${err.errorDescription}")
        }
        return psi
    }

    /**
     * `1_core/proto/enscript.c:717` — `Class` as an ordinary type argument, not a parameter marker.
     *
     * This asserts *names* rather than the absence of errors, and the distinction is the whole
     * point: the snippet used to parse with zero `PsiErrorElement`s while naming the typedef `Class`
     * and silently swallowing every declaration after it. That is how `class set` and `class map` —
     * two of the most-used types in the language — stayed invisible to the plugin. No parse test
     * could see it, so this one looks at what came out instead.
     */
    fun testClassAsAnOrdinaryTypeArgumentDoesNotSwallowTheRest() {
        val psi = createPsiFile(
            "enscript.c",
            """
            typedef array<Class> TClassArray;
            typedef array<ref Managed> TManagedRefArray;

            class set<Class T>
            {
            	proto native int Count();
            }
            """.trimIndent(),
        )
        val names = PsiTreeUtil.findChildrenOfType(psi, EnNamedElement::class.java)
            .filter { EnfusionIndexes.isTypeDeclaration(it) }
            .mapNotNull { it.name }
        assertEquals(listOf("TClassArray", "TManagedRefArray", "set"), names)
    }

    /** 3_game/vehicles/car.c:452 — attribute, then a proto method with parameters. */
    fun testAttributedProtoWithParams() = check(
        "car.c",
        """
        class Car
        {
        	[Obsolete("Use Car.GetThrottle")]
        	proto native float GetThrust();

        	[Obsolete("Use Car.SetThrottle/Car.SetTurbo/Car.SetGentle")]
        	proto native void SetThrust(float in, float gentle = 0, float turbo = 0);
        }
        """.trimIndent(),
    )

    /** Narrowing of the same: is it the parameter list, or the attribute before it? */
    fun testProtoWithDefaultedParamsNoAttribute() = check(
        "proto_params.c",
        "class Car\n{\n\tproto native void SetThrust(float in, float gentle = 0, float turbo = 0);\n}",
    )

    fun testAttributeBeforeParameterlessProto() = check(
        "attr_proto.c",
        "class Car\n{\n\t[Obsolete(\"Use Car.GetThrottle\")]\n\tproto native float GetThrust();\n}",
    )

    /** dynamicmusicplayerregistrysakhal.c:108 — assignment of a nested array literal. */
    fun testNestedArrayLiteralAssignment() = check(
        "sakhal.c",
        """
        class A
        {
        	void f()
        	{
        		array<ref TVectorArray> industrial;
        		industrial = {
        			{"a b c", "d e f"},

        			{"g h i", "j k l"},
        			{"m n o", "p q r"}
        		};
        	}
        }
        """.trimIndent(),
    )

    /** dayzgame.c:2386 — an `#ifdef` guarding only the `else` arm of an `if`. */
    fun testPreprocessorGuardedElse() = check(
        "ifdef_else.c",
        """
        class A
        {
        	void f()
        	{
        		if (a)
        		{
        			b();
        		}
        		#ifdef PLATFORM_PS4
        		else
        		{
        			c();
        		}
        		#endif
        	}
        }
        """.trimIndent(),
    )

    // --- Syntax that arrived with a DayZ update. Each was a corpus failure on the new vanilla tree.

    /**
     * 3_game/worlddata.c:495 and constructionparttyped.c:700 — `sealed` on a method.
     *
     * Asserts the methods by name. The lookahead that tells a method from a field skips modifiers
     * by a list of its own; had `sealed` been missing there, these would not be methods at all.
     * The field named `sealed` proves the contextual keyword still works as an identifier.
     */
    fun testSealedMember() {
        val psi = parseClean(
            "worlddata.c",
            """
            class WorldData
            {
            	bool sealed;

            	sealed void WeatherSandstormStart(bool continuedSession)
            	{
            	}

            	sealed protected void ErrorConfigUndefined(string undefinedParam, string partName)
            	{
            	}
            }
            """.trimIndent(),
        )
        val methods = PsiTreeUtil.findChildrenOfType(psi, EnMethodDeclMixin::class.java).map { it.name }
        assertEquals(listOf("WeatherSandstormStart", "ErrorConfigUndefined"), methods)
    }

    /** 1_core/proto/enconvert.c:618 — `<<=`, and `>>=` alongside it. Neither is a token. */
    fun testCompoundShiftAssignment() {
        val psi = parseClean(
            "enconvert.c",
            """
            void Shift(int value)
            {
            	int bit = 1;
            	bit <<= 1;
            	bit >>= 1;
            	bool b = (value >> 1) > 0 && value >= bit;
            }
            """.trimIndent(),
        )
        assertEquals(2, PsiTreeUtil.findChildrenOfType(psi, EnAssignExpr::class.java).size)
    }

    /** The pair must be adjacent, exactly as `>>` must be: `a << = b` is not an assignment. */
    fun testSpacedShiftAssignmentIsAnError() {
        val psi = createPsiFile("spaced.c", "void F()\n{\n\ta << = b;\n}\n")
        assertNotNull(PsiTreeUtil.findChildOfType(psi, PsiErrorElement::class.java))
    }

    /** constructiondebugs.c:154 and cachedinfosinstanced.c:26 — `ref` on a foreach variable. */
    fun testRefForeachVariable() {
        val psi = parseClean(
            "constructiondebugs.c",
            """
            void Debug()
            {
            	foreach (int hash, ref ActionInfoDebugData data : m_ActionInfoDebug)
            	{
            		data.Print();
            	}
            	foreach (string groupName, ref TypeAttachmentGroupData groupData : groupMap)
            	{
            	}
            }
            """.trimIndent(),
        )
        val vars = PsiTreeUtil.findChildrenOfType(psi, EnForeachVar::class.java).map { (it as EnNamedElement).name }
        assertEquals(listOf("hash", "data", "groupName", "groupData"), vars)
    }

    /** environment.c:25 — a stray separator before the value: `GROUND, = 1,`. */
    fun testStraySeparatorBeforeEnumValue() {
        val psi = parseClean(
            "environment.c",
            """
            enum EHeatCategory
            {
            	PLAYER				= 0,	// Player (Environment)
            	GROUND,				= 1,	// Ground (CE Update)
            	PLAYER_HEATSOURCE,	= 2,
            	NEXT,
            	LAST
            }
            """.trimIndent(),
        )
        val names = PsiTreeUtil.findChildrenOfType(psi, EnEnumConstant::class.java).map { (it as EnNamedElement).name }
        assertEquals(listOf("PLAYER", "GROUND", "PLAYER_HEATSOURCE", "NEXT", "LAST"), names)
    }

    /**
     * motorbikescript.c:1140 and plugintransmissionagents.c:416 — statements before the first
     * `case`, including a switch that has no `case` at all.
     */
    fun testStatementsBeforeTheFirstCase() = check(
        "plugintransmissionagents.c",
        """
        void Transmit(int gear, int sourceAgents)
        {
        	switch (gear)
        	{
        		if (m_BrakesArePressed)
        			m_RearLightType = 1;
        		else
        			m_RearLightType = 0;
        	}
        	switch (sourceAgents)
        	{
        		float protectionLevel = 0.0;
        		case 1:
        			protectionLevel = 1.0;
        			break;
        		default:
        			break;
        	}
        }
        """.trimIndent(),
    )

    /**
     * constructionbase.c:935 as the preprocessor leaves it with DIAG_DEVELOPER defined: the inner
     * `else` runs straight into the closing brace, and the outer `else` must still attach.
     */
    fun testElseWithoutABody() = check(
        "constructionbase.c",
        """
        void Check()
        {
        	if (playerInside)
        	{
        		success = true;
        		if (fullDebugEnabled)
        		{
        			Draw();
        		}
        		else
        	}
        	else
        	{
        		Other();
        	}
        }
        """.trimIndent(),
    )
}
