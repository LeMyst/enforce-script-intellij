package io.github.lemyst.enfusion

import com.intellij.psi.PsiPolyVariantReference
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.psi.impl.EnMethodDeclMixin

/**
 * `auto` in the type slot.
 *
 * It is not a keyword -- the lexer leaves it an ordinary identifier -- so it reaches type inference
 * as a `typeRef` reading `auto` that names no class. Vanilla writes it two ways, both covered here:
 * `auto p = new Param1<int>(...)` (49 sites) and `foreach (auto x : collection)` (10 sites).
 */
class EnfusionAutoLocalTest : BasePlatformTestCase() {

    private val containers = """
        class array<Class T>
        {
        	proto T Get(int n);
        }

        class map<Class TKey, Class TValue>
        {
        	proto TValue Get(TKey key);
        }

        class int {}

        class Zone
        {
        	void Activate() {}
        }

        class Holder
        {
        	Zone MakeZone() { return null; }
        }
    """.trimIndent()

    private fun resolveMethodAtCaret(): EnMethodDeclMixin? =
        (myFixture.getReferenceAtCaretPosition() as? PsiPolyVariantReference)
            ?.multiResolve(false)
            ?.firstNotNullOfOrNull { it.element as? EnMethodDeclMixin }

    /** The `new` form, which is most of the real sites. */
    fun testAutoFromNewExpression() {
        myFixture.configureByText(
            "a.es",
            """
            $containers

            class Server
            {
            	void Run()
            	{
            		auto zone = new Zone();
            		zone.Activ<caret>ate();
            	}
            }
            """.trimIndent(),
        )
        assertEquals("Activate", resolveMethodAtCaret()?.name)
    }

    /** The initializer may itself need inference -- here a call's return type. */
    fun testAutoFromCallReturnType() {
        myFixture.configureByText(
            "a.es",
            """
            $containers

            class Server
            {
            	Holder m_Holder;
            	void Run()
            	{
            		auto zone = m_Holder.MakeZone();
            		zone.Activ<caret>ate();
            	}
            }
            """.trimIndent(),
        )
        assertEquals("Activate", resolveMethodAtCaret()?.name)
    }

    /** `auto` over a generic accessor: both mechanisms at once. */
    fun testAutoFromGenericAccessor() {
        myFixture.configureByText(
            "a.es",
            """
            $containers

            class Server
            {
            	ref array<ref Zone> m_Zones;
            	void Run()
            	{
            		auto zone = m_Zones.Get(0);
            		zone.Activ<caret>ate();
            	}
            }
            """.trimIndent(),
        )
        assertEquals("Activate", resolveMethodAtCaret()?.name)
    }

    fun testForeachAutoOverArray() {
        myFixture.configureByText(
            "a.es",
            """
            $containers

            class Server
            {
            	ref array<ref Zone> m_Zones;
            	void Run()
            	{
            		foreach (auto zone : m_Zones)
            		{
            			zone.Activ<caret>ate();
            		}
            	}
            }
            """.trimIndent(),
        )
        assertEquals("Activate", resolveMethodAtCaret()?.name)
    }

    /** Over a map, a single variable is the *value* -- the last type argument. */
    fun testForeachAutoOverMapYieldsTheValue() {
        myFixture.configureByText(
            "a.es",
            """
            $containers

            class Server
            {
            	ref map<string, ref Zone> m_ByName;
            	void Run()
            	{
            		foreach (auto zone : m_ByName)
            		{
            			zone.Activ<caret>ate();
            		}
            	}
            }
            """.trimIndent(),
        )
        assertEquals("Activate", resolveMethodAtCaret()?.name)
    }

    /** Two variables over a map bind key then value. */
    fun testForeachAutoOverMapWithKeyAndValue() {
        myFixture.configureByText(
            "a.es",
            """
            $containers

            class Server
            {
            	ref map<ref Holder, ref Zone> m_Map;
            	void Run()
            	{
            		foreach (auto holder, auto zone : m_Map)
            		{
            			holder.MakeZ<caret>one();
            		}
            	}
            }
            """.trimIndent(),
        )
        assertEquals("MakeZone", resolveMethodAtCaret()?.name)
    }

    /**
     * Two variables over an array bind *index* then element, unlike a map -- so the first one is an
     * `int`, not the container's single type argument.
     */
    fun testForeachAutoOverArrayBindsIndexThenElement() {
        myFixture.configureByText(
            "a.es",
            """
            $containers

            class Server
            {
            	ref array<ref Zone> m_Zones;
            	void Run()
            	{
            		foreach (auto i, auto zone : m_Zones)
            		{
            			zone.Activ<caret>ate();
            		}
            	}
            }
            """.trimIndent(),
        )
        assertEquals("Activate", resolveMethodAtCaret()?.name)
    }

    /** An `auto` with nothing to infer from resolves to nothing rather than to anything close. */
    fun testAutoWithoutInitializerResolvesToNothing() {
        myFixture.configureByText(
            "a.es",
            """
            $containers

            class Server
            {
            	void Run()
            	{
            		auto mystery;
            		mystery.Activ<caret>ate();
            	}
            }
            """.trimIndent(),
        )
        assertNull(resolveMethodAtCaret())
    }
}
