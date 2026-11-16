package io.github.lemyst.enfusion

import io.github.lemyst.enfusion.tools.detect.DayZAddonPacking
import io.github.lemyst.enfusion.tools.detect.DayZLaunchCommand
import io.github.lemyst.enfusion.tools.detect.DayZModRelease
import io.github.lemyst.enfusion.tools.detect.DayZModReleasePlan
import junit.framework.TestCase
import java.nio.file.Path

/** A release build's plan: what to build, copy and remove, decided before anything runs. */
class EnfusionModReleaseTest : TestCase() {

    private val root: Path = Path.of("").toAbsolutePath().root
    private val src = root.resolve("src").resolve("MyMod")
    private val out = root.resolve("out").resolve("@MyMod")
    private val addons = out.resolve("addons")
    private val keys = out.resolve("keys")
    private val key = root.resolve("keys").resolve("MyMod.biprivatekey")

    private val scripts = DayZAddonPacking.Addon(src.resolve("Scripts").resolve("Client"), "MyMod\\Scripts\\Client")
    private val data = DayZAddonPacking.Addon(src.resolve("Data"), "MyMod\\Data")

    private fun release(signKey: Path? = key) = DayZModRelease(
        output = out,
        addons = listOf(scripts, data),
        pbos = listOf(root.resolve("third").resolve("Extra.pbo")),
        keys = listOf(root.resolve("third").resolve("someone.bikey")),
        signKey = signKey,
        modCpp = src.resolve("mod.cpp"),
        extras = listOf(DayZModRelease.Extra(src.resolve("LICENSE"), "LICENSE")),
    )

    fun testNamesAndKeys() {
        assertEquals(root.resolve("keys").resolve("MyMod.bikey"), DayZModReleasePlan.publicKey(key))
        assertEquals("Scripts_Client.pbo.MyMod.bisign", DayZModReleasePlan.signatureName(scripts.pboName, key))
    }

    /** A first build builds every addon and copies everything else into its place. */
    fun testFirstBuild() {
        val plan = DayZModReleasePlan.plan(
            release(),
            manifest = { "files" },
            previous = { null },
            exists = { false },
            inAddons = emptyList(),
            inKeys = emptyList(),
            signaturesOf = { listOf(it.resolveSibling(it.fileName.toString() + ".someone.bisign")) },
        )
        assertEquals(listOf(scripts, data), plan.build)
        assertEquals(
            listOf(
                root.resolve("third").resolve("Extra.pbo") to addons.resolve("Extra.pbo"),
                root.resolve("third").resolve("Extra.pbo.someone.bisign") to addons.resolve("Extra.pbo.someone.bisign"),
                root.resolve("third").resolve("someone.bikey") to keys.resolve("someone.bikey"),
                root.resolve("keys").resolve("MyMod.bikey") to keys.resolve("MyMod.bikey"),
                src.resolve("mod.cpp") to out.resolve("mod.cpp"),
                src.resolve("LICENSE") to out.resolve("LICENSE"),
            ),
            plan.copies,
        )
        assertTrue(plan.delete.isEmpty())
    }

    /** Unchanged addons with their PBO and signature are left alone; a changed one is rebuilt. */
    fun testOnlyWhatChangedIsBuilt() {
        val built = setOf(
            addons.resolve("Scripts_Client.pbo"), addons.resolve("Scripts_Client.pbo.MyMod.bisign"),
            addons.resolve("Data.pbo"), addons.resolve("Data.pbo.MyMod.bisign"),
        )
        val plan = DayZModReleasePlan.plan(
            release(),
            manifest = { if (it == data) "edited" else "same" },
            previous = { "same" },
            exists = { it in built },
            inAddons = built.toList(),
            inKeys = emptyList(),
            signaturesOf = { emptyList() },
        )
        assertEquals(listOf(data), plan.build)
        assertEquals(listOf(scripts), plan.upToDate)
    }

    /** A missing signature on a signed mod means a rebuild, even with the files unchanged. */
    fun testMissingSignatureRebuilds() {
        val plan = DayZModReleasePlan.plan(
            release(),
            manifest = { "same" },
            previous = { "same" },
            exists = { it.fileName.toString().endsWith(".pbo") },
            inAddons = emptyList(),
            inKeys = emptyList(),
            signaturesOf = { emptyList() },
        )
        assertEquals(listOf(scripts, data), plan.build)
        assertEquals("unsigned: no signature needed", 0, DayZModReleasePlan.plan(
            release(signKey = null),
            manifest = { "same" }, previous = { "same" }, exists = { it.fileName.toString().endsWith(".pbo") },
            inAddons = emptyList(), inKeys = emptyList(), signaturesOf = { emptyList() },
        ).build.size)
    }

    /** The key and prefix are part of what a PBO depends on. */
    fun testManifestCarriesPrefixAndKey() {
        val signed = DayZModReleasePlan.manifestOf(scripts, "files", key)
        assertFalse(signed == DayZModReleasePlan.manifestOf(scripts, "files", null))
        assertFalse(signed == DayZModReleasePlan.manifestOf(data, "files", key))
    }

    /** PBOs, signatures and keys no longer part of the mod go; other files a user put there stay. */
    fun testOrphansAreRemoved() {
        val plan = DayZModReleasePlan.plan(
            release(),
            manifest = { "same" },
            previous = { "same" },
            exists = { true },
            inAddons = listOf(
                addons.resolve("Scripts_Client.pbo"), addons.resolve("Old.pbo"), addons.resolve("Old.pbo.MyMod.bisign"),
                addons.resolve("readme.txt"),
            ),
            inKeys = listOf(keys.resolve("MyMod.bikey"), keys.resolve("former.bikey")),
            signaturesOf = { emptyList() },
        )
        assertEquals(
            listOf(addons.resolve("Old.pbo"), addons.resolve("Old.pbo.MyMod.bisign"), keys.resolve("former.bikey")),
            plan.delete,
        )
    }

    fun testClashesAreProblems() {
        val twin = DayZAddonPacking.Addon(src.resolve("Other").resolve("Scripts").resolve("Client"), "Other\\Scripts\\Client")
        try {
            DayZModReleasePlan.plan(
                release().copy(addons = listOf(scripts, twin)),
                manifest = { "" }, previous = { null }, exists = { false }, inAddons = emptyList(), inKeys = emptyList(), signaturesOf = { emptyList() },
            )
            fail("two addons built under one name")
        } catch (e: DayZLaunchCommand.Problem) {
            assertTrue(e.message!!, e.message!!.contains("Scripts_Client.pbo"))
        }
        try {
            DayZModReleasePlan.plan(
                release().copy(extras = listOf(DayZModRelease.Extra(src.resolve("other.cpp"), "mod.cpp"))),
                manifest = { "" }, previous = { null }, exists = { false }, inAddons = emptyList(), inKeys = emptyList(), signaturesOf = { emptyList() },
            )
            fail("two files copied to mod.cpp")
        } catch (e: DayZLaunchCommand.Problem) {
            assertTrue(e.message!!, e.message!!.contains("mod.cpp"))
        }
    }
}
