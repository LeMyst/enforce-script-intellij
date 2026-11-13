package io.github.lemyst.enfusion

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.pbo.format.Derapifier
import io.github.lemyst.enfusion.pbo.format.PboFormatException

class EnfusionDerapifierTest : BasePlatformTestCase() {

    fun testClassesValuesAndArrays() {
        val text = Derapifier.derapify(SAMPLE)
        assertEquals(SAMPLE_TEXT, text)
        val file = myFixture.configureByText("config.cpp", text)
        assertNull("the rebuilt config must parse", PsiTreeUtil.findChildOfType(file, PsiErrorElement::class.java))
    }

    fun testRejectsNonRap() {
        assertFalse(Derapifier.isRapified("class A {};".toByteArray()))
        try {
            Derapifier.derapify("class A {};".toByteArray())
            fail("expected PboFormatException")
        } catch (_: PboFormatException) {
        }
    }

    fun testRejectsOffsetOutsideFile() {
        val bin = RapBytes.write(RapBytes.Cls("", "", listOf(RapBytes.Cls("A", "", listOf()))))
        // The root body is at 16: empty parent name (1 byte), count (1 byte), then type 0, "A\0",
        // and the u32 offset. Point it far past the end.
        val offsetAt = 16 + 1 + 1 + 1 + 2
        bin[offsetAt + 3] = 0x7F
        try {
            Derapifier.derapify(bin)
            fail("expected PboFormatException")
        } catch (_: PboFormatException) {
        }
    }

    fun testRejectsSelfReferencingClass() {
        val bin = RapBytes.write(RapBytes.Cls("", "", listOf(RapBytes.Cls("A", "", listOf(RapBytes.Cls("B", "", listOf()))))))
        // Point A's body offset back at the root body, an endless loop without the depth bound.
        val offsetAt = 16 + 1 + 1 + 1 + 2
        bin[offsetAt] = 16
        bin[offsetAt + 1] = 0
        bin[offsetAt + 2] = 0
        bin[offsetAt + 3] = 0
        try {
            Derapifier.derapify(bin)
            fail("expected PboFormatException")
        } catch (_: PboFormatException) {
        }
    }

    companion object {
        val SAMPLE: ByteArray = RapBytes.write(
            RapBytes.Cls(
                "", "",
                listOf(
                    RapBytes.Cls(
                        "CfgPatches", "",
                        listOf(
                            RapBytes.Cls(
                                "MyMod_Scripts", "",
                                listOf(
                                    RapBytes.Arr("units", listOf()),
                                    RapBytes.Arr("requiredAddons", listOf("DZ_Data", "JM_CF_Scripts")),
                                    RapBytes.Value("requiredVersion", 0.1f),
                                ),
                            ),
                        ),
                    ),
                    RapBytes.Extern("Inventory_Base"),
                    RapBytes.Cls(
                        "CfgVehicles", "",
                        listOf(
                            RapBytes.Extern("Inventory_Base"),
                            RapBytes.Cls(
                                "MyItem", "Inventory_Base",
                                listOf(
                                    RapBytes.Value("scope", 2),
                                    RapBytes.Value("displayName", "Say \"hi\" now"),
                                    RapBytes.Value("weight", 1.0f),
                                    RapBytes.Value("tiny", 1.0e-6f),
                                    RapBytes.Arr("hiddenSelections", listOf(listOf(1, 2.5f), "a"), append = 1),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        // A raw string, so no `"""` may appear inside it: a config string ending in an escaped
        // quote (`"…""";`) would close the literal early. Hence "now" after the quoted word.
        val SAMPLE_TEXT: String = """
            class CfgPatches
            {
            	class MyMod_Scripts
            	{
            		units[] = {};
            		requiredAddons[] = {"DZ_Data", "JM_CF_Scripts"};
            		requiredVersion = 0.1;
            	};
            };
            class Inventory_Base;
            class CfgVehicles
            {
            	class Inventory_Base;
            	class MyItem : Inventory_Base
            	{
            		scope = 2;
            		displayName = "Say ""hi"" now";
            		weight = 1;
            		tiny = 1.0e-6;
            		hiddenSelections[] += {{1, 2.5}, "a"};
            	};
            };

            """.trimIndent()
    }
}
