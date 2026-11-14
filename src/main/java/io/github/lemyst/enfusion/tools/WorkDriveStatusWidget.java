package io.github.lemyst.enfusion.tools;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.StatusBarWidget;
import com.intellij.util.Consumer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.Component;
import java.awt.event.MouseEvent;

/**
 * The work-drive entry of the status bar. Java on purpose: see {@link WorkDriveWidgetSupport}.
 */
public final class WorkDriveStatusWidget implements StatusBarWidget, StatusBarWidget.TextPresentation {

    private final Project project;

    public WorkDriveStatusWidget(@NotNull Project project) {
        this.project = project;
    }

    @Override
    public @NotNull String ID() {
        return WorkDriveWidgetFactory.ID;
    }

    @Override
    public void install(@NotNull StatusBar statusBar) {
        WorkDriveWidgetSupport.refresh(project);
    }

    @Override
    public @Nullable WidgetPresentation getPresentation() {
        return this;
    }

    @Override
    public @NotNull String getText() {
        return WorkDriveWidgetSupport.text();
    }

    @Override
    public float getAlignment() {
        return Component.CENTER_ALIGNMENT;
    }

    @Override
    public @Nullable String getTooltipText() {
        return "DayZ work drive: click to mount or unmount";
    }

    @Override
    public @Nullable Consumer<MouseEvent> getClickConsumer() {
        return event -> WorkDriveWidgetSupport.showPopup(project, event);
    }

    @Override
    public void dispose() {
    }
}
