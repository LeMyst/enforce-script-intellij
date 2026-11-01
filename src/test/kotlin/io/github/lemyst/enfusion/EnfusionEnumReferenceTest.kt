package io.github.lemyst.enfusion

import com.intellij.psi.PsiPolyVariantReference
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.psi.EnNamedElement
import io.github.lemyst.enfusion.psi.EnfusionTypes

/**
 * An enum name used as a *static qualifier*, and the constant after it.
 *
 * `CallType.Client` is the shape every RPC handler in a real mod opens with, and neither half used
 * to resolve: a bare identifier fell back to `classesNamed`, which matches only `EnClassDeclMixin`,
 * and member resolution went through `classesOf`, which likewise sees only classes. The confusing
 * part in practice was that the *same name* one line up -- `void Handler(CallType type, ...)` --
 * resolved fine, because a `typeRef` takes an entirely different path that always did include enums.
 *
 * The fixture enum is named `TestCallType` rather than `CallType` on purpose: the bundled Community
 * Framework stub declares a real `CallType`, and a fixture reusing that name resolves to both -- a
 * correct poly-variant answer that would make these assertions fail for an unrelated reason.
 */
class EnfusionEnumReferenceTest : BasePlatformTestCase() {

    private val callType = """
        enum TestCallType
        {
        	Server = 0,
        	Client
        }
    """.trimIndent()

    fun testEnumNameResolvesAsAStaticQualifier() {
        myFixture.configureByText(
            "a.es",
            """
            $callType

            class Foo
            {
            	void Handler(TestCallType type)
            	{
            		if (type != TestCall<caret>Type.Client)
            			return;
            	}
            }
            """.trimIndent(),
        )
        val reference = myFixture.getReferenceAtCaretPosition() as PsiPolyVariantReference
        val target = reference.multiResolve(false).singleOrNull()?.element as? EnNamedElement
        assertNotNull("the enum name should resolve as a qualifier", target)
        assertEquals("TestCallType", target!!.name)
        assertEquals(EnfusionTypes.ENUM_DECL, target.node?.elementType)
    }

    fun testEnumConstantResolvesAfterTheQualifier() {
        myFixture.configureByText(
            "a.es",
            """
            $callType

            class Foo
            {
            	void Handler(TestCallType type)
            	{
            		if (type != TestCallType.Cli<caret>ent)
            			return;
            	}
            }
            """.trimIndent(),
        )
        val reference = myFixture.getReferenceAtCaretPosition() as PsiPolyVariantReference
        val target = reference.multiResolve(false).singleOrNull()?.element as? EnNamedElement
        assertNotNull("the enum constant should resolve", target)
        assertEquals("Client", target!!.name)
        assertEquals(EnfusionTypes.ENUM_CONSTANT, target.node?.elementType)
    }

    /** The path that always worked -- kept so a fix to the two above cannot regress it. */
    fun testEnumNameStillResolvesAsAParameterType() {
        myFixture.configureByText(
            "a.es",
            """
            $callType

            class Foo
            {
            	void Handler(TestCall<caret>Type type) {}
            }
            """.trimIndent(),
        )
        val reference = myFixture.getReferenceAtCaretPosition() as PsiPolyVariantReference
        val target = reference.multiResolve(false).singleOrNull()?.element as? EnNamedElement
        assertNotNull(target)
        assertEquals("TestCallType", target!!.name)
    }

    /** A typedef is the third `isTypeDeclaration` shape, and it took the same broken path. */
    fun testTypedefResolvesAsAQualifier() {
        myFixture.configureByText(
            "a.es",
            """
            typedef int TCustomId;

            class Foo
            {
            	void Bar() { TCustom<caret>Id.Something(); }
            }
            """.trimIndent(),
        )
        val reference = myFixture.getReferenceAtCaretPosition() as PsiPolyVariantReference
        val target = reference.multiResolve(false).singleOrNull()?.element as? EnNamedElement
        assertNotNull("a typedef name should resolve as a qualifier", target)
        assertEquals("TCustomId", target!!.name)
    }
}
