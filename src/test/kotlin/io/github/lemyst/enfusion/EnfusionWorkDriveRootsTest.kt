package io.github.lemyst.enfusion

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.roots.EnfusionRoots
import io.github.lemyst.enfusion.settings.EnfusionProjectSettings
import io.github.lemyst.enfusion.tools.DayZInstallation

/**
 * The vanilla roots indexed from the work drive. Only the paths are tested: they are built from the
 * drive letter without touching the disk, and a fixture cannot put a drive on a letter, so whether
 * each one resolves is left to the `runIde` checklist.
 */
class EnfusionWorkDriveRootsTest : BasePlatformTestCase() {

    private var saved: Triple<Boolean, Boolean, List<String>>? = null
    private var savedLetter: String? = null

    override fun setUp() {
        super.setUp()
        // The light project and the application service outlive this test, so both are put back.
        val settings = EnfusionProjectSettings.getInstance(project)
        saved = Triple(settings.state.indexWorkDrive, settings.state.indexWorkDriveConfigs, settings.roots)
        savedLetter = DayZInstallation.getInstance().state.workDriveLetter
    }

    override fun tearDown() {
        try {
            val settings = EnfusionProjectSettings.getInstance(project)
            saved?.let { (drive, configs, roots) ->
                settings.state.indexWorkDrive = drive
                settings.state.indexWorkDriveConfigs = configs
                settings.roots = roots
            }
            DayZInstallation.getInstance().state.workDriveLetter = savedLetter
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun enable(configs: Boolean = true) {
        val state = EnfusionProjectSettings.getInstance(project).state
        state.indexWorkDrive = true
        state.indexWorkDriveConfigs = configs
    }

    private fun drive(letter: String) = listOf(
        "$letter:/scripts",
        "$letter:/gui/layouts",
        "$letter:/gui/imagesets",
        "$letter:/gui/looknfeel",
        "$letter:/languagecore",
        "$letter:/DZ",
    )

    fun testNothingComesFromTheWorkDriveUntilItIsTurnedOn() {
        EnfusionProjectSettings.getInstance(project).state.indexWorkDrive = false
        assertEmpty(EnfusionRoots.workDriveRoots(project, trusted = true))
    }

    fun testTheDefaultLetterIsP() {
        enable()
        assertEquals(drive("P"), EnfusionRoots.workDriveRoots(project, trusted = true))
    }

    fun testTheLetterSetInDayZInstallationIsUsed() {
        enable()
        DayZInstallation.getInstance().state.workDriveLetter = "q"
        assertEquals(drive("Q"), EnfusionRoots.workDriveRoots(project, trusted = true))
    }

    fun testAnInvalidLetterFallsBackToTheDefault() {
        enable()
        DayZInstallation.getInstance().state.workDriveLetter = "PP"
        assertEquals(drive("P"), EnfusionRoots.workDriveRoots(project, trusted = true))
    }

    fun testTheGameDataCanBeLeftOut() {
        enable(configs = false)
        assertEquals(drive("P").dropLast(1), EnfusionRoots.workDriveRoots(project, trusted = true))
    }

    fun testAnUntrustedProjectIndexesNothingFromTheWorkDrive() {
        enable()
        assertEmpty(EnfusionRoots.workDriveRoots(project, trusted = false))
        assertEmpty(EnfusionRoots.rootList(project, trusted = false).all)
    }

    fun testTheWorkDriveComesBeforeTheConfiguredRoots() {
        enable(configs = false)
        EnfusionProjectSettings.getInstance(project).roots = listOf("C:/Mods/Dependency")
        assertEquals(
            drive("P").dropLast(1) + "C:/Mods/Dependency",
            EnfusionRoots.rootList(project, trusted = true).all,
        )
    }
}
