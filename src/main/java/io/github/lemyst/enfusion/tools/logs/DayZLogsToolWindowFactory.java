package io.github.lemyst.enfusion.tools.logs;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import org.jetbrains.annotations.NotNull;

/**
 * The <i>DayZ Logs</i> tool window. Java on purpose: see {@link DayZLogsContent}.
 */
public final class DayZLogsToolWindowFactory implements ToolWindowFactory, DumbAware {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        DayZLogsContent.fill(project, toolWindow);
    }

    @Override
    public boolean shouldBeAvailable(@NotNull Project project) {
        return SystemInfo.isWindows;
    }
}
