package io.github.lemyst.enfusion.cfg.psi;

import com.intellij.psi.tree.IElementType;
import io.github.lemyst.enfusion.cfg.EnfusionConfigLanguage;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

public class EnfusionConfigElementType extends IElementType {
    public EnfusionConfigElementType(@NotNull @NonNls String debugName) {
        super(debugName, EnfusionConfigLanguage.INSTANCE);
    }
}
