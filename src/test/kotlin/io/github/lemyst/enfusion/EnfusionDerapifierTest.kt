package io.github.lemyst.enfusion

import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.pbo.format.Derapifier
import io.github.lemyst.enfusion.pbo.format.PboFormatException
import java.io.ByteArrayOutputStream

class EnfusionDerapifierTest : BasePlatformTestCase() {

    fun testClassesValuesAndArrays() {
        val bin = RapBytes.write(
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
                                    RapBytes.Value("displayName", "Say \"hi\""),
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
        val text = Derapifier.derapify(bin)
        assertEquals(
            """
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
            		displayName = "Say ""hi""";
            		weight = 1;
            		tiny = 1.0e-6;
            		hiddenSelections[] += {{1, 2.5}, "a"};
            	};
            };

            """.trimIndent(),
            text,
        )
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
}

/** Writes rapified configs for tests, laying out class bodies after their parent's entries. */
object RapBytes {
    sealed interface Item
    class Cls(val name: String, val parent: String, val items: List<Item>) : Item
    class Value(val name: String, val value: Any) : Item
    class Arr(val name: String, val elements: List<Any>, val append: Int? = null) : Item
    class Extern(val name: String) : Item

    fun write(root: Cls): ByteArray {
        val out = Buf()
        out.bytes(byteArrayOf(0, 'r'.code.toByte(), 'a'.code.toByte(), 'P'.code.toByte()))
        out.u32(0)
        out.u32(8)
        val enumOffsetAt = out.size
        out.u32(0)
        body(out, root)
        out.patch(enumOffsetAt, out.size)
        out.u32(0) // an empty enum table
        return out.toByteArray()
    }

    private fun body(out: Buf, cls: Cls) {
        out.asciiz(cls.parent)
        out.varint(cls.items.size)
        val pending = ArrayList<Pair<Int, Cls>>()
        for (item in cls.items) {
            when (item) {
                is Cls -> {
                    out.u8(0)
                    out.asciiz(item.name)
                    pending += out.size to item
                    out.u32(0)
                }
                is Value -> {
                    out.u8(1)
                    out.u8(typeOf(item.value))
                    out.asciiz(item.name)
                    scalar(out, item.value)
                }
                is Arr -> {
                    if (item.append != null) {
                        out.u8(5)
                        out.u32(item.append)
                    } else {
                        out.u8(2)
                    }
                    out.asciiz(item.name)
                    array(out, item.elements)
                }
                is Extern -> {
                    out.u8(3)
                    out.asciiz(item.name)
                }
            }
        }
        for ((at, child) in pending) {
            out.patch(at, out.size)
            body(out, child)
        }
    }

    private fun typeOf(v: Any) = when (v) {
        is String -> 0
        is Float -> 1
        is Int -> 2
        is List<*> -> 3
        else -> error("unsupported $v")
    }

    private fun scalar(out: Buf, v: Any) {
        when (v) {
            is String -> out.asciiz(v)
            is Float -> out.u32(v.toRawBits())
            is Int -> out.u32(v)
            else -> error("unsupported $v")
        }
    }

    private fun array(out: Buf, elements: List<Any>) {
        out.varint(elements.size)
        for (e in elements) {
            out.u8(typeOf(e))
            if (e is List<*>) array(out, e.filterNotNull()) else scalar(out, e)
        }
    }

    private class Buf : ByteArrayOutputStream() {
        val size get() = count
        fun u8(v: Int) = write(v)
        fun bytes(b: ByteArray) = write(b)
        fun u32(v: Int) {
            for (shift in 0 until 32 step 8) write((v shr shift) and 0xFF)
        }
        fun asciiz(s: String) {
            write(s.toByteArray(Charsets.UTF_8))
            write(0)
        }
        fun varint(v: Int) {
            var x = v
            while (true) {
                if (x < 0x80) {
                    write(x)
                    return
                }
                write((x and 0x7F) or 0x80)
                x = x ushr 7
            }
        }
        fun patch(at: Int, value: Int) {
            for (k in 0 until 4) buf[at + k] = ((value shr (8 * k)) and 0xFF).toByte()
        }
    }
}
