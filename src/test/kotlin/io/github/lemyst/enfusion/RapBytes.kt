package io.github.lemyst.enfusion

import java.io.ByteArrayOutputStream

/**
 * Writes rapified (`config.bin`) configs for tests, laying out class bodies after their parent's
 * entries the way the game's binarizer does. Real ones cannot be committed.
 */
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
                    val append = item.append
                    if (append != null) {
                        out.u8(5)
                        out.u32(append)
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
