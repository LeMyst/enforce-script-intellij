package io.github.lemyst.enfusion

import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.psi.impl.EnClassDeclMixin
import io.github.lemyst.enfusion.psi.impl.EnMethodDeclMixin

/**
 * Element types of the vanilla containers.
 *
 * `array` and `map` declare their accessors as returning a bare *type parameter* --
 * `proto T Get(int n)`, `proto TValue Get(TKey key)` in `1_core/proto/enscript.c` -- so the element
 * type appears nowhere at the call site. Without substituting the arguments from the variable's
 * declaration, every `.Get(0).Member` in a real mod dead-ends.
 */
class EnfusionGenericTypeTest : BasePlatformTestCase() {

    /** The declarations vanilla actually uses, trimmed to what matters here. */
    private val containers = """
        class array<Class T>
        {
        	proto native int Count();
        	proto T Get(int n);
        }

        class map<Class TKey, Class TValue>
        {
        	proto TValue Get(TKey key);
        }

        class Zone
        {
        	void Activate() {}
        	int m_Radius;
        }
    """.trimIndent()

    private fun resolveMethodAtCaret(): EnMethodDeclMixin? =
        (myFixture.getReferenceAtCaretPosition() as? PsiPolyVariantReference)
            ?.multiResolve(false)
            ?.firstNotNullOfOrNull { it.element as? EnMethodDeclMixin }

    private fun EnMethodDeclMixin.owningClassName(): String? =
        PsiTreeUtil.getParentOfType(this, EnClassDeclMixin::class.java)?.name

    fun testArrayElementMethodResolves() {
        myFixture.configureByText(
            "a.es",
            """
            $containers

            class Server
            {
            	ref array<ref Zone> m_Zones;
            	void Run() { m_Zones.Get(0).Activ<caret>ate(); }
            }
            """.trimIndent(),
        )
        assertEquals("Activate", resolveMethodAtCaret()?.name)
    }

    /** `map` binds two parameters positionally, so the value type is the second one. */
    fun testMapValueMethodResolves() {
        myFixture.configureByText(
            "a.es",
            """
            $containers

            class Server
            {
            	ref map<string, ref Zone> m_ByName;
            	void Run() { m_ByName.Get("a").Activ<caret>ate(); }
            }
            """.trimIndent(),
        )
        assertEquals("Activate", resolveMethodAtCaret()?.name)
    }

    /** A nested container: the argument is itself generic, and stays PSI rather than text. */
    fun testNestedArrayElementResolves() {
        myFixture.configureByText(
            "a.es",
            """
            $containers

            class Server
            {
            	ref array<ref array<ref Zone>> m_Grid;
            	void Run() { m_Grid.Get(0).Get(1).Activ<caret>ate(); }
            }
            """.trimIndent(),
        )
        assertEquals("Activate", resolveMethodAtCaret()?.name)
    }

    /** The container's own non-generic members must still resolve on the container itself. */
    fun testContainerOwnMethodStillResolves() {
        myFixture.configureByText(
            "a.es",
            """
            $containers

            class Server
            {
            	ref array<ref Zone> m_Zones;
            	void Run() { m_Zones.Cou<caret>nt(); }
            }
            """.trimIndent(),
        )
        val target = resolveMethodAtCaret()
        assertEquals("Count", target?.name)
        assertEquals("array", target?.owningClassName())
    }

    /** A parameter of a generic type substitutes exactly like a field does. */
    fun testGenericParameterSubstitutes() {
        myFixture.configureByText(
            "a.es",
            """
            $containers

            class Server
            {
            	void Run(array<ref Zone> zones) { zones.Get(0).Activ<caret>ate(); }
            }
            """.trimIndent(),
        )
        assertEquals("Activate", resolveMethodAtCaret()?.name)
    }

    /** A non-generic chain must not regress -- this is the path that already worked. */
    fun testPlainReturnTypeChainStillResolves() {
        myFixture.configureByText(
            "a.es",
            """
            $containers

            class Holder
            {
            	Zone GetZone() { return null; }
            }

            class Server
            {
            	Holder m_Holder;
            	void Run() { m_Holder.GetZone().Activ<caret>ate(); }
            }
            """.trimIndent(),
        )
        assertEquals("Activate", resolveMethodAtCaret()?.name)
    }
}
