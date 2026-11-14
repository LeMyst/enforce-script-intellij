package io.github.lemyst.enfusion.tools.run

import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.SystemInfo
import io.github.lemyst.enfusion.tools.detect.DayZLaunchMode

/**
 * *Tools | DayZ | Launch Buldozer*: runs the project's Buldozer configuration, creating one named
 * "Buldozer" the first time, so its mods and profile can be changed like any run configuration.
 */
class LaunchBuldozerAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = SystemInfo.isWindows && e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val runManager = RunManager.getInstance(project)
        val type = DayZRunConfigurationType::class.java
        val existing = runManager.getConfigurationSettingsList(type)
            .firstOrNull { (it.configuration as? DayZRunConfiguration)?.options?.mode == DayZLaunchMode.BULDOZER.name }
        val factory = ConfigurationTypeUtil.findConfigurationType(type).configurationFactories.first()
        val settings = existing ?: runManager.createConfiguration("Buldozer", factory).also {
            (it.configuration as DayZRunConfiguration).options.mode = DayZLaunchMode.BULDOZER.name
            runManager.addConfiguration(it)
        }
        runManager.selectedConfiguration = settings
        ExecutionUtil.runConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
    }
}
