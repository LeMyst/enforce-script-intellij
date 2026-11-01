package io.github.lemyst.enfusion

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.inspections.EnDuplicateLocalInspection
import io.github.lemyst.enfusion.inspections.EnMissingOverrideInspection
import io.github.lemyst.enfusion.inspections.EnModdedClassWithoutBaseInspection
import io.github.lemyst.enfusion.inspections.EnRedundantOverrideInspection
import io.github.lemyst.enfusion.inspections.EnTypeNameShadowedInspection
import io.github.lemyst.enfusion.inspections.EnUnresolvedDispatchNameInspection
import io.github.lemyst.enfusion.inspections.EnUnresolvedStringTableKeyInspection

/**
 * Each inspection gets a case it must report and a case it must stay quiet on.
 *
 * The negative half carries the weight. These all report on code the compiler accepts *elsewhere* in
 * the C family, so a rule that is even slightly too eager produces warnings on correct EnfusionScript
 * and gets switched off wholesale — at which point the ones that are right are gone too.
 */
class EnfusionInspectionTest : BasePlatformTestCase() {

    private fun report(tool: LocalInspectionTool, vararg files: Pair<String, String>): List<String> {
        myFixture.enableInspections(tool)
        files.drop(1).forEach { (name, text) -> myFixture.addFileToProject(name, text) }
        val (name, text) = files.first()
        myFixture.configureByText(name, text)
        return myFixture.doHighlighting()
            .filter { it.severity >= HighlightSeverity.WEAK_WARNING }
            .mapNotNull(HighlightInfo::getDescription)
    }

    private fun assertQuiet(reports: List<String>) =
        assertEquals("expected no report, got $reports", 0, reports.size)

    // --- override ---------------------------------------------------------------------------

    fun testMissingOverrideOnASubclassRedeclaration() {
        val reports = report(
            EnMissingOverrideInspection(),
            "b.es" to "class RaceCar extends Car\n{\n\tvoid Drive()\n\t{\n\t}\n}\n",
            "a.es" to "class Car\n{\n\tvoid Drive()\n\t{\n\t}\n}\n",
        )
        assertEquals(1, reports.size)
        assertTrue(reports[0], reports[0].contains("without 'override'"))
    }

    /** The `modded class` half: a layer redeclaring a base method needs the keyword just as much. */
    fun testMissingOverrideOnAModdedLayer() {
        val reports = report(
            EnMissingOverrideInspection(),
            "b.es" to "modded class Car\n{\n\tvoid Drive()\n\t{\n\t}\n}\n",
            "a.es" to "class Car\n{\n\tvoid Drive()\n\t{\n\t}\n}\n",
        )
        assertEquals(1, reports.size)
    }

    /** Different arity is an overload, which is legal and needs no keyword. */
    fun testDifferentParameterCountIsAnOverloadNotAnOverride() {
        assertQuiet(
            report(
                EnMissingOverrideInspection(),
                "b.es" to "class RaceCar extends Car\n{\n\tvoid Drive(int speed)\n\t{\n\t}\n}\n",
                "a.es" to "class Car\n{\n\tvoid Drive()\n\t{\n\t}\n}\n",
            ),
        )
    }

    /** A constructor is `void ClassName()` — never an override of the superclass's constructor. */
    fun testConstructorIsNotReported() {
        assertQuiet(
            report(
                EnMissingOverrideInspection(),
                "b.es" to "class RaceCar extends Car\n{\n\tvoid RaceCar()\n\t{\n\t}\n}\n",
                "a.es" to "class Car\n{\n\tvoid Car()\n\t{\n\t}\n}\n",
            ),
        )
    }

    fun testRedundantOverrideWhenNothingMatches() {
        val reports = report(
            EnRedundantOverrideInspection(),
            "b.es" to "class RaceCar extends Car\n{\n\toverride void Fly()\n\t{\n\t}\n}\n",
            "a.es" to "class Car\n{\n\tvoid Drive()\n\t{\n\t}\n}\n",
        )
        assertEquals(1, reports.size)
        assertTrue(reports[0], reports[0].contains("no base declaration"))
    }

    /**
     * The gate that keeps this usable: a class whose parent the plugin never indexed would otherwise
     * have every one of its overrides reported.
     */
    fun testRedundantOverrideStaysQuietWhenTheParentIsNotVisible() {
        assertQuiet(
            report(
                EnRedundantOverrideInspection(),
                "b.es" to "class RaceCar extends SomeMissingMod\n{\n\toverride void Fly()\n\t{\n\t}\n}\n",
            ),
        )
    }

    // --- duplicate locals -------------------------------------------------------------------

    fun testDuplicateLocalAcrossDisjointBranches() {
        val reports = report(
            EnDuplicateLocalInspection(),
            "a.es" to "class Car\n{\n\tvoid Drive(bool fast)\n\t{\n\t\tif (fast)\n\t\t{\n\t\t\tint speed = 2;\n\t\t}\n\t\telse\n\t\t{\n\t\t\tint speed = 1;\n\t\t}\n\t}\n}\n",
        )
        assertEquals(1, reports.size)
        assertTrue(reports[0], reports[0].contains("already declared"))
    }

    fun testParameterAndLocalOfTheSameNameCollide() {
        val reports = report(
            EnDuplicateLocalInspection(),
            "a.es" to "class Car\n{\n\tvoid Drive(int speed)\n\t{\n\t\tint speed = 1;\n\t}\n}\n",
        )
        assertEquals(1, reports.size)
    }

    /** Vanilla writes this constantly, so the compiler plainly allows it. */
    fun testRepeatedForLoopCounterIsNotReported() {
        assertQuiet(
            report(
                EnDuplicateLocalInspection(),
                "a.es" to "class Car\n{\n\tvoid Drive()\n\t{\n\t\tfor (int i = 0; i < 3; i++)\n\t\t{\n\t\t}\n\t\tfor (int i = 0; i < 4; i++)\n\t\t{\n\t\t}\n\t}\n}\n",
            ),
        )
    }

    /** The same name in two different methods is two different scopes. */
    fun testSameNameInTwoMethodsIsFine() {
        assertQuiet(
            report(
                EnDuplicateLocalInspection(),
                "a.es" to "class Car\n{\n\tvoid A()\n\t{\n\t\tint x = 1;\n\t}\n\n\tvoid B()\n\t{\n\t\tint x = 2;\n\t}\n}\n",
            ),
        )
    }

    // --- modded class -----------------------------------------------------------------------

    fun testModdedClassWithNoBase() {
        val reports = report(
            EnModdedClassWithoutBaseInspection(),
            "a.es" to "modded class DayZGma\n{\n}\n",
        )
        assertEquals(1, reports.size)
        assertTrue(reports[0], reports[0].contains("patch"))
    }

    fun testModdedClassWithABaseIsFine() {
        assertQuiet(
            report(
                EnModdedClassWithoutBaseInspection(),
                "b.es" to "modded class DayZGame\n{\n}\n",
                "a.es" to "class DayZGame\n{\n}\n",
            ),
        )
    }

    // --- dispatch strings -------------------------------------------------------------------

    fun testDispatchStringNamingNoMethod() {
        val reports = report(
            EnUnresolvedDispatchNameInspection(),
            "a.es" to "class Car\n{\n\tvoid Drive()\n\t{\n\t\tAddTimer(1.0, this, \"OnTikc\");\n\t}\n\n\tvoid OnTick()\n\t{\n\t}\n}\n",
        )
        assertEquals(1, reports.size)
        assertTrue(reports[0], reports[0].contains("OnTikc"))
    }

    fun testDispatchStringNamingARealMethodIsFine() {
        assertQuiet(
            report(
                EnUnresolvedDispatchNameInspection(),
                "a.es" to "class Car\n{\n\tvoid Drive()\n\t{\n\t\tAddTimer(1.0, this, \"OnTick\");\n\t}\n\n\tvoid OnTick()\n\t{\n\t}\n}\n",
            ),
        )
    }

    /** No target in the call means nothing to check it against, so nothing is said. */
    fun testDispatchWithoutATargetIsNotReported() {
        assertQuiet(
            report(
                EnUnresolvedDispatchNameInspection(),
                "a.es" to "class Car\n{\n\tvoid Drive()\n\t{\n\t\tSendRPC(NS, \"NoSuchHandler\", NULL, true);\n\t}\n}\n",
            ),
        )
    }

    // --- stringtable keys -------------------------------------------------------------------

    fun testUnknownStringTableKey() {
        val reports = report(
            EnUnresolvedStringTableKeyInspection(),
            "a.es" to "class Car\n{\n\tvoid Drive()\n\t{\n\t\tSetText(\"#STR_BR_NOPE\");\n\t}\n}\n",
            "stringtable.csv" to "\"Language\",\"original\"\n\"STR_BR_WIN\",\"You win\"\n",
        )
        assertEquals(1, reports.size)
        assertTrue(reports[0], reports[0].contains("STR_BR_NOPE"))
    }

    fun testKnownStringTableKeyIsFine() {
        assertQuiet(
            report(
                EnUnresolvedStringTableKeyInspection(),
                "a.es" to "class Car\n{\n\tvoid Drive()\n\t{\n\t\tSetText(\"#STR_BR_WIN\");\n\t}\n}\n",
                "stringtable.csv" to "\"Language\",\"original\"\n\"STR_BR_WIN\",\"You win\"\n",
            ),
        )
    }

    /** With no stringtable anywhere, every key is unresolved and the inspection must say nothing. */
    fun testNoStringTableMeansNoReports() {
        assertQuiet(
            report(
                EnUnresolvedStringTableKeyInspection(),
                "a.es" to "class Car\n{\n\tvoid Drive()\n\t{\n\t\tSetText(\"#STR_BR_WIN\");\n\t}\n}\n",
            ),
        )
    }

    // --- type-name collisions ---------------------------------------------------------------

    fun testVariableNamedAfterAType() {
        val reports = report(
            EnTypeNameShadowedInspection(),
            "a.es" to "class Car\n{\n\tvoid Drive()\n\t{\n\t\tint map = 1;\n\t}\n}\n",
            "b.es" to "class map\n{\n}\n",
        )
        assertEquals(1, reports.size)
        assertTrue(reports[0], reports[0].contains("also the name of a type"))
    }

    // --- noise measurement ------------------------------------------------------------------

    /**
     * The duplicate-local rule, checked against code that is known to compile.
     *
     * This is the one rule here derived from a claim about the compiler rather than from resolution
     * the plugin already does, and getting its scoping wrong is invisible in hand-written fixtures:
     * treating a `for` initialiser as method-scoped looks perfectly reasonable and would light up
     * most real files, since repeating `for (int i = 0; ...)` is universal. Vanilla ships and
     * therefore compiles, so every report over vanilla source is a false positive by construction.
     *
     * Skips when the corpus is absent, like the parse tests. A green run without `P:` mounted proves
     * nothing.
     */
    fun testDuplicateLocalRuleIsSilentOnVanilla() {
        val root = System.getProperty("enfusion.corpus.root").orEmpty()
        if (root.isEmpty() || !java.io.File(root).isDirectory) {
            println("[inspect] corpus not present, skipping")
            return
        }
        myFixture.enableInspections(EnDuplicateLocalInspection())
        val files = java.io.File(root).walkTopDown()
            .filter { it.isFile && it.extension.equals("c", ignoreCase = true) }
            .take(120)
            .toList()

        val noisy = ArrayList<String>()
        for (file in files) {
            myFixture.configureByText("probe.es", file.readText().replace("\r\n", "\n"))
            val reports = myFixture.doHighlighting()
                .filter { it.severity >= HighlightSeverity.WEAK_WARNING }
                .mapNotNull(HighlightInfo::getDescription)
            if (reports.isNotEmpty()) noisy += "${file.name}: ${reports.first()}"
        }
        assertTrue(
            "reported on ${noisy.size} of ${files.size} vanilla files: ${noisy.take(3)}",
            noisy.isEmpty(),
        )
        println("[inspect] duplicate-local rule stayed silent across ${files.size} vanilla files")
    }

    /**
     * The case that actually bites, and the reason this rule exists at all: `map` is declared
     * `class map<Class TKey, Class TValue>`, so a generic declaration has to be found by name just
     * like a plain one.
     */
    fun testVariableNamedAfterAGenericType() {
        val reports = report(
            EnTypeNameShadowedInspection(),
            "a.es" to "class Car\n{\n\tvoid Drive()\n\t{\n\t\tint map = 1;\n\t}\n}\n",
            "b.es" to "class map<Class TKey,Class TValue>\n{\n}\n",
        )
        assertEquals(1, reports.size)
    }

    fun testOrdinaryVariableNameIsFine() {
        assertQuiet(
            report(
                EnTypeNameShadowedInspection(),
                "a.es" to "class Car\n{\n\tvoid Drive()\n\t{\n\t\tint speed = 1;\n\t}\n}\n",
                "b.es" to "class map\n{\n}\n",
            ),
        )
    }
}
