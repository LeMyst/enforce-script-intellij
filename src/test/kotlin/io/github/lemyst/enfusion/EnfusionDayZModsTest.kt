package io.github.lemyst.enfusion

import io.github.lemyst.enfusion.tools.detect.DayZLaunchCommand
import io.github.lemyst.enfusion.tools.detect.DayZMod
import io.github.lemyst.enfusion.tools.detect.DayZModKind
import io.github.lemyst.enfusion.tools.detect.DayZMods
import io.github.lemyst.enfusion.tools.detect.DayZPboStaging
import io.github.lemyst.enfusion.tools.detect.WorkDriveLink
import junit.framework.TestCase
import java.nio.file.Files
import java.nio.file.Path

/** The run configuration's mod list: rescans, the work-drive mapping and the PBO staging. */
class EnfusionDayZModsTest : TestCase() {

    private val root: Path = Path.of("").toAbsolutePath().root

    /** A rescan adds what is new, drops addons that are gone, and never re-ticks a mod turned off. */
    fun testRescan() {
        val current = listOf(
            DayZMod("@CF", DayZModKind.MOD),
            DayZMod("P:\\MyMod\\Scripts", DayZModKind.ADDON, enabled = false),
            DayZMod("P:\\MyMod\\Gone", DayZModKind.ADDON),
            DayZMod("D:\\x\\gone.pbo", DayZModKind.PBO),
        )
        val exists = { path: String -> !path.contains("Gone") && !path.contains("gone") }
        val result = DayZMods.rescan(current, listOf("p:\\mymod\\scripts\\", "P:\\MyMod\\Data"), listOf("C:\\src\\@Mine"), exists)
        assertEquals(
            listOf(
                DayZMod("@CF", DayZModKind.MOD),
                DayZMod("P:\\MyMod\\Scripts", DayZModKind.ADDON, enabled = false),
                DayZMod("D:\\x\\gone.pbo", DayZModKind.PBO),
                DayZMod("C:\\src\\@Mine", DayZModKind.MOD),
                DayZMod("P:\\MyMod\\Data", DayZModKind.ADDON),
            ),
            result,
        )
    }

    /** The checkout is reached through the link at the drive's root that is the same folder. */
    fun testWorkDriveLink() {
        val drive = root.resolve("work")
        val checkout = root.resolve("src").resolve("MyMod")
        val link = drive.resolve("MyMod-Link")
        val same = { a: Path, b: Path -> a == link && b == checkout }
        val found = WorkDriveLink.linkOf(checkout, drive, listOf(drive.resolve("Other"), link), same)!!
        assertEquals(link.resolve("Scripts").resolve("Client"), WorkDriveLink.onDrive(checkout.resolve("Scripts").resolve("Client"), found))
        assertNull("outside the linked folder", WorkDriveLink.onDrive(root.resolve("elsewhere"), found))
        val onDrive = WorkDriveLink.linkOf(drive.resolve("Mine"), drive, emptyList(), same)!!
        assertEquals("already on the drive", drive.resolve("Mine").resolve("Data"), WorkDriveLink.onDrive(drive.resolve("Mine").resolve("Data"), onDrive))
        assertNull(WorkDriveLink.linkOf(root.resolve("nowhere"), drive, listOf(link), same))
    }

    fun testPboStagingPlan() {
        val addons = root.resolve("staged").resolve("Addons")
        val a = root.resolve("x").resolve("a.pbo")
        val aSig = root.resolve("x").resolve("a.pbo.author.bisign")
        val b = root.resolve("y").resolve("b.pbo")
        val plan = DayZPboStaging.plan(
            pbos = listOf(a, b),
            addons = addons,
            existing = listOf(addons.resolve("a.pbo"), addons.resolve("old.pbo")),
            signaturesOf = { if (it == a) listOf(aSig) else emptyList() },
            unchanged = { source, _ -> source == a },
        )
        assertEquals(listOf(aSig to addons.resolve("a.pbo.author.bisign"), b to addons.resolve("b.pbo")), plan.copy)
        assertEquals(listOf(addons.resolve("old.pbo")), plan.delete)
        try {
            DayZPboStaging.plan(listOf(a, root.resolve("z").resolve("A.pbo")), addons, emptyList(), { emptyList() }) { _, _ -> false }
            fail("two PBOs with one name in one folder")
        } catch (e: DayZLaunchCommand.Problem) {
            assertTrue(e.message!!, e.message!!.contains("both named"))
        }
    }

    fun testPboStagingOnDisk() {
        val temp = Files.createTempDirectory("staging")
        try {
            val source = Files.createDirectories(temp.resolve("source"))
            val pbo = Files.writeString(source.resolve("extra.pbo"), "pbo")
            Files.writeString(source.resolve("extra.pbo.someone.bisign"), "sig")
            Files.writeString(source.resolve("other.pbo.someone.bisign"), "not this one's")
            val mod = DayZPboStaging.stage(listOf(pbo), temp.resolve("out"))
            assertEquals(DayZPboStaging.MOD_NAME, mod.fileName.toString())
            val staged = Files.list(mod.resolve("Addons")).use { s -> s.map { it.fileName.toString() }.sorted().toList() }
            assertEquals(listOf("extra.pbo", "extra.pbo.someone.bisign"), staged)
            DayZPboStaging.stage(emptyList(), temp.resolve("out"))
            assertEquals("a PBO no longer listed is removed", 0L, Files.list(mod.resolve("Addons")).use { it.count() })
        } finally {
            temp.toFile().deleteRecursively()
        }
    }
}
