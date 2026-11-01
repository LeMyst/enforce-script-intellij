package io.github.lemyst.enfusion

import com.intellij.psi.PsiPolyVariantReference
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.psi.impl.EnMethodDeclMixin

/**
 * Navigation from a string that names a method, and from one that names a keybind action.
 *
 * These are the two providers whose input is not a file path or a key — they resolve into ordinary
 * script and into XML respectively.
 */
class EnfusionDispatchReferenceTest : BasePlatformTestCase() {

    private fun resolveMethodAtCaret(): EnMethodDeclMixin? =
        myFixture.getReferenceAtCaretPosition()?.resolve() as? EnMethodDeclMixin

    /** The RPC registration form, whose third argument names the handler's owner. */
    fun testAddRpcResolvesThroughItsTargetArgument() {
        myFixture.configureByText(
            "rpc.es",
            """
            class BattleRoyaleRPC
            {
            	void BattleRoyaleRPC()
            	{
            		GetRPCManager().AddRPC(RPC_DAYZBR_NAMESPACE, "SetPlay<caret>erCount", this);
            	}

            	void SetPlayerCount(CallType type, ParamsReadContext ctx, PlayerIdentity sender, Object target) {}
            	void SetCountdownSeconds(CallType type, ParamsReadContext ctx, PlayerIdentity sender, Object target) {}
            }
            """.trimIndent(),
        )
        val target = resolveMethodAtCaret()
        assertNotNull("the handler should resolve", target)
        assertEquals("SetPlayerCount", target!!.name)
    }

    /** Exactly one target, because `this` pins the owning class — no picker. */
    fun testAddRpcResolvesToASingleTarget() {
        myFixture.addFileToProject(
            "other.es",
            """
            class SomethingElse
            {
            	void SetPlayerCount() {}
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "rpc.es",
            """
            class BattleRoyaleRPC
            {
            	void BattleRoyaleRPC() { GetRPCManager().AddRPC(NS, "SetPlay<caret>erCount", this); }
            	void SetPlayerCount() {}
            }
            """.trimIndent(),
        )
        val reference = myFixture.getReferenceAtCaretPosition() as? PsiPolyVariantReference
        assertNotNull(reference)
        assertEquals(1, reference!!.multiResolve(false).size)
    }

    /** `AddTimer(duration, target, name, …)` — the name is the third argument, not the second. */
    fun testAddTimerResolvesItsMethodName() {
        myFixture.configureByText(
            "state.es",
            """
            class BattleRoyaleDebug
            {
            	void Activate() { AddTimer(5.0, this, "OnPlayerCount<caret>Changed", NULL, true); }
            	void OnPlayerCountChanged() {}
            }
            """.trimIndent(),
        )
        assertEquals("OnPlayerCountChanged", resolveMethodAtCaret()?.name)
    }

    /** The coroutine form: `GameScript.Call(target, name, params)`. */
    fun testGameScriptCallResolvesItsMethodName() {
        myFixture.configureByText(
            "prepare.es",
            """
            class BattleRoyalePrepare
            {
            	void Activate() { GetGame().GameScript.Call(this, "Process<caret>Players", NULL); }
            	void ProcessPlayers() {}
            }
            """.trimIndent(),
        )
        assertEquals("ProcessPlayers", resolveMethodAtCaret()?.name)
    }

    /** A handler inherited from a base class is still the right answer. */
    fun testDispatchResolvesThroughTheSuperclassChain() {
        myFixture.configureByText(
            "state.es",
            """
            class BattleRoyaleState
            {
            	void OnPlayerCountChanged() {}
            }

            class BattleRoyaleDebug extends BattleRoyaleState
            {
            	void Activate() { AddTimer(5.0, this, "OnPlayerCount<caret>Changed", NULL, true); }
            }
            """.trimIndent(),
        )
        assertEquals("OnPlayerCountChanged", resolveMethodAtCaret()?.name)
    }

    /**
     * `SendRPC` names no target — the handler lives on whichever class registered it — so this one
     * legitimately falls back to a name-only search.
     */
    fun testSendRpcFallsBackToTheGlobalSymbolIndex() {
        myFixture.addFileToProject(
            "rpc.es",
            """
            class BattleRoyaleRPC
            {
            	void ShowWinScreen(CallType type) {}
            }
            """.trimIndent(),
        )
        myFixture.configureByText(
            "server.es",
            """
            class BattleRoyaleServer
            {
            	void Win() { GetRPCManager().SendRPC(NS, "ShowWin<caret>Screen", NULL, true, identity); }
            }
            """.trimIndent(),
        )
        assertEquals("ShowWinScreen", resolveMethodAtCaret()?.name)
    }

    /** A dispatch name with no matching method resolves to nothing rather than to anything close. */
    fun testUnknownDispatchNameResolvesToNothing() {
        myFixture.configureByText(
            "state.es",
            """
            class State
            {
            	void Activate() { AddTimer(5.0, this, "NoSuch<caret>Method", NULL, true); }
            }
            """.trimIndent(),
        )
        val reference = myFixture.getReferenceAtCaretPosition()
        assertNotNull("the literal is still recognised as a dispatch name", reference)
        assertNull(reference!!.resolve())
    }

    /** A string in an argument position that names nothing gets no reference at all. */
    fun testNonDispatchArgumentHasNoMethodReference() {
        myFixture.configureByText(
            "state.es",
            """
            class State
            {
            	void Activate() { Print("just a m<caret>essage"); }
            }
            """.trimIndent(),
        )
        assertNull(myFixture.getReferenceAtCaretPosition())
    }

    // ---- keybind action names -------------------------------------------------------------------

    private val inputsXml = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes" ?>
        <modded_inputs>
            <inputs>
                <actions>
                    <input name="UAPartyMenu" loc="Party Menu" />
                    <input name="UAPartyPing" loc="Place Party Ping" />
                </actions>
                <sorting name="brparty" loc="PARTY">
                    <input name="UAPartyMenu" />
                </sorting>
            </inputs>
            <preset>
                <input name="UAPartyMenu">
                    <btn name="kP" />
                </input>
            </preset>
        </modded_inputs>
    """.trimIndent()

    /**
     * The name is never a literal at the call site — it goes through a `const string` — so the
     * reference has to attach to the constant's initializer, matched by the `UA` convention.
     */
    fun testActionNameResolvesFromAConstantInitializer() {
        myFixture.addFileToProject("Party/Data/Inputs.xml", inputsXml)
        myFixture.configureByText(
            "constants.es",
            """
            const string BR_PARTY_INPUT_MENU = "UAPa<caret>rtyMenu";
            """.trimIndent(),
        )
        val target = myFixture.getReferenceAtCaretPosition()?.resolve()
        assertNotNull("the action name should resolve into Inputs.xml", target)
        assertTrue("expected an XML attribute value, got $target", target is XmlAttributeValue)
        assertEquals("UAPartyMenu", (target as XmlAttributeValue).value)
    }

    /**
     * The declaration is the one under `<actions>`. The same name also appears under `<sorting>`
     * and `<preset>`, and landing on the preset would put the cursor on a key binding instead.
     */
    fun testActionResolvesToTheDeclarationNotThePreset() {
        myFixture.addFileToProject("Party/Data/Inputs.xml", inputsXml)
        myFixture.configureByText(
            "constants.es",
            """
            const string BR_PARTY_INPUT_MENU = "UAPa<caret>rtyMenu";
            """.trimIndent(),
        )
        val target = myFixture.getReferenceAtCaretPosition()?.resolve() as XmlAttributeValue
        val owner = com.intellij.psi.util.PsiTreeUtil
            .getParentOfType(target, com.intellij.psi.xml.XmlTag::class.java)
        assertEquals("actions", owner?.parentTag?.name)
    }

    /** An action that no `Inputs.xml` declares resolves to nothing. */
    fun testUnknownActionResolvesToNothing() {
        myFixture.addFileToProject("Party/Data/Inputs.xml", inputsXml)
        myFixture.configureByText(
            "constants.es",
            """
            const string MISSING = "UANoS<caret>uchAction";
            """.trimIndent(),
        )
        val reference = myFixture.getReferenceAtCaretPosition()
        assertNotNull(reference)
        assertNull(reference!!.resolve())
    }
}
