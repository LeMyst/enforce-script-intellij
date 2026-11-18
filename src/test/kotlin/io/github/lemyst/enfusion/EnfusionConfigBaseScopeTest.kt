package io.github.lemyst.enfusion

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.lemyst.enfusion.inspections.EnCfgUndeclaredBaseInspection
import java.io.File

/**
 * *Base class not declared in this scope*, on the cases CfgConvert was run on: each `reported`
 * case is one it refused with *Undefined base class*, each `fine` case one it binarised.
 */
class EnfusionConfigBaseScopeTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(EnCfgUndeclaredBaseInspection())
        // Every base below exists in some indexed config, which is the inspection's gate.
        myFixture.addFileToProject(
            "vanilla/config.cpp",
            "class CfgVehicles\n{\n\tclass Apple\n\t{\n\t\tclass DamageSystem\n\t\t{\n\t\t};\n\t};\n\tclass Base\n\t{\n\t};\n};\n",
        )
    }

    private fun warn(name: String) =
        "<warning descr=\"Base class '$name' is not declared before this class: Addon Builder reports it as undefined\">$name</warning>"

    private fun check(text: String) {
        myFixture.configureByText("config.cpp", text)
        myFixture.checkHighlighting(true, false, false)
    }

    fun testDeclaredBeforeIsFine() = check("class CfgVehicles\n{\n\tclass Apple;\n\tclass MyApple: Apple\n\t{\n\t};\n};\n")

    fun testNotDeclared() = check("class CfgVehicles\n{\n\tclass MyApple: ${warn("Apple")}\n\t{\n\t};\n};\n")

    fun testDeclaredAfter() = check("class CfgVehicles\n{\n\tclass MyApple: ${warn("Apple")}\n\t{\n\t};\n\tclass Apple;\n};\n")

    fun testEnclosingBodyBeforeTheEnclosingClass() =
        check("class CfgVehicles\n{\n\tclass Base;\n\tclass Item: Base\n\t{\n\t\tclass Sub: Base\n\t\t{\n\t\t};\n\t};\n};\n")

    fun testEnclosingBodyAfterTheEnclosingClass() =
        check("class CfgVehicles\n{\n\tclass Item\n\t{\n\t\tclass Sub: ${warn("Base")}\n\t\t{\n\t\t};\n\t};\n\tclass Base;\n};\n")

    fun testFileScope() = check("class Apple;\nclass CfgVehicles\n{\n\tclass MyApple: Apple\n\t{\n\t};\n};\n")

    fun testSiblingSectionIsNotSearched() =
        check("class CfgWeapons\n{\n\tclass Apple;\n};\nclass CfgVehicles\n{\n\tclass MyApple: ${warn("Apple")}\n\t{\n\t};\n};\n")

    fun testInheritedNestedClass() = check(
        "class CfgVehicles\n{\n\tclass Apple\n\t{\n\t\tclass DamageSystem\n\t\t{\n\t\t};\n\t};\n" +
            "\tclass MyApple: Apple\n\t{\n\t\tclass DamageSystem: DamageSystem\n\t\t{\n\t\t};\n\t};\n};\n",
    )

    /** An external base has no members to inherit. */
    fun testNestedClassThroughAnExternalBase() = check(
        "class CfgVehicles\n{\n\tclass Apple;\n\tclass MyApple: Apple\n\t{\n\t\tclass DamageSystem: ${warn("DamageSystem")}\n\t\t{\n\t\t};\n\t};\n};\n",
    )

    fun testInheritedThroughTwoLevels() = check(
        "class CfgVehicles\n{\n\tclass Apple\n\t{\n\t\tclass DamageSystem;\n\t};\n\tclass Middle: Apple\n\t{\n\t};\n" +
            "\tclass Leaf: Middle\n\t{\n\t\tclass Sub: DamageSystem\n\t\t{\n\t\t};\n\t};\n};\n",
    )

    fun testCaseIsIgnored() = check("class CfgVehicles\n{\n\tclass apple;\n\tclass MyApple: Apple\n\t{\n\t};\n};\n")

    /** A base no indexed config declares may be a dependency outside the roots: no report. */
    fun testUnknownBaseIsSilent() = check("class CfgVehicles\n{\n\tclass MyThing: SomeDependencyBase\n\t{\n\t};\n};\n")

    fun testModCppIsNotChecked() {
        myFixture.configureByText("mod.cpp", "class CfgVehicles\n{\n\tclass MyApple: Apple\n\t{\n\t};\n};\n")
        myFixture.checkHighlighting(true, false, false)
    }

    fun testQuickFixAfterTheExistingExternals() {
        myFixture.configureByText(
            "config.cpp",
            "class CfgVehicles\n{\n\tclass Inventory_Base;\n\tclass Thing: Inventory_Base\n\t{\n\t};\n\tclass MyApple: App<caret>le\n\t{\n\t};\n};\n",
        )
        myFixture.launchAction(myFixture.findSingleIntention("Add 'class Apple;'"))
        myFixture.checkResult(
            "class CfgVehicles\n{\n\tclass Inventory_Base;\n\tclass Apple;\n\tclass Thing: Inventory_Base\n\t{\n\t};\n\tclass MyApple: Apple\n\t{\n\t};\n};\n",
        )
    }

    fun testQuickFixIntoAnEmptyHead() {
        myFixture.configureByText("config.cpp", "class CfgVehicles\n{\n\tclass MyApple: App<caret>le\n\t{\n\t};\n};\n")
        myFixture.launchAction(myFixture.findSingleIntention("Add 'class Apple;'"))
        myFixture.checkResult("class CfgVehicles\n{\n\tclass Apple;\n\tclass MyApple: Apple\n\t{\n\t};\n};\n")
    }

    /** The sandbox review typed the class on one line, and left out its `;`. */
    fun testQuickFixOnAOneLineClass() {
        for (written in listOf("{};", "{}")) {
            myFixture.configureByText("config.cpp", "class CfgPatches\n{\n};\nclass CfgVehicles\n{\n\tclass MyApple: App<caret>le $written\n};\n")
            myFixture.launchAction(myFixture.findSingleIntention("Add 'class Apple;'"))
            myFixture.checkResult("class CfgPatches\n{\n};\nclass CfgVehicles\n{\n\tclass Apple;\n\tclass MyApple: Apple $written\n};\n")
        }
    }

    /**
     * At the top of the file, `class Apple;` would binarise but name a top-level `Apple`, which
     * nothing declares: the class belongs in a section, so no fix is offered, only the warning.
     */
    fun testNoQuickFixAtFileScope() {
        myFixture.configureByText("config.cpp", "class MyApple: App<caret>le\n{\n};\n")
        assertEmpty(myFixture.filterAvailableIntentions("Add 'class Apple;'"))
        myFixture.configureByText("config.cpp", "class MyApple: ${warn("Apple")}\n{\n};\n")
        myFixture.checkHighlighting(true, false, false)
    }

    /**
     * The configs of `enfusionConfigRoots`, each root loaded whole so that the gate sees its
     * bases: vanilla's must give nothing, since they all binarise; the mods' counts are printed.
     */
    fun testConfigRoots() {
        val roots = System.getProperty("enfusion.config.roots").orEmpty().split(';')
            .map(String::trim).filter(String::isNotEmpty).map(::File).filter(File::isDirectory)
        if (roots.isEmpty()) {
            println("[cfgbase] no config roots, skipping")
            return
        }
        val loaded = roots.mapIndexed { index, root ->
            root to root.walkTopDown().onEnter { it.name != ".git" && it.name != ".claude" }
                .filter { it.isFile && it.name.equals("config.cpp", ignoreCase = true) }
                .map { file ->
                    val relative = file.relativeTo(root).invariantSeparatorsPath
                    myFixture.addFileToProject("roots/r$index/$relative", file.readText().replace("\r\n", "\n"))
                }.toList()
        }
        val manager = InspectionManager.getInstance(project)
        val tool = EnCfgUndeclaredBaseInspection()
        for ((root, files) in loaded) {
            val reports = ArrayList<String>()
            for (file in files) reports += inspect(tool, manager, file).map { "${file.virtualFile.path.substringAfter("/roots/")}: $it" }
            println("[cfgbase] ${root.name}: ${files.size} configs, ${reports.size} reports")
            reports.take(15).forEach { println("[cfgbase]   $it") }
            if (root.name.equals("DZ", ignoreCase = true)) assertTrue("vanilla reported: ${reports.take(5)}", reports.isEmpty())
        }
    }

    private fun inspect(tool: EnCfgUndeclaredBaseInspection, manager: InspectionManager, file: PsiFile): List<String> {
        val holder = ProblemsHolder(manager, file, false)
        val visitor = tool.buildVisitor(holder, false)
        PsiTreeUtil.processElements(file) {
            visitor.visitElement(it)
            true
        }
        return holder.results.map { "${it.psiElement.text} (line ${it.lineNumber + 1})" }
    }
}
